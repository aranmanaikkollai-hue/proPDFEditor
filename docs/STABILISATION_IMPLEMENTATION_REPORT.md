# STABILISATION IMPLEMENTATION REPORT

Status words: IMPLEMENTED (code written, **not built**), EXECUTED LOCALLY (ran in the authoring sandbox), NOT RUN, BLOCKED, NOT DONE.
**This sandbox has no Gradle, Android SDK, Kotlin compiler, emulator or network. No Android build, JVM test, instrumented test, lint run or CI run was executed.** Only the Python tooling was executed (see `docs/TEST_RESULTS.md`).

## 1. Source baseline (Part 1)

| Item | Value |
|---|---|
| Archive | `proPDFEditor-main1.zip` |
| Archive SHA-256 | `55a1cf8a11c11e9744d8f3f1f67ae83133eb9d1eddc5b538e55a3d896cc8a60d` |
| Extracted files | 1,325 (root `proPDFEditor-main/`) |
| Source-tree fingerprint (pristine archive) | `5e49861ee298ffcd74874861bc8d6a56e1da73c5615be562b9e9b6cf18c3388a` |
| Fingerprint method | `tools/release/source_fingerprint.py` = `find . -type f -not -path './.git/*' \| LC_ALL=C sort \| xargs sha256sum \| sha256sum`; reproduced by the script on the pristine archive (identical value) |
| `.git` history / commit | none in the archive; **no commit SHA is available** |
| Fingerprint of the patched tree | **not stated here** (a file cannot contain its own tree hash). Each CI run now writes `reports/source-identity.txt` with the commit SHA and fingerprint of what it built |
| Old fingerprint reused? | No. The previous reports cite `6d8bfe0f...04e7` (archive `...__6__1.zip`, 1,317 files); this archive differs, so all old run results are HISTORICAL |
| Existing build/test evidence in the archive | none (no XML, no logs, no lint report, no `DEPENDENCY_LICENSES.csv`, no `insight-*`) |

Android project root = archive root. Gradle: AGP 8.2.2, Kotlin 1.9.22, Hilt 2.51.1, Room 2.6.1, compile/target SDK 34, min SDK 23 (unchanged). 14 modules. Workflows present: `android.yml`, `android-tests.yml`, `release-audit.yml`, `signing-validation.yml`.

## 2. P1 - DocumentScanWorker data-loss risk (Part 2): IMPLEMENTED, NOT RUN

**Root causes (both confirmed in source)**
1. `EditorDocumentRepositoryImpl.getRecentFileByUri` mapped *every* non-success `AppResult` to `null`, so a database error looked identical to "record missing", and the worker then inserted.
2. `RecentFilesDao.insert` is `OnConflictStrategy.REPLACE`; an insert over a row that exists but was not read (lookup failed, or a concurrent write) deletes and recreates it, resetting favourite, category and last-opened. The mirror write in `RecentFilesRepositoryImpl.add` also overwrote the `pdf_documents` row (`isFavorite = file.isFavorite`).
3. Not fixable by checking the error type: `Throwable.toAppException()` maps a `FileNotFoundException` to `AppException.FileNotFound`, the same type `getByUri` uses for "missing", so "is it FileNotFound?" is not a safe NOT_FOUND test.

**Fix**
- `core/.../domain/repository/RecentFileLookup.kt` (new): `RecentFileLookup` = `Found` / `NotFound` / `Failed(cause, retryable)`; `RecentFileInsertResult` = `Inserted` / `AlreadyExists` / `Failed`.
- `RecentFilesRepository` + `RecentFilesRepositoryImpl`: `lookupByUri` (reads the DAO directly; errors become `Failed`, never `NotFound`; `CancellationException` is rethrown) and `addIfAbsent`.
- `RecentFilesDao.insertIfAbsent` = `@Insert(onConflict = IGNORE)`. Atomic, so a row created between lookup and insert is not overwritten. No REPLACE on the scan path. The `pdf_documents` mirror is insert-only-if-absent and never modifies an existing row.
- App `DocumentRepository`: nullable `getRecentFileByUri` **removed** (it was the unsafe API; its only caller was the worker) and replaced by `lookupRecentFile` / `insertRecentFileIfAbsent`. `insertOrUpdateRecentFile` is unchanged (still used by `DocumentManagerViewModel`).
- `DocumentScanRunner.kt` (new, Android-free): the production decision logic. Inserts only on explicit `NotFound`. `Found` -> skip. `Failed`, a thrown exception, or a `Found` row for a different URI -> no insert, failure recorded with the original `Throwable`. One bad document does not stop the others.
- `DocumentScanWorker.kt`: now a thin wrapper (MediaStore source -> runner -> WorkManager result). `Result.retry()` only if every failure was transient (locked/busy DB, disk I/O, `IOException`) and attempts remain (`MAX_ATTEMPTS = 3`); permanent failures -> `Result.failure()` with the error *class name*. The log line has counts and the exception class only; no URIs, names or paths. The unused `DATA` (file path) projection column was dropped.
- Scheduling untouched: `WorkScheduler`, the legacy initializer and the legacy BootReceiver are unchanged (Part 11).

**Tests**: `DocumentScanWorkerRuleTest` expanded to 16 JVM tests (the 8 required cases plus race, partial failure, retry policy, source failure) driving the real `DocumentScanRunner` with a fake repository whose `insertOrUpdateRecentFile` has REPLACE semantics, so any use of it would visibly destroy data. New `core/src/androidTest/.../RecentFilesRepositoryLookupInstrumentedTest.kt` (8 in-memory Room tests, including "dropped table -> `Failed`, not `NotFound`" and "existing `pdf_documents` row untouched"). **NOT RUN.**
**Limit**: a `CoroutineWorker` was not instantiated (no Robolectric/work-testing dependency; none added). The worker class itself, the MediaStore source and `Result` mapping are unexercised. A real MediaStore test is NOT IMPLEMENTED.

## 3. P1 - iText release scan (Part 3): EXECUTED LOCALLY (tool), release APK scan NOT RUN

`tools/release/scan_itext_in_apk.py` (stdlib `zipfile`): requires the APK; ZIP `testzip()` integrity; finds every `classes*.dex`; fails if none; reads each completely; validates DEX magic, header size, `file_size` and Adler-32; scans for `Lcom/itextpdf/...` descriptors and prints each; also fails on `com/itextpdf` APK entries; optional `--mapping` scans R8 `mapping.txt`.
Exit codes: 0 clean, 1 iText found, 2 scan incomplete. 16 tests, all required cases (clean, iText, iText in `classes2.dex`, multi-DEX, no DEX, corrupt APK, corrupt DEX, missing APK, empty DEX) plus truncated ZIP, bad CRC, bad magic, mapping cases. **All 16 passed locally.**
**Why `--mapping`**: the release build has `minifyEnabled true`. R8 renames classes, so a descriptor scan alone could miss renamed iText classes. The workflow passes `app/build/outputs/mapping/release/mapping.txt`, and the job fails if it is missing or empty. Even so, the dependency-graph check (section 4) is the primary proof.
Test weakness: the bad-CRC test asserts exit 2 but does not assert the *reason* (the flipped byte could hit a header).

## 4. P1 - Dependency inspection (Part 4): IMPLEMENTED, workflow NOT RUN

`release-audit.yml`: `|| true` removed. `tools/release/check_dependency_insight.py` separates PRESENT / ABSENT (Gradle's "No dependencies matching..." message, exit 0) / FAILED (non-zero exit, empty or unrecognisable report). Expectations on `releaseRuntimeClasspath` for `:app` and `:core`: iText **absent**; PDFBox-Android (`com.tom-roush`) **present** (positive control so a broken inspection cannot look like "absent"); Bouncy Castle recorded. iText on `:app:debugAndroidTestRuntimeClasspath` is recorded (expected present: test-only). The dependency-graph step also fails on empty or wrong files. The test-only `androidTestImplementation('com.itextpdf:itext7-core:7.2.5')` is **kept**; `PdfBoxPasswordInstrumentedTest` fixtures are untouched. 8 classifier tests passed locally.
Also fixed a masking bug of the same kind: default `run` shells have no `pipefail`, so `cmd | tee` hid failures. `release-audit.yml` and `android-tests.yml` now set `defaults.run.shell: bash`.

## 5. P1 - Release lint gate (Part 5): IMPLEMENTED, NOT RUN

`tools/release/lint_gate.py` reads `app/build/reports/lint-results-release.xml`. Exit 2 if the report is missing, empty, malformed, not an `<issues>` document, or any issue has a missing/unknown severity; exit 1 on any `Error`/`Fatal`; exit 0 otherwise. Warnings are listed separately and do not gate. 9 tests (zero, one, multiple, warnings only, missing, malformed, empty, wrong root, unknown severity) passed locally.
`release-audit.yml`: `continue-on-error` removed from `:app:lintRelease`; the gate step runs next; the XML/HTML reports are uploaded. Root `abortOnError false` is **deliberately unchanged** (the gate, not the task, decides).
**Current lint findings are UNKNOWN**: no lint report exists in the archive and none could be generated here. So no "confirmed error" was fixed or suppressed, and nothing about Android 14 foreground-service, WorkManager, resource-qualifier or R8 findings can be claimed. Observed in source only: `SystemForegroundService` is declared `foregroundServiceType="dataSync"` with `FOREGROUND_SERVICE_DATA_SYNC`; `app/lint.xml` has one narrow path-scoped ignore (`EnsureInitializerMetadata` for the two unregistered initializers). No SDK/AGP/Kotlin/dependency version was changed.

## 6. P1 - Exact-tree verification (Part 6): NOT RUN

Cannot be done here. What was prepared: `source_fingerprint.py` + commit SHA recorded at the start of `android-tests.yml`, `release-audit.yml` and `signing-validation.yml`; `summarize_test_results.py` (counts per module, passed/failed/skipped, **no reports = not a pass**); the test job and the instrumented job summaries are uploaded; `signing-validation.yml`'s verdict step previously passed silently when no result XML existed and now fails (signing code and tests untouched; pyHanko/qpdf/pdfsig steps unchanged). **Run all four workflows on the final commit and record `source-identity.txt` with each result.** Results from any other commit are HISTORICAL.

## 7. P2 - Database schema export and migration safety (Part 7)

- Inventory re-traced from source with import-aware DAO resolution: see `docs/DATABASE_SAFETY_REVIEW.md` (17 `@Database`, 13 destructive-fallback sites, confirmed).
- Schema export: `room.schemaLocation` added (KSP `ksp { arg(...) }` in `:core :annotations :scanner :security :storage :nas :sync`; KAPT `kapt { arguments {...} }` in `:app`, which runs Room on KAPT). `exportSchema` flipped `false -> true` on **ProPDFDatabase, OcrDatabase, ScannerDatabase, StorageDatabase** (annotation only). **No version, entity or column changed. Schema JSON was NOT generated** (needs Gradle) and none was written by hand. Expected paths after a CI build: `<module>/schemas/<fully.qualified.Db>/<version>.json`; commit them.
- Historical v2/v3 schemas of ProPDFDatabase: **not available** (no `.git`, no schema files, and `exportSchema` was `false`, so Room never wrote them even in history). Migration tests 2->3 and 3->4: **BLOCKED**; nothing invented. Exact needs and an important latent migration risk: `docs/DATABASE_SAFETY_REVIEW.md`.
- Destructive fallback: nothing removed. No version bumped. No second database.

## 8. P2 - Documentation (Part 8)

Created under `docs/`: this report, `TEST_RESULTS.md`, `DATABASE_SAFETY_REVIEW.md`, `RELEASE_READINESS_CHECKLIST.md`, `DEPENDENCY_LICENSE_REVIEW.md`, `ARCHITECTURE_CURRENT_STATE.md`, `MANUAL_DEVICE_TEST_CHECKLIST.md`. The ten older root reports got a "HISTORICAL SNAPSHOT" banner only (content below it untouched). Stale code comment corrected in `SecurityHubScreen.kt` (comment only). `docs/REMAINING_WORK.md`, referenced by a comment in `app/lint.xml`, does not exist in this archive.

## 9. Licensing (Part 9): BLOCKED

See `docs/DEPENDENCY_LICENSE_REVIEW.md`. No CSV or insight reports in the archive; nothing guessed. No LICENSE/NOTICE file created; no licence chosen; packaging excludes untouched.

## 10-12. FileProvider/SAF, WorkManager, protected PDF functionality: preserved

Unchanged: `ShareStaging`, `file_paths.xml`, SAF code, all PDF engines, PDFBox-Android 2.0.27.0, signing/encryption code, scheduling. No new dependency, API, database or Room version.

## 13. Findings discovered while tracing (not fixed; owner decisions)

1. **Recycle-bin cleanup targets a different database than the bin UI reads.** `batch/RecycleBinCleanupWorker` injects `com.propdf.editor.data.local.dao.PdfDocumentDao` (`:app` `AppDatabase`, file `propdf_app_database`). `RecycleBinViewModel` goes through use cases -> `EditorDocumentRepositoryImpl` -> `:core` `DocumentRepository` (`ProPDFDatabase`, file `propdf_database`). Each has its own `pdf_documents` table. Whether the auto-purge ever sees soft-deleted rows from the other database was **not verified**. Not changed (Part 11: do not alter recycle-bin scheduling). Needs an owner decision and a test.
2. **Latent migration risk in `ProPDFDatabase`**: `MIGRATION_2_3` assumes `pdf_documents` already exists at v2, and neither migration creates the tag/collection/form/activity tables that the v4 entity list declares. Code comments say these entities were added to this database late. If any shipped v2/v3 file lacks them, Room's post-migration validation throws and the app can crash-loop (fallback only applies when *no migration path* exists). Cannot be settled without the real v2/v3 schemas.
3. `BootReceiver` registered in the manifest is `com.propdfeditor.receiver.BootReceiver` (schedules only the recycle-bin cleanup). The legacy `com.propdf.editor.receiver.BootReceiver` (schedules document scan and storage analysis) is **not** registered; the legacy `WorkManagerInitializer` is not registered. Consistent with the owner's decision; `DocumentScanWorker` is not scheduled by any registered path, so the fix is **latent** until the owner activates it.
4. README says "MIT License - see `LICENSE` file" but there is no LICENSE file, and the owner chose "decide later". Not edited.
5. `RecentFilesRepositoryImpl.kt` already contained non-ASCII characters in comments before this work (6 lines in the pristine archive); my additions are ASCII.
6. `RecentFilesDatabase` (core, `recent_files_database`, v2) holds the user's favourites/categories and has destructive fallback with no migrations, and had no schema location until now. It is the database the scan fix protects.

## 14. Files changed

New: `app/.../data/worker/DocumentScanRunner.kt`; `core/.../domain/repository/RecentFileLookup.kt`; `core/src/androidTest/.../data/local/RecentFilesRepositoryLookupInstrumentedTest.kt`; `tools/release/` (`scan_itext_in_apk.py`, `lint_gate.py`, `check_dependency_insight.py`, `summarize_test_results.py`, `source_fingerprint.py`, and 4 `test_*.py`); `docs/` (7 files).
Modified: `app/.../DocumentScanWorker.kt`, `EditorDocumentRepositoryImpl.kt`, `domain/repository/DocumentRepository.kt`, `DocumentScanWorkerRuleTest.kt`, `SecurityHubScreen.kt` (comment); `core/.../RecentFilesDao.kt`, `RecentFilesRepositoryImpl.kt`, `RecentFilesRepository.kt`, `ProPDFDatabase.kt`, `OcrDatabase.kt`; `scanner/.../ScannerDatabase.kt`; `storage/.../StorageDatabase.kt`; `build.gradle` of `app core annotations scanner security storage nas sync`; `.github/workflows/android-tests.yml`, `release-audit.yml`, `signing-validation.yml`; banners on 10 root `.md` files. Nothing deleted.

## 15. Regression risks

- Compile risk (never compiled): the new interface methods, Kotlin smart-casts in `DocumentScanRunner`, and the `SQLite*Exception` references in `RecentFilesRepositoryImpl`.
- Removing `getRecentFileByUri` breaks any caller outside this archive (none inside).
- Enabling `exportSchema` and `room.schemaLocation` can only add files; if a module has KSP/KAPT misconfigured the build log will show a Room schema warning/error. `"$projectDir/schemas".toString()` is used so Groovy passes a `String`.
- Workflow YAML parsed OK locally but was never run on GitHub.
- The lint gate may turn an existing green workflow red the first time: that is intended and is the real current state.

## 16. Outstanding decisions

1. Run the four workflows on the final commit. 2. Commit the generated `schemas/`. 3. Recycle-bin cleanup DAO target (finding 1). 4. Where to obtain v2/v3 schemas (see DB review). 5. Project licence and README wording (decide later). 6. Whether/when to activate the scan worker. 7. Notices approach once the licence CSV exists.

---
## Follow-up round (recycle bin, README) - source-read only, nothing built or run
No new archive or repository access was available, so the repository identity (branch, commit, parent, patch completeness) could **not** be established, the CI workflows were **not** run, and Room schemas were **not** generated. Those requirements remain open.
Done: recycle-bin data flow traced and corrected (`docs/RECYCLE_BIN_DATA_FLOW.md`: authoritative table is `:core` `propdf_database.pdf_documents`; auto-cleanup was pointed at `:app` `propdf_app_database` and never matched; an `Int` overflow in `cleanOldRecycleBinItems` is fixed); 17 new tests written (not run); README no longer claims MIT.
Finding 1 above is therefore **resolved in source, unverified**. Finding 4 (README) is **resolved**.

## Owner-decision round (auto-delete A, rows-only deletion) - source only, not built
Implemented: `permanentlyDelete` no longer deletes files (covers per-item delete, Empty Bin, `batchDelete`, age cleanup); added tests for 1-day purge, 29-day retention, exact-threshold, 90-day, null timestamp, and real-temp-file preservation (JVM and Room). Docs updated with reboot-triggered timing. No scheduling, database version or database structure change. Still NOT RUN: compilation, all Kotlin tests, four workflows.

## Round 3: first full CI run and its defects (commit `6f248056...`)
Fixed (source only, not re-run): (1) release lint gate found 49 real errors, almost all APIs newer than minSdk 23 plus a tablet layout without constraints and two invalid format strings (`docs/RELEASE_LINT_FINDINGS.md`); (2) my fingerprint step polluted the hash with its own output file (workflows corrected); (3) my licence step aborted under `pipefail` (corrected, reproduced and verified locally). Security timestamps changed from `Instant` to epoch-millis `Long` (same stored values, schema unchanged; `InstantConverter.kt` deleted).
Confirmed by CI: debug build, 168 JVM tests, 279 instrumented tests, signing validation, dependency insight, new iText scanner.

## Round 4: all four workflows green on one commit (`578ab77c...`)
Lint gate 0 errors after deleting the leftover `InstantConverter.kt`; all acceptance rows that CI can prove are PASS (see `docs/TEST_RESULTS.md`). Added a validating upload of the generated Room schema JSON (`room-schemas` artifact, fails if none) so the schemas can be committed. Remaining: review the licence CSV, commit schemas, obtain v2/v3 schemas for migration tests, real-device testing, owner decisions (licence, notices).

## Round 5: schemas
Schemas generated by Room in CI (13 files, commit `f4ce76a5...`), verified against declared versions, migration SQL compared with the authentic v4 schema (exact match), schema files and a drift check added. Migration tests still blocked on v2/v3 history.
