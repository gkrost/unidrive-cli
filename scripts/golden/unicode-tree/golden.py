#!/usr/bin/env python3
"""Linux harness of the golden unicode tree: the same suite as the PowerShell scripts, without PowerShell.

Python 3.9+, standard library only. Subcommands (same findings, same line formats, same exit codes as the .ps1 twins, so
expected/*.tsv files and compare work on either side):

  generate      build the tree from layout.json / check or rewrite manifest.tsv     (generate.ps1)
  verify        compare a directory with the manifest                               (verify.ps1)
  compare       sort findings into unexpected / expected / stale                    (compare-expected.ps1)
  daemon-view   the daemon's cloud-side view over its IPC socket                    (daemon-view.ps1 + ipc-auth.ps1)
  remote-live   the provider's own listing via `unidrive ls --live`                 (remote-live.ps1)
  selftest      damage fresh trees and feed compare made-up findings                (selftest.ps1, verify + compare halves)
  run           the whole run on an existing mount, or --localfs for an isolated profile   (run-localfs.ps1)

Not ported: the download-direction suite (download.json, seed-download.ps1) and the probes/. Names are shown as {U+XXXX}.
"""
import argparse, base64, contextlib, hashlib, hmac, io, json, os, re, shutil, socket, subprocess, sys, tempfile, time, unicodedata
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
TOKEN_RE = re.compile(r'\{U\+([0-9A-Fa-f]{4,6})\}')
REP_RE = re.compile(r'\{rep:([^:}]+):(\d+)\}')
CLASSES = ['MISSING', 'EXTRA', 'FORM', 'CASE', 'SIZE', 'HASH', 'MTIME', 'KIND', 'ERROR', 'PENDING', 'NOREMOTE', 'NFCMERGE', 'LEAK', 'LISTFAIL']
TOOL_RE = re.compile(r'^(verify|daemon-view|remote-live)(:[a-z0-9-]+)?$')


def expand_tokens(s):
    s = TOKEN_RE.sub(lambda m: chr(int(m.group(1), 16)), s)
    return REP_RE.sub(lambda m: m.group(1) * int(m.group(2)), s)


def to_ascii(s):
    return ''.join(c if 0x20 <= ord(c) < 0x7F else '{U+%04X}' % ord(c) for c in s)


def utf16_key(s):  # ordinal UTF-16 code unit order, as .NET sorts
    return s.encode('utf-16-be', 'surrogatepass')


def sort_ordinal(items):
    return sorted(items, key=utf16_key)


def upper_invariant(s):  # .NET ToUpperInvariant: simple per-character mapping, no "ss" for sharp s
    return ''.join(u if len(u := c.upper()) == 1 else c for c in s)


def get_form(s):
    c, d = unicodedata.is_normalized('NFC', s), unicodedata.is_normalized('NFD', s)
    return 'both' if c and d else 'NFC' if c else 'NFD' if d else 'other'


def utc(ts):
    return datetime.fromtimestamp(int(ts), timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ')


def parse_utc(s):
    return datetime.strptime(s, '%Y-%m-%dT%H:%M:%SZ').replace(tzinfo=timezone.utc).timestamp()


def sha256_file(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''):
            h.update(chunk)
    return h.hexdigest()


def load_manifest(path):
    rows = {}
    for line in Path(path).read_text(encoding='utf-8').split('\n'):
        if not line or line.startswith('#'):
            continue
        c = line.split('\t')
        rows[expand_tokens(c[5])] = {'kind': c[0], 'size': c[1], 'hash': c[2], 'mtime': c[3]}
    return rows


# ---- generate ----------------------------------------------------------------------------------------------------------------------
def build_plan(layout):
    spec = json.loads(Path(layout).read_text(encoding='utf-8'))
    sid, max_seg = spec['id'], int(spec['maxSegments'])
    default_mtime = parse_utc('2026-01-01T12:00:00Z')
    dirs, files = set(), []

    def add_dir(parts):
        for n in range(1, len(parts) + 1):
            dirs.add('/'.join(parts[:n]))

    for node in spec['nodes']:
        parts = [expand_tokens(x) for x in node['dir']]
        add_dir(parts)
        for e in node.get('emptyDirs') or []:
            add_dir(parts + [expand_tokens(e)])
        for f in node.get('files') or []:
            name = expand_tokens(f['name'])
            if f.get('nf') in ('NFC', 'NFD'):
                name = unicodedata.normalize(f['nf'], name)
            files.append((f, '/'.join(parts + [name])))
    for p in list(dirs) + [r for _, r in files]:
        if len(p.split('/')) > max_seg:
            raise SystemExit(f'Path has more than {max_seg} segments: {to_ascii(p)}')
    seen = {}
    for p in list(dirs) + [r for _, r in files]:
        k = upper_invariant(p)
        if k in seen and seen[k] != p:
            print(f'WARNING: names equal under invariant upper-casing (intended, a case-folding hazard): {to_ascii(p)} vs {to_ascii(seen[k])}', file=sys.stderr)
        seen[k] = p
    once = set()
    for _, r in files:
        if r in once:
            raise SystemExit(f'The same file path twice: {to_ascii(r)}')
        once.add(r)

    def new_bytes(count, seed, zeros):
        buf = bytearray()
        i = 0
        while len(buf) < count:
            buf += hashlib.sha256(f'{seed}|{i}'.encode()).digest()
            i += 1
        buf = bytearray(buf[:count])
        if zeros:
            buf[0::2] = bytes(len(buf[0::2]))
        return bytes(buf)

    def encode_text(f, rel):
        body = f.get('text') or ''
        if not f.get('raw'):
            body += '\n'
        if not f.get('plain'):
            body += f'{sid} | {rel}\n'
        if f.get('eol') == 'crlf':
            body = body.replace('\n', '\r\n')
        enc = f.get('enc')
        if enc == 'utf8bom': return b'\xef\xbb\xbf' + body.encode('utf-8')
        if enc == 'utf16le': return b'\xff\xfe' + body.encode('utf-16-le')
        if enc == 'utf16be': return b'\xfe\xff' + body.encode('utf-16-be')
        if enc == 'latin1': return body.encode('latin-1')
        return body.encode('utf-8')

    rows = [{'kind': 'D', 'size': '-', 'hash': '-', 'mtime': '-', 'path': d, 'data': None, 'mt': None} for d in dirs]
    for f, rel in files:
        if f.get('bytes') is not None:
            data = new_bytes(int(f['bytes']), str(f['seed']) if f.get('seed') else f'{sid}|{rel}', bool(f.get('zeros')))
        else:
            data = encode_text(f, rel)
        if len(data) > 1000000:
            raise SystemExit(f'File larger than 1,000,000 bytes: {to_ascii(rel)}')
        mt = parse_utc(str(f['mtime'])) if f.get('mtime') else default_mtime
        rows.append({'kind': 'F', 'size': len(data), 'hash': hashlib.sha256(data).hexdigest(), 'mtime': utc(mt), 'path': rel, 'data': data, 'mt': mt})
    rows.sort(key=lambda r: utf16_key(r['path']))
    manifest = f'#{sid} manifest: kind, size, sha256, mtime_utc, form, path_ascii, path (UTF-8, LF)\n'
    for r in rows:
        manifest += f"{r['kind']}\t{r['size']}\t{r['hash']}\t{r['mtime']}\t{get_form(r['path'])}\t{to_ascii(r['path'])}\t{r['path']}\n"
    return sid, rows, manifest.encode('utf-8')


def cmd_generate(a):
    if not (a.out or a.check or a.update_manifest):
        raise SystemExit('Give --out <directory>, --check or --update-manifest.')
    sid, rows, mbytes = build_plan(a.layout)
    mhash = hashlib.sha256(mbytes).hexdigest()
    nf = sum(r['kind'] == 'F' for r in rows)
    print(f"layout: {len(rows) - nf} directories, {nf} files, {sum(r['size'] for r in rows if r['kind'] == 'F')} bytes; manifest sha256 {mhash}")
    mp = Path(a.manifest)
    committed = hashlib.sha256(mp.read_bytes() if mp.exists() else b'').hexdigest()
    if a.update_manifest:
        mp.write_bytes(mbytes)
        (mp.parent / 'manifest.sha256').write_text(f'{mhash}  manifest.tsv\n')
        print(f'wrote {mp} (was {committed})')
        if not a.out:
            return 0
    elif committed != mhash:
        print(f'DIFFERS from {mp} ({committed}); after a deliberate layout change run --update-manifest')
        return 1
    if a.check:
        print('matches the committed manifest')
        return 0
    root = Path(a.out) / sid
    if root.exists():
        raise SystemExit(f'{root} already exists; nothing is overwritten.')
    root.mkdir(parents=True)
    for r in (r for r in rows if r['kind'] == 'D'):
        (root / r['path']).mkdir(parents=True, exist_ok=True)
    for r in (r for r in rows if r['kind'] == 'F'):
        p = root / r['path']
        if os.path.lexists(p):
            raise SystemExit(f"Collision on the file system (a name the file system treats as equal): {to_ascii(r['path'])}")
        p.write_bytes(r['data'])
        os.utime(p, (r['mt'], r['mt']))
    print(f'created {root}')
    return 0


# ---- verify ------------------------------------------------------------------------------------------------------------------------
def verify_tree(root, manifest, size_only=False, strict_mtime=False):
    """Returns (findings, counts, summary_line)."""
    root = os.path.abspath(root).rstrip('/')
    if not os.path.isdir(root):
        raise SystemExit(f'Not a directory: {root}')
    expected = load_manifest(manifest)
    actual = {}
    for d, dn, fn in os.walk(root):
        for n in dn + fn:
            if n in ('desktop.ini', 'Thumbs.db'):
                continue
            p = os.path.join(d, n)
            actual[os.path.relpath(p, root)] = os.path.isdir(p) and not os.path.islink(p)
    counts = dict.fromkeys(['MISSING', 'EXTRA', 'FORM', 'CASE', 'SIZE', 'HASH', 'MTIME', 'KIND', 'OK'], 0)
    findings = []

    def add(kind, text):
        counts[kind] += 1
        findings.append(f'{kind}  {text}')

    by_nfc, by_fold = {}, {}
    for k in sort_ordinal(actual):
        n = unicodedata.normalize('NFC', k)
        by_nfc.setdefault(n, []).append(k)
        by_fold.setdefault(upper_invariant(n), []).append(k)
    claimed = set()
    sorted_exp = sort_ordinal(expected)
    for e in sorted_exp:  # pass 1: exact names claim their entry first, so a twin cannot take its sibling's entry as FORM
        if e in actual:
            claimed.add(e)

    def unclaimed(m, key):
        return next((c for c in m.get(key, []) if c not in claimed), None)

    for e in sorted_exp:
        x = expected[e]
        clean = True
        if e in actual:
            found = e
        else:
            nfc = unicodedata.normalize('NFC', e)
            found = unclaimed(by_nfc, nfc)
            if found is not None:
                clean = False
                add('FORM', f'{to_ascii(e)}  ->  found as  {to_ascii(found)}')
            else:
                found = unclaimed(by_fold, upper_invariant(nfc))
                if found is not None:
                    clean = False
                    add('CASE', f'{to_ascii(e)}  ->  found as  {to_ascii(found)}')
            if found is None:
                add('MISSING', to_ascii(e))
                continue
            claimed.add(found)
        is_dir = actual[found]
        if (x['kind'] == 'D') != is_dir:
            add('KIND', f"{to_ascii(e)}: expected {x['kind']}, found {'directory' if is_dir else 'file'}")
            continue
        if x['kind'] == 'D':
            counts['OK'] += clean
            continue
        full = os.path.join(root, found)
        st = os.stat(full)
        if str(st.st_size) != x['size']:
            add('SIZE', f"{to_ascii(e)}: expected {x['size']}, found {st.st_size}")
            continue
        if not size_only:
            try:
                h = sha256_file(full)
                if h != x['hash']:
                    add('HASH', f"{to_ascii(e)}: expected {x['hash'][:12]} found {h[:12]}")
                    continue
            except OSError as ex:
                add('HASH', f'{to_ascii(e)}: cannot read: {ex}')
                continue
        mt = utc(st.st_mtime)
        if mt != x['mtime']:
            add('MTIME', f"{to_ascii(e)}: expected {x['mtime']}, found {mt}")
            clean = False
        counts['OK'] += clean
    for a in sort_ordinal(actual):
        if a not in claimed and a not in expected:
            add('EXTRA', to_ascii(a))
    c = counts
    summary = (f"expected {len(expected)}, found {len(actual)}: ok {c['OK']}, missing {c['MISSING']}, extra {c['EXTRA']}, form {c['FORM']}, "
               f"case {c['CASE']}, size {c['SIZE']}, hash {c['HASH']}, kind {c['KIND']}, mtime {c['MTIME']}"
               + ('' if strict_mtime else ' (information only; --strict-mtime makes it a finding)'))
    return findings, counts, summary


def print_findings(findings, max_lines):
    for f in findings[:max_lines]:
        print(f)
    if len(findings) > max_lines:
        print(f'... {len(findings) - max_lines} more')


def cmd_verify(a):
    findings, c, summary = verify_tree(a.root, a.manifest, a.size_only, a.strict_mtime)
    print_findings(findings, a.max_lines)
    print(summary)
    fail = sum(c[k] for k in ('MISSING', 'EXTRA', 'FORM', 'CASE', 'SIZE', 'HASH', 'KIND')) + (c['MTIME'] if a.strict_mtime else 0)
    return 1 if fail else 0


# ---- compare -----------------------------------------------------------------------------------------------------------------------
def compare_findings(expected_file, tool, lines, root='golden-unicode-v2', fail_on_stale=False, max_lines=80):
    """Returns (exit_code, output_lines)."""
    if not TOOL_RE.match(tool):
        raise SystemExit(f'unknown tool {tool}')
    rules = []
    for n, line in enumerate(Path(expected_file).read_text(encoding='utf-8').split('\n'), 1):
        if not line or line.startswith('#'):
            continue
        c = line.split('\t')
        if len(c) < 4:
            raise SystemExit(f'{expected_file}:{n} needs tool, class, path and issue, tab-separated')
        if not TOOL_RE.match(c[0]):
            raise SystemExit(f"{expected_file}:{n} unknown tool '{c[0]}'")
        if c[1] not in CLASSES:
            raise SystemExit(f"{expected_file}:{n} unknown class '{c[1]}'")
        if not c[3].strip():
            raise SystemExit(f'{expected_file}:{n} has no issue: an accepted deviation needs one')
        rx = re.compile('^' + re.escape(c[2]).replace('\\*', '.*') + '$', re.S)
        rules.append({'line': n, 'tool': c[0], 'cls': c[1], 'path': c[2], 'issue': c[3], 'note': c[4] if len(c) > 4 else '', 'rx': rx, 'hits': 0})
    base = tool.split(':')[0]
    mine = [r for r in rules if r['tool'] in (tool, base)]
    unexpected, seen = [], 0
    marker = f'/{root}/'
    for line in lines:
        m = re.match(r'^([A-Z]+)\s+(.*)$', line)
        if not m or m.group(1) not in CLASSES:
            continue
        seen += 1
        rest = m.group(2)
        for sep in ('  ->  ', '  listed under  '):
            if sep in rest:
                rest = rest[:rest.index(sep)]
        if ': ' in rest:
            rest = rest[:rest.index(': ')]
        if marker in rest:
            rest = rest[rest.index(marker) + len(marker):]
        rest = rest.strip()
        rule = next((r for r in mine if r['cls'] == m.group(1) and r['rx'].match(rest)), None)
        if rule:
            rule['hits'] += 1
        else:
            unexpected.append(line)
    used, stale = [r for r in mine if r['hits'] > 0], [r for r in mine if r['hits'] == 0]
    out = [f'UNEXPECTED  {u}' for u in unexpected[:max_lines]]
    if len(unexpected) > max_lines:
        out.append(f'... {len(unexpected) - max_lines} more unexpected')
    for r in used:
        out.append(f"EXPECTED    {r['hits']:5d} x {r['cls']} {r['path']}  ({r['issue']}{'; ' + r['note'] if r['note'] else ''})")
    for r in stale:
        out.append(f"STALE       {r['cls']} {r['path']}  ({r['issue']}): no finding matched; fixed? (line {r['line']})")
    out.append(f'{seen} findings: unexpected {len(unexpected)}, expected {seen - len(unexpected)} (by {len(used)} rules), stale rules {len(stale)}')
    return (1 if unexpected or (fail_on_stale and stale) else 0), out


def cmd_compare(a):
    code, out = compare_findings(a.expected, a.tool, Path(a.findings).read_text(encoding='utf-8').split('\n'), a.root, a.fail_on_stale, a.max_lines)
    print('\n'.join(out))
    return code


# ---- IPC (daemon-view) -------------------------------------------------------------------------------------------------------------
def b64u(b):
    return base64.urlsafe_b64encode(b).decode().rstrip('=')


def unb64u(t):
    if not re.fullmatch(r'[A-Za-z0-9_-]+', t) or len(t) % 4 == 1:
        return None
    try:
        b = base64.urlsafe_b64decode(t + '=' * (-len(t) % 4))
    except ValueError:
        return None
    return b if b64u(b) == t else None


def ipc_proof(key, side, scope, profile, cn, sn, client_proof=None):
    msg = f'unidrive-ipc-v2|{side}|{scope}|{len(profile.encode())}:{profile}|{cn}|{sn}'
    if side == 'server':
        msg += f'|{client_proof}'
    return b64u(hmac.new(key, msg.encode(), hashlib.sha256).digest())


def profile_dir(profile, config_dir=None):
    if not config_dir:
        config_dir = os.environ.get('UNIDRIVE_CONFIG_DIR') or str(Path.home() / '.config' / 'unidrive')
    return Path(config_dir) / profile


def default_socket(profile):
    return os.path.join(os.environ.get('XDG_RUNTIME_DIR') or f'/run/user/{os.getuid()}', f'unidrive-{profile}.sock')


class Ipc:
    def __init__(self, profile, scope='read', config_dir=None, sock_path=None, timeout=5.0):
        self.profile, self.scope, self.config_dir, self.sock_path, self.timeout = profile, scope, config_dir, sock_path or default_socket(profile), timeout
        self.sock = self.rf = None
        self.connect()

    def connect(self):
        self.close()
        tf = profile_dir(self.profile, self.config_dir) / ('ipc.token' if self.scope == 'full' else 'ipc.read.token')  # read afresh: a restarted daemon has new tokens
        key = None
        if tf.exists():
            key = unb64u(tf.read_text().strip())
            if key is None or len(key) != 32:
                raise RuntimeError(f'the IPC token file {tf} is not a valid token')
        s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        s.settimeout(self.timeout)
        try:
            s.connect(self.sock_path)
            self.sock, self.rf = s, s.makefile('rb')
            if key is None:
                st = self.ask({'verb': 'daemon.status'})
                if int(st.get('protocol_version', 1)) >= 2:
                    raise RuntimeError(f'cannot authenticate to this daemon (IPC protocol 2): no IPC token at {tf}')
                print(f'warning: no IPC token at {tf}; connecting without authentication', file=sys.stderr)
            else:
                cn = b64u(os.urandom(16))
                s1 = self.ask({'verb': 'hello', 'protocol': 2, 'scope': self.scope, 'client': 'unidrive-golden', 'nonce': cn})
                sn = unb64u(s1['snonce']) if isinstance(s1.get('snonce'), str) else None
                if not (s1.get('ok') is True and s1.get('step') == 1 and sn is not None and len(sn) == 16):
                    raise RuntimeError(f"the daemon rejected the IPC handshake ({s1.get('error')})")
                proof = ipc_proof(key, 'client', self.scope, self.profile, cn, s1['snonce'])
                s2 = self.ask({'verb': 'hello.proof', 'proof': proof})
                if s2.get('ok') is not True:
                    raise RuntimeError(f"the daemon rejected the IPC token ({s2.get('error')})")
                want = ipc_proof(key, 'server', self.scope, self.profile, cn, s1['snonce'], proof)
                if s2.get('scope') != self.scope or not hmac.compare_digest(want, str(s2.get('proof'))):
                    raise RuntimeError('the daemon proof does not match the IPC token')
            s.settimeout(None)
        except Exception:
            s.close()
            self.sock = None
            raise

    def ask(self, req):
        self.sock.sendall((json.dumps(req, ensure_ascii=False, separators=(',', ':')) + '\n').encode())
        line = self.rf.readline()
        if not line:
            raise RuntimeError('daemon closed the connection')
        return json.loads(line.decode('utf-8'))

    def request(self, req):
        for attempt in (1, 2, 3):
            try:
                if self.sock is None:
                    self.connect()
                return self.ask(req)
            except (OSError, RuntimeError):
                self.close()
                if attempt == 3:
                    raise
                time.sleep(2)

    def close(self):
        if self.sock:
            self.sock.close()
        self.sock = self.rf = None


def daemon_findings(ipc, prefix, manifest, watch=False, timeout=600, log=print):
    rows = []
    for line in Path(manifest).read_text(encoding='utf-8').split('\n'):
        if line and not line.startswith('#'):
            c = line.split('\t')
            rows.append({'path': prefix + '/' + expand_tokens(c[5]), 'kind': c[0], 'size': c[1]})
    expected, merged = {}, []
    for r in rows:  # NFC names first: state.db keys paths in NFC (archive#171), so the NFC member of a twin pair owns the key
        if r['path'] == unicodedata.normalize('NFC', r['path']):
            expected[r['path']] = r
    for r in rows:
        key = unicodedata.normalize('NFC', r['path'])
        if r['path'] == key:
            continue
        if key in expected:
            merged.append(f"NFCMERGE {to_ascii(r['path'])}  ->  one row with  {to_ascii(key)}")
        else:
            expected[key] = r

    def tree():
        out, leaks, stack = {}, [], [prefix]
        while stack:
            p = stack.pop()
            r = ipc.request({'verb': 'hydration.list', 'prefix': p})
            if not r.get('ok'):
                raise RuntimeError(f"hydration.list {to_ascii(p)} failed: {r.get('error')}")
            for e in r.get('entries', []):
                if e['path'].rsplit('/', 1)[0] != p:
                    leaks.append(f"{to_ascii(e['path'])}  listed under  {to_ascii(p)}")
                    continue
                out[e['path']] = e
                if e.get('folder'):
                    stack.append(e['path'])
        return out, leaks

    deadline = time.time() + timeout
    while True:
        got, leaks = tree()
        ents = list(got.values())
        pending = sum(bool(e.get('pending_upload')) for e in ents)
        errors = sum(bool(e.get('error')) for e in ents)
        norem = sum(1 for e in ents if not e.get('folder') and not e.get('remote_id'))
        log(f"  {time.strftime('%H:%M:%S')}  entries {len(ents)}/{len(expected)}, pending {pending}, error {errors}, no remote id {norem}, leaked {len(leaks)}")
        if not watch or (pending == 0 and len(ents) >= len(expected)) or time.time() > deadline:
            break
        time.sleep(10)
    f = [f'LEAK     {x}' for x in leaks] + merged
    for k in sort_ordinal(expected):
        x = expected[k]
        if k not in got:
            f.append(f"MISSING  {to_ascii(x['path'])}")
            continue
        e = got[k]
        if x['kind'] == 'F':
            if str(e.get('size')) != x['size']:
                f.append(f"SIZE     {to_ascii(k)}: expected {x['size']}, daemon says {e.get('size')}")
            if e.get('error'):
                f.append(f'ERROR    {to_ascii(k)}')
            elif e.get('pending_upload'):
                f.append(f'PENDING  {to_ascii(k)}')
            elif not e.get('remote_id'):
                f.append(f'NOREMOTE {to_ascii(k)}')
    f += [f'EXTRA    {to_ascii(k)}' for k in sort_ordinal(got) if k not in expected]
    return f


def cmd_daemon_view(a):
    ipc = Ipc(a.profile, 'read', a.config_dir, a.socket)
    f = daemon_findings(ipc, a.prefix, a.manifest, a.watch, a.timeout)
    print_findings(f, a.max_lines)
    print(f'findings: {len(f)}')
    return 1 if f else 0


# ---- remote-live -------------------------------------------------------------------------------------------------------------------
def remote_findings(profile, prefix, subtree, manifest, launcher, env=None, log=print):
    expected = load_manifest(manifest)

    def listing(cloud):
        p = subprocess.run([launcher, '-p', profile, 'ls', '--live', cloud], capture_output=True, env=env)
        text = [l for l in p.stdout.decode('utf-8', 'replace').split('\n') + p.stderr.decode('utf-8', 'replace').split('\n') if l and not l.startswith('Picked up JAVA_TOOL_OPTIONS')]
        if p.returncode != 0:
            return None, ' | '.join(text)
        ents = []
        for l in text:
            m = re.search(r'\s{2}(\d{4}-\d{2}-\d{2}T\S+|-)$', l)
            if not m:
                continue
            rest = l[:len(l) - len(m.group(1))].rstrip(' ')  # ASCII spaces only: a name may end in U+00A0 or U+3000
            size = None
            m2 = re.search(r'\s{2,}(\d+) B$', rest)
            if m2:
                size, rest = m2.group(1), rest[:m2.start()]
            else:
                m3 = re.search(r'\s{2,}[\d.,]+ (?:KB|MB|GB|KiB|MiB|GiB)$', rest)
                if m3:
                    rest = rest[:m3.start()]
            isdir = rest.endswith('/')
            ents.append({'name': rest[:-1] if isdir else rest, 'dir': isdir, 'size': size})
        return ents, None

    queue, got, findings, listed, calls = [subtree or ''], {}, [], set(), 0
    while queue:
        rel = queue.pop(0)
        ents, err = listing(f'{prefix}/{rel}' if rel else prefix)
        calls += 1
        if ents is None:
            findings.append(f'LISTFAIL {to_ascii(rel)}: {err}')
            continue
        listed.add(rel)
        for e in ents:
            full = f"{rel}/{e['name']}" if rel else e['name']
            got[full] = e
            if e['dir']:
                queue.append(full)
        if calls % 25 == 0:
            log(f'  ... {calls} folders listed, {len(got)} entries')
    in_scope = (lambda k: k == subtree or k.startswith(subtree + '/')) if subtree else (lambda k: True)
    parent = lambda k: k.rsplit('/', 1)[0] if '/' in k else ''
    for k in sort_ordinal(expected):
        if not in_scope(k) or parent(k) not in listed:
            continue
        x = expected[k]
        if k not in got:
            nfc = unicodedata.normalize('NFC', k)
            alt = next((g for g in got if unicodedata.normalize('NFC', g) == nfc and g not in expected), None)
            findings.append(f'FORM     {to_ascii(k)}  ->  provider has  {to_ascii(alt)}' if alt else f'MISSING  {to_ascii(k)}')
            continue
        e = got[k]
        if (x['kind'] == 'D') != e['dir']:
            findings.append(f'KIND     {to_ascii(k)}')
        elif x['kind'] == 'F' and e['size'] is not None and e['size'] != x['size']:
            findings.append(f"SIZE     {to_ascii(k)}: expected {x['size']}, provider {e['size']}")
    findings += [f'EXTRA    {to_ascii(k)}' for k in sort_ordinal(got) if k not in expected]
    checked = sum(1 for k in expected if in_scope(k) and parent(k) in listed)
    return findings, f'folders listed {calls}, entries seen {len(got)}, manifest entries checked {checked}'


def default_launcher():
    return shutil.which('unidrive') or str(Path.home() / '.local/bin/unidrive')


def cmd_remote_live(a):
    f, summary = remote_findings(a.profile, a.prefix, a.subtree, a.manifest, a.launcher or default_launcher())
    print_findings(f, a.max_lines)
    print(f'{summary}; findings {len(f)}')
    return 1 if f else 0


# ---- selftest ----------------------------------------------------------------------------------------------------------------------
def cmd_selftest(a):
    work = Path(a.work) if a.work else Path(tempfile.mkdtemp(prefix='golden-selftest-'))
    work.mkdir(parents=True, exist_ok=True)
    sid = json.loads((HERE / 'layout.json').read_text(encoding='utf-8'))['id']
    U = expand_tokens
    first = lambda d, pred=lambda p: True: sorted(p for p in Path(d).rglob('*') if p.is_file() and pred(p))[0]
    sizes = 'binary/sizes'

    def rm(r, rel): os.remove(os.path.join(r, U(rel)))
    def truncate(r): os.truncate(first(Path(r) / sizes, lambda p: p.stat().st_size > 100), 10)
    def content(r):
        f = first(Path(r) / sizes, lambda p: p.stat().st_size > 100)
        st = f.stat(); b = bytearray(f.read_bytes()); b[5] ^= 0xFF; f.write_bytes(b); os.utime(f, (st.st_atime, st.st_mtime))
    def nfc_to_nfd(r):
        f = first(Path(r) / 'european-latin', lambda p: unicodedata.normalize('NFD', p.name) != p.name)
        f.rename(f.with_name(unicodedata.normalize('NFD', f.name)))
    def case(r):
        d = Path(r) / 'hazards/case-fold'; (d / 'STRASSE.txt').rename(d / 'tmp.txt'); (d / 'tmp.txt').rename(d / 'strasse.txt')
    def dir_to_file(r):
        d = Path(r) / 'hazards/empty-things'; shutil.rmtree(d); d.write_text('x')
    def twin_nfd_to_nfc(r):
        d = Path(r) / 'hazards/nfc-nfd-twins'; (d / U('caf{U+00E9}.txt')).unlink(); (d / U('cafe{U+0301}.txt')).rename(d / U('caf{U+00E9}.txt'))
    tw = 'hazards/nfc-nfd-twins/'
    cases = {
        'unchanged': (lambda r: None, {}),
        'nfc-to-nfd': (nfc_to_nfd, {'FORM': 1}),
        'truncate': (truncate, {'SIZE': 1}),
        'content': (content, {'HASH': 1}),
        'mtime': (lambda r: os.utime(first(Path(r) / sizes), (time.time(), time.time())), {'MTIME': 1}),
        'extra': (lambda r: Path(r, 'cats/extra.txt').write_text('x'), {'EXTRA': 1}),
        'delete': (lambda r: first(Path(r) / sizes).unlink(), {'MISSING': 1}),
        'case': (case, {'CASE': 1}),
        'dir-to-file': (dir_to_file, {'KIND': 1, 'MISSING': 7}),
        'twin-del-nfc': (lambda r: rm(r, tw + 'caf{U+00E9}.txt'), {'MISSING': 1}),
        'twin-del-nfd': (lambda r: rm(r, tw + 'cafe{U+0301}.txt'), {'MISSING': 1}),
        'twin-del-ang-nfd': (lambda r: rm(r, tw + 'A{U+030A}ngstro{U+0308}m.txt'), {'MISSING': 1}),
        'twin-nfd-to-nfc': (twin_nfd_to_nfc, {'MISSING': 1, 'SIZE': 1}),
        'sigma-del': (lambda r: rm(r, 'hazards/case-fold/{U+03A3}.txt'), {'MISSING': 1}),
        'nfkc-ligature-del': (lambda r: rm(r, 'hazards/nfkc-only/{U+FB01}les.txt'), {'MISSING': 1}),
        'angstrom-212b-del': (lambda r: rm(r, tw + '{U+212B}ngstr{U+00F6}m.txt'), {'MISSING': 1}),
    }
    failed = total = 0
    if a.only in ('all', 'verify'):
        for name, (do, want) in cases.items():
            total += 1
            out = work / name
            ns = argparse.Namespace(out=str(out), check=False, update_manifest=False, layout=str(HERE / 'layout.json'), manifest=str(HERE / 'manifest.tsv'))
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                rc = cmd_generate(ns)
            if rc != 0:
                raise SystemExit(f'generate failed for {name}')
            root = out / sid
            do(str(root))
            _, c, _ = verify_tree(root, HERE / 'manifest.tsv', strict_mtime=True)
            bad = [f'{k} {c[k]} (want {want.get(k, 0)})' for k in ('MISSING', 'EXTRA', 'FORM', 'CASE', 'SIZE', 'HASH', 'KIND', 'MTIME') if c[k] != want.get(k, 0)]
            if bad:
                failed += 1
                print(f"FAIL  {name}  {', '.join(bad)}")
            else:
                print(f'ok    {name}')
    if a.only in ('all', 'compare'):
        T = '\t'
        rules_ok = ['# a comment and an empty line are ignored', '', f'verify{T}MTIME{T}*{T}gkrost/unidrive#486{T}times', f'verify{T}SIZE{T}cats/x [final].txt{T}gkrost/unidrive#1{T}brackets are literal',
                    f'verify{T}MISSING{T}Docs/*{T}gkrost/unidrive#2{T}case-sensitive', f'daemon-view{T}NFCMERGE{T}hazards/nfc-nfd-twins/*{T}gkrost/unidrive#491', f'daemon-view{T}LEAK{T}african/*{T}gkrost/unidrive#489',
                    f'daemon-view{T}ERROR{T}binary/sizes/b0000000.bin{T}gkrost/unidrive#485', f'verify:mirror{T}MISSING{T}empty/*{T}gkrost/unidrive#500{T}only on the mirror']
        vo = ['MTIME  binary/sizes/b0000000.bin: expected 2026-01-01T12:00:00Z, found 2026-10-03T06:46:35Z', 'MTIME  african/a.txt: expected 2026-01-01T12:00:00Z, found 2026-10-03T06:34:36Z',
              'SIZE  cats/x [final].txt: expected 10, found 9', 'expected 539, found 539: ok 536, missing 0, extra 0, form 0, case 0, size 1, hash 0, kind 0, mtime 2']
        pre = '/_INBOX/golden-unicode-v2/'
        do_ = ['09:31:35  entries 138/534, pending 2, error 2, no remote id 2, leaked 1', f'LEAK     {pre}african/{{U+1E900}}/{{U+1E901}}/f.txt  listed under  {pre}african/{{U+1E900}}',
               f'NFCMERGE {pre}hazards/nfc-nfd-twins/cafe{{U+0301}}.txt  ->  one row with  {pre}hazards/nfc-nfd-twins/caf{{U+00E9}}.txt', f'MISSING  {pre}cats/{{U+1F431}}', 'findings: 3']
        cc = [
            ('all-expected', rules_ok, 'verify', vo, False, 0, 'unexpected 0, expected 3 (by 2 rules), stale rules 1'),
            ('one-unexpected', rules_ok, 'verify', vo + ['HASH  cats/y.txt: expected aaaa found bbbb'], False, 1, 'unexpected 1,'),
            ('brackets-literal', rules_ok, 'verify', ['SIZE  cats/x f.txt: expected 10, found 9'], False, 1, 'unexpected 1,'),
            ('case-sensitive', rules_ok, 'verify', ['MISSING  docs/a.txt', 'MISSING  Docs/a.txt'], False, 1, 'unexpected 1, expected 1'),
            ('other-tool-ignored', rules_ok, 'remote-live', ['MTIME  a.txt: x'], False, 1, 'unexpected 1, expected 0 (by 0 rules), stale rules 0'),
            ('daemon-prefix-stale', rules_ok, 'daemon-view', do_, False, 1, 'unexpected 1, expected 2 (by 2 rules), stale rules 1'),
            ('fail-on-stale', rules_ok, 'verify', vo, True, 1, 'stale rules 1'),
            ('rule-without-issue', [f'verify{T}MTIME{T}*{T} '], 'verify', vo, False, 1, 'has no issue'),
            ('surface-applies', rules_ok, 'verify:mirror', ['MISSING  empty/dir'], False, 0, 'unexpected 0, expected 1'),
            ('surface-not-other', rules_ok, 'verify:mount', ['MISSING  empty/dir'], False, 1, 'unexpected 1,'),
            ('unknown-class', [f'verify{T}TIME{T}*{T}gkrost/unidrive#486'], 'verify', vo, False, 1, 'unknown class'),
        ]
        for name, rules, tool, lines, fos, want_exit, want_text in cc:
            total += 1
            ef = work / f'{name}.tsv'
            ef.write_text('\n'.join(rules) + '\n', encoding='utf-8')
            try:
                code, out = compare_findings(ef, tool, [expand_tokens(l) for l in lines], fail_on_stale=fos)
                text = '\n'.join(out)
            except SystemExit as ex:
                code, text = 1, str(ex)
            bad = []
            if code != want_exit: bad.append(f'exit {code} (want {want_exit})')
            if want_text not in text: bad.append(f'output lacks [{want_text}]')
            if bad:
                failed += 1
                print(f"FAIL  compare:{name}  {', '.join(bad)}")
            else:
                print(f'ok    compare:{name}')
    print(f'{total - failed} of {total} passed')
    if not a.keep:
        shutil.rmtree(work, ignore_errors=True)
    return 1 if failed else 0


# ---- run ---------------------------------------------------------------------------------------------------------------------------
def step(t):
    print(f'\n== {t}')


def cmd_run(a):
    sid = json.loads((HERE / 'layout.json').read_text(encoding='utf-8'))['id']
    launcher = a.launcher or default_launcher()
    work = Path(a.work or (Path.home() / 'unidrive-golden/runs' / time.strftime('%Y%m%d-%H%M%S')))
    if work.exists():
        raise SystemExit(f'{work} exists; nothing is overwritten.')
    out = work / 'out'
    out.mkdir(parents=True)
    man, lay = HERE / 'manifest.tsv', HERE / 'layout.json'
    step('preflight')
    if cmd_generate(argparse.Namespace(out=None, check=True, update_manifest=False, layout=str(lay), manifest=str(man))) != 0:
        raise SystemExit('layout.json and manifest.tsv have drifted apart')
    procs, env = [], dict(os.environ)
    profile, mount, prefix = a.profile, a.mount, a.prefix.strip('/')
    remote_dir = mirror = None
    try:
        if a.localfs:
            profile = 'golden_' + re.sub(r'[^A-Za-z0-9]', '_', work.name)
            cfg, remote_dir, mirror = work / 'config', work / 'remote', work / 'engine-sync-root'
            mount = str(work / 'mount')
            for d in (cfg, remote_dir / '_INBOX', mirror, Path(mount)):
                d.mkdir(parents=True, exist_ok=True)
            (cfg / 'config.toml').write_text(f'[general]\ndefault_profile = "{profile}"\n\n[providers.{profile}]\ntype = "localfs"\nmode = "mount"\n'
                                             f'root_path = "{remote_dir}"\nsync_root = "{mirror}"\nsync_path = ["/_INBOX"]\n')
            env['UNIDRIVE_CONFIG_DIR'] = str(cfg)
            env['UNIDRIVE_DIAG_DIR'] = str(out / 'diagnostics')
            step(f'start an isolated daemon and mount (profile {profile})')
            procs.append(subprocess.Popen([launcher, '-p', profile, 'daemon', 'run', '--poll-interval', '60'], env=env, stdout=open(out / 'daemon.log', 'wb'), stderr=subprocess.STDOUT))
            for _ in range(120):
                if os.path.exists(default_socket(profile)):
                    break
                time.sleep(1)
            else:
                raise SystemExit('the daemon did not come up (see out/daemon.log)')
            procs.append(subprocess.Popen([launcher, '-p', profile, 'mount', mount], env=env, stdout=open(out / 'mount.log', 'wb'), stderr=subprocess.STDOUT))
            for _ in range(60):
                if os.path.ismount(mount):
                    break
                time.sleep(1)
            else:
                raise SystemExit('the mount did not come up (see out/mount.log)')
            ipc_cfg = str(cfg)
        else:
            if not (profile and mount):
                raise SystemExit('give --localfs, or --profile and --mount of an already running daemon and mount')
            if not os.path.ismount(mount):
                raise SystemExit(f'{mount} is not a mount point')
            ipc_cfg = None
        step('staging tree')
        staging = work / 'staging'
        cmd_generate(argparse.Namespace(out=str(staging), check=False, update_manifest=False, layout=str(lay), manifest=str(man)))
        golden = staging / sid
        target = Path(mount) / prefix / sid
        if target.exists():
            raise SystemExit(f'{target} exists; nothing is overwritten (remove it or use another --prefix).')
        step('copy the tree into the mount')
        t0 = time.time()
        copy_errors = []
        try:
            shutil.copytree(golden, target, copy_function=shutil.copy2)
        except shutil.Error as ex:  # copytree finishes the rest and raises at the end: a refused name is a finding, not a stop
            copy_errors = [(to_ascii(os.path.relpath(s, golden)), str(e).split(':')[0]) for s, _, e in ex.args[0]]
        print(f'  copy returned after {time.time() - t0:.1f} s, {len(copy_errors)} error(s)')
        for n, e in copy_errors:
            print(f'  ERR {n}: {e}')
        step(f'wait until the daemon has nothing pending (timeout {a.timeout} s)')
        ipc = Ipc(profile, 'read', ipc_cfg)
        cloud_prefix = f'/{prefix}/{sid}'
        daemon_f = daemon_findings(ipc, cloud_prefix, man, watch=True, timeout=a.timeout)
        settled = not any(x.startswith('PENDING') for x in daemon_f)
        surfaces = [('mount', 'verify', str(target))]
        if remote_dir:
            surfaces = [('provider', 'verify', str(remote_dir / prefix / sid)), ('mirror', 'verify:mirror', str(mirror / prefix / sid))] + surfaces
        step('verify the surfaces against the manifest')
        files, results = {}, []
        for name, tool, root in surfaces:
            if not os.path.isdir(root):
                print(f'  {name:<9}skipped: {root} does not exist (this profile mode keeps no such copy)')
                continue
            f, c, summary = verify_tree(root, man, strict_mtime=True)
            (out / f'{name}.out').write_text('\n'.join(f + [summary]) + '\n', encoding='utf-8')
            files[name] = (tool, f)
            results.append((name, summary))
            print(f'  {name:<9}{summary}')
        files['daemon'] = ('daemon-view', daemon_f)
        results.append(('daemon', f'findings: {len(daemon_f)}'))
        print(f'  daemon   findings: {len(daemon_f)}')
        if a.remote_live:
            step("the provider's own listing (unidrive ls --live)")
            rf, rs = remote_findings(profile, cloud_prefix, a.subtree, man, launcher, env)
            files['remote-live'] = ('remote-live', rf)
            results.append(('remote', f'{rs}; findings {len(rf)}'))
            print(f'  remote   {rs}; findings {len(rf)}')
        exit_code = 0 if settled else 2
        if not settled:
            print('  NOT SETTLED within the timeout')
        step(f'compare with {Path(a.expected).name}' if a.expected else 'no --expected given: findings are not classified')
        for name, (tool, f) in files.items():
            (out / f'{name}.findings').write_text('\n'.join(f) + '\n', encoding='utf-8')
            if a.expected:
                code, o = compare_findings(a.expected, tool, f, sid)
                (out / f'{name}.compare.txt').write_text('\n'.join(o) + '\n', encoding='utf-8')
                print(f'  {name:<11}{o[-1]}')
                if code and exit_code == 0:
                    exit_code = 1
        lines = [f'{sid} run {work.name}', f'engine : {launcher}', f"target : {'isolated localfs profile ' + profile if a.localfs else 'profile ' + profile + ' via ' + mount}",
                 f"upload : {'settled' if settled else 'NOT SETTLED'}"] + [f'{n:<9} {s}' for n, s in results]
        (work / 'SUMMARY.txt').write_text('\n'.join(lines) + '\n')
        step('summary')
        print('\n'.join(lines))
        print(f'\nfull outputs: {out}')
        return exit_code
    finally:
        for p in reversed(procs):
            p.terminate()
        if a.localfs and mount:
            subprocess.run(['fusermount3', '-uz', mount], capture_output=True)
        for p in procs:
            try:
                p.wait(timeout=30)
            except subprocess.TimeoutExpired:
                p.kill()


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sp = ap.add_subparsers(dest='cmd', required=True)
    man, lay = str(HERE / 'manifest.tsv'), str(HERE / 'layout.json')
    g = sp.add_parser('generate'); g.add_argument('--out'); g.add_argument('--layout', default=lay); g.add_argument('--manifest', default=man)
    g.add_argument('--check', action='store_true'); g.add_argument('--update-manifest', action='store_true'); g.set_defaults(f=cmd_generate)
    v = sp.add_parser('verify'); v.add_argument('root'); v.add_argument('--manifest', default=man); v.add_argument('--size-only', action='store_true')
    v.add_argument('--strict-mtime', action='store_true'); v.add_argument('--max-lines', type=int, default=80); v.set_defaults(f=cmd_verify)
    c = sp.add_parser('compare'); c.add_argument('--expected', required=True); c.add_argument('--tool', required=True); c.add_argument('--findings', required=True)
    c.add_argument('--root', default='golden-unicode-v2'); c.add_argument('--fail-on-stale', action='store_true'); c.add_argument('--max-lines', type=int, default=80); c.set_defaults(f=cmd_compare)
    d = sp.add_parser('daemon-view'); d.add_argument('--profile', required=True); d.add_argument('--prefix', default='/_INBOX/golden-unicode-v2'); d.add_argument('--manifest', default=man)
    d.add_argument('--watch', action='store_true'); d.add_argument('--timeout', type=int, default=600); d.add_argument('--max-lines', type=int, default=60)
    d.add_argument('--config-dir'); d.add_argument('--socket'); d.set_defaults(f=cmd_daemon_view)
    r = sp.add_parser('remote-live'); r.add_argument('--profile', required=True); r.add_argument('--prefix', default='/_INBOX/golden-unicode-v2'); r.add_argument('--subtree')
    r.add_argument('--manifest', default=man); r.add_argument('--launcher'); r.add_argument('--max-lines', type=int, default=80); r.set_defaults(f=cmd_remote_live)
    s = sp.add_parser('selftest'); s.add_argument('--work'); s.add_argument('--only', choices=['all', 'verify', 'compare'], default='all'); s.add_argument('--keep', action='store_true'); s.set_defaults(f=cmd_selftest)
    u = sp.add_parser('run'); u.add_argument('--localfs', action='store_true', help='isolated localfs profile, own daemon and mount')
    u.add_argument('--profile'); u.add_argument('--mount'); u.add_argument('--prefix', default='_INBOX'); u.add_argument('--work'); u.add_argument('--launcher')
    u.add_argument('--expected'); u.add_argument('--timeout', type=int, default=900); u.add_argument('--remote-live', action='store_true'); u.add_argument('--subtree'); u.set_defaults(f=cmd_run)
    a = ap.parse_args()
    sys.exit(a.f(a))


if __name__ == '__main__':
    main()
