# RECYCLE BIN DATA FLOW

Source: patched tree derived from `proPDFEditor-main1.zip` (pristine fingerprint `5e49861e...388a`). Verified by source reading only; **no build, test or device run**. Commit SHA: none available (no repository access this round).

## Authoritative database
**`:core` `ProPDFDatabase`, SQLite file `propdf_database`, table `pdf_documents`** (columns `is_in_recycle_bin`, `deleted_at`, entity `com.propdf.core.data.entity.PdfDocumentEntity`, DAO `com.propdf.core.data.local.dao.PdfDocumentDao`). Every active delete, list, restore and manual purge uses it.

## The second database
**`:app` `AppDatabase` (`data.local.db`), file `propdf_app_database`, its own `pdf_documents` table** (columns `isDeleted`, `deletedAt`; DAO `com.propdf.editor.data.local.dao.PdfDocumentDao`). Both databases are real, separate physical files, with different column names and different DAO classes that share the simple name `PdfDocumentDao`; Hilt provides each under its own type, so there is no binding clash. They do **not** contain the same documents and **no mirroring or synchronisation exists between them**. (`RecentFilesRepositoryImpl.syncToDocumentTable` mirrors `recent_files` into the `:core` table only.) No transaction spans the two files.
Writers to the `:app` table found: `CloudSyncManager.insert` (cloud path) and workers/use cases of dormant features. **No caller of its `moveToRecycleBin` exists**, so no active path ever sets `isDeleted = 1` there.

## Flow (before this change)
| Stage | Code | Database / table |
|---|---|---|
| Delete (list, duplicate finder) | `MoveToRecycleBinUseCase` / `DuplicateFinderScreen` -> `EditorDocumentRepositoryImpl.deleteDocument` -> `core DocumentRepositoryImpl.moveToRecycleBin` -> `UPDATE ... is_in_recycle_bin=1, deleted_at=:ts` | core `propdf_database.pdf_documents` |
| Bin screen | `RecycleBinViewModel` -> `GetDeletedFilesUseCase` -> `getRecycleBinDocuments()` | core |
| Restore | `RestoreDocumentUseCase` -> `restoreFromRecycleBin` (`is_in_recycle_bin=0, deleted_at=NULL`) | core |
| Manual permanent delete / empty | `PermanentDeleteUseCase`, `EmptyRecycleBinUseCase` -> `permanentlyDelete` (**before**: also deleted the file at `filePath` if it existed; **now rows only**, see below) | core |
| **Auto cleanup** | `batch/RecycleBinCleanupWorker` -> `permanentDeleteOld(cutoff)` | **`:app` `propdf_app_database.pdf_documents`** |
| Scheduling | only `com.propdfeditor.receiver.BootReceiver` (after a reboot) | - |

## Answers
- Both databases active? Both are built by Hilt and used; only the `:core` one holds bin items.
- Which receives deletion / supplies the screen / receives restoration? `:core`, `:core`, `:core`.
- Which is auto-cleaned? **Was `:app`.** The cleanup therefore matched nothing real: soft-deleted `:core` items were never auto-purged and stayed until a manual purge. Data-loss impact: none (it could not delete a wrong document, because the `:app` table never receives soft-deletes); functional impact: the Settings "auto-delete" promise was not honoured.
- Can cleanup delete the wrong document / a restored one? No (wrong table never held bin rows).
- Can deleted documents stay visible indefinitely? Yes (until emptied manually) before this change.

## Additional defect found on the `:core` path (fixed)
`DocumentRepositoryImpl.cleanOldRecycleBinItems(days)` computed `days * 24 * 60 * 60 * 1000` in **Int**. For 30 days that is `-1,702,967,296`, so the cutoff moved into the future and **every** bin item counted as old. `RecycleBinViewModel.emptyBin()` called it with the default `30`, so "empty bin" emptied everything only by accident. Also `(deletedAt ?: 0) < cutoff` made rows with no timestamp always eligible.

## Changes made (smallest safe correction; no merge, no new/deleted database, no version bump)
1. `RecycleBinPolicy` (new, pure): Long-based cutoff, null `deletedAt` never age-eligible, auto-delete days clamped to 1..90.
2. `cleanOldRecycleBinItems`: uses the policy; `days == 0` is the explicit "empty everything" path.
3. `RecycleBinViewModel.emptyBin()` now passes `olderThanDays = 0` explicitly, preserving its current effective behaviour.
4. `RecycleBinCleanupWorker` retargeted to the authoritative table via the existing core DAO query `deleteOldRecycleBinItems(cutoff)`: **database rows only, never original files**, never rows without `deleted_at` (SQL NULL). Scheduling untouched (still after reboot only).
**Behaviour change to be aware of:** after a reboot, bin items older than the user's auto-delete setting (default 30 days, clamped 1..90) will now actually be removed from the bin. Owner may prefer to confirm this before release.

## Tests added (all NOT RUN)
`RecycleBinPolicyTest` (6, JVM); `RecycleBinCleanupRepositoryTest` (6, JVM, mocked DAOs: recent kept, old deleted, 90-day no overflow, null timestamp kept, zero = all, repeat idempotent, DAO error deletes nothing); `RecycleBinLifecycleInstrumentedTest` (5, in-memory Room, never a user database: move -> bin shows same row with metadata, restore -> not purgeable, purge only old rows, idempotent, absent id).
**Not covered:** the worker class itself (`CoroutineWorker` not instantiated); a mirror-record-missing scenario cannot exist because there is no mirror; the `:app` table's own soft-delete behaviour (dormant).


## Owner decisions (Nov 2026) and resulting behaviour
**A - auto-delete approved.** Default 30 days, minimum 1, maximum 90, Long arithmetic, existing reboot-triggered worker only, no new scheduling or background jobs.
**Rows-only permanent deletion (for now).** `permanentlyDelete` no longer deletes any file. Because Empty Bin, per-item permanent delete, `batchDelete` and age-based cleanup all route through it (and the worker uses a row-only DAO query), every one of them removes the database row and preserves the original PDF. Reason: `filePath` may be a real path to a user's original document and file ownership/provenance is not established. To be reconsidered after app-owned files are distinguished from user originals.

| Operation | Database row | Original PDF |
|---|---|---|
| Automatic cleanup (worker) | delete | preserve |
| Empty Bin | delete | preserve |
| Per-item permanent delete | delete | preserve |
| Restore | `is_in_recycle_bin=0`, `deleted_at=NULL` | preserve |
Consequence to be aware of: a permanently deleted entry's file stays on disk (it just no longer appears in the app's database); storage is not reclaimed.

## Cleanup timing (accurate statement)
The only scheduler is the registered `com.propdfeditor.receiver.BootReceiver`, which enqueues the periodic worker **when the device boots**. An entry becomes eligible once `deleted_at` is strictly older than the threshold (an item exactly at the threshold is kept). It is actually removed **on the next worker run after that**, which in practice means after the next reboot (and then roughly daily while the process/work schedule is alive). It is **not** removed on day N exactly, and on a device that is never rebooted it may stay in the bin well past N days. Bin items are never removed by age while the worker has not run. This is the accepted consequence of the decision not to add scheduling.
Whether the periodic request survives without reboot (it is KEEP-policy periodic work) was **not verified**.

## Release-condition test matrix (written; NOT RUN)
| Case | Where |
|---|---|
| 1-day setting purges items older than 1 day, keeps newer | `RecycleBinCleanupRepositoryTest`, `...InstrumentedTest.thresholdCases` |
| 10 / 29 days kept at 30-day setting | same |
| exactly 30 days kept; 30 days + 1 ms eligible | `RecycleBinPolicyTest`, instrumented threshold test |
| 60 days purged at 30-day setting | repository + instrumented |
| 90-day setting keeps younger items | `RecycleBinCleanupRepositoryTest`, instrumented `ninetyDaySetting...` |
| null `deleted_at` kept | repository + instrumented (DAO) |
| Empty Bin purges all rows | `zeroDays_emptiesTheWholeBin`, `emptyBin_neverDeletesTheOriginalFiles` |
| Original file untouched by auto cleanup / Empty Bin / per-item delete | JVM (real temp files) + instrumented `rowPurge_neverTouchesTheFileOnDisk` |
| Worker class end-to-end | NOT COVERED |
