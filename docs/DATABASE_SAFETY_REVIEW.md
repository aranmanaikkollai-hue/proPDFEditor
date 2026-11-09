# DATABASE SAFETY REVIEW

Source: `proPDFEditor-main1.zip`, fingerprint `5e49861e...388a`. Re-traced from source (Hilt providers, builders, import-aware DAO resolution: a DAO simple name such as `PdfDocumentDao`, `CollectionDao`, `AnnotationDao`, `SignatureDao`, `BookmarkDao` exists in several databases, so matching by simple name was rejected). Evidence level: "wired" = DAO injected by a repository/worker; **UI reachability was not traced for every row**. No database opened, no version bumped, nothing consolidated.

## Inventory (17 `@Database`)
| # | Class (module) | File | Ver | exportSchema (was -> now) | Fallback | Migrations | Class (proposed, owner to confirm) | Status / evidence |
|---|---|---|---|---|---|---|---|---|
| 1 | `ProPDFDatabase` (core) | `propdf_database` | 4 | false -> **true** | yes | 2->3, 3->4 | USER-CRITICAL (pdf_documents favourites, bookmarks, collections, tags, form data, activity) | active: `DocumentRepositoryImpl`, `RecentFilesRepositoryImpl` mirror, `SettingsRepositoryImpl`, `BackupRepositoryImpl` |
| 2 | `RecentFilesDatabase` (core) | `recent_files_database` | 2 | true | yes | none | USER-CRITICAL (favourite, category, last-opened) + compression history | active: `RecentFilesRepositoryImpl`, `PdfBoxCompressionRepository`. **v1->v2 would wipe** |
| 3 | `OcrDatabase` (core) | `ocr_jobs_database` | 1 | false -> **true** | yes | none | background-job state | active: `OcrJobManager`, `OcrWorker` |
| 4 | `SearchDatabase` (core) | `search_database` | 1 | false | none | none | search index (rebuildable) | provided; `SearchDao` has no non-DI user found: UNVERIFIED |
| 5 | `AppDatabase` (core, `com.propdfeditor.core.database`) | `propdf_editor.db` | 3 | true | yes | none | USER-CRITICAL (signatures, signature history, certificates metadata) | active: signature repositories. Destructive fallback on this is high risk |
| 6 | `AppDatabase` (app, `data.local`) | `conversion_tasks_database` | 1 | true | yes | none | dormant / job state | conversion workspace unreachable |
| 7 | `AppDatabase` (app, `data.local.db`) | `propdf_app_database` | 3 | true | yes | none | USER-CRITICAL (folders, tags, collections) + rebuildable index/hashes in same file | active: `RecycleBinCleanupWorker`, `FolderRepositoryImpl`, `CloudRepositoryImpl`; has its own `pdf_documents` |
| 8 | `RecentFilesDatabase` (app) | `recent_files.db` | 1 | false | yes | none | dormant duplicate | provider only; DAO has no non-DI user |
| 9 | `BatchDatabase` (app) | `batch_jobs.db` | 1 | true | yes | none | background-job state (holds Keystore-encrypted job passwords); excluded from backup | active |
| 10 | `AnnotationDatabase` (annotations) | `annotations.db` | 2 | true | yes | none | USER-CRITICAL (annotations) | active: `AnnotationRepository`. **v1->v2 would wipe** |
| 11 | `AnnotationDatabase` (viewer) | `viewer_annotations.db` | 1 | false | none | none | duplicate; would be user data if used | wired to `AnnotationPersistenceManager`; reachability UNVERIFIED |
| 12 | `ViewerDatabase` (viewer) | `viewer_database` | 1 | false | yes | none | dormant (bookmarks/recent pages) | no DAO user |
| 13 | `ScannerDatabase` (scanner) | `scanner_database.db` | 1 | false -> **true** | yes (2 builder sites) | none | user data (scan sessions) | wired: `ScannerRepositoryImpl` |
| 14 | `SecurityDatabase` (security) | `security_database` | 1 | true | yes | none | user data (operation/redaction history) | wired: `SecurityRepository` |
| 15 | `StorageDatabase` (storage) | `storage_database` / `storage.db` (2 builder sites) | 1 | false -> **true** | none | none | user-critical-ish (persisted SAF grants; loss = user must re-grant) | wired: `SafRepositoryImpl` |
| 16 | `NasDatabase` (nas) | `nas.db` | 1 | true | none | none | dormant | no DAO user |
| 17 | `SyncDatabase` (sync) | `sync.db` / `sync_database` | 1 | true | none | none | dormant module | `FolderWatchRepositoryImpl` only |
Destructive-fallback call sites: **13** (BatchDatabase, app RecentFilesDatabase, AppDatabaseModule, app DatabaseModule, ViewerModule, annotations AnnotationDatabase, ScannerDatabase, ScannerModule, core AppDatabase, LocalDatabaseModule x2, core DatabaseModule, SecurityModule). A 14th grep hit is a comment. Databases without fallback and without migrations (#4, #11, #15-17) would **crash**, not wipe, on a version bump without a migration.
Duplicate names (not duplicate files): three `AppDatabase`, two `RecentFilesDatabase`, two `AnnotationDatabase`; two separate `pdf_documents` tables (#1 and #7).

## Schema export
Configured (additive): `room.schemaLocation` via KSP in core/annotations/scanner/security/storage/nas/sync and via KAPT in `:app`. Output: `<module>/schemas/<fully.qualified.Db>/<version>.json`. **Generated: NO** (needs a Gradle build in CI). Not written by hand. After the first CI build, commit those files and compare each with the declared entities. Databases #4, #8, #11, #12 still have `exportSchema = false` (dormant/unverified; not changed).

## Historical schemas and migration tests: BLOCKED
Needed for `MigrationTestHelper` tests of `ProPDFDatabase` 2->3 and 3->4: authentic `2.json` and `3.json`. Not in the archive; no `.git`; `exportSchema` was `false`, so Room never emitted them, even in history. Possible authentic route: check out the commits where `version = 2` and `version = 3`, set `exportSchema = true` temporarily with the schema location, build with the real Room compiler, and keep the output. Also confirm whether v2/v3 ever shipped to users. Not fabricated. Tests to write once available: 2->3, 3->4, rows/favourites/bookmarks/collections/signature metadata survive, DAO works afterwards.
**Latent risk (unverified):** `MIGRATION_2_3` ALTERs `pdf_documents` (must exist at v2); neither migration creates the tag, collection, form-field/data or activity tables that v4 declares. Source comments say these entities were added to this database late. If any shipped v2/v3 file lacks them, Room's post-migration validation fails and the app can crash on open. Settle with the real schemas before any release that may meet such users.

## Destructive fallback and recovery plan (not implemented)
1. Do not remove any fallback yet. Rebuildable (#3, #4, #6, #8, #12 caches/jobs) may keep it. User-critical (#1, #2, #5, #7, #10, #13, #14) should lose it only after steps 2-4.
2. Obtain schemas, write migration tests, pass them in CI (`ProPDFDatabase` first).
3. Add recovery before any version bump: copy the database file aside (and `-wal`/`-shm`) before migrating; on migration failure keep the copy, open a fresh database, and tell the user; never silently delete.
4. Then remove the fallback for that database only, behind its tests. Never bump a version in the same change as removing a fallback.
5. Decide, separately, whether #2, #5, #10 (no migrations, destructive fallback, user data) need baseline schemas committed *before* their next version change.

## Other finding
Recycle-bin cleanup (`batch/RecycleBinCleanupWorker`) uses the `propdf_app_database` `PdfDocumentDao` while the bin UI reads `propdf_database`: see the implementation report, finding 1. Unverified, not changed.

## Follow-up round
- Recycle bin: `:core` `propdf_database` is authoritative; `:app` `propdf_app_database.pdf_documents` is a separate, unsynchronised table that no active path soft-deletes into. Details: `docs/RECYCLE_BIN_DATA_FLOW.md`. Cleanup worker retargeted to `:core` (rows only).
- Migration safety: still **BLOCKED**. No Git history, release tags, release APKs or schema artifacts were available. To recover authentic schemas the owner can: (1) in the GitHub repo find the commits where `ProPDFDatabase` was `version = 2` and `version = 3` (`git log -S"version = 3" -- core/.../ProPDFDatabase.kt`); (2) at each, set `exportSchema = true` and `room.schemaLocation`, build with CI, keep the generated `2.json` / `3.json`; (3) confirm from release history whether v2/v3 ever reached users; (4) alternatively pull the SQLite file from a device that ran those builds and read its `sqlite_master`. Then write `MigrationTestHelper` tests (2->3, 3->4). Not fabricated.

## Round 3 note
`security_database` entities `SecurityOperationEntity` and `SecureDocumentEntity` now declare their timestamp columns as `Long` instead of `Instant` (same INTEGER epoch-millis values, same nullability, version stays 1, `InstantConverter` removed). Verify against the generated `security` schema JSON after the next build. Instrumented Room tests for the lookup and recycle-bin paths now have real PASS evidence (8/8 each).
