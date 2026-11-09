#!/usr/bin/env python3
"""A minimal in-memory S3 (path-style) that logs every request, to see what a client really sends.
serve:   s3stub.py serve --port 9000 --log FILE      (GET /__mark/<name> writes a phase marker)
analyze: s3stub.py analyze FILE
No authentication is checked. Supports ListObjectsV2/V1, GET/HEAD/PUT/DELETE of objects, multi-delete, aws-chunked and chunked bodies."""
import os, sys, json, time, threading, hashlib, re, urllib.parse, email.utils
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

STORE = {}            # (bucket, key) -> dict(body, meta, etag, mtime)
LOCK = threading.Lock()
LOGF = None
INFLIGHT = [0]


def log(rec):
    with LOCK:
        LOGF.write(json.dumps(rec) + "\n"); LOGF.flush()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"          # keep-alive
    def log_message(self, *a): pass

    def handle_one_request(self):
        t = time.time()
        super().handle_one_request()
        if self.raw_requestline == b"" or not self.raw_requestline:
            log({"close": "client-eof", "conn": self.client_address[1], "idle_s": round(time.time() - getattr(self, "_last_end", t), 4)})
        elif self.close_connection:
            log({"close": "server-decided", "conn": self.client_address[1], "m": self.command, "cx": self.headers.get("Connection", ""), "inm": self.headers.get("If-None-Match", ""), "ver": self.request_version})
        self._last_end = time.time()

    def _body(self):
        te = self.headers.get("Transfer-Encoding", "").lower()
        enc = self.headers.get("Content-Encoding", "").lower()
        sha = self.headers.get("x-amz-content-sha256", "")
        n = self.headers.get("x-amz-decoded-content-length") or self.headers.get("Content-Length")
        if "chunked" in te or "aws-chunked" in enc or sha.startswith("STREAMING"):
            out = bytearray()
            if "chunked" in te:                       # HTTP chunked framing (may carry aws-chunked inside)
                raw = bytearray()
                while True:
                    size = int(self.rfile.readline().split(b";")[0].strip() or b"0", 16)
                    if size == 0:
                        while self.rfile.readline() not in (b"\r\n", b"\n", b""): pass
                        break
                    raw += self.rfile.read(size); self.rfile.readline()
                data = bytes(raw)
                if "aws-chunked" in enc or sha.startswith("STREAMING"): return self._awschunked(data)
                return data
            data = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            return self._awschunked(data)
        return self.rfile.read(int(n)) if n else b""

    @staticmethod
    def _awschunked(data):
        out, i = bytearray(), 0
        while i < len(data):
            j = data.index(b"\r\n", i); size = int(data[i:j].split(b";")[0], 16)
            if size == 0: break
            out += data[j + 2:j + 2 + size]; i = j + 2 + size + 2
        return bytes(out)

    def _send(self, code, body=b"", headers=None, head=False):
        self.send_response(code)
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("x-amz-request-id", "stub")
        self.end_headers()
        if not head and body: self.wfile.write(body)
        return len(body)

    def _xml(self, code, text, head=False):
        return self._send(code, text.encode(), {"Content-Type": "application/xml"}, head)

    def _list(self, bucket, q):
        prefix = q.get("prefix", [""])[0]; delim = q.get("delimiter", [""])[0]
        maxk = int(q.get("max-keys", ["1000"])[0]); after = q.get("start-after", [""])[0]
        tok = q.get("continuation-token", q.get("marker", [""]))[0]; v2 = q.get("list-type", ["1"])[0] == "2"
        urlenc = q.get("encoding-type", [""])[0] == "url"
        enc = (lambda s: urllib.parse.quote(s, safe="/")) if urlenc else (lambda s: s.replace("&", "&amp;").replace("<", "&lt;"))
        with LOCK: keys = sorted(k for (b, k) in STORE if b == bucket and k.startswith(prefix))
        start = tok or after
        keys = [k for k in keys if k > start] if start else keys
        contents, cps, last, trunc = [], [], "", False
        seen = set()
        for k in keys:
            if len(contents) + len(cps) >= maxk: trunc = True; break
            rest = k[len(prefix):]
            if delim and delim in rest:
                cp = prefix + rest.split(delim)[0] + delim
                if cp not in seen: seen.add(cp); cps.append(cp)
                last = k; continue
            contents.append(k); last = k
        x = ['<?xml version="1.0" encoding="UTF-8"?><ListBucketResult>', f"<Name>{bucket}</Name><Prefix>{enc(prefix)}</Prefix>",
             f"<MaxKeys>{maxk}</MaxKeys><KeyCount>{len(contents)+len(cps)}</KeyCount>", f"<IsTruncated>{'true' if trunc else 'false'}</IsTruncated>"]
        if urlenc: x.append("<EncodingType>url</EncodingType>")
        if delim: x.append(f"<Delimiter>{delim}</Delimiter>")
        if trunc: x.append(f"<NextContinuationToken>{last}</NextContinuationToken><NextMarker>{enc(last)}</NextMarker>")
        for k in contents:
            o = STORE[(bucket, k)]
            x.append(f"<Contents><Key>{enc(k)}</Key><LastModified>{time.strftime('%Y-%m-%dT%H:%M:%S.000Z', time.gmtime(o['mtime']))}</LastModified>"
                     f'<ETag>"{o["etag"]}"</ETag><Size>{len(o["body"])}</Size><StorageClass>STANDARD</StorageClass></Contents>')
        for c in cps: x.append(f"<CommonPrefixes><Prefix>{enc(c)}</Prefix></CommonPrefixes>")
        x.append("</ListBucketResult>")
        return self._xml(200, "".join(x))

    def handle_any(self):
        t0 = time.time()
        with LOCK: INFLIGHT[0] += 1; conc = INFLIGHT[0]
        u = urllib.parse.urlsplit(self.path); q = urllib.parse.parse_qs(u.query, keep_blank_values=True)
        parts = urllib.parse.unquote(u.path).lstrip("/").split("/", 1); bucket = parts[0]; key = parts[1] if len(parts) > 1 else ""
        m = self.command; bin_ = 0; bout = 0; code = 200
        try:
            if bucket == "__mark":
                log({"mark": key, "t": t0}); bout = self._send(200); return
            if bucket == "__clone":   # GET /__clone/<bucket>?from=p1/&to=p2/  server-side copy of every object under a prefix (keeps metadata, new mtime)
                with LOCK:
                    for (bb, k), o in list(STORE.items()):
                        if bb == key and k.startswith(q["from"][0]): STORE[(bb, q["to"][0] + k[len(q["from"][0]):])] = dict(o, mtime=time.time())
                bout = self._send(200); return
            if m in ("PUT", "POST"):
                body = self._body(); bin_ = len(body)
            if not key:                                              # bucket-level
                if m == "GET" and "versioning" in q: bout = self._xml(200, "<VersioningConfiguration/>")
                elif m == "GET": bout = self._list(bucket, q)
                elif m == "POST" and "delete" in q:
                    keys = re.findall(rb"<Key>(.*?)</Key>", body)
                    with LOCK:
                        for k in keys: STORE.pop((bucket, k.decode()), None)
                    bout = self._xml(200, "<DeleteResult/>")
                else: bout = self._send(200, head=(m == "HEAD"))
            elif m == "PUT":
                if os.environ.get("STUB_REFUSE_INM") and self.headers.get("If-None-Match"): code = 501; bout = self._xml(501, "<Error><Code>NotImplemented</Code><Message>If-None-Match</Message></Error>")
                elif self.headers.get("x-amz-copy-source"): code = 501; bout = self._send(501)
                else:
                    meta = {k: v for k, v in self.headers.items() if k.lower().startswith("x-amz-meta-")}
                    etag = hashlib.md5(body).hexdigest()
                    with LOCK: STORE[(bucket, key)] = dict(body=body, meta=meta, etag=etag, mtime=time.time())
                    bout = self._send(200, headers={"ETag": f'"{etag}"'})
            elif m in ("GET", "HEAD"):
                with LOCK: o = STORE.get((bucket, key))
                if o is None: code = 404; bout = self._xml(404, "<Error><Code>NoSuchKey</Code><Message>no</Message></Error>", head=(m == "HEAD"))
                else:
                    h = {"ETag": f'"{o["etag"]}"', "Last-Modified": email.utils.formatdate(o["mtime"], usegmt=True), "Accept-Ranges": "bytes", **o["meta"]}
                    body = o["body"]
                    rng = self.headers.get("Range")
                    if rng and m == "GET":
                        a, _, b = rng.replace("bytes=", "").partition("-"); a = int(a or 0); b = int(b) if b else len(body) - 1
                        code = 206; body = body[a:b + 1]; h["Content-Range"] = f"bytes {a}-{b}/{len(o['body'])}"
                    if m == "HEAD": self.send_response(code); [self.send_header(k, v) for k, v in h.items()]; self.send_header("Content-Length", str(len(o["body"]))); self.end_headers(); bout = 0
                    else: bout = self._send(code, body, h)
            elif m == "DELETE":
                with LOCK: STORE.pop((bucket, key), None)
                code = 204; bout = self._send(204)
            else: code = 501; bout = self._send(501)
        finally:
            with LOCK: INFLIGHT[0] -= 1
            if bucket != "__mark":
                log({"t": t0, "d": round(time.time() - t0, 5), "conn": self.client_address[1], "m": m, "key": key, "q": sorted(q), "code": code,
                     "in": bin_, "out": bout, "conc": conc,
                     "sha": ("UNSIGNED" if self.headers.get("x-amz-content-sha256", "").startswith("UNSIGNED") else
                             "STREAMING" if self.headers.get("x-amz-content-sha256", "").startswith("STREAMING") else
                             "hex" if self.headers.get("x-amz-content-sha256") else "none"),
                     "md5": "Content-MD5" in self.headers, "expect": self.headers.get("Expect", ""), "ae": self.headers.get("Accept-Encoding", ""),
                     "ce": self.headers.get("Content-Encoding", ""), "cx": self.headers.get("Connection", ""), "ver": self.request_version, "te": self.headers.get("Transfer-Encoding", ""), "ua": self.headers.get("User-Agent", "")[:40]})

    do_GET = do_PUT = do_POST = do_HEAD = do_DELETE = handle_any


def analyze(path):
    from collections import Counter, defaultdict
    recs = [json.loads(l) for l in open(path)]
    phases, cur = defaultdict(list), "start"
    for r in recs:
        if "mark" in r: cur = r["mark"]; continue
        if "close" in r: continue
        phases[cur].append(r)
    closes = Counter(r["close"] for r in recs if "close" in r)
    if closes: print("closes: " + str(dict(closes)) + "; client-eof idle after last response (s): " + str(sorted({r["idle_s"] for r in recs if r.get("close") == "client-eof"})[:6]))
    for ph, rs in phases.items():
        if not rs: continue
        def kind(r):
            q = r["q"]
            if r["m"] == "GET" and not r["key"]: return "LIST" if ("list-type" in q or "prefix" in q or "delimiter" in q) else "GET bucket?" + ",".join(q)
            if r["m"] == "POST": return "POST " + ",".join(q)
            if r["m"] == "PUT" and r["key"].endswith("/"): return "PUT folder-marker"
            return f'{r["m"]}' + (" " + ",".join(q) if q else "") if r["key"] else f'{r["m"]} bucket'
        c = Counter(kind(r) for r in rs); conns = Counter(r["conn"] for r in rs)
        t0 = min(r["t"] for r in rs); t1 = max(r["t"] + r["d"] for r in rs); span = t1 - t0
        busy = sum(r["d"] for r in rs)
        files = {r["key"] for r in rs if r["key"] and not r["key"].endswith("/")}
        print(f"== phase {ph}: {len(rs)} requests in {span:.1f}s ({len(rs)/max(span,1e-9):.1f} req/s), {len(conns)} connections, {len(rs)/len(conns):.1f} req/conn")
        print("   by kind: " + ", ".join(f"{k} x{v}" for k, v in sorted(c.items(), key=lambda kv: -kv[1])))
        print(f"   max in flight {max(r['conc'] for r in rs)}, mean in flight {busy/max(span,1e-9):.2f}; bytes in {sum(r['in'] for r in rs)/1e6:.1f} MB, out {sum(r['out'] for r in rs)/1e6:.1f} MB")
        if files: print(f"   per object key: {len(rs)/len(files):.2f} requests (over {len(files)} keys)")
        puts = [r for r in rs if r["m"] == "PUT" and r["key"] and not r["key"].endswith("/")]
        if puts:
            print("   PUT headers: payload-hash " + str(dict(Counter(r["sha"] for r in puts))) + ", Content-MD5 " + str(dict(Counter(r["md5"] for r in puts))) +
                  ", Expect " + str(dict(Counter(r["expect"] for r in puts))) + ", chunked " + str(dict(Counter(r["te"] or r["ce"] or "-" for r in puts))))
        print("   Connection header " + str(dict(Counter(r.get("cx", "?") for r in rs))) + ", version " + str(dict(Counter(r.get("ver", "?") for r in rs))))
        if puts: print("   PUT If-None-Match " + str(dict(Counter(r.get("inm", "?") for r in puts))) + "; status " + str(dict(Counter(r["code"] for r in puts))))
        print("   UA " + str(dict(Counter(r["ua"] for r in rs))) + "; Accept-Encoding " + str(dict(Counter(r["ae"] for r in rs))))
        # per-key request sequence of a typical key
        by = defaultdict(list)
        for r in rs:
            if r["key"] and not r["key"].endswith("/"): by[r["key"]].append(r)
        if by:
            seqs = Counter(" > ".join(kind(x) for x in sorted(v, key=lambda x: x["t"])) for v in by.values())
            print("   per-key sequences: " + "; ".join(f"[{s}] x{n}" for s, n in seqs.most_common(3)))


if __name__ == "__main__":
    if sys.argv[1] == "analyze": analyze(sys.argv[2]); sys.exit()
    port = int(sys.argv[sys.argv.index("--port") + 1]); LOGF = open(sys.argv[sys.argv.index("--log") + 1], "a")
    ThreadingHTTPServer.request_queue_size = 128; ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
