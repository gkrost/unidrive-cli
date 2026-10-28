# Hydration IPC Write Protocol — Spec

**Status:** Implemented. This document is the client-facing contract for the
write-back side of the hydration IPC: the verb sequences a co-client follows
to create, write, move, and delete files through the daemon, the events it
gets back, and the guarantees the daemon makes underneath. A co-client should
be able to implement write-back from this document without reading engine
code. Request/reply byte shapes are pinned by the golden corpus under
`core/app/cli/src/test/resources/ipc-contract/` (replayed by
`IpcContractCorpusTest`), and the end-to-end sequences below are pinned by
`IpcWriteSequenceContractTest` over a live socket.

**Touches:**
- `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/` — SPI, implementation, IPC handler.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/` — transfer budget, state rows, scope.
- `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt` — wiring, startup order, replay.

## 1. Transport and lifecycle

- One Unix-domain socket per profile (same socket serves the push-only sync
  progress stream; the two subscriber sets are independent). Framing is NDJSON:
  one JSON object per `\n`-terminated line, UTF-8. One request line in, one
  reply line out, in order, on the same connection.
- The daemon binds the socket only after every handler is registered: **a
  connect that succeeds is guaranteed a reply for every documented verb.**
  Clients poll for the socket file's existence, connect, and send; there is no
  window in which an accepted request is silently dropped. A verb the daemon
  does not know is answered with `{"ok":false,"error":"unknown_verb"}`; a
  request without a `verb` field gets `{"ok":false,"error":"missing_verb"}` —
  never silence — with one exception: a request longer than 64 KiB (`MAX_REQUEST_BYTES`, counted before the
  newline) is not answered; the daemon logs a warning and closes the connection, so the client sees end of stream.
  A mirror profile's daemon has no hydration layer: it answers every hydration verb and `sync.enumerate` with
  `{"ok":false,"error":"wrong_mode"}`, and so `ignore.explain` when the engine has no upload-ignore layer. A
  client should still bound its wait for a reply line.
- Every connection authenticates first (`hello`, `hello.proof`) and is limited
  to the verbs of its scope: see [ipc-authentication.md](ipc-authentication.md).
- The verb is the top-level string member `verb` of the request object. A line
  that is not one JSON object, a `verb` that is not a string or appears twice,
  and nesting deeper than 64 levels are all answered with `missing_verb`.
- Replies are `{"ok":true,...}` or `{"ok":false,"error":"<token>"}`. Error
  tokens are the stable cross-repo contract listed in §7.
- Events flow only on connections that have issued `hydration.subscribe`.
  Each subscriber has a bounded queue (capacity 64); when it overflows the
  oldest event is dropped and one `{"event":"lost","since_last":N}` sentinel
  precedes the next deliverable event. A client must treat `lost` as
  "resync your per-file state from `hydration.list`".
- The daemon serves at most 32 connections at a time by default
  (`UNIDRIVE_IPC_MAX_CLIENTS`, `docs/env-vars.md`). A connection over that
  cap reads one line, `{"ok":false,"error":"too_many_clients"}`, and then end
  of stream; it is never served. The line is best effort (a reset can still
  lose it), and a client that sent a request at once reads it in place of the
  reply. Such a client should close that connection, not pool it, and back off
  before it connects again.
- A connection that sent no request for `UNIDRIVE_IPC_IDLE_TIMEOUT_MS` (30 min
  by default, `docs/env-vars.md`) is closed between requests, without a line.
  A `sync.subscribe` subscriber and a connection that has used any
  `hydration.*` verb are never closed for being idle. A pooled request that
  meets end of stream before any reply byte was not processed and can be sent
  again on a new connection.

## 2. Handle model

- Write verbs that open state (`hydration.create`, `hydration.open_write`,
  `hydration.open_write_begin`) carry an opaque `handle_id`. Handles live on
  the connection that opened them: when the connection drops, the daemon
  releases that connection's handles automatically (no close storm after a
  crash).
- archive#438: `hydration.close_handle` may arrive on another connection than the
  open (a pooled client hands out any idle connection). The daemon looks on
  the caller's connection first, then on the others — a handle id is the
  client's own name, so a match on another connection is taken only when it
  is unique across them, and one client's close never releases another
  client's handle with the same id. An id two connections held at the same
  time is closed only on its own connection (or by that connection
  dropping) until no connection holds it, so a repeated close from the
  client whose handle is already gone cannot find the other one. A close
  that finds nothing anywhere still answers ok.
- Daemon-issued ids. `hydration.open_write` and `hydration.create` sent
  WITHOUT `handle_id` make the daemon issue the id, and the ok reply carries
  it: `{"ok":true,"cache_path":...,"handle_id":"dh-<run>-<n>"}`. `<run>` is
  random for each daemon start and `<n>` a counter, so an issued id names
  exactly one handle of that run. The client uses that id for every later
  call (`close_handle`) and to correlate the `uploading` and `completed`
  events; those can reach the subscribe stream before the reply does, so a
  client keeps an event for an id it does not know yet until the reply
  arrives. An issued id is found on any connection (a pooled close works),
  and since no client can name one, a close from one client never releases
  another's handle. A client-named id that starts with `dh-` is refused with
  `invalid_handle_id` on every verb that opens a handle. A retry of that
  `open_write` on the same connection, for the same path, with no `handle_id`,
  while the reply that carries the id has not been returned, is the same open:
  the daemon returns the id it already issued and does not register a second
  one. The client does not send back an id it has not seen. `create` is not
  retried this way: it does not wait on the upload queue, and a second
  `create` of a path that exists answers `path_exists`. `daemon.status`
  lists `open_write_create_handle_ids` in `capabilities` when the daemon
  issues the ids of `open_write` and `create` only — not `open_write_begin`;
  an older engine answers `missing_handle_id` to a request without one.
  `open_write_begin` is unchanged: without `handle_id` it stays a bare
  truncate.
- Client-named ids (`wh-N`, ...) are still accepted, for one release, with
  the rule of the previous point; a client should move to daemon-issued ids.
  The protocol version does not change: the field is optional and additive.
- One connection per mount is the expected shape; a client correlates its own
  `handle_id` on the `completed` event to the write it started.
- `hydration.close_handle` releases a handle early (optional; the daemon
  also cleans up on connection close).

## 3. Write sequences

All remote paths are absolute (`/a/b.txt`). For a scoped profile the mount
root maps to the scope root, so the client prefixes the scope; paths outside
the scope are refused (§7).

### 3.1 New file

```
1. hydration.create  {path}                     -> {"ok":true,"cache_path":<local>,"handle_id":<daemon id>}
2. write the user's bytes into <cache_path>     (local file I/O, no daemon involvement)
3. hydration.open_write {path, cache_path}
                                                -> {"ok":true,"cache_path":...,"handle_id":<daemon id>}
4. await completed (direction=upload) for that handle_id
```

- Step 3 returns immediately: the upload runs in the daemon (queued, then
  transferred under the daemon-wide per-provider budget — see §6).
- The upload phase emits `queued` → `hydrating` → (`uploading`…) → `hydrated`
  → `completed ok:true`.
- `completed ok:true` is the ONLY signal that the cloud has the content. A
  client must not mark a file in-sync from `hydrated` alone — `hydrated`
  fires for downloads too and, for uploads, only says the daemon finished
  the transfer, which (for providers without atomic replace) is still the
  completion event's job to correlate.

### 3.2 Overwrite (save of an existing file)

Same sequence as 3.1 minus `create`: the client writes the full new content
to the cache path the engine returned (from `open_write_begin`, `create`, or
`open_read` for 3.4) and issues `open_write` with that path. Clients must send
the path the engine returned; a cache path outside the profile's hydration
cache folder is refused with `invalid_path` before anything is queued (§7).

- Base-etag guard: if the client observed an `etag` for the file (via
  `hydration.list`) it SHOULD send it as `base_etag`. The guard runs twice:
  when the row's token no longer matches — someone changed the cloud copy
  since the client last looked — the write is refused with
  `{"ok":false,"error":"conflict"}` BEFORE anything is uploaded; and where
  the provider supports conditional writes, the token is forwarded so a
  change that lands between the guard and the transfer fails the upload with
  `conflict` as well (upload-time convergence). Either way both copies
  survive. Absent `base_etag` (or a row with no token) the write is
  unconditional. A conflicted upload is terminal — it is not retried; the
  row stays visibly failed until the client re-sends with a fresh etag.

### 3.3 Truncate / fresh empty file

- `hydration.open_write_begin {path[, handle_id]}` prepares an empty cache
  file (O_TRUNC shape) and answers `{"ok":true,"cache_path":...}`. With
  `handle_id` the open is registered (dehydrate refuses while it is open);
  without one it is a one-shot truncate that registers nothing. On a file
  that exists in the cloud it also notes, in the row, that the cache copy
  now holds an edit the cloud has not seen, together with the cloud version
  (the row's `etag`) the edit starts from. `open_write` notes it too if it is
  not noted yet (a `base_etag` the client sent is that version). The note
  ends when an upload of the file lands. While it stands the cache copy is
  the copy of record: `open_read` and `hydrate` serve it as it is (whatever
  its size) instead of downloading over it, the enumeration's reap leaves it,
  and the start-up replay (section 6) sends it.

### 3.4 Append

No dedicated verb: the client reads the cache copy (after `open_read` if the
content is not local), appends, and issues `open_write` with the full new
content. The upload always replaces the remote object.

### 3.5 Rename / move (POSIX safe-save is 3.6)

```
hydration.rename {old_path, new_path}      -> {"ok":true}
errors: old_path_not_found / new_parent_not_found / new_path_exists /
        outside_scope / busy
```

- A never-uploaded source is renamed purely locally (row + cache file move);
  an uploaded source is moved in the cloud.
- `replace:false` refuses an existing destination (`new_path_exists`) — the
  historic contract; userland does unlink-then-rename.
- Both ends must lie inside the profile's scope; either end outside refuses
  with `outside_scope` and nothing is moved.

### 3.6 Replace-rename (editors' safe-save: temp file + rename over target)

```
1. hydration.create {handle_id, path=/dir/.tmp-save}   (or open_write_begin)
2. write the new content into the temp cache file
3. hydration.open_write {handle_id, path=/dir/.tmp-save, cache_path}
4. hydration.rename {old_path=/dir/.tmp-save, new_path=/dir/target, replace:true}
```

- With `replace:true` an existing FILE destination is deleted first through
  the same path `unlink` takes (trash/undo semantics), then the source takes
  its place. A folder destination is never replaced (`new_path_exists`).
- The delete-then-move is not atomic: a crash between the two leaves the
  destination tombstoned (recoverable) and the temp file intact.
- If an upload of the source or the destination is still queued or running,
  the rename is refused with `{"ok":false,"error":"busy"}` and nothing is
  touched; the client retries after the `completed` event for that handle.
  Safe-save closes the temp file before renaming, so this window is the
  normal case, not an edge.

### 3.7 Delete file / delete folder

```
hydration.unlink {path}   -> {"ok":true} | {"ok":false,"error":"path_is_folder"} | ...
hydration.rmdir   {path[,recursive]} -> {"ok":true} | {"ok":false,"error":"path_is_file"} |
                                          {"ok":false,"error":"not_empty"} | ...
```

- A never-uploaded file's row is hard-deleted (there is no cloud object to
  trash); otherwise the delete goes through the provider's trash.
- If the user deleted a file whose upload is still in flight, the client
  first issues `hydration.cancel {path}` (§5), then `unlink`.
- `rmdir` is strict (archive#1035): a folder with alive rows below answers `not_empty`, before
  anything is deleted (no provider refuses a folder with children; they delete the
  tree). A client that means the whole tree sends `"recursive":true`; the folder and
  everything below go in one call, except while a file below holds bytes only this
  computer has (a never-uploaded file, an edit that failed to upload, or a cache copy
  that was changed while the daemon was stopped and differs from what was last synced):
  that is `not_empty` too, and the user deletes such a file on its own (`unlink`). An engine
  before archive#1035 ignores the flag and deletes the tree either way, so a client may
  always send it.

### 3.8 Mkdir / rmdir

`hydration.mkdir` creates the folder in the cloud and emits
`hydrating`/`hydrated` for it; `hydration.rmdir` refuses non-empty folders
(`not_empty`, unless `"recursive":true`, section 3.7). Both refuse out-of-scope paths.

### 3.9 Two spellings of one name (`name_clash`)

The engine keys every path in NFC, so `caf` + U+00E9 and `cafe` + U+0301 are one row and one cloud path. A client
that holds both as two files would have the second one's bytes replace the first one's content in the cloud
(gkrost/unidrive-cli#24; owner decision 2026-10-03: refuse such pairs).

- The daemon remembers, per name below the root, the spelling the first mutating verb used for it (the `path` as the
  client sent it, before the NFC step). A later `create`, `mkdir`, `open_write`, `open_write_begin`, `rename` (either
  end), `unlink` or `rmdir` that names that name, or a folder above it, by another spelling answers
  `{"ok":false,"error":"name_clash"}` before anything is touched or queued. The client renames one of the two files.
- Only names with a non-ASCII character are tracked (a plain ASCII name cannot have a twin). A successful `unlink`,
  `rmdir` or the old end of a `rename` frees the spellings of the name and of everything below it.
- Reads are not guarded: a read verb resolves an NFD request to the NFC row (archive#171).
- **Limit:** the record is in memory, per daemon run. After a restart the first spelling a client names wins again; a
  persisted spelling needs a state.db column and is not built (#24 stays open for it). `HydrationIpcHandler` and
  `NameSpellings` hold the rule.

**A refusal that shows (`hydration.refuse_name`).** A client that sees both spellings on disk uploads neither, so no
mutating verb ever reaches the daemon and nothing above applies; without more, the files are in no count, listing or
status (golden run of 2026-10-27, #24). Such a client records the refusal instead:

```
hydration.refuse_name {path, sibling?}   -> {"ok":true}
hydration.refuse_name {path, clear:true} -> {"ok":true,"cleared":true|false}
```

- `path` is the file's engine path (NFC, as always), `sibling` the other file's name as the client spells it on disk.
  The daemon keeps the refusal as a row blocked by name with the reason `name_collides_after_normalisation`: `blocked`
  on the `hydration.list` entry and an item of `uploads.blocked` in `daemon.status`, like a drive's own `similar_name`.
  Such a row is never pending, never replayed at a start and never dropped as gone. Refusing again is the same
  refusal (the newest sibling is named).
- A name without a row gets a row that holds no bytes. A never-uploaded row (its file was created before the twin
  arrived) keeps its bytes and is marked. A cloud row is not marked (`path_exists`): a blocked row's delete and rename
  stay local, which a cloud item must not. Only files: a folder is `path_is_folder`, a missing parent `parent_not_found`.
- `clear` when the clash is over (one of the two renamed or deleted): a row that was only the refusal goes, a marked row
  keeps its bytes and waits for its upload again. A client clears before it creates the name: the refusal's row would
  otherwise answer that create with `path_exists`.
- `daemon.status` lists `hydration.refuse_name` in `capabilities` on an engine that answers the verb; a client sends it
  only then, and keeps the refusal to itself on an older engine.

**Browsing before the first scan has finished (`hydration.list_live`).** A profile's first enumeration writes its rows at
the end of a scan that takes hours on a big drive (#27). On an engine that lists `hydration.list_live` in the
`daemon.status` `capabilities`, `hydration.list` of a folder state.db does not know yet reads the folder from the
cloud (one provider listing, paged by the provider) and answers with it. Nothing changes on the wire: the same reply,
the same entries (cloud-only rows: `hydrated:false`, `remote_id` and `remote_modified_ms` set).

- **When.** While no enumeration has completed (`daemon.status` `enumeration.first` is true) and the folder holds no row
  that came from the cloud: the root, or a folder that the scan or an earlier live listing has brought in. A folder
  state.db knows keeps answering from state.db; after the first scan completes nothing here applies and a folder with
  no rows is empty. A path in the trash namespace, a view, or outside the `sync_path` scope is never read live.
- **What is kept.** The rows go into state.db as the scan writes them (same fields, same scope and name checks), so
  the scan later finds them and reaps nothing that exists in the cloud. A pending upload's row stays its own; two
  cloud items of one name are left to the scan, which places both under their decorated names.
- **Failure.** A listing that fails answers `hydration.list` with `ok:false` (ask again), never with an empty folder.
  A folder the cloud no longer has answers from state.db.
- **Cost.** One listing per folder at a time, four at once across folders, and a folder found empty in the cloud is not
  asked again for ten seconds. A client that lists many folders together may do so; the later ones wait for a permit.
- A client needs no new handling: it may read the capability to know that a folder lists at once during the first scan
  (to drop a "waiting for the scan" state for folders it can open), and treats an older engine as before.

## 4. Events (subscription stream)

| Event | Shape | Meaning |
|---|---|---|
| `queued` | `{"event":"queued","path":…}` | upload accepted, waiting for its turn (column: Waiting) |
| `hydrating` | `{"event":"hydrating","path":…}` | transfer started (upload or download) |
| `uploading` | `{"event":"uploading","path":…,"handle_id":…,"bytes_done":N,"bytes_total":M}` | upload byte progress, coalesced to a few per second, correlated by handle |
| `hydrated` | `{"event":"hydrated","path":…,"bytes":N}` | the cache copy is complete |
| `skipped` | `{"event":"skipped","path":…}` | excluded name: accepted, deliberately never uploaded |
| `dehydrated` | `{"event":"dehydrated","path":…}` | cache copy freed |
| `failed` | `{"event":"failed","path":…,"error":"…"[,"retry_scheduled":true\|false]}` | attempt failed; uploads say whether the daemon will retry |
| `completed` | `{"event":"completed","path":…,"handle_id":…,"direction":"upload\|download","ok":true\|false[,"error":"…"]}` | handle-scoped outcome — the in-sync signal |
| `view.invalidated` | `{"event":"view.invalidated","paths":[…][,"moved":[{"from":…,"to":…}]]}` or `{"event":"view.invalidated","full":true}` | `full:true` stands alone, without `paths` (more than 256 changed paths, or the tracked scope was reshaped: drop every cache); otherwise `paths` is present. Enumeration changed the view, or `hydration.unlink` / `hydration.rmdir` removed `paths` (a delete through the daemon, e.g. `unidrive rm`, archive#1199); drop readdir/getattr caches |
| `lost` | `{"event":"lost","since_last":N}` | your queue overflowed; resync from `hydration.list` |

Blocked names: a drive that refuses an entry by its name (OneDrive's uniqueness check ignores some characters, so a
sibling that differs from an existing one only in such characters is refused) first gets the entry under a stable
server name, invisible to the client: the local name stays and `hydration.list` shows it as an ordinary synced entry. If that
is refused too, the entry is *blocked*. A blocked file keeps its bytes and ends with `failed`
(`error:"similar_name[: <sibling>]"`, `retry_scheduled:false`) and `completed ok:false` with the same error token; a
blocked folder is **accepted by `mkdir` (`ok:true`)** so the client can fill it, with the same `failed` event, and
everything created below it ends with `blocked_by_parent: <folder>` without a request to the drive. `hydration.list`
entries of blocked rows carry `"blocked":"similar_name"` or `"blocked":"blocked_by_parent"` (absent otherwise), next to
`pending_upload:true` and `error:true`. `daemon.status` summarises them as `uploads.blocked`
([unidrive-daemon-design.md](unidrive-daemon-design.md) section 4.3). A daemon restart asks the drive again.

Excluded names (`exclude_patterns`): `create`/`open_write` answer
`{"ok":true,...,"excluded":true}` and the stream carries `skipped` and a
`completed ok:false error:excluded` — never `hydrating`/`hydrated`. The file
is served from local content only. `hydration.list` entries remain visible and
carry `"excluded":true` for such rows. `open_read` serves an excluded file
from local content (created locally, or complete in the cache) and refuses one
that would need a download with `{"ok":false,"error":"excluded"}`, preceded by
`skipped` and a `completed ok:false error:excluded`, never `failed`. `ls` marks excluded rows and `get` skips excluded
paths rather than downloading them.

### 4.1 The trash namespace is read-only

When the provider keeps live items in its trash (Internxt: the items below a trashed folder keep reporting `EXISTS`), the
engine shows them apart, in a virtual top-level folder (default name `Papierkorb`, profile key `trash_folder_name`, set by
the client from the user's language). The root folder and everything below it is **read-only**; an item there keeps the
path it has in the provider's own tree below the root, so the user sees where it came from. The engine is the guarantee:
it never sends an upload, a delete, a move or a rename for such a path to the cloud, ignores local changes there, and a
local delete does not become a remote delete. Reading, hydrating and copying out work as everywhere else.

`hydration.list` entries carry the machine-readable marker, so a client can refuse the action before it asks (a courtesy:
the engine refuses anyway):

| field | meaning |
|---|---|
| `"read_only":true` | the entry is the trash root or lies below it. Absent otherwise (a reply for a live item is unchanged) |
| `"trash_root":true` | the entry is the root folder itself: label it and give it an icon of its own. Its `remote_id` is the stable key `unidrive:trash`, whatever the folder is called. Absent otherwise |

Every verb that would write answers `{"ok":false,"error":"read_only"}` before anything is touched or queued: `create`,
`mkdir`, `unlink`, `rmdir`, `rename` (either end, so nothing moves in, out or within), `open_write_begin` and
`open_write`. A file edited in the namespace behind the engine's back keeps its bytes (a refusal is not a delete); the
engine uploads nothing for it. `daemon.status` carries a `trash` object while something is in the trash
([unidrive-daemon-design.md](unidrive-daemon-design.md) section 4.3).

An item that was shown among the live items by an earlier version arrives under the root with the same `remote_id`: it is a
rename (`view.invalidated` carries it in `moved`), never a delete and a create, and its cache copy follows the row.

### 4.2 Read-only views

A provider can list **views**: ordered references to items that already live in its tree. The engine shows each view the
profile asks for in a mount as a read-only virtual top-level folder (`Recents`: the account's most recently changed files;
`Shared`: the items it shares) whose entries *stand for* the real items. Nothing is copied: no row, no byte and no id is duplicated, the item
stays at its real path and one cache copy is kept, at that path. A mirror profile shows no view. A profile with no view
answers every reply exactly as before.

**The folder.** A view has a stable key, `unidrive:view:<kind>` (`recents`, `favorites`, `shared`), and a name: the
profile's folder name for it, the kind's English default (`Recents`, `Shared`) when unset; the client sets the name from the user's
language, as for the trash. A view is active when the profile asks for it and the provider declares the kind; a provider
that does not declare it shows no folder at all, whatever the profile key says. The folder exists whenever the view is
active, also while it is empty (also when a provider that declares the kind answers at runtime that it has none). A live top-level item that
already has the name keeps it; the folder takes the first free `<name> (2)`, `(3)`, ... A client that creates a real
top-level folder with that name through the mount gets `read_only` while the view holds it.

`hydration.list` marks what belongs to a view, additively:

| field | meaning |
|---|---|
| `"read_only":true` | the entry is a view's root or an entry in a view (as for the trash, section 4.1) |
| `"view":"recents"` | the view the entry belongs to: the root folder itself and every entry in it. Absent otherwise |
| `"view_target":"/Work/Reports/q3.pdf"` | for an entry in a view: the logical path of the real item it stands for. Absent for the root and for every other row |

`hydration.list("/")` has one such folder entry per active view, after the rows; its `remote_id` is the stable key and its
`mtime_ms` is the daemon's start (constant while it runs; `remote_modified_ms` is `null`). `hydration.list("/Recents")` has one
entry per reference, in the provider's order (Recents: the newest change first), its `remote_id` the synthetic key
`unidrive:view:recents:<id of the real item>`, never the bare id (a client must not see one id at two paths). `size`,
`mtime_ms`, `remote_modified_ms`, `etag`, `hydrated`, `pending_upload`, `error`, `excluded` and `blocked` are the real row's, read
at each listing. A view is flat except for folders (below): `hydration.list` of a path below a file entry answers an empty list.

**Folder aliases.** A reference to a folder is an entry whose `folder` is `true` and which is a read-only **alias** of the real
folder: `hydration.list("/Favorites/Docs")` answers the real folder's direct children, and so does every path below it, to any
depth. Each child has the alias prefix in `path` (`/Favorites/Docs/sub/a.txt`), `read_only:true`, `view`, the synthetic
`remote_id` `unidrive:view:<kind>:<id of the real item>` and `view_target`, the real logical path
(`/Docs/sub/a.txt`); everything else is the real row's. A folder that has no row (or a name the real folder lacks) lists nothing.
The alias is found by the entry's name, whole segments only (`/Favorites/Doc` is not `/Favorites/Docs`), compared as names are
(NFC, case folded where the file system folds case); the part below it is looked up in the real tree as given. The alias follows
a rename or move of the real folder, because the entry is joined to its row by id: the entry takes the new name at the next
listing. A reference to a folder with no live row, a row inside the trash namespace or outside `sync_path`, or a row that is not
a folder, is skipped and counted like any other. A favorited file inside a favorited folder is listed twice (in the view and in
the alias); that is intended. The rule is the same for every view kind whose provider references folders.

An entry is called by the last segment of its real path. When two entries want the same name (compared in NFC, and with case
folded where the file system folds case) the one whose item is a folder, else the one with the smaller id, keeps the plain name
and the others are called `stem (dup-<first 8 characters of the id>).ext`, whatever order the provider lists them in; a longer
part of the id is used only when 8 characters do not tell two apart. A name stays within 255 UTF-16 units.

A reference with no live row, a row inside the trash namespace or a row outside the profile's `sync_path` is not listed;
`daemon.status` counts them (`skipped`). The first listing of a view waits for the provider at most five seconds (past that the
list is empty and the state is `unavailable` until the fill lands, which sends `view.invalidated`); after that the list is
answered at once from memory and refreshed behind the answer when it is older than 60 seconds. Nothing is asked of the provider
while nobody lists the view, and `hydration.list("/")` never waits for it.

**The kinds.** `recents` lists the account's most recently changed files, newest first. `shared` lists the items the account
shares with others, with a person or by a public link: a flat folder, folders first, whose entries are the real items under
their own names, a shared folder being an alias of the real folder (above). It does not list what others share with the account, and it shows neither
with whom an item is shared nor a link. No reply, status or log line carries a key, a link code or a credential of a share.
The engine never creates, changes or removes a share: deleting an entry in `Shared` leaves the item shared. The profile keys
are `shared_folder_name` (the folder's name; `Shared` when unset) and `shared_view` (whether the profile shows the folder; on when unset).

**Reads.** `open_read`, `hydrate`, `dehydrate` and `last_synced` given the path of an entry, or of anything below a folder alias, run on the real item's path: the one
cache copy, the transfer budget, the pause and the memory of a truncated object behave as for the real path, and `open_read`
returns the real item's `cache_path`. The events such a transfer emits (`hydrating`, `hydrated`, `failed`, `completed`) name the
real path; `completed` keeps the client's `handle_id`. The root and a name the view does not list answer `unknown_path`.

**Writes.** Every verb that would write answers `{"ok":false,"error":"read_only"}` before anything is touched or queued, as in
section 4.1: `create`, `mkdir`, `unlink`, `rmdir`, `rename` (either end), `open_write_begin` and `open_write`, on an entry, on a folder alias's root and on every path below it, whether or not the
real item exists. Deleting or moving an entry in a view never deletes or moves the real item. Bytes found in a cache copy at a view path are neither uploaded nor
recorded.

**Change.** When a refresh changes a view's list the engine sends `view.invalidated` with `paths` holding the view's root
(`["/Recents"]`: the folder whose list changed, to be listed again), once per change; the first fill of a list counts as a
change. A change of the real rows (a rename, a download) does not send one for the view: the entries are read from the rows at
each listing.

**Pages.** The folder lists at most the view's limit (Recents: `recents_limit`, 50 by default). A front-end's own list (a
"more" button under the recent files) asks for the ones after them with `view.page`, a read-class verb:

```
{"verb":"view.page","kind":"recents","offset":50,"limit":50}
{"ok":true,"kind":"recents","offset":50,"entries":[...],"skipped":0,"next_offset":100,"state":"ok","as_of_ms":1760000000000}
{"ok":true,"kind":"recents","offset":100,"entries":[...],"skipped":1,"end":true,"state":"ok","as_of_ms":1760000000000}
```

- `kind` is a view the profile shows (`recents`, `favorites`, `shared`); any other answers `unknown_view`, and so does a daemon
  that shows none. `offset` (default 0) counts the provider's references, not the entries; `limit` (default 50, 1 to 1000) is
  how many references the page covers. A bad value answers `invalid_offset` or `invalid_limit`, a missing kind `missing_kind`.
- `entries` are the **real rows** the page's references join to, in the provider's order, each as `hydration.list` of its real
  folder writes it (its own `path` and `remote_id`, no `view` and no `view_target`): a page can reach past the folder's list,
  so its entries are not paths in the view. A reference without a row the mount can show is left out and counted in `skipped`,
  as in the folder. A read verb takes the entry's `path`.
- `next_offset` is where the next page starts; `end:true` (instead) says no reference follows: the provider answered fewer than
  were asked for, or the page reaches the most the view may page to (Recents: 1,000, what the server answers at most; a view
  whose limit is already its cap, Favorites and Shared, pages within its folder's list). `state` and `as_of_ms` are those of the
  list the page was cut from (`ok`, `stale`, `unavailable`, as in `daemon.status`).
- The provider's route has no offset: the engine asks for the first `offset + limit` references and cuts. A page within the
  folder's limit is cut from the folder's own list (the same 60-second time to live and the same single request in flight). A
  page past it is cut from a second, longer list per view, asked for with its size rounded up to a multiple of 50 and kept under
  the same rules: one request in flight per view, no new request within the time to live for a size no larger than one already
  asked for, an older list answered at once and refreshed behind the answer, the first fetch of a longer list waited for at most
  five seconds. So paging through Recents fifty at a time asks the provider once per page and nothing again within the time to
  live. Paging never changes what the folder lists and sends no `view.invalidated`.
- A mirror profile answers `wrong_mode`, as for every hydration verb.

`daemon.status` lists `view_page` in `capabilities` next to `views` on an engine that answers `view.page`.

`daemon.status` carries a `views` array, and lists `views` in `capabilities`, while the profile shows a view
([unidrive-daemon-design.md](unidrive-daemon-design.md) section 4.3).

## 5. Cancel

`hydration.cancel {path}` aborts every in-flight submission for the path —
queued, waiting for the transfer budget, mid-transfer, or in a retry backoff.
Reply `{"ok":true,"cancelled":true}` when something was aborted,
`{"ok":true,"cancelled":false}` when nothing was in flight (idempotent). Each
aborted submission reports `completed ok:false error:cancelled` for its
handle. A cancel mid-transfer cuts the provider call at its next suspension
point, so a provider that commits a partial object before suspending can
still leave a remote item; the caller follows with the row-level verb
(`unlink`/`rename`) for the path itself.

## 6. Ordering, budget, and durability

- **Per-path FIFO:** same-path uploads run strictly in submission order; a
  burst of saves cannot land an older save after a newer one.
- **Daemon-wide transfer budget:** all uploads — mount submissions and sync
  passes — share one per-provider cap (`ProviderMetadata.maxConcurrentTransfers`: Internxt 2, Dropbox 4, OneDrive,
  S3 and localfs 8; 4 for a provider that declares none). A burst larger
  than the cap waits, not races the provider's rate limits.
- **Bounded waiting queue with back-pressure:** at most
  `uploadQueueDepth` (default 256) uploads wait at a time. A burst beyond
  that suspends the `open_write` caller — the client's own queue is the
  pressure valve, not daemon memory.
- **Retry:** a failed attempt is retried with backoff up to `maxUploadAttempts`
  (default 3). Every failed attempt stamps the row (`error` flag in
  `hydration.list`, doctor surfacing) and emits `failed` with
  `retry_scheduled:true`; the last attempt emits `retry_scheduled:false`. The backoff is 2 s after the first failed
  attempt and 10 s after each later one. While a path waits in it the daemon keeps that on record, so a client that
  was not connected when `failed` went out still learns it: `daemon.status` counts the path in `uploads.retrying`
  with the earliest `uploads.next_try_at_ms`, and the upload's history row (`history.list`) is `running` with
  `attempts` (the failed attempts), `max_attempts` (the limit above) and `next_try_at_ms`
  ([unidrive-daemon-design.md](unidrive-daemon-design.md) sections 4.3 and 4.8). All of it ends when the backoff
  ends: the retry is due, `next_try_at_ms` is `null` again, and the path counts as `in_flight` once the attempt
  holds a transfer permit; after the last attempt the row is `failed` with `next_try_at_ms` `null`.
- **Rounds after the ladder** (gkrost/unidrive-cli#70): the three attempts are one ladder. When it is used up the
  upload is offered again in the same daemon process, up to five times, 5, 15, 30, 60 and 60 minutes after the
  previous round gave up (`HydrationImpl.DEFAULT_EXHAUSTED_RETRY_DELAYS_MS`; the list's length is the cap, an empty list
  switches the rounds off). A round is a new submission with its own queue place, transfer permit and history row; the
  row of the ladder before it stays `failed`. A round starts only if the row still waits for an upload (never uploaded,
  or a dirty overwrite that passes the same cloud-version check as the start-up replay), nothing else is uploading the
  path, the cache copy still exists and the provider has not refused these exact bytes. Terminal outcomes (`conflict`,
  a refused name, a permanent refusal such as `parent_gone`) get no rounds. A client's own `open_write` of the path
  resets the count, a landed upload ends it. The timer lives in the daemon's memory: a restart drops it and replays the
  row through the start-up replay below, as before. While a path waits for a round the daemon keeps no `retrying` or
  `next_try_at_ms` record (those describe the 2 s and 10 s backoff only): it is `pending` and, when never uploaded,
  `failed`. Each round runs the ladder of attempts again.
- **A daemon stop loses no data:** stopping the daemon cancels the upload scope, and the cancellation is rethrown
  before the failure handler, so a stopped upload stamps no failure on its row. A never-uploaded row stays in the
  pending set and is replayed at the next start; an overwrite is found by the dirty replay (the cache copy differs
  from the row's baseline, or an edit base is noted). An upload that was cut restarts from byte 0 (multipart per-part
  resume across a restart is open, #72).
- **Limits are constructor parameters, not profile settings:** the transfer budget (`ProviderMetadata.maxConcurrentTransfers`),
  the queue depth (256), the attempts (3) and the cache access grace (60 s) are logged once at start
  (`hydration limits: transfer budget …, upload queue depth …`). Profile keys for them are open (#72).
  Failures that are never retried emit `retry_scheduled:false` at once: a `conflict` (the cloud copy
  changed under the edit), an upload whose row or cache copy has vanished while it was queued (renamed away,
  unlinked, reaped), a name the drive refuses (`similar_name`, `blocked_by_parent`, below), and an upload whose folder
  the provider proved gone in the cloud (`parent_gone`, below): nothing a later
  attempt can change, and waiting would keep the path busy to `dehydrate` and replace-`rename`.
- **Replay:** state.db rows are the durable queue. At daemon start every
  hydrated file row whose content never reached the cloud is re-enqueued once
  through the same queue — a restart alone drains the backlog with no client
  connected. Excluded and out-of-scope rows are skipped. The same pass
  replays the edits of files that exist in the cloud whose cache copy the
  engine noted as not uploaded (section 3.3) or finds changed against the
  row's baseline. An edit is sent from the cloud version it started from, not
  from the version the row records by now (a poll moves that to whatever
  another device wrote since): when the cloud copy has changed since the
  edit began, nothing is sent, the edit stays in the cache, the row shows the
  failure and the `failed` / `completed` events carry `conflict` — both
  copies survive, as for an upload that met the conflict itself. The co-daemon's own
  `recovery-<n>` scanner remains the client-side complement for cache files
  whose rows it knows better; duplicate replays are harmless (same bytes,
  per-path serialized).
- **Eviction and rename interlocks:** `dehydrate` and replace-`rename` refuse
  with `busy` while an upload of the path is in flight; re-listing after a
  `completed` event observes `pending_upload:false`. If a queued upload's row
  disappears anyway (renamed away, unlinked, reaped), the upload refuses —
  the client gets a failed `completed` and the bytes stay in the cache; the
  daemon never resurrects the old remote path.

## 7. Error tokens (stable cross-repo contract)

| Token | Raised by | Meaning |
|---|---|---|
| `unknown_path` | row-lookup verbs | no such row (ENOENT) |
| `parent_not_found` | create, mkdir | parent row missing or not a folder |
| `path_exists` | create | row already exists (EEXIST) |
| `new_path_exists` | rename | destination exists and replace was not asked, or it was asked and the destination or the source is a folder (replace overwrites a file with a file only) |
| `old_path_not_found` / `new_parent_not_found` | rename | row/parent missing |
| `path_is_folder` / `path_is_file` | unlink/rmdir/open_write_begin | wrong kind |
| `not_empty` | rmdir | folder still has children (plain), or a file below holds bytes only this computer has (recursive, archive#1035) |
| `conflict` | open_write | base_etag no longer matches the row |
| `invalid_path` | open_write (`cache_path`) | the cache path lies outside the profile's hydration cache folder, or is not a valid local path; refused before anything is queued |
| `outside_scope` | create, mkdir, rename (both ends), open_write_begin | path outside the profile's sync_path set |
| `read_only` | create, mkdir, unlink, rmdir, rename (either end), open_write_begin, open_write | the path is the trash root or lies below it (section 4.1), or is the root of a view or lies below it (section 4.2); nothing was touched or queued |
| `busy` | dehydrate, replace-rename, unlink, rmdir | an upload is in flight on that path (unlink, dehydrate), below it (rmdir), or on the source or destination of a replace-rename; nothing was touched, retry once the upload's `completed` event has arrived |
| `name_clash` | create, mkdir, open_write, open_write_begin, rename, unlink, rmdir | the path names a name, or a folder above it, by another Unicode spelling (composed / decomposed) than the one a client used for it first in this daemon run; both are one row and one cloud path, so the second would overwrite or delete the first. Nothing was touched or queued. Section 3.9 |
| `excluded` | completed event, rename | keep-local name, never uploaded; a rename of a row that has a cloud copy onto such a name is refused with it (the row and the remote are untouched, `HydrationImpl.rename`) |
| `cancelled` | completed event | upload aborted by hydration.cancel |
| `similar_name[: <sibling>]` | failed and completed events, `hydration.list` (`blocked`) | the drive compares the entry's name as equal to a sibling's and refused it under its own name and under a server name; the local file is kept, nothing is retried. `: <sibling>` names the conflicting sibling when known |
| `name_collides_after_normalisation` | `hydration.list` (`blocked`), `daemon.status` (`uploads.blocked`) | a client refused to upload the file because another file of its folder is the same name after NFC (`hydration.refuse_name`, section 3.9); the file stays local, nothing is retried until the client clears it |
| `blocked_by_parent: <path>` | failed and completed events, `hydration.list` (`blocked`) | a folder above the entry was refused by name and exists only in this mount; no request was made for the entry |
| `parent_gone: <path>` | failed and completed events of an upload (`retry_scheduled:false`) | the folder the file goes into is gone in the cloud (trashed or deleted elsewhere, often before the drive's change feed reports it): the provider listed the folder above `<path>` successfully and `<path>` was not in it. `<path>` is the first folder of the file's path that was not found (the file's own folder or one above it). A listing that failed is never this. The upload is not retried; the same bytes are not sent again at a daemon start or a resubmission (a resubmission is answered with the same token without a request); new content of the file is. The bytes stay in the cache, the row shows the failure (`error` in `hydration.list`). A client shows the file as failed because its folder was deleted in the cloud, and keeps the local copy. A client matches the token up to `": "`; one that does not know the token sees a failed upload that is not retried, as before the token existed. Pinned by `events/failed-parent-gone.ndjson` and `events/completed-parent-gone.ndjson` |
| `not_found` | open_read, hydrate (and their `failed` / `completed` events) | the cloud object is gone: the provider answered a stable "not found" for it, so no download can succeed until the row is refreshed. It means only that; a file that exists in the cloud but cannot be served carries one of the tokens below |
| `content_refused: <reason>` | open_read, hydrate (and their `failed` / `completed` events) | the object was downloaded, but its content failed the integrity check the network recorded for it (`<reason>` is for example `SHA-256 mismatch` or `RIPEMD-160(SHA-256) mismatch`), so the bytes were discarded instead of served as the file. The file is not gone. Another attempt reads the same bytes: a client should not retry in a loop, and the answer changes only when the cloud copy changes. The daemon remembers the refusal per cloud object like `remote_incomplete` below (about an hour, and until the listing reports another size, hash or modification time): a repeat of the request is answered with the same token and reason without downloading the object again. `<reason>` is short and names no file; a client matches the token up to `": "` |
| `unsupported_object: <reason>` | open_read, hydrate (and their `failed` / `completed` events) | the object exists, but this client cannot read it as stored: it is stored in several parts or its encryption index is malformed. The file is not gone. `<reason>` is short and names no file; a client matches the token up to `": "` |
| `remote_object_missing: <reason>` | open_read, hydrate (and their `failed` / `completed` events) | the drive lists the file, but its stored cloud object is absent, so no download can succeed. The local bytes are kept; the file is not a deleted one. A client matches the token up to `": "` |
| `remote_incomplete: got <stored> of <declared> bytes` | open_read, hydrate (and their `failed` / `completed` events) | the stored cloud copy is of a different size than the drive reports (shorter, a truncated upload, or longer; `<stored>` may then exceed `<declared>`), so no download can produce the file the drive describes; the file is not gone. The daemon remembers the refusal per cloud object for the run (about an hour, see unidrive-daemon-design.md §4.3, `remote_incomplete`): a repeat of the request is answered with the same token and the same numbers without asking the cloud again. A client should not retry in a loop; the answer changes only when the cloud copy changes |
| `paused` | open_read, hydrate (and their `failed` / `completed` events) | the daemon is paused (`daemon.pause`, unidrive-daemon-design.md §4.7) and the file is not in the cache: nothing was started and nothing waits. A file already in the cache is still served. The client asks the user whether to lift the pause, calls `daemon.resume` and sends the request again |
| `invalid_path` | every verb with a path | a `.` or `..` segment, a control character, an empty segment other than that of a leading or trailing slash, or a segment over 255 UTF-16 units; a new name the host's file system cannot hold (create, mkdir, rename); a path whose cache file would lie outside the profile's hydration cache folder. Refused before anything is changed |
| `invalid_handle_id` | open_read, open_write, open_write_begin, create | the client named a handle id with the prefix `dh-` of the ids the daemon issues (section 2); nothing was opened |
| `unknown_view` | view.page | the profile shows no view of that kind (section 4.2) |
| `missing_kind` / `invalid_offset` / `invalid_limit` | view.page | no `kind`, or an `offset` or `limit` that is not a whole number in its range (section 4.2); the provider was not asked |
| `unknown_verb` / `missing_verb` | any | request-level refusal (startup-safe) |
| `missing_handle_id` / `missing_path` / `missing_cache_path` / `missing_old_path` / `missing_new_path` / `missing_local_path` / `missing_prefix` | the hydration verbs that take the member | the member is absent (or, for `cache_path` and `local_path`, empty); nothing was touched. A path that is present but unusable answers `invalid_path` |
| `wrong_mode` | every hydration verb, `sync.enumerate`, `ignore.explain` | the daemon serves a mirror profile and has no hydration layer (section 1) |
| `auth_required` / `auth_failed` / `forbidden` | any | not authenticated yet, a refused handshake step, a verb outside the connection's scope ([ipc-authentication.md](ipc-authentication.md)) |
| `too_many_clients` | connection (no request) | the daemon's connection cap is reached; the line is written once and the connection closed (§1) |

**A short download is not a size change** (gkrost/unidrive-cli#80). A download that returns fewer bytes than the row
(or, when the row's remote id no longer resolves, the live item it was re-read from) holds is accepted as the file's
new size only when the provider's metadata for the path reports exactly that size now (the file really shrank). In
every other case the read fails like a transient error (a generic failure, EIO: read again): no `remote_incomplete`
refusal is remembered, and the row and the cache copy stay as they were. A longer body is taken as before (the file
grew). `CloudProvider.download` must throw when it ends short; the check above is the engine's guard for a provider
whose truncation test needs a `Content-Length` it did not get (a chunked response that closes early). The
integrity check of the content (`verifyIntegrity`) is off by default and is not what catches this. A read or hydrate
that is cancelled (a client's cancel, a daemon stop) is a cancellation, not a failed download: its history row ends
`interrupted`.

**The two times of a listed row.** `mtime_ms` of a `hydration.list` entry is the local watermark (the local file's time when there is one,
else the row's last-synced time): for a cloud-only row the time of the enumeration that wrote it, for a hydrated row
the time of its download (`ListResult.Entry`). The provider's own modified time is `remote_modified_ms` (`null` for a
never-uploaded row). Many rows with the same `mtime_ms` are one enumeration batch, not an adoption.

## 8. Who may write the mounted folder

> **In force since the archive#560 cutover (2026-10-08), see [`docs/adr/independent-profiles.md`](../../adr/independent-profiles.md).** The single-writer rule below holds for a mount profile: the mount's co-client is the only local writer — the daemon writes the mount's files only into the hydration cache and uploads from there (archive#478's `sync_root` mirroring and archive#510's rescan are retired). A mirror of the same account is a separate profile, an independent cloud-side writer whose changes reach the view only through enumeration. The verbs and events in this document are unchanged.

While a mount runs, the mount's co-daemon is the single writer of the
profile's view: the daemon serves reads from the hydration cache and writes
from the co-daemon's verbs, and direct cloud edits reach the view only
through enumeration (`refresh.run`, `sync.enumerate`, or the poll). A second
`unidrive sync` on the same profile is excluded by the process lock. A client
that cannot reach the daemon must never write user content into the sync
folder from its own authority — it would desync from the rows.

## 9. Contract tests

- Golden request/reply corpus: `core/app/cli/src/test/resources/ipc-contract/<verb>.ndjson`,
  replayed by `IpcContractCorpusTest` (one fixture per registered verb, both
  sides key-order-insensitive). Lines written without a request live in
  `ipc-contract/connection/<token>.ndjson` (one line each; today
  `too_many_clients`), checked over a live socket by the same test.
  The pause adds `ipc-contract/sessions/pause-{cycle,timed}.ndjson` (request/reply pairs on one connection) and
  `ipc-contract/events/{paused,paused-timed,resumed,resumed-timeout}.ndjson` (the lines the subscribers read; the
  timeout line is pinned against the serialiser, as a live socket cannot wait for it).
- Connection cap and idle timeout: `IpcServerConnectionLimitsTest`.
- Sequences (this document §3), at the SPI level against a real
  `HydrationImpl` + engine: the full write sequence and the safe-save
  replace-rename sequence in `HydrationUploadQueueTest`; scope refusals and
  the keep-local excluded sequence in `HydrationScopeAndExclusionTest`; the read-only trash
  in `HydrationReadOnlyTrashTest`;
  cancel, progress, and replay sequences in `HydrationUploadProgressAndCancelTest`
  and `HydrationUploadQueueTest`; the recovery-<n> handle contract in
  `HydrationImplTest`.
- Startup liveness: `DaemonRuntimeTest.verbs_sent_as_soon_as_the_socket_appears_all_get_a_reply`.
- Path validation (`invalid_path`): the boundary checks in `HydrationIpcHandlerPathValidationTest`; the
  cache-folder containment of the verbs in `HydrationPathContainmentTest`, `ResolveCachePathContainmentTest`
  and `CachePathsTest`.
