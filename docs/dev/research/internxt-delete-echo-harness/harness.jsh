import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import kotlin.coroutines.*;
import kotlinx.coroutines.*;
import org.krost.unidrive.*;

String PROFILE = System.getenv("H_PROFILE");
String ROOT = System.getenv("H_ROOT");
Path OUT = Paths.get(System.getenv("H_OUT"));
Path FLAG = Paths.get(System.getenv("H_FLAG"));
int MINUTES = Integer.parseInt(System.getenv("H_MINUTES"));
String[] PARENTS = System.getenv("H_PARENTS").split(",");
String SINCE = System.getenv("H_SINCE");

void log(String s) throws Exception { Files.writeString(OUT, s + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
String q(String s) { return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ").replace("\r", " ") + "\""; }
Object rb(java.util.function.Function<Continuation<Object>, Object> f) throws Exception {
    return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (s, c) -> f.apply((Continuation<Object>) c));
}

Files.deleteIfExists(OUT);
org.krost.unidrive.cli.Main m = new org.krost.unidrive.cli.Main();
m.setProvider(PROFILE);
CloudProvider p = m.createProvider();   // the one vault unlock of this run
rb(c -> p.authenticate(c));
log("{\"ev\":\"begin\",\"at\":" + q(java.time.Instant.now().toString()) + "}");
Object api = null;
for (Method mm : p.getClass().getMethods()) if (mm.getName().startsWith("getApi")) api = mm.invoke(p);
Method listFiles = null, listFolders = null, fileMeta = null, folderMeta = null;
for (Method mm : api.getClass().getMethods()) {
  if (mm.getName().equals("listFiles") && mm.getParameterCount() == 6) listFiles = mm;
  if (mm.getName().equals("listFolders") && mm.getParameterCount() == 6) listFolders = mm;
  if (mm.getName().equals("getFileMeta")) fileMeta = mm;
  if (mm.getName().equals("getFolderMeta")) folderMeta = mm;
}
Object apiF = api; Method lf = listFiles, ld = listFolders, fm = fileMeta, dm = folderMeta;

Map<String,String> tracked = new LinkedHashMap<>();   // uuid -> "parent/name"
Map<String,Boolean> trackedIsFolder = new HashMap<>();
long t0 = System.currentTimeMillis();
int round = 0;
boolean actionsStarted = false;
while (System.currentTimeMillis() - t0 < MINUTES * 60_000L) {
  long rs = System.currentTimeMillis();
  StringBuilder sb = new StringBuilder("{\"ev\":\"round\",\"n\":" + round + ",\"start\":" + q(java.time.Instant.now().toString()) + ",\"parents\":{");
  boolean firstP = true;
  for (String par : PARENTS) {
    String names;
    try {
      List<?> kids = (List<?>) rb(c -> p.listChildren(ROOT + "/" + par, c));
      StringBuilder ns = new StringBuilder("[");
      for (Object k : kids) {
        CloudItem ci = (CloudItem) k;
        if (round == 0) { tracked.put(ci.getId(), par + "/" + ci.getName()); trackedIsFolder.put(ci.getId(), ci.isFolder()); }
        ns.append(q(ci.getName())).append(",");
      }
      names = ns.append("]").toString();
    } catch (Exception e) { names = q("ERR " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage())); }
    sb.append(firstP ? "" : ",").append(q(par)).append(":").append(names); firstP = false;
  }
  sb.append("},\"trash\":");
  try {
    List<?> tr = (List<?>) rb(c -> p.listTrash(c));
    StringBuilder ts = new StringBuilder("[");
    for (Object t : tr) ts.append(q(t.toString())).append(",");
    sb.append(ts).append("]");
  } catch (Exception e) { sb.append(q("ERR " + e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage()))); }
  // the incremental feed (status=ALL, sort=updatedAt), both kinds, paged to the end
  for (int kind = 0; kind < 2; kind++) {
    Method mt = kind == 0 ? lf : ld;
    sb.append(kind == 0 ? ",\"feed_files\":[" : ",\"feed_folders\":[");
    int off = 0, total = 0, hits = 0; String err = null;
    try {
      while (true) {
        final int o = off;
        List<?> rows = (List<?>) rb(c -> { try { return mt.invoke(apiF, SINCE, 50, o, "ALL", "updatedAt", c); } catch (Exception e) { throw new RuntimeException(e); } });
        for (Object r : rows) {
          Method gu = r.getClass().getMethod("getUuid");
          String uuid = (String) gu.invoke(r);
          total++;
          if (tracked.containsKey(uuid)) {
            hits++;
            sb.append("{\"u\":").append(q(tracked.get(uuid)))
              .append(",\"status\":").append(q(String.valueOf(r.getClass().getMethod("getStatus").invoke(r))))
              .append(",\"deleted\":").append(r.getClass().getMethod("getDeleted").invoke(r))
              .append(",\"removed\":").append(r.getClass().getMethod("getRemoved").invoke(r))
              .append(",\"updatedAt\":").append(q(String.valueOf(r.getClass().getMethod("getUpdatedAt").invoke(r)))).append("},");
          }
        }
        if (rows.size() < 50) break;
        off += 50;
        if (off > 2000) { err = "cap"; break; }
      }
    } catch (Exception e) { err = e.getClass().getSimpleName() + ": " + String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage()); }
    sb.append("],\"feed_").append(kind == 0 ? "files" : "folders").append("_total\":").append(total).append(",\"feed_").append(kind == 0 ? "files" : "folders").append("_err\":").append(q(err));
  }
  // by-id meta of the tracked a*, b*, e* items and the first child of each pc* folder
  sb.append(",\"meta\":{");
  boolean firstM = true;
  for (Map.Entry<String,String> e : tracked.entrySet()) {
    String nm = e.getValue();
    boolean want = nm.matches("pa/a[0-9].*|pb/b[0-9]|pe/e[0-9].*|pc[0-9]/f1[.]txt");
    if (!want) continue;
    String res;
    try {
      boolean isF = trackedIsFolder.get(e.getKey());
      Method mt = isF ? dm : fm;
      Object r = rb(c -> { try { return mt.invoke(apiF, e.getKey(), c); } catch (Exception ex) { throw new RuntimeException(ex); } });
      res = String.valueOf(r.getClass().getMethod("getStatus").invoke(r)) + "/del=" + r.getClass().getMethod("getDeleted").invoke(r) + "/rem=" + r.getClass().getMethod("getRemoved").invoke(r);
    } catch (Exception ex) { Throwable t = ex.getCause() != null ? ex.getCause() : ex; res = "ERR " + t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()); if (res.length() > 120) res = res.substring(0,120); }
    sb.append(firstM ? "" : ",").append(q(nm)).append(":").append(q(res)); firstM = false;
  }
  sb.append("},\"end\":").append(q(java.time.Instant.now().toString())).append("}");
  log(sb.toString());
  if (round == 0) Files.writeString(FLAG, "baseline-done");
  round++;
  long left = 60_000L - (System.currentTimeMillis() - rs);
  if (left > 0) Thread.sleep(left);
}
log("{\"ev\":\"end\",\"at\":" + q(java.time.Instant.now().toString()) + "}");
p.close();
/exit
