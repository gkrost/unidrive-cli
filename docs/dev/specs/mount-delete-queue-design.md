# Mount delete queue: durable write-behind deletes and subtree coalescing (design)

**Status:** proposal, no code. It covers the three parts of the mount-delete throughput work that were left open on
purpose: a durable write-behind delete queue, one folder trash in place of its children's jobs, and the folder guard's
per-folder recursive listing. The throughput part that is already built (the kind hint, one trash call per listed item,
the batch SPI, the synchronous coalescing window) is described in section 1 as the baseline.

File:line references point at the tree this document was added to. They describe the code as it is, not a plan.

---

## 1. The path as built

### 1.1 From the verb to the provider

| Step | Where | What it does |
|---|---|---|
| `hydration.unlink` | `HydrationImpl.kt:3072`, `unlinkRow` `:3083` | Refuses with `busy` while a rename record names the path (`:3090`, `RenameRollForward.blocks`) or the file's own upload is queued or running (`:3098`). A row without a cloud id is either probed for a ghost (`:3129-3146`) or dropped locally (`deleteEntry`). Otherwise it calls `mount.deleteRemoteFile` (`:3172`). |
| `hydration.rmdir` | `rmdirRow` `:3209` | `busy` for a rename record (`:3217`) or an upload anywhere below (`:3225`). A plain rmdir is strict (`not_empty`). A recursive one is refused while a row below holds bytes that only this computer has (`:3246`, `unsynced_bytes`) unless `discard_local` is set. `keepBelow` (`:3253`) spares rows made or written while the delete runs. The provider delete runs inside `RmdirWindow.deleting` (`:3259`), so an upload below waits for it (`:1444`). |
| Rowless `rm -rf` | `rmdirRowless` `:3294` | Unlinks the files one by one, then deletes the folder rows deepest first (`:3308`). |
| `MountEngine.deleteRemote` / `deleteRemoteFile` | `MountEngine.kt:2007-2015` | Thin delegates to `MountDelete` (`:2020`). |
| `MountDelete.deleteRemote` | `MountDelete.kt:87` | Reads the row first (`:97`). A **file** with a cloud id and no `permanent` flag goes through the coalescing window (`:109-115`). A **folder** runs the guard `checkFolderDeleteSnapshot` (`:118`) and then a single provider call: by id with the kind hint where the provider takes it (`:120-126`). A not-found answer counts as done (`:127-133`, `RemoteErrors.isAlreadyGone`). Only after that is state.db written: a file's tombstone (`:153-156`); for a folder, one transaction that tombstones the folder and every row below except the kept ones (`:140-150`). |
| `DeleteCoalescer` | `DeleteCoalescer.kt:31` | The synchronous window. Deletes that arrive while up to `MAX_IN_FLIGHT = 2` calls (`:106`) are running share the next call of at most `MAX_BATCH = 50` items (`:103`). Each caller waits for its own item's answer (`:64-65`), so the IPC reply of each unlink stays what it was. No timer: a lone delete goes out at once (`:23-25`). Cancellation: the batch leader sends under `NonCancellable`; once a request is committed, its caller finishes the item's local work (`:27-29`, `:71-79`). |
| `BatchDeleteProvider.deleteMany` | `BatchDelete.kt:25-27` | An optional SPI: one answer slot per item, `null` = deleted, otherwise the error that item's own `deleteById` would have thrown. Providers without it take `deleteManyOrEach`, the per-item loop (`:33-37`). The window is only built for a provider with a native batch (`MountDelete.kt:30-32`, `ReadOnlyTrashProvider.kt:49`). |
| Internxt batch | `InternxtProvider.kt:2750`, `trashInBatches` `InternxtBatchTrash.kt:27` | Items that a listing named go to `POST /storage/trash/add`, 50 per call (`TRASH_BATCH_MAX`, `:7`). Others go to the single delete. A 400 for a call names no item, so every item of that call is retried singly (`:17-19`, `:62`). Any other failure is the answer of every item in the call (`:20-21`, `:63`). `trashItems` retries 5xx because the first attempt may have committed. The "400 after a 5xx means already trashed" shortcut is kept for a single item and not for a batch (`InternxtApiService.kt:1285-1294`). |

### 1.2 The folder guard

`checkFolderDeleteSnapshot` (`MountDelete.kt:169-204`) does the following:

1. Snapshots the rows below the folder that have a cloud id (`:171-172`).
2. Refuses a row a delta indexed after the client last saw the folder whole (`:176`, `RmdirRefusal.kt:71-85`).
3. Walks the cloud subtree breadth first with one `listChildren` per folder (`:181-200`, `snapshotListing` `:208-216`).

Each live listed child must match its row by id and version (`differsFromObserved`, `RmdirRefusal.kt:37`). If it does not, the delete is refused with `UnobservedChildException`, which reaches the client as `not_empty` with a reason. A listed child that this mount deleted itself within the lagging-listing window is passed over and never descended into (`listedChildPassesOver`, `RmdirRefusal.kt:113`). The window is `RemoteGather.LAGGING_LISTING_WINDOW = 2 h` (`RemoteGather.kt:2090`). The receipt that makes this work is `mount_deleted_at`, written in the same transaction as the tombstone (`StateDatabase.kt:1840-1860`) and read by `recentlyDeletedRemoteIds` (`StateDatabase.kt:3704`).

The guard exists because a poll can lag another device's writes (`MountDelete.kt:158-161`). A cascading folder delete must not take a child that the mount has never seen, or a file whose cloud version moved on.

### 1.3 In memory versus persisted

| State | Where | Survives a restart |
|---|---|---|
| Tombstone + `mount_deleted_at` receipt | `sync_entries` (`StateDatabase.kt:1840`, `:1969`) | yes. Written **after** the provider call. |
| Rename record (old path, new path, id) | `rename_intents` (`StateDatabase.kt:3313`), `RenameRollForward.kt:12-33` | yes. Written **before** the cloud move and settled at start (`settleAll`, `:95`). This is the engine's only write-ahead journal today. |
| Coalescing window and its open batch | `DeleteCoalescer` fields | no, and none is needed: nothing in it has been acknowledged yet. |
| Folders deleted while rows below were kept | `RmdirWindow.deletedWithKeptRows` (`RmdirWindow.kt:11-13`) | no. After a restart, a kept row's upload is refused as `parent_gone`. |
| Sweep progress of a released hold | `DeleteSweepProgress` (`DeleteSweepProgress.kt:24`), counts only | no. A restart starts the sweep's progress over. |
| Held deletes and the mass-delete guard | the **client** (counts-only shared record) | the client's business. The engine sees only the drain (`sweep_total` on `unlink`/`rmdir`, `HydrationIpcHandler.kt:116-128`). |

### 1.4 Where a crash loses or duplicates a delete today

The contract is "OK means the provider operation succeeded or the item is already gone" (`MountDelete.kt:40-55`, `DeleteCoalescer.kt:19-21`). A crash therefore never loses an acknowledged delete. These are the windows that exist:

1. **The provider call succeeded, the process died before the tombstone** (`MountDelete.kt:113`/`:136`/`:140`). The client got no OK (the connection dropped), and the row stays alive. The view shows the item until a delta reports it trashed. A retried `rm` deletes by the same id and gets the already-gone answer, so the effect is idempotent and only the call is repeated. Side effect: no `mount_deleted_at` receipt was written, so for up to the lagging window a guard on the parent folder can refuse with `not_empty`, because the lagging listing still names that child and the passing-over rule cannot apply.
2. **A batch whose reply was lost after the server committed it.** The 5xx retry or the per-item 400 fallback answers each item as already gone (`InternxtBatchTrash.kt:62`, `:66`). Calls are duplicated, the effect is not.
3. **A folder trash that committed, then a crash before the subtree tombstones.** The same as 1, for the whole subtree. The next start shows the subtree until the delta catches up. On Internxt the trash does not cascade into child listings or deltas, so the rows below the folder settle only through the dead-parent and lagging-delta handling, not through per-child tombstones.
4. **A kept row below a deleted folder** (`RmdirWindow`): after a restart its upload has no folder to land in (`parent_gone`). No delete is lost, but a file the user kept ends up refused.
5. **No duplicate onto a wrong target.** Deletes go by cloud id (`MountDelete.kt:105-108`), so a path reused by a new item is never deleted by an old job.

The durable queue in section 2 must keep 1 to 5 at least this good. The new window it opens is **acknowledged but not yet sent**.

---

## 2. Durable write-behind queue: design options

The goal: `rm -rf` of thousands of entries over a single-threaded client returns as fast as state.db can commit, and the cloud work drains in batches behind it. The synchronous window cannot help that client, because the co-daemon waits for each reply and so there is never a second delete in flight.

### 2.1 Where the journal lives

**Option J1: a table in state.db (recommended).** `delete_jobs(op_id PK, account, remote_id, kind, expected_version, parent_remote_id, diag_path, permanent, state, attempts, last_error, queued_at, sent_at, done_at)`.

- The tombstone and the job are written in **one** transaction before the reply (`markDeleted` already uses `batch`, `StateDatabase.kt:1847`). A crash then leaves both or neither: no job without a tombstone, and no tombstone that hides a job.
- It follows the `rename_intents` precedent: a write-ahead record in state.db, settled at start.
- The guard, the poll's anti-resurrection rule and the status counts can query it together with `sync_entries`.
- Cost: a schema addition. As a new table this is additive. Whether it needs a `SCHEMA_VERSION` bump (`StateDatabase.kt:4036`) depends on one question: may an older build that ignores the table run against the file? It must not, because it would show tombstoned rows as deleted while their cloud copies are still live, and never drain them. So it is a bump.

**Option J2: a file journal (append-only, beside state.db).** It is independent of SQLite locking and cheap to append. But the tombstone and the job no longer commit atomically: an fsync ordering across two files is needed, and every crash point between them is a new test case. Status and guard queries need a second reader. Rejected unless state.db write latency turns out to be the bottleneck (measure first).

### 2.2 Identity and idempotency

- A job is keyed by **operation id + cloud id**, not by path. The path is diagnostic only. A new file at the same path gets its own row and never overwrites the old job or its receipt.
- The drainer **leases** jobs (`state = sending`, `sent_at`). After a crash, a job in `sending` is retried. That is safe, because every delete goes by id and a not-found answer counts as done (`RemoteErrors.isAlreadyGone`, as today).
- Per-item outcomes only. A batch answer that does not name each item (the Internxt 400) splits back to singles, as `trashInBatches` does today. A job is `done` only on its own success or its own already-gone answer.
- `expected_version`: a file job carries the version the tombstone saw. If the cloud item moved on before the drain, the job does not delete the newer version. It becomes `failed: changed_remotely` and the row is shown again. That is the same rule the folder guard applies to children.
- Receipts: the anti-resurrection window starts when the **intent** commits, because a lagging listing must not bring back a queued item. A separate `done_at` starts the ordinary recent-success receipt (`mount_deleted_at` semantics), so the 2 h window runs from the moment the cloud actually changed.

### 2.3 Ordering against uploads and renames of the same subtree

- **Acceptance keeps today's refusals.** `busy` for a rename record or an upload of the path or below (`HydrationImpl.kt:3090`, `:3098`, `:3217`, `:3225`), and `unsynced_bytes` (`:3246`). Nothing is queued that today would be refused.
- **A create or upload below a queued folder delete** has to wait or be refused, as `RmdirWindow.awaitDeletesAbove` (`:1444`) does for a running delete. With a queue, "running" lasts until the job is `done`. Option: answer `busy` for creates below a queued folder (simple, but visible to the user), or let the create go and drop the folder job in favour of child jobs (more code, and the guard's evidence changes). Recommendation: `busy`, with an explicit retry hint.
- **A rename out of a queued subtree.** It is refused while the job is not `done`: the cloud item to move may be trashed by the folder job at any moment. A rename *into* a queued folder is refused for the same reason.
- **Recreate at the same path** after a queued file delete is allowed. The new row has no cloud id until its upload lands, and the old job deletes by the old id.
- **Order within the queue.** Children before their folder, unless subtree coalescing (section 3) replaces them. Folder jobs are sent alone, after their guard, as today (`MountDelete.kt:117`), so that unrelated file batches cannot age the guard's snapshot.

### 2.4 Held deletes and the mass-delete guard

The client holds deletes behind its own mass-delete guard: per-account thresholds, moves out of the drive counted as deletes, and the guard disarming after 10 quiet minutes. The engine sees a released hold as a drain over `unlink`/`rmdir` marked with `sweep_total` (`DeleteSweepProgress.kt:9-14`).

- The queue sits **behind** the client's guard. A held delete is never in the engine's queue, so releasing a hold enqueues and cancelling it enqueues nothing. Nothing in the engine changes the client's thresholds or its quiet-time disarm.
- What the client's guard counts does not change: it counts what the user did, and the queue only changes when the cloud sees it.
- A drain of a released hold is the main beneficiary: thousands of `unlink`s acknowledged at state.db speed. `DeleteSweepProgress` keeps counting **acknowledged** items. A second count, *sent*, shows the cloud's progress (see 2.5).
- The engine's own delete guards (`max_delete_*`, `bulk_reap_guard`, `DeleteGuards.kt:8-25`) apply to the sync engine's planned deletes, not to mount verbs, and stay as they are.

### 2.5 IPC and status surface

- **Opt in, never by default.** An advertised capability (for example `hydration.delete_queue` in `daemon.status`) and a per-request field (`"deferred":true`) on `unlink`/`rmdir`. Without it, the verb stays synchronous, so existing clients and `rm --permanent` see no change. Permanent deletes stay synchronous in the first slice: a deferred permanent delete that fails cannot be undone by the user.
- **Reply.** `{"ok":true,"queued":true,"op_id":"…"}`: "queued" is a result of its own, never folded into a plain `ok`. This follows the decision taken for `trash purge --remote --json`, which reports a provider-queued purge as `"result":"partial","queued":true` and not as `"emptied"`.
- **daemon.status** gets a `delete_queue` object with counts only (no paths, like `delete_sweep`): `queued`, `sending`, `failed`, `oldest_queued_ms`.
- **Failures after the OK** cannot become the syscall's errno. They land in a failed-ops list: a new `hydration.delete_queue.list` / `.retry` / `.cancel`, and `unidrive` CLI subcommands with `--json`. Each entry has a stable reason code (`changed_remotely`, `refused`, `auth_required`, `network_error`, …). A cancelled or abandoned job brings its row back to life **only** with an explicit cancel, never silently.
- **Draining** uses the provider's existing request budget and the `DeleteCoalescer` batch shape (50 per call, 2 in flight) as its sender. The window becomes the drainer's flush, not a second mechanism.

---

## 3. Subtree coalescing

When a queued `rmdir` covers a folder whose children are themselves queued, one folder trash can replace N child jobs. The saving is large on Internxt: one call instead of ⌈N/50⌉ plus the folder.

### 3.1 When one folder trash may replace its children's jobs

All of these must hold at drain time:

1. **Complete evidence.** The guard's walk of the cloud subtree (or its replacement, section 4) is complete and every live descendant is either a queued job of this operation or a `done` receipt. A new child, a changed version, an incomplete walk or an unprovable identity blocks the coalescing. The children are then sent one by one, followed by the folder delete with its normal guard.
2. **No pending rename or move involves the subtree.** No `rename_intents` record, and no failed or queued move has an old path below the folder. See 3.2.
3. **No kept rows below** (`keepBelow`, `RmdirWindow`): a kept row means the user still wants something in there.
4. **Durable coverage until confirmed.** The child jobs are marked `superseded_by = <folder op>`, not deleted. They are closed only when the folder job is `done`. If the folder call fails or the process crashes, the child jobs come back, and nothing is lost.
5. **Same account and namespace**, not permanent, and the provider says its folder delete covers the subtree (3.3).

### 3.2 The risk: a child moved out whose move never reached the cloud

A user moves `/A/x` to `/B/x`. The local move succeeds, but the cloud move is refused or still pending. Then the user deletes `/A`. In the cloud, `x` is still below `A`, so one folder trash of `A` takes `x` with it, and `/B/x` loses its cloud copy. This case was found on the client side: it is exactly what rule 3.1.2 blocks. The check must use the durable rename records and the failed-move state, not the in-memory `running` set. A guard that walks the cloud subtree (rule 1) also sees `x` with a row whose path is no longer below `A`. Treat that as unobserved and block the coalescing: the walk proves membership in the cloud, the rows prove intent locally, and they must agree.

### 3.3 Provider differences

| Provider | Folder delete | Batch of items | Coalescing worth it |
|---|---|---|---|
| Internxt | `trash/add` with `type=folder`. The trash does **not** cascade into child listings or deltas, so children stay listed as live until the lag clears (the reason for the passing-over rule). | yes, 50 per call (`InternxtBatchTrash.kt:7`) | **yes**: largest saving; needs the dead-parent and lagging-delta protections kept. |
| OneDrive | `DELETE /items/{id}` moves the folder and its subtree to the recycle bin (`OneDriveProvider.kt:616-622`, `GraphApiService.kt:817`). | `$batch` exists (used for reads, `GraphApiService.kt:280-298`), not for deletes today | yes; saving is N calls to 1. A batched delete via `$batch` (20 per request) is an alternative for non-coalescable sets. |
| Dropbox | `files/delete_v2` deletes the folder recursively; restore is per file within retention (`DropboxProvider.kt:478-482`, `:504-512`). | `files/delete_batch_v2` exists in the API; not implemented here | yes; a recursive delete is one call. Restoring a whole folder takes many calls, so it is less forgiving than a trash. |
| S3 | **No trash and no folder object.** A folder id is a prefix; a folder delete lists every key below it and deletes them with `DeleteObjects` (1000 per call) for good (`S3Provider.kt:630-640`, `:654-668`, `S3Api.kt:643`). | the provider batches internally; it does not implement `BatchDeleteProvider` | **no**: the "folder delete" is itself a listing plus batches, so coalescing saves nothing, and it permanently deletes keys the engine never saw. On S3 the queue sends per-key jobs only (a `BatchDeleteProvider` over `DeleteObjects` is the cheap win). |

---

## 4. The guard's recursive listing

**Cost today.** One `listChildren` per folder of the subtree, for each folder delete (`MountDelete.kt:181-200`). A bottom-up `rm -rf` lists each folder roughly once, because child folders already deleted are passed over and not descended into. A single recursive `rmdir` of a large tree lists every folder below it. For the measured 622-entry tree (358 folders), the arithmetic on the issue gives up to about 725 listings, against at most 13 batched trash calls. **The listing is now the largest cost of a big delete.**

**Option G1: read state.db instead of the cloud.** That is free, but it proves only what the engine already knows, which is the opposite of the guard's purpose (`MountDelete.kt:158-161`: the poll lags other devices). It is acceptable only where a fresher statement than the rows exists:

- **G1a: whole-listing freshness.** `lastWholeListing(folder)` (`MountDelete.kt:176`) already records when a client last saw the folder whole. If that is within a short window (seconds) *and* no delta since then touched the subtree, the rows are as good as a listing. Saves the listings of an `rm -rf` issued right after a directory walk, which is the common case for a file manager.
- **G1b: delta-cursor freshness.** For providers whose delta is prompt (OneDrive, Dropbox), a delta poll after the delete was accepted proves the subtree unchanged. Internxt's delta lags by minutes, so this does not help there.

**Option G2: list once per operation, not once per folder delete.** For a coalesced subtree (section 3), one walk at drain time is the evidence for the single folder trash. The per-folder walks of a bottom-up `rm -rf` then collapse into one. This is the natural pairing with coalescing.

**Option G3: keep the walk as it is** for non-coalesced folder deletes. It is the safe default.

Recommendation: G2 with coalescing, G1a as a measured optimisation behind a short freshness window, G3 everywhere else. G1b is not worth building while Internxt is the main workload.

---

## 5. Recommendation, slices, tests, questions

### 5.1 Recommendation

Build the queue **only** if a single-threaded bulk delete (Linux `rm -rf`, a scripted cleanup) of thousands of entries is a real workload after a live re-measure. The synchronous window already serves parallel deleters such as Explorer and released holds that drain in parallel. If the queue is built: J1 (state.db), opt-in IPC, file jobs first, then folder jobs, then coalescing with G2. Do G1a apart from the queue: it helps the synchronous path too.

### 5.2 Slices

| # | Slice | Size | Depends on |
|---|---|---|---|
| S0 | Live re-measure of the 622-entry `rm -rf` (calls, wall time) on the current engine | S (no code) | none |
| S1 | G1a: skip the guard's walk when a whole listing is fresh and no delta touched the subtree | S-M (~80 lines + tests) | none |
| S2 | S3 `BatchDeleteProvider` over `DeleteObjects` (per-key answers) | S (~60 lines) | none |
| S3 | `delete_jobs` table, schema bump, `markDeleted` + job in one transaction, start-up settle, drainer over `DeleteCoalescer`; **file jobs only**, opt-in `deferred` | L (~300 lines + crash matrix) | S0 decision |
| S4 | Status counts, failed-ops list/retry/cancel verbs and CLI | M (~200 lines) | S3 |
| S5 | Folder jobs (no coalescing): guard at drain time, `busy` below a queued folder | M (~150 lines) | S3 |
| S6 | Subtree coalescing with G2, `superseded_by`, rename-record check; Internxt, OneDrive and Dropbox only | L (~250 lines) | S5 |

### 5.3 Test strategy

- **Fakes only.** `FakeCloudProvider`-style providers with per-call gates (as the streaming tests already use) and an injectable "crash here" that stops the engine at named points.
- **Crash matrix** (one test per cell, each restarting the engine from the same state.db): before the transaction / after commit, before reply / after reply / after lease / after the provider call, before `done` / after the folder call, before the child jobs close. Each cell asserts no lost acknowledged delete, no delete of a newer version, no resurrected tombstone, and the same final rows as the synchronous path.
- **Batch outcomes:** all succeed; a lost reply after the server committed; one bad id (400) inside a 50; 51 items; a mixed per-item answer.
- **Ordering:** a create, upload or rename below a queued folder; a recreate at a queued file's path with a new id; a rename out of the subtree whose cloud move failed (must block coalescing).
- **Guard:** an unknown child appearing between the queue and the drain blocks coalescing; a lagging listing during the queue and after `done`; G1a's window boundary.
- **Pinned HTTP counts** for a known folder and for 50/51 targets, per provider fake.

### 5.4 Questions for the owner

1. **Build the queue at all?** A: only after S0 shows that a single-threaded bulk delete is a real, slow workload. B: build it now as an opt-in capability.
2. **Journal:** A: a `delete_jobs` table in state.db with a schema bump. B: a separate append-only file journal.
3. **IPC default:** A: opt in per request (`deferred`), with synchronous deletes as the default. B: deferred by default for clients that advertise the capability.
4. **Permanent deletes:** A: always synchronous. B: queueable like trash deletes.
5. **Create below a queued folder delete:** A: answer `busy` until the job is done. B: accept it and split the folder job into child jobs.
6. **A failed job after OK:** A: the row stays deleted and the failure goes to the failed-ops list until the user retries or cancels. B: the row comes back to life automatically, with a notice.
7. **Subtree coalescing:** A: build it (S6) for Internxt, OneDrive and Dropbox, never S3. B: leave it out and rely on 50-item batches.
8. **The guard's walk:** A: G1a (fresh whole listing) now, apart from the queue. B: keep the walk unchanged until the queue lands with G2.
