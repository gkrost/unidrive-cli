# Internxt delete echo and the expected-deletions ledger (spike, 2026-11-27)

Status: **spike report, live measurement NOT DONE.** The test profile `19notte78-gmail-com-2` had no credentials available to the
agent (it is not in the owner's config, and the agent does not type passwords or use the owner's vault). Everything in section 1
that is not marked "from code" or "from an earlier live finding" is therefore a *protocol*, not a result. No provider was called.
Sections 2 to 4 are an estimate from code and docs. They are written so that the measured numbers drop into the tables of section 1
and the deadline values of section 2.4 without changing the structure.

Read against engine main `8551c7f4`. Line numbers are of that commit.

## 0. Summary

- **What exists today.** Own deletes are hidden by a fixed two-hour receipt (`RemoteGather.LAGGING_LISTING_WINDOW`,
  `RemoteGather.kt:2090`; `mount_deleted_at` on the tombstone row). Four places read it: `ownDeletesRemoved` (`RemoteGather.kt:2033`),
  the folder-delete guard (`MountDelete.kt:179`, `RmdirRefusal.kt:113`), `TombstonePurge` (`TombstonePurge.kt:39`) and the state
  reset (`ProfileStateResetter.kt:262-271`). The two hours are a guess, not a measurement (`sync-engine-stability-design.md`: "a single
  prior observation, not a measured bound").
- **What is known from earlier live work (not re-measured here).** A trashed *folder* keeps being listed live for 40 to 160 s
  (finding E3, `InternxtTrashLag.kt:9-12`). A trash does *not* cascade into the children's `/files` rows: they keep `EXISTS` with the
  dead folder as parent (`RemoteGather.kt:2047-2056`, archive#601). `/files` lagged a web-UI trash by 60 minutes or more
  (`mount-view-refresh-design.md` 3.2): one observation. These three numbers disagree by a factor of ~50, which is the reason to
  measure per evidence kind before choosing a deadline.
- **Estimate.** Provider-neutral half: about 14 to 18 engineer-days (L/XL). Internxt lag half: about 6 to 8 days (M/L). The neutral
  half is the larger and carries the safety value (never resurrect an open id, never re-delete a changed item, surfaces).
- **Top risk.** The ledger can turn a *lagging* listing into a *permanent* hide: an entry that never gets evidence stays open and
  masks a genuine restore by another actor. The deadline-and-escalate rule (section 2.5) is what prevents that, and its by-id check
  needs a version token that most `deleteById` paths do not carry (design review D6).

## 1. Measured reality

### 1.1 Endpoints and fields (from code)

| Purpose | Call | Fields that matter | Code |
|---|---|---|---|
| Incremental feed of files | `GET /files?updatedAt=<cursor>&status=ALL&sort=updatedAt&limit&offset` | `uuid`, `status` (`EXISTS` / `TRASHED` / `DELETED`), `deleted`, `removed`, `updatedAt`, `folderUuid` | `InternxtApiService.listFiles` (`:585`), used with `status=ALL` when a cursor exists (`InternxtProvider.kt:3404`) |
| Same for folders | `GET /folders?...status=ALL` | same, folders may carry no `status`, then `deleted`/`removed` | `listFolders` (`:602`), `InternxtShared.kt:36-44` |
| Cursor listing (full scan) | `GET /files/sync`, `GET /folders/sync` | `status=EXISTS` only | `getFilesSync` (`:641`), `SYNC_LISTING_STATUS` (`InternxtCursorListing.kt:45`) |
| Folder contents | `GET /folders/content/{uuid}` and `/folders/v2/content/{uuid}/files|folders` (cursor) | live children only | `getFolderContents`, `getFolderContentsPaged` (`:351`, `:378`) |
| Item by id | `GET /files/{uuid}/meta`, `GET /folders/{uuid}/meta` | `status`, `updatedAt`, `deleted`, `removed` | `getFileMeta` (`:656`), `getFolderMeta` (`:664`) |
| Trash (write) | `POST /storage/trash/add` `{items:[{uuid,type}]}` (cap 50) | | `trashItems` (`:1285`), `trashInBatches` (`InternxtBatchTrash.kt:27`) |
| Trash (read) | `GET /storage/trash/paginated?type=files|folders&limit<=50&offset&root=true` | `uuid`, `deletedAt`, `parent`, `expiresAt` | `trashPage` (`:1343`), `InternxtTrashRow.kt` |
| Permanent delete | `DELETE /files/{uuid}`, `DELETE /folders/{uuid}` | 204 / 404 = gone | `deleteFile` (`:1253`), `deleteFolder` |
| Empty trash | `DELETE /storage/trash/all` | `status: processing|completed` | `emptyTrash` |

Gaps: the CLI has **no restore from the provider's trash** (`unidrive trash restore` is the local emulation only,
`TrashCommand.kt:121-138`); there is no `restore` call in `InternxtApiService`. Case (d) therefore needs the web UI, a manual
`moveFile` on a trashed uuid (untested hypothesis), or a new endpoint call found by reading the official SDK. This is an owner
question (section 4, Q3).

### 1.2 Protocol (ready to run; **not done**)

Precondition: the owner (or the orchestrator) provides a throwaway config dir with `19notte78-gmail-com-2` signed in, passed as
`-c <dir>` or `UNIDRIVE_CONFIG_DIR`. Never the owner's `%APPDATA%\unidrive`. First command of the run:
`unidrive -c <dir> -p 19notte78-gmail-com-2 quota` (or the profile's status verb). Stop if the free 1 GiB is more than 70 % used;
`unidrive ... trash purge --remote --all` is allowed on this profile for cleanup only.

**Probe harness (to be written, test scope, about 150 lines).** A Kotlin `main` (or a `@Disabled`-by-default live test) over
`InternxtApiService` that, every 15 s for the first 10 minutes, every 60 s until 3 h (extend to 6 h for the first repetition), for
each tracked uuid logs one CSV row `t_since_trash_s, uuid, source, observation`:

1. `listFiles(updatedAt = T0 - 1 min, status = "ALL", sort = "updatedAt")`: the row's `status/deleted/removed` or "absent".
2. `getFileMeta(uuid)` / `getFolderMeta(uuid)`: `status`, or the HTTP error.
3. `getFolderContentsPaged(parentUuid)`: whether the uuid is listed.
4. `trashPage("files"|"folders", 50, offset)` walked to the end: whether the uuid is listed, and `deletedAt`.
5. `listFiles(... status = "EXISTS")` incremental, because that is what the full-scan path sees.

"Flap" = a source that reported the item gone and later reported it present again (or the reverse). Record the first time each
source changes and every later change.

**Cases, each at least 5 repetitions, each in `/_ud-spike-ledger-<timestamp>/` of that profile only:**

| Case | Setup | Action (engine CLI) |
|---|---|---|
| (a) single file | 5 files of 1 KiB, one per repetition, fresh each time | upload through a throwaway sync root (`sync --upload-only`), then `rm <path>` (the engine's own trash route) |
| (b) folder with ~20 children | 5 folders of 20 files | `rm -r <folder>`; probe the folder and each child |
| (c) batch | 30 files per repetition | the `deleteMany` route: delete 30 files in one sweep through the daemon (mount path) so `trashInBatches` batches them; a CLI loop of single `rm` does not exercise it |
| (d) restore | trash a file, restore it 2, 10 and 60 min later | web UI by the owner, or the untested move; log what the feed shows, including `updatedAt` |
| (e) permanent | 5 files | `rm --permanent` (`DELETE /files/{uuid}`); the feed's answer |

Per case, the table to fill in (times are seconds after the 2xx of the delete call; `never` = not seen within the probe window):

| Evidence | min | median | max | flaps | notes |
|---|---|---|---|---|---|
| `/files` incremental stops reporting `EXISTS` | | | | | |
| `/files` incremental reports `TRASHED` or `DELETED` | | | | | |
| `/files/{uuid}/meta` reports non-`EXISTS` | | | | | |
| `/folders/content/{parent}` no longer lists it | | | | | |
| `/storage/trash/paginated` lists it | | | | | |
| (b only) each child: any non-`EXISTS` status | | | | | |
| (b only) each child: leaves `/folders/content/{folder}` (unreachable once the folder is trashed: record the HTTP answer) | | | | | |

Cleanup: delete `/_ud-spike-ledger-<timestamp>/`, then `trash purge --remote --all` on this profile, then confirm the quota. Log no file
contents and no account data beyond the profile name.

### 1.3 Results

**Not measured.** The tables above stay empty until the run. What the code lets us say today is limited to this:

| Evidence kind | What the code expects of Internxt | Basis |
|---|---|---|
| Explicit `TRASHED` / `DELETED` in the incremental `/files` feed | Arrives late and may flap; for a *file* it is the only per-item feed signal | `mount-view-refresh-design.md` 3.2 (≥60 min, one observation) |
| Item missing from a complete `/folders/content/{uuid}` | Prompt (the engine notes a web-UI trash showed in folder contents "immediately"); a trashed folder was still listed live for 40-160 s | `sync-engine-stability-design.md` (delete-detection), `InternxtTrashLag.kt:9-12` |
| Parent's confirmed delete (cascade) | **Not available**: children keep `EXISTS` with the dead parent | `RemoteGather.kt:2047-2056` |
| Item in the trash listing | Plausible and cheap by id, but a 50-per-page walk of the whole trash; `deletedAt` per row | `trashPage` |
| By-id meta | Untested as evidence; likely the most direct, one call per item | `getFileMeta` |

Hypotheses the run must confirm or kill, in order of value: (H1) `/folders/content/{parent}` stops listing a trashed file within
seconds; (H2) `/files/{uuid}/meta` reports `TRASHED` within seconds although the `/files` feed lags; (H3) the trash listing shows
the item within seconds; (H4) children of a trashed folder never change status. If H1 or H2 holds, the "lag" is a defect of one
endpoint (the incremental feed), the ledger needs no hour-long deadline, and the Internxt half shrinks to a by-id check.

## 2. Ledger design notes

### 2.1 Entry and storage

A side table `expected_deletes` in `state.db`, created like the other side tables (`StateSideTables.kt`), **no schema bump**
(review D7 on #1011: a bump makes older builds refuse the file; add the stamp at the first insert if needed).

| Column | Meaning |
|---|---|
| `remote_id` (unique among open rows) | the cloud id the delete was sent for |
| `kind` | file / folder |
| `path_at_delete` | for display and the path-reuse check only, never for addressing |
| `version` | etag / `updatedAt` / `remoteContentId` of the row at delete time (null when the row had none) |
| `sent_at`, `operation` | `trash` / `permanent` / `cascade-root` |
| `parent_entry` | for a folder delete: nothing; for a child covered by a folder's cascade: the folder's entry id |
| `state` | `open`, `confirmed`, `resent`, `failed`, `contested` (another actor won) |
| `confirmed_by`, `confirmed_at` | which evidence kind, when |
| `checks`, `next_check_at` | deadline bookkeeping |

Writing the row: in the same transaction as the tombstone and `mount_deleted_at` today (`StateDatabase.kt:1846-1860`), so a crash
leaves either both or none. `mount_deleted_at` stays for one release as the fallback for rows written before the ledger.

### 2.2 Tick-off rules per provider

| Evidence | Internxt (**to be measured**) | OneDrive (not measured) | Dropbox (not measured) | S3 (not measured) |
|---|---|---|---|---|
| Explicit delete in the delta | `status` ∈ {`TRASHED`,`DELETED`} or `deleted`/`removed` in the `/files` ALL feed; late, may flap, so a single sighting confirms only after the by-id check agrees | `deleted` facet in delta; the lag is short | tombstone without id: match by path and entry (`isTombstoneOfOtherItem` handles this today) | no feed; n/a |
| Missing from a complete listing of its folder | `/folders/content/{uuid}` (cursor variant), authoritative if H1 holds; **a complete listing only** (`complete` flag, not a truncated page) | complete delta full listing | `list_folder` is consistent | `ListObjectsV2` is consistent: the primary evidence |
| Parent's confirmed delete (cascade) | **Never** (no cascade in feeds) | yes: recycle-bin delete covers the subtree | yes: recursive delete | no folder object; a prefix delete is per key, each key its own entry |
| In the provider's trash | `/storage/trash/paginated`; costs a walk, so by-id preferred | recycle bin API exists, not used by the engine | restore list exists, per file | none (hard delete) |
| By-id check (escalation) | `getFileMeta` / `getFolderMeta` | `getItemById` | `get_metadata` | `HeadObject` (404 = gone) |

Rule common to all: **confirmation needs one positive piece of evidence of a kind the provider is known to deliver promptly**.
Absence from a *lagging* listing is never evidence (that is exactly today's bug class).

### 2.3 What the ledger does while an entry is open (owner items 3)

- **No resurrection by a listing, for as long as the entry is open** (review D2): `ownDeletesRemoved` and `listedChildPassesOver`
  ask the ledger for open entries by id; the time window survives only for `confirmed` entries (a short grace of 10 min, since a
  confirmed delete can still be echoed by a stale page).
- **No adoption of that id at the same path** (review D1): `MountEngine.existingFolderAt` (`MountEngine.kt:2236`) refuses a folder
  whose id has an open entry; a create at the path of an open folder entry waits for it or fails `busy`.
- **Housekeeping keeps open entries** (review D4): `PURGEABLE_TOMBSTONES` (`TombstonePurge.kt:36-48`) gets `NOT EXISTS (open entry)`;
  `ProfileStateResetter.deleteTombstones` (`:262`) and `refuseIfBusy` treat an open entry as an operation on record; a full reset
  with open entries refuses with a reason, or carries the table across, as the owner decides (Q6).
- **Never re-delete after the item changed** (escalation, 2.5).

### 2.4 Deadline values (to be set from the measurements)

Until measured, the proposal is conservative and provider-specific, replacing the single two hours:

| Provider | First check by id after | Further checks | Hard stop |
|---|---|---|---|
| Internxt | max(10 min, 3 × the measured median of the slowest evidence kind that is still prompt) | 30 min, 2 h, 6 h | 24 h → `failed` or `contested` after the by-id check |
| OneDrive | 5 min | 30 min | 6 h |
| Dropbox | 2 min | 15 min | 2 h |
| S3 | 30 s | 5 min | 1 h |

If H2 holds, Internxt's by-id check is itself prompt evidence and the row confirms within minutes; the 2 h receipt disappears.

### 2.5 Escalation

When `next_check_at` is reached the by-id check runs (one call per entry; batched where the provider can, 50 per
`/storage/trash`-style call does not apply to reads, so Internxt reads are single calls, rate-limited through the existing
`driveBudget`):

| Answer | Action |
|---|---|
| gone / 404 / `TRASHED` / `DELETED` | tick off, evidence kind `by-id` |
| alive, **same version** as `version` | the cloud said OK and did not delete: **re-send once** (`resent`), second time → `failed`, reported through the failed-operations surface |
| alive, **version changed**, or the item is no longer in the trash after having been in it | another actor wrote it or restored it: `contested`, **resurrect the row**, notify the user, **never delete again** |
| error (network, auth, 5xx) | keep open, back off, never escalate on an error |

The version problem (D6): there is no by-id delete that takes a token (`CloudProvider.kt:221`). "Same version" is therefore
decided on the *check*, and the re-send is an unconditional by-id delete, which is the same exposure as today's single delete.

### 2.6 How it replaces `LAGGING_LISTING_WINDOW`

1. `ownDeletesRemoved` (`RemoteGather.kt:2033`): `recentlyDeletedRemoteIds(now - 2h)` becomes `openOrRecentlyConfirmedIds()`.
   For a provider with no listing lag the call returns an empty set after the grace period and the code is a no-op.
2. `listedChildPassesOver` (`RmdirRefusal.kt:113`) and `MountDelete.kt:179`: the same query.
3. `TombstonePurge` and `ProfileStateResetter`: keep open entries instead of `mount_deleted_at < bound`.
4. The constant stays as a default for rows without an entry (old rows, a daemon restarted into an old state), then is removed
   one release later.

## 3. ETA

Sizes: S ≤ 1 day, M 2-3 days, L 4-6 days. "Lines" are production plus tests, rough. Days are engineer-days of an agent-assisted
workflow including review; owner decisions are not on the clock.

### 3.1 Provider-neutral half (all providers, safety and surface)

| # | Slice | Size | Lines | Depends on |
|---|---|---|---|---|
| N1 | Side table `expected_deletes`, repository, states, no bump, migration test | S | 250 | none |
| N2 | Write path: record in the tombstone transaction (`MountDelete`, `BatchDelete`, CLI `rm`, sync-engine apply) | M | 350 | N1 |
| N3 | Tick-off from delta tombstones and complete listings (`RemoteGather`), cascade where the provider covers the subtree | M | 400 | N1, N2 |
| N4 | Open-entry protections: no resurrection (D2), no adoption (D1, `existingFolderAt`), purge and reset keep entries (D4), `listedChildPassesOver` | M | 350 | N1 |
| N5 | By-id check SPI on `CloudProvider` (default: unsupported) plus implementations for OneDrive, Dropbox, S3; version token only as evidence | M | 400 | N1 |
| N6 | Deadline worker and escalation (re-send once, contested → resurrect + notify, failed list) | M | 450 | N3, N5 |
| N7 | Surfaces: confirmed count beside `DeleteSweepProgress`, `daemon.status`, counts only | S | 150 | N3 |
| N8 | Unexpected-deletion marking and a guard hook (holds an unexpected mass delete before it is applied locally; interacts with `bulk_reap_guard`) | M | 350 | N3 |
| N9 | Docs, spec updates (`cli-rm-command.md`, `mount-view-refresh-design.md`), failure-injection tests | S | 300 | all |

Total ≈ 3 S + 6 M ≈ **14 to 18 days**; critical path N1 → N2 → N3 → N6 ≈ 9 days. The Windows client's card for N7 and the client
side of N8 (a notification, the failed-ops list) are **not** in these numbers: they are a separate issue in the client repo and
a few days there.

### 3.2 Internxt lag half

| # | Slice | Size | Lines | Depends on |
|---|---|---|---|---|
| L1 | The live measurement of section 1 (harness + 5 cases × 5 repetitions + cleanup + report) | S-M | 150 harness | profile access (Q1) |
| L2 | Internxt evidence providers: folder-contents absence (complete listing), by-id meta, trash-listing lookup by uuid (cached per pass), mapping `TRASHED`/`DELETED`/flap handling | M | 350 | L1, N5 |
| L3 | Internxt deadlines from the measured medians; removal of the 2 h default for this provider | S | 100 | L1, N3 |
| L4 | Folder deletes: children keep `EXISTS`; entry per folder plus a dependent-children rule (children of a trashed folder are `confirmed-by-parent` only after the folder is confirmed by a prompt kind; the dead-parent guard stays) | M | 300 | N3, L2 |
| L5 | Batch path (`trashInBatches`) writes one entry per item with the batch's confirmation; 400-fallback keeps entries consistent (D5) | S-M | 200 | N2 |
| L6 | Trash-add 5xx ambiguity (first attempt may have committed) maps to an entry in state `open`, never `failed` | S | 80 | N6 |

Total ≈ **6 to 8 days**, of which L1 is the gate. If H1 or H2 holds, L2 is smaller (one cheap call) and L3 is mostly deleted code.

### 3.3 Ordering

N1 → N2 → N4 first: they close the D1/D2/D4 data-loss holes with no provider work and make the two-hour window obsolete for
non-lagging providers. L1 can run in parallel as soon as the profile is available. N5/N6 and L2-L4 follow. N7 and N8 are the
surfaces and can land last or be split.

## 4. Risks and owner questions

Risks:

1. A ledger entry that never gets evidence hides a real restore. Mitigation: hard stop and the by-id check; never a silent open entry.
2. The by-id check costs one call per open entry: a 10,000-file sweep is 10,000 calls on Internxt, which already rate-limits.
   Mitigation: check only entries past deadline, use cheaper evidence first (folder-contents listing is one call per folder).
3. Folder deletes on Internxt do not cascade, so a ledger keyed by folder confirms the folder only; children need their own
   evidence or the dead-parent rule (L4).
4. Version evidence is weak for deletes (D6): "same version" decided at check time cannot prove nothing changed in between.
5. All Internxt numbers here are unmeasured; the whole Internxt half is sized on hypotheses H1-H4.

Owner questions (A/B):

1. **Profile access for the measurement.** A: owner prepares a throwaway config dir with `19notte78-gmail-com-2` signed in, agent runs
   the protocol against it. B: owner runs the harness himself and sends the CSV.
2. **Schema.** A: side table without a bump, stamp at the first insert (recommended, review D7). B: bump the schema.
3. **Case (d), restore.** A: owner restores through the web UI during the run. B: agent tries the move-out-of-trash call
   (untested; may fail or move the wrong thing). C: skip (d).
4. **Scope of the first release.** A: neutral half N1-N4 first (safety), Internxt half after the measurement. B: both together.
5. **Unexpected-deletion guard (N8).** A: mark and show only. B: hold unexpected mass deletes before applying them locally,
   reusing `bulk_reap_guard`'s second-listing rule (default off, as today).
6. **State reset with open entries.** A: refuse with a reason. B: carry the table across the reset.
7. **Failed delete.** A: report through the failed-operations list and leave the row hidden until the user decides.
   B: un-hide after the hard stop.
8. **#1011 queue.** A: build no queue before the live re-measure (the owner's earlier choice stands; the ledger records only,
   it does not defer). B: let the ledger's entry double as the queue job later (one table), accepting the D1-D5 findings as
   requirements for it.
