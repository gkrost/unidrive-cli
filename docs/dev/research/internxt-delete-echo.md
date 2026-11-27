# Internxt delete echo and the expected-deletions ledger (spike)

Status: **spike report, revision 3.** Measured on two test profiles through their running daemons (one Internxt, one OneDrive;
both named by type here, never by account). What was and was not measured is stated per case in section 1. Everything marked
"not measured" is a protocol or an estimate, not a result. Sections 2 to 4 are design notes and an estimate from code, docs and
the measurements; they were revised after the design review of this PR (both review rounds are answered in the PR threads).

Read against engine main `8551c7f4`. Line numbers are of that commit.

## 0. Summary

- **Internxt, engine-sent deletes of fresh fixtures (one test profile, 75 one-minute rounds):** the parent's folder contents, by-id
  meta, the provider trash and the incremental `/files` / `/folders` listing (`status=ALL`) all showed the delete by the first poll
  that began after the delete returned: within 10-30 s for trash (cases a, b), within one further poll (about 60 s) for a permanent
  delete. No reversal, no regression, no read error in 75 rounds. The data cannot rank the sources against each other (the poll
  period is 60 s); it does not show any of them lagging. The 60-minute lag of the specs (a web-UI trash, one observation, a lower
  bound) was **not reproduced and not refuted**: no UI trash was run.
- **The listing measured is the poll's fallback**, `/files?status=ALL` (`InternxtProvider.kt:3397-3404`). The primary incremental
  listing, `/files/sync` and `/folders/sync` without a status filter (`:3676-3688`), was **not read**. Its behaviour for a trashed
  and a permanently deleted item is the first thing to measure before any Internxt deadline is sized (L1).
- **Wire semantics proven by the capture:** a trashed item is `status=TRASHED`, `deleted=true`, `removed=false`; a permanent delete
  is `status=DELETED`, `deleted=false`, `removed=true`. The engine's `itemStateById` and `folderStateById` read the two flags the
  other way round (`InternxtProvider.kt:2628-2629`, `:2651-2652`): on the live wire a trashed item answers `NotFound`, and
  `Trashed` never comes back. Filed as gkrost/unidrive-cli#1019. Until it is fixed, a ledger built on `itemStateById` cannot tell
  trash from permanent deletion on Internxt (2.4, N3, N5).
- **A folder trash marks only the folder row.** `POST /storage/trash/add` updates the folder alone; the children's rows stay
  `EXISTS` in listing, by-id meta and feed for as long as the parent is trashed. That is the server's data model, not a lag with a
  horizon, so coverage of children cannot end by time (2.5).
- **What the measurement supports directly:** by-id state is prompt, so a restart can finish an interrupted delete instead of
  waiting for a delta (2.3).
- **S0 (622 entries, `rm -r` through the daemon):** 106 s on the Internxt test profile, 104 s on the OneDrive test profile (about
  6 entries/s); the bound fixed beforehand was 30 minutes or under 1 entry/s, so neither is slow. The Internxt run made about 840
  listing calls and one `trash/add` (1.6).
- **Batch (`deleteMany`) was not measured.** The parallel `rm` run measured the CLI's connection to the daemon, not batching (1.4).
- **Not measured:** OneDrive's delta echo, the primary Internxt sync listing, a real restore, raw wire fields, a web-UI trash, the
  Windows released-hold drain (the owner's).
- **ETA (re-baselined):** provider-neutral core 20 / 29 / 40 engineer-days (low / base / high); Internxt lag half 9 / 14 / 20, gated
  by one more measurement run. Ship in shadow mode first.
- **Top risk:** a protection that ends too early while a slow listing still emits `EXISTS` (2.1), and one that never ends because a
  restored item has the same uuid (2.6). Section 2.1 gives one rule for both.

## 1. Measured reality

### 1.1 Prior evidence (not re-measured here)

Three earlier statements are in the specs. They are different kinds of observation and are **not comparable timings**; none is a
bound for the others.

| Statement | Item kind | Action | Endpoint | Samples | Kind of value |
|---|---|---|---|---|---|
| A trashed folder was still listed live 40-160 s (`InternxtTrashLag.kt:9-12`, "test drive 2") | folder | trash by this client | folder listing / create 409 path | not recorded | interval (observed range) |
| `/files` status lagged a web-UI trash by 60 min or more (`mount-view-refresh-design.md` 3.2) | file | trash in the provider's web UI | incremental `/files` status | one observation | **lower bound** |
| A folder trash does not change its children's feed rows: they keep `EXISTS` with the dead folder as parent (`RemoteGather.kt:2047-2056`, archive#601) | file children of a folder | folder trash | incremental feed | qualitative | qualitative |

### 1.2 Endpoints and fields (from code)

| Purpose | Call | Fields that matter | Code |
|---|---|---|---|
| Incremental listing of files, **fallback** | `GET /files?updatedAt=<cursor>&status=ALL&sort=updatedAt&limit&offset` | `uuid`, `status`, `deleted`, `removed`, `updatedAt`, `folderUuid` | `InternxtApiService.listFiles` (`:585`); what the poll uses only when a server has no sync listings (`InternxtProvider.kt:3397-3404`, `:3745-3747`). **This is the listing this run measured.** |
| Same for folders | `GET /folders?...status=ALL` | same; a folder may carry no `status` | `listFolders` (`:602`) |
| Sync listing, **primary** | `GET /files/sync`, `GET /folders/sync` | the full scan asks `status=EXISTS`; the incremental poll reads them without a status filter, each row naming its own status (`InternxtProvider.kt:3676-3688`, `:3733`) | `getFilesSync` (`:641`), `SYNC_LISTING_STATUS` (`InternxtCursorListing.kt:45`). **Not read in this run.** |
| Folder contents (by path: `listChildren`) | `resolveFolder(path)`, then `GET /folders/content/{uuid}`; a cold resolve lists the parent's sub-folders (`/folders/v2/content/{id}/folders`) to find the name | live children only | `InternxtProvider.kt:507-514`, `:5026` |
| Item by id | `GET /files/{uuid}/meta`, `GET /folders/{uuid}/meta`; engine entry point `CloudProvider.itemStateById` (`CloudProvider.kt:467`, implemented by Internxt `InternxtProvider.kt:2646` and OneDrive `OneDriveProvider.kt:940`) | `status`, `deleted`, `removed`; result `Live` / `Trashed` / `NotFound` / `Unknown`. **On the wire a trashed item is `deleted=true, removed=false`; the engine reads `deleted` as gone for good (gkrost/unidrive-cli#1019).** | |
| Trash write | `POST /storage/trash/add` (cap 50) | | `trashItems`, `trashInBatches` (`InternxtBatchTrash.kt:27`) |
| Trash read | `GET /storage/trash/paginated?type=files|folders&limit<=50&offset&root=true` | `uuid`, `deletedAt`, `parent`, `expiresAt` | `trashPage`, `InternxtTrashRow.kt` |
| Permanent delete | `DELETE /files/{uuid}`, `DELETE /folders/{uuid}` | 204 / 404 | `deleteFile`, `deleteFolder` |

The wire model defaults an omitted `status` to `EXISTS` and omitted booleans to `false` (`InternxtFile.kt`, `InternxtFolder.kt`).
A harness built on the decoded objects therefore cannot tell an omitted field from an explicit live value. This run used the
decoded objects and says so; a presence-aware capture is part of the protocol for the next run (section 1.6).

`trash restore` of the CLI is the local emulation only (`TrashCommand.kt:121-138`); the engine has no restore from the provider's
trash. Case (d) therefore **was not run**.

### 1.3 How the measurement was made

One Internxt test profile, one OneDrive test profile, both mount profiles with a running daemon. Fixtures were created through the
mounted folder of each profile (the engine uploads them through its daemon) in one dedicated sub-tree per profile, then deleted
with `unidrive rm` (an RPC to the daemon: the engine's own delete route). `sync` is a mirror-profile command and `rm` serves mount
profiles only (`SyncCommand.kt:314`, `RmCommand.kt:218-232`), so the earlier draft's `sync --upload-only` setup could not have run on
one profile; it is removed.

Reads were made by **one JVM** that built one provider through the engine's own `Main.createProvider()` (one vault unlock, one
Windows Hello prompt per run) and then read once per minute: `listChildren` of each fixture folder (`/folders/content`), the provider
trash (`listTrash`), the incremental `/files` and `/folders` feed (`status=ALL`, `sort=updatedAt`, paged to the end, restricted to the
time window of the run), and by-id meta of about 25 tracked items. A first attempt that polled with one CLI process per read opened
the vault once per process and produced several Hello prompts; it was stopped and is not part of the data. The single-JVM harness itself was started twice: the first start unlocked the vault once and ended at once on a regex typo in the script; the second start (one more unlock) produced all data.

Observation model: a poll gives an **interval** (last stale observation, first changed observation), not a time. A value reported
as "+a..+b s" means: still old at a seconds after the delete call returned, already new at b seconds. "Not seen" is right-censored
(no change within the run), not a duration. The first poll after each delete was up to 60 s later, so intervals narrower than the poll
period cannot be claimed.

### 1.4 Results: Internxt delete echo (one test profile, 75 rounds, one poll per minute)

All times are seconds after the `rm` reply returned. Each cell is the **interval upper bound**: the end of the first poll that read the new state. Only a poll that *began* after the delete
returned counts as evidence about the state after it; a poll that straddled the delete (started before the reply, as for e3, e4 and
part of e5) is discarded, because its listing may have been read before the delete was sent. The first poll after the deletes of cases a, b and e
came 10-30 s after each delete, so **"new by the next poll" is the finest statement the data supports; the true propagation may be faster, and the data cannot rank the four sources.** The harness listed the incremental `/files` and `/folders` listing with `status=ALL` (the poll's fallback listing); `/files/sync` was not read.
Nothing was censored except the trash column of case e (a permanent delete never reaches the trash, as expected).

| Case | n | Source | min | median | max | later reversals | censored |
|---|---|---|---|---|---|---|---|
| (a) single file, trash | 5 | folder contents (`/folders/content`) stops listing it | 21 | 25 | 29 | 0 | 0 |
| | | by-id meta reports `TRASHED` (`deleted=true`) | 21 | 25 | 29 | 0 | 0 |
| | | incremental `/files` (status=ALL) has the row as `TRASHED` | 21 | 25 | 29 | 0 | 0 |
| | | provider trash lists it | 21 | 25 | 29 | 0 | 0 |
| (b) folder with 20 children, trash | 5 | folder contents of the parent stops listing the folder | 10 | 14 | 19 | 0 | 0 |
| | | by-id meta of the folder `TRASHED` | 10 | 14 | 19 | 0 | 0 |
| | | incremental `/folders` row `TRASHED` (`deleted=true`) | 10 | 14 | 19 | 0 | 0 |
| | | provider trash lists the folder | 10 | 14 | 19 | 0 | 0 |
| (e) single file, permanent delete | 5 | by-id meta `DELETED` (`removed=true`, `deleted=false`) | 6 | 59 | 63 | 0 | 0 |
| | | folder contents stops listing it | 6 | 59 | 63 | 0 | 0 |
| | | incremental `/files` row `DELETED` | 6 | 59 | 63 | 0 | 0 |
| | | provider trash lists it | n/a | n/a | n/a | n/a | 5 (expected) |

Per-item raw rows (`item`, first-seen interval in seconds; `-` = still old at the last poll before):

| item | case | listing | trash | by-id meta | feed |
|---|---|---|---|---|---|
| a1..a5 | a | 0..29, 0..27, 0..25, 0..23, 0..21 | same | same | same |
| b1..b5 | b | 0..19, 0..16, 0..14, 0..12, 0..10 | same | same | same |
| e1 | e | 0..8 | n/a | 0..8 | 0..8 |
| e2 | e | 0..6 | n/a | 0..6 | 0..6 |
| e3 | e | 0..63 | n/a | 0..63 | 0..63 |
| e4 | e | 0..61 | n/a | 0..61 | 0..61 |
| e5 | e | 0..59 | n/a | 0..59 | 0..59 |

For e3 to e5 the round that was running when the delete returned began before the reply, so it is discarded (an earlier version of this table
took by-id readings from such a round and printed shorter by-id intervals; the listing, which a round reads first, was read before the delete
was sent). What is left says "new by the next poll" for all three sources and nothing tighter. Every read of every source answered in all
75 rounds (0 read errors in meta, listing, trash or feed).

**(c) What the parallel `rm` run measured.** Thirty `rm` processes per repetition were started ten at a time through the daemon, five
repetitions. This measures **the CLI's connection to the daemon and the coalescer under that arrival pattern, not the batch path**: the
coalescer has no timer and sends a lone delete at once (`DeleteCoalescer.kt:23-25`), and ten starting processes reach the daemon a few at
a time (each `rm` takes about two seconds). 21, 21, 26, 22 and 22 `POST /storage/trash/add` calls were counted for the five repetitions
(daemon request counters), which is the coalescer behaving as designed for that arrival pattern. 20 of the 150 `rm` calls (2, 6, 3, 5, 4 per
repetition) were answered `daemon_not_answering` ("nothing was deleted"), which `RmCommand.kt:387-405` raises when the daemon did not answer
(no socket file, an I/O error on the way, or a request not written in full); it says nothing about batching, and the cause was not
determined (open question A in section 4). Those files stayed live and, as untouched controls, stayed `EXISTS` in listing, by-id meta and the
feed for the rest of the run. Of the 130 deletes that succeeded: all 130 had a listing row `TRASHED` and were gone from the parent listing at
the first poll after the repetition (within 17, 58, 35, 12 and 46 s); the provider trash showed 130 additional file entries; no row
regressed over 55 pre-cleanup rounds. **`deleteMany` / `trashInBatches` was not measured**; a drain over one connection (the Windows
released-hold drain, or a harness that issues the deletes over one IPC connection) would measure it.

**Children of a trashed folder.** The five trashed 20-child folders produced **no listing rows for their 100 children**: the incremental
file listing held 140 rows in total and all 140 were the tracked individually deleted files (135 trashed + 5 permanent), and the folder
listing held exactly the five trashed folders. When the test root itself was trashed at the end, the listings of the folders below it
(still holding the untouched controls and the 20 failed deletes) kept returning their items, by-id meta of the controls stayed `EXISTS`,
and the feed had no rows for them until the run ended 20 minutes later; the folder listing gained one row (the root). This is the server's
data model, not a lag: `POST /storage/trash/add` updates the folder rows alone (`moveFoldersToTrash` in the provider's public server source),
the engine already says so (`RemoteGather.kt:2047-2049`), and the trash listing is a flat query over `TRASHED` rows, so the 145 trash
entries held no child of the trashed folders: they are live rows under a trashed parent. A child's by-id meta answers `EXISTS` for as long
as its parent is trashed, and names its parent. Not read: what the provider's purge of a trashed folder does with those rows (open question B).

**Untouched controls** (`a6`, `b6`): present in listing, `EXISTS` in by-id meta and absent from the feed in all 75 rounds.

**What was not measured, and why.**

| Item | Status |
|---|---|
| Case (d), restore | not run: the engine has no restore from the provider's trash (`TrashCommand.kt:121-138`); a real owner-performed UI restore is owner question 3 |
| The primary incremental listing (`/files/sync`, `/folders/sync` without a status filter), the full-scan walk and its fallbacks | **not read** (the measured `/files?status=ALL` is the poll's fallback) |
| Raw wire fields (present vs defaulted) | not captured; the harness decodes through the engine's models |
| A fixture beyond page one / outside the time window as a control | not built: the feed window held 140 rows |
| Web-UI trash (the original 60-minute observation) | not run; so the old figure is neither confirmed nor refuted for UI trashes |
| Children's own listing after their folder's trash (b1..b5 contents) | not probed (the children were not tracked); the deeper listings under the trashed root do answer, see above |
| Polls finer than 60 s | not made |

The decoded-model caveat of 1.2 applies to every cell above.

### 1.5 Results: OneDrive (one test profile, through its running daemon)

Only daemon operations were used (no vault-opening process), so the delta feed and the recycle bin were **not read**:
"how fast the delta reports the delete" and "whether a folder delete reports the children" are **not measured**. The daemon's
`refresh` RPC answers `refresh.done` only; the engine's own deletes are tombstoned at once, so the plain `ls` view cannot show the
echo. The statement in the engine docs that OneDrive's delta lag is short is therefore still unconfirmed here.

What was measured is the S0 delete (1.6). A separate finding about this profile's daemon: its log shows
`auto-poll: enumerate failed (Not authenticated)` with the back-off growing to 600 s, then `quota refresh failed ... no OneDrive token
is stored for this profile`; a restarted daemon later polled every 60 s and reported authenticated. Whether a token was written in
between or a provider instance never re-read one is not decidable from here; the log lines and their timestamps are the evidence.
That episode belongs to gkrost/unidrive-windows#1596 and is not tracked here. Nothing was restarted or repaired by this spike.

### 1.6 Results: S0, `rm -r` of a 622-entry tree through each daemon

Tree: 358 nested folders (3-ary) plus 264 files of 1000 bytes (622 entries below one root); uploaded through the mounted folder, deleted
with `unidrive rm -r --yes` (daemon route). "Slow" was fixed beforehand as more than 30 minutes, or under 1 entry/s.

| | Internxt test profile | OneDrive test profile |
|---|---|---|
| Upload through the mount, until `daemon.status` shows no pending upload | about 73 s | about 87 s |
| `rm -r` wall time | **106.0 s** | **103.6 s** |
| Entries per second | 5.9 | 6.0 |
| Verdict against the pre-fixed bound | not slow | not slow |

Calls during the Internxt `rm -r` (daemon request counters, difference before/after; the daemon's own 60 s background poll adds a few
`favorites`, `recents`, `files/sync`, `folders/sync`, `sharings` calls):

| Class | Endpoint | Calls |
|---|---|---|
| content listing (guard; label is an inference) | `GET /folders/content/{id}` | 408 |
| sibling listing of the path resolve (inference) | `GET /folders/v2/content/{id}/folders` | 433 |
| meta | `GET /files/{id}/meta` | 10 |
| trash | `POST /storage/trash/add` | 1 (the whole subtree is one folder trash) |
| state.db commit | not exposed by the counters | not measured |

The guard calls `provider.listChildren(path)` by path (`MountDelete.kt:190-213`); on Internxt that is `resolveFolder(path)` and then
`getFolderContents(uuid)` (`InternxtProvider.kt:507-514`), and a cache miss in the resolve lists the parent's sub-folders to find one by
name (`:5026`). So a cold cache costs one sibling listing and one content call per folder, which fits 433 and 408 over 359 folders; the
counters are per endpoint, so the split into "guard" and "resolve" is an inference. The guard already holds each child's id from the
parent's listing, so a by-id listing would remove the resolve call, about half of the 841 (#1011 section 4).

No timing without the guard was taken, so the guard's share is inferred from the call counts, not measured: its listings (about 840
calls) dominate the 106 s; the trash itself is one call. The OneDrive daemon exposes no request counters, so its call split is not
measured. The Windows released-hold drain (review B1 of #1011) needs the tray and a live CfAPI mount and is the owner's.

### 1.7 Cleanup and what is left in the provider trash

Only the run's own sub-tree was moved to the trash (`rm -r` on `/_ud-spike-ledger-<ts>/` on each profile). No `trash purge`, with or
without `--all`, was run.

- **Internxt test profile:** the root and its subtree are in the provider trash. By the last poll (20 minutes after the root was trashed)
  the trash held 145 entries: the run's own 142 (root, the S0 sub-tree, five `a` files, five `b` folders, 130 batch files) and **three
  older folders that are not from this spike**, left untouched for the owner to decide. The ids of the run's entries are in a manifest
  kept with the session notes, outside this repo; the names and ids of the three older folders are in the PR conversation.
- **OneDrive test profile:** the root was moved to the recycle bin (`deleted_as: trash`). The recycle bin was not listed (no vault read),
  so its ids were not recorded. The test mount folder still holds 227 local placeholder entries of the run under the root while the
  daemon's view no longer lists the root; nothing was done to them (open question D).
- No credential file of the Internxt profile was rewritten during the run (modification time unchanged); the unlock helper ran once for
  the run that produced these data.

### 1.8 Reproducing

`internxt-delete-echo-harness/harness.jsh` (one JVM through `jshell --class-path <engine jar>`: one `Main().createProvider()`, read-only
loop) and `analyze.py` / `analyze2.py` (tables above from the JSON-lines it writes; `analyze.py` discards a round that began before the delete
returned, scores `TRASHED` and `DELETED` explicitly, and counts read errors per source). Environment: profile, test root, output file,
minutes, parents, feed time window. Fixtures are created through the mount folder and deleted through `unidrive rm`.


## 2. Ledger design notes (revised after review)

### 2.1 Two questions, kept apart

The earlier draft let one flag, "confirmed", end the protection. The measurements and the review show these are different:

- **Delete observed** (the cloud has the item as trashed/gone): by-id state, a complete listing of the parent, the trash listing.
- **Safe for a listing to reintroduce this id**: a listing can still carry the item as `EXISTS` after the delete was observed, because
  it is stale, or because the item was restored (a restore brings the **same uuid** back with its content, so "live and different"
  cannot recognise it), or because the delete never took effect. One rule covers all three and needs no timer and no source-specific
  bound: **a listing or feed row that reports an item with an active entry triggers one by-id read.** `Live`: the row returns (restored,
  or never gone). `Trashed` / `NotFound` (read through a corrected `itemStateById`, #1019): the row is dropped and the guard stays.
  `Unknown` or a failed read: keep hiding, as today. This is the pattern the folder guard already uses for `DELETED` rows
  (`RmdirRefusal.kt:113-125`, `:132-151`).

So "observed" is a state of the ledger entry and the protection against reintroduction is decided per contradicting row, not by a clock.
The probe budget of 2.6 shrinks to the contradictions. A timeline test is required: fast by-id confirmation, then a late stale `EXISTS`
from the listing (must stay hidden), then a genuine restore (the next `EXISTS` row must surface it). This replaces the earlier
10-minute grace and the feed-specific bound. (Review rounds 1 and 2.)

### 2.2 Entry, identity and states

A side table `expected_deletes` in `state.db`, created like the other side tables (`StateSideTables.kt`).

| Column | Meaning |
|---|---|
| `op_id` (primary key) | immutable operation id, one per delete attempt |
| `remote_id`, `kind` | the cloud id and file/folder |
| `generation` | the strongest identity token the provider gives (Internxt: uuid; OneDrive: id + eTag/cTag; Dropbox: id + rev; S3: key + version-id/etag; null when none). **A remote id is not a generation** (S3's id is the key, `S3Provider.kt:626-629`; a recreated key has the same id) |
| `path_at_delete` | display and path-reuse check only, never for addressing |
| `operation` | `trash` / `permanent` |
| `sent_at`, `deadline_at`, `attempts` | scheduling |
| `state` | `intent` (written before dispatch), `acknowledged`, `ambiguous`, `refused`, `observed`, `unresolved`, `contested`, `released` |
| `parent_op_id` | for a child covered by a folder delete: the folder's op; covered-by-parent is **not** observed (2.5) |
| `last_contradiction_at` | the last time a listing row contradicted the entry and the by-id read settled it (2.1); diagnostics only, no timer |

Active states = `intent`, `acknowledged`, `ambiguous`, `unresolved` (one shared predicate for every query and a partial unique index
on `(remote_id, generation)` over active rows). A restore followed by a second delete of the same id creates a new `op_id`; evidence
carries the `op_id` it was gathered for and is discarded if it names an older one. Terminal rows are kept a bounded time.

### 2.3 Writing the entry: intent before dispatch

Today `MountDelete.deleteRemote` calls the provider before `finishFileDelete`/`markDeleted`, so a crash after the provider accepted
and before the state transaction leaves neither a tombstone nor an entry. The ledger writes a **non-executing observation intent
before dispatch** (never a replay instruction), then records `acknowledged`, `ambiguous` (5xx whose first attempt may have
committed) or `refused`, and on restart reconciles every `intent`/`ambiguous` row **by id** (by-id state), never by re-sending.
What the reconciliation writes: `Trashed` or `NotFound` finishes the delete (the tombstone and the receipt the crash skipped, instead of
waiting for a delta; this is the one gain the measurement supports directly); `Live` drops the intent, since the call never happened
and the caller got no OK; `Unknown` or a failed read keeps the intent for the next start.
Tests: a lost reply, a crash on either side of the acknowledgement, partial batch results. This does not turn the ledger into a
deferred-delete queue (PR #1011 is a separate decision).

### 2.4 Evidence per operation and provider

**On Internxt the wire says** a trashed item is `TRASHED`, `deleted=true`, `removed=false` and a permanent delete is `DELETED`,
`removed=true` (section 1.4). The engine's `itemStateById` / `folderStateById` read the two flags the other way round
(`InternxtProvider.kt:2628-2629`, `:2651-2652`; gkrost/unidrive-cli#1019), so today a trashed item answers `NotFound` and `Trashed`
never comes back; N3 and N5 cannot tell trash from permanent deletion on Internxt until that is fixed.

Success is defined per operation. A `TRASHED` object satisfies `trash`; it does **not** satisfy `permanent`. A 404 on a live-only
endpoint does not distinguish trash from permanent absence. Outcomes are recorded as one of: *absent from the live namespace*,
*known trashed*, *verified permanently gone*, or *unknown* (the provider cannot distinguish). A `permanent` entry is never closed
as verified on `Trashed`/404 alone; a folder id is checked against the folder endpoint before a 404 is accepted.

**Absence from the old parent is not a deletion** (a concurrent move to another parent looks identical; `itemStateById` models a
moved item as `Live`). A complete listing of the old parent is a reason to ask by id, not terminal evidence; a live moved item is
reconciled at its new location. Controls: a move between folders, and a partial/failed listing.

| Evidence | Internxt | OneDrive | Dropbox | S3 |
|---|---|---|---|---|
| By-id state (`itemStateById`) | exists (flags inverted, #1019); file meta first, then folder meta (up to two calls) | exists | not implemented | not implemented |
| Delta/feed tombstone | `/files?status=ALL` fallback listing was prompt (section 1); the primary `/files/sync` and `/folders/sync` rows are not measured | `deleted` facet | id-less tombstone: **path-only, inconclusive after path reuse** (`isTombstoneOfOtherItem`) | none |
| Complete parent listing | `/folders/content`, prompt (section 1), not terminal | complete delta full listing | consistent, not terminal | consistent; id = key, so **a recreated key hides behind an open entry** unless the entry carries a generation (version-id/etag) |
| Provider trash | `/storage/trash/paginated` | recycle-bin API (not used) | restore list | none (hard delete) |

Open-entry filters compare `(remote_id, generation)`, not the id alone. Tests: S3 delete then recreate (also with identical bytes)
and a late Dropbox tombstone after a replacement. Neither replacement is hidden or re-deleted.

### 2.5 Folders: a confirmed parent is not a confirmed child

A folder trash marks only the folder row; the children's rows stay `EXISTS` for as long as the parent is trashed (section 1.4). That is
the server's state, so **coverage of children cannot end by time**, and it needs no listing: a child's by-id meta names its parent.
For a folder delete the ledger records the **vetted membership at dispatch** (the rows `MountDelete` vetted; descendants it did not vet
are kept by `keepBelow`). Children are `covered_while_parent_unreachable`, never `observed`. A child whose by-id read names a parent that
is live (moved out before the trash, or a restored parent) releases its coverage and reconciles by identity. Tests: a moved-out child, a
concurrent new or dirty descendant, a restored parent. What the provider's purge of a trashed folder does with its children is not
known (open question B), so coverage is never closed on the strength of a purge.

### 2.6 Deadlines, unresolved state and retries

- **Scheduling interval vs retirement bound.** The first read-only check is scheduled from the measured prompt sources (section 1)
  with jitter and backoff, a per-account probe budget and oldest-entry-first fairness, so a 10,000-item sweep is not one
  synchronised wave. A five-run median is a scheduling hint only; **it is not a bound that retires protection.** Where no tail
  bound is established, retirement stays evidence-based (a by-id read settled the contradicting row; see 2.1).
- **Evidence never arrives** (offline, expired authorisation, `Unknown`, unsupported provider): at the deadline the entry moves to a
  visible `unresolved` state, **automatic writes stop**, and the item is surfaced to the user. Protection is released by fresh
  authoritative live evidence (by-id says `Live`; see 2.1) or by an explicit user action. An unverified object is never labelled
  deleted merely to close the row. Tests: restart past deadline, permanent 401, unsupported provider.
- **No automatic retry of a delete that carries no condition.** A by-id GET returning version V followed by an unconditional DELETE
  is not a version check on the write: a restore or edit can land between them. A resend is allowed only where the provider enforces
  an identity-and-generation condition on the delete itself (OneDrive `If-Match`, Dropbox `parent_rev`). Everywhere else the
  entry goes to `contested` / needs review and **nothing is written**. Tests: an intervening restore, a missing token, a provider
  that does not enforce the condition.
- **Restored or never gone** (a by-id read of an item with an active entry says `Live`): the row returns and the user is told. Never
  re-delete. "Different version" is not required: a restore brings the same uuid back.

### 2.7 While an entry is active

- No resurrection by a listing row that contradicts an active entry unless the by-id read of that row says `Live` (2.1).
- No adoption of that id at the same path: `MountEngine.existingFolderAt` (`MountEngine.kt:2236`) refuses a folder whose
  `(id, generation)` has an active entry; a create at the path of an active folder entry waits or fails `busy`.
- `TombstonePurge` (`TombstonePurge.kt:36-48`), `ProfileStateResetter.deleteTombstones` (`:262`) and its `refuseIfBusy` treat an
  active entry as an operation on record; a reset with active entries refuses with a reason (owner question 6).

### 2.8 Coverage: which writers and providers

| Caller / route | Mode | State owner | Ledger |
|---|---|---|---|
| Mount `hydration.unlink` / `rmdir` (and the coalesced batch) | mount | daemon `state.db` | **yes** (N2) |
| `unidrive rm` via the daemon route | mount, daemon running | daemon `state.db` | **yes**, same path as above |
| `unidrive rm` provider route (`RmCommand.kt:407-432`) | mount without `state.db` | none | **excluded**: no state to protect, and creating state here would be new mount state; the CLI prints the outcome and the user's next enumeration is the first observation |
| Sync apply of a remote delete | mirror | `state.db` | not a delete the client *sent*; out of scope |
| `localfs` | any | n/a | excluded: no remote lag, deletes are immediate |
| Providers | Internxt, OneDrive, Dropbox, S3 | | per 2.4; S3 and Dropbox need a generation rule before the ledger may hide anything |

One acceptance test per supported writer.

### 2.9 Downgrade

An additive table is compatible in form, but an older engine ignores active entries and reverts to the two-hour receipts: it can
re-adopt or purge rows the new build protects, and `mount_deleted_at` as a fallback does not cover an entry older than two hours.
Decision proposed: **raise the supported-reader stamp atomically with the first entry that needs the new semantics** (the shadow-mode
entries of 3.3 do not need it), leaving profiles that never wrote an entry downgradeable. Tests: an older binary opening an untouched
and an active-ledger database, including after the legacy window has expired.

### 2.10 How it replaces `LAGGING_LISTING_WINDOW`

Only after the shadow period (3.3): `ownDeletesRemoved` (`RemoteGather.kt:2033`), the folder guard (`MountDelete.kt:179`,
`RmdirRefusal.kt:113`), `TombstonePurge` and `ProfileStateResetter` ask the ledger for active entries (and settle a contradicting row by id, 2.1). The constant
stays as the default for rows without an entry, then goes one release later.

### 2.11 Unexpected deletions and the hold

The ledger knows the deletes this client sent. A delete it did not send is *not necessarily* unexpected (another client's legitimate
delete leaves no receipt here). "Stands out" means shown, not blocked. A **hold** of unexpected mass deletions is a separate,
recoverable mechanism and not part of the first release: it must name which mount and mirror actions pause, thresholds and independent
corroboration, which cursor and held evidence survives a restart, and how an operator inspects, releases or rejects it. `bulk_reap_guard`
is a view/reaping guard, not a general gate for destructive mirror actions, so it is a precedent, not the mechanism. Tests: an external
bulk delete with unsent local edits; a crash between detection and release; never advance past changes that cannot be recovered.

## 3. ETA (re-baselined)

### 3.1 Assumptions

Effort in engineer-days of an agent-assisted workflow including review; one engineer unless stated; owner decisions and the
observation windows of live runs are **elapsed time, not effort**, and are listed separately. Sizes by the rubric S ≤ 1 d,
M 2-3 d, L 4-6 d; low/base/high is the spread of each slice, not a statistical bound. `CloudProvider.itemStateById` already exists
(Internxt, OneDrive), so the by-id slice is an extension, not a new SPI. A slice whose high exceeds 6 days (N7, N10) is over the
rubric's L and should be split before it is scheduled. The fix of gkrost/unidrive-cli#1019 (inverted `deleted`/`removed` in the Internxt
by-id state) is a prerequisite of N3 and N5 on Internxt and is its own issue, not counted here.

### 3.2 Provider-neutral half

| # | Slice | Size | Low | Base | High | Depends on |
|---|---|---|---|---|---|---|
| N1 | Side table, op ids, active predicate and index, stamp-at-first-active-entry, migration/downgrade tests | M | 2 | 3 | 4 | none |
| N2 | Intent before dispatch and outcome recording for the covered writers (2.8), crash/lost-reply/partial-batch tests | M-L | 3 | 4 | 6 | N1 |
| N3 | Observation and generation rules per provider on top of `itemStateById` (needs #1019 on Internxt); permanent vs trash outcomes; move/partial-listing controls | M | 2 | 3 | 4 | N1 |
| N4 | **Shadow mode**: entries are written and observed but hide nothing; status shows active/overdue counts, a list, and a release action | M | 2 | 3 | 4 | N2, N3 |
| N5 | Extend `itemStateById` (optional version/parent/evidence), missing providers (Dropbox, S3, localfs policy), wrapper forwarding, capability tests | M | 2 | 3 | 4 | N1 |
| N6 | Deadline to `unresolved`, conditional resend only where enforced, contested + notification | M | 2 | 3 | 4 | N3, N5 |
| N7 | **Enable suppression**: no-resurrection, no-adoption, purge/reset protection, the contradicting-row by-id rule of 2.1 and its timeline test | L | 4 | 5 | 7 | N4, N6 |
| N8 | Surfaces: confirmed count beside `DeleteSweepProgress`, unexpected-delete marking (show only) | S-M | 1 | 2 | 3 | N3 |
| N9 | Docs, spec updates, failure-injection suite | M | 2 | 3 | 4 | all |
| N10 | *Optional, separable:* recoverable hold of unexpected mass deletions (2.11) | L | 4 | 5 | 8 | N8 |

Core (N1-N9) sums to **low 20, base 29, high 40 engineer-days** (one engineer doing everything). Elapsed: with every slice serial except
N8, which is off the path, the chain N1, N2, N3, N4, N5, N6, N7, N9 is 19 / 27 / 37 days (N3 and N5 are in series for one engineer, and N4
and N6 need them). With a second engineer on N5 and N8 (3 / 5 / 7 days, N5 before N6) the first engineer keeps N1, N2, N3, N4, N6, N7,
N9: 17 / 24 / 33 days. N10 adds 4-8 days if wanted. The client half (the card for N8, notifications, the failed/unresolved list) is a
separate issue in the client repo and is not counted.

### 3.3 Rollout order (shadow first)

1. **Release A:** N1, N2, N3, N4. The table is written and observed; **nothing is hidden by it.** The two-hour window stays the
   only suppression. This is safe to stop at: an entry that is never confirmed costs a row and a status line, not a hidden restore.
2. **Release B:** N5, N6, N7 for a provider only once that provider has a bounded release path, diagnostics and a recovery action.
   Suppression is switched on per provider.
3. Test an upgrade that stops after release A with a restore and an expired entry.

### 3.4 Internxt lag half

| # | Slice | Size | Low | Base | High | Depends on |
|---|---|---|---|---|---|---|
| L1 | The live measurement, **first reading `/files/sync` and `/folders/sync` for one trashed and one permanently deleted item** (the primary listing, not read so far), then a presence-aware capture, a move control, a fixture beyond page one and a *real* restore; elapsed 2-6 h per run | S-M | 1 | 2 | 3 (+ owner/elapsed) | profile access |
| L2 | Internxt evidence from the existing by-id state plus folder-contents and trash lookups, flap handling | M | 2 | 3 | 4 | L1, N3 |
| L3 | The contradicting-row by-id rule of 2.1 against the primary sync listing's measured behaviour; first-check scheduling intervals | M | 2 | 3 | 4 | L1, N7 |
| L4 | Folder deletes: coverage-while-parent-unreachable by the child's by-id parent, and release (2.5) | M | 2 | 3 | 4 | N3, L2 |
| L5 | Batch path writes one entry per item; the 400 fallback keeps entries consistent | S-M | 1 | 2 | 3 | N2 |
| L6 | Ambiguous 5xx maps to `ambiguous`, never `refused` (shares N2's mechanism) | S | 1 | 1 | 2 | N2 |

Total ≈ **low 9, base 14, high 20 days**, gated by L1.

## 4. Risks and owner questions

Risks:

1. A protection that ends too early while a slow listing still emits `EXISTS` re-admits the stale row the ledger exists to suppress
   (the old observation, a web-UI trash); one that never ends hides a restore, and a restore has the same uuid. Mitigation: the single
   by-id rule of 2.1 and the `unresolved` state of 2.6, with shadow mode first (3.3).
2. The by-id state is inverted for trashed items on Internxt (#1019) until fixed; a ledger built on it before then reads trash as
   permanent absence.
3. Provider ids are not generations (S3 key, id-less Dropbox tombstone). Mitigation: 2.2 and 2.4.
4. The headline lag was measured on the poll's fallback listing only (`/files?status=ALL`), on one account, with decoded fields and small
   fixtures; the primary sync listing is unread (L1).

Open questions found while writing (unverified here, none filed from this report):

- **A.** Why did about 13% of the `rm` calls (20 of 150) answer `daemon_not_answering` when ten ran at once? `askDaemon` gives up on a
  missing socket file, an I/O error or a request not written in full (`RmCommand.kt:387-405`); the daemon log can say which. The question
  is whether about one `rm` in eight should fail at that concurrency.
- **B.** What does the provider's purge of a trashed folder do with the children's rows (still `EXISTS` under a trashed parent)?
- **C.** The OneDrive daemon's "no token stored" episode (1.5) is part of gkrost/unidrive-windows#1596.
- **D.** After a daemon-routed delete of 622 rows the mounted folder still showed 227 entries while the daemon's view no longer listed the
  root. Whether the invalidation for a bulk subtree tombstone names every path is the first thing to check; the numbers are in 1.6 and 1.7.

Owner questions (A/B):

1. **The missing cases.** A: schedule another run (one Hello prompt) that first reads `/files/sync` and `/folders/sync` for a trashed and a
   permanently deleted item, with raw-field capture, a move control and a real UI restore performed by the owner. B: accept this report's
   coverage and size the Internxt half on the fallback listing only.
2. **Downgrade.** A: raise the supported-reader stamp at the first *active* (non-shadow) entry (recommended). B: bump the schema.
3. **Restore control.** A: owner restores one item in the provider's web UI during a run. B: skip; restore safety stays unvalidated and
   no result may be used to justify automatic re-delete.
4. **First release.** A: shadow mode (release A of 3.3). B: ship suppression with the first release.
5. **Unexpected-delete hold (N10).** A: mark and show only. B: build the recoverable hold.
6. **State reset with active entries.** A: refuse with a reason. B: carry the table across.
7. **A failed or unresolved delete.** A: keep the item visible and surface it; the user decides. B: hide it until the user decides.
8. **#1011 queue.** A: no queue; the ledger records only. B: let an entry double as a job later (accepting D1-D5 of that review as
   requirements).
