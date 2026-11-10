# TEST RESULTS

Tested commit: **none**. The archive has no `.git`, and no build or test of Android code was executed in this round (no Gradle/SDK/emulator/network in the sandbox). Pristine-archive fingerprint `5e49861ee298ffcd74874861bc8d6a56e1da73c5615be562b9e9b6cf18c3388a`.

## Current round (this patch)
| Item | Status | Evidence |
|---|---|---|
| Debug build (`android.yml`) | NOT RUN | |
| Release build / R8 | NOT RUN | |
| JVM tests incl. `DocumentScanWorkerRuleTest` (16 new/expanded) | NOT RUN | written, never compiled |
| Instrumented tests incl. `RecentFilesRepositoryLookupInstrumentedTest` (8 new) | NOT RUN | |
| Release lint + gate | NOT RUN | no lint report exists for any tree |
| iText release-APK scan | NOT RUN | no release APK |
| Dependency inspection | NOT RUN | |
| Signature validation (qpdf / pdfsig / pyHanko) | NOT RUN | |
| Room schema generation | NOT RUN | no `schemas/` produced |
| Migration tests | BLOCKED | historical schemas unavailable |
| Real-device checklist | NOT RUN | `docs/MANUAL_DEVICE_TEST_CHECKLIST.md` |
| Skipped tests | none observed (nothing ran) | |

### Executed locally (Python tooling only; `python3 -m unittest discover -s tools/release`)
| Suite | Tests | Result |
|---|---|---|
| `test_scan_itext_in_apk.py` | 16 | PASS |
| `test_lint_gate.py` | 9 | PASS |
| `test_check_dependency_insight.py` | 8 | PASS |
| `test_summarize_test_results.py` | 5 | PASS |
| Total | 38 | PASS |
Also: `source_fingerprint.py` reproduced the pristine-archive fingerprint. Workflow YAML parsed with PyYAML (syntax only; never run on GitHub).

## HISTORICAL (not evidence for this tree)
Reported by the owner in earlier sessions and the earlier root report (`TEST_RESULTS.md`, itself now bannered). None is tied to this archive; none may be used to claim this commit passed.
| Run | Commit | Result |
|---|---|---|
| "Run N" (earlier report) | `e3945f803b2864c1a11df803b3a4b9a3d1afb589` (from its checkout log) | JVM 132, 0 failed; instrumented 266, 0 failed, 3 skipped; release DEX: 0 iText classes |
| Baseline CI, 18 Oct 2026 | `611778a12ccd5ecee99be3027bc48b5561283347` | JVM 79 pass; instrumented 200 run, 197 pass, 3 skipped (2 Tamil-font assumes, 1 ViewerFlowTest), 0 fail; corpus 21/21 |
| CI with Phase 2B patch, 18 Oct 2026 | `11d5960a326750d5838078e36cf38b85af677262` | JVM 110/110; instrumented 211 run, 207 pass, 3 skipped, 1 fail (`ViewerFlowTest.homeScreen_showsQuickActions_orTheEmptyStateActions`, cause not established) |
The three sets are mutually inconsistent in counts because they are different trees.

## Required next run
`android.yml`, `android-tests.yml`, `release-audit.yml`, `signing-validation.yml` on the final commit; keep each run's `reports/source-identity.txt` and the summaries (`unit-summary.txt`, `instrumented-summary.txt`, `lint-gate.txt`, `itext-classes-in-release-dex.txt`, `dependency-insight-summary.txt`).

## Follow-up round additions (all NOT RUN)
New JVM tests: `RecycleBinPolicyTest` (6), `RecycleBinCleanupRepositoryTest` (6). New instrumented: `RecycleBinLifecycleInstrumentedTest` (5). No commit, no CI run, no Android result exists for this tree. Python tooling unchanged (38 pass, run earlier).

## Owner-decision round additions (all NOT RUN)
`RecycleBinCleanupRepositoryTest` now 11 tests and `RecycleBinPolicyTest` 8 (JVM); `RecycleBinLifecycleInstrumentedTest` 8 (Room). No compilation or execution occurred.

---
## CI evidence received (owner-supplied logs/artifacts, Nov 2026)
**Tested commit: `73775cff108ee0b51ea25055a01814e92c9446ec`** (from the `git log -1` line of the checkout step in both logs). This commit is the repository state *before* the stabilisation patch (no `tools/release`, old workflows). It contains the recycle-bin round after the `DocumentRepositoryImpl` syntax fix. Source fingerprint for this run was not recorded (the old workflows did not record it). Results below are therefore for that commit only and are **HISTORICAL for any later commit**.

| Workflow | Result | Evidence |
|---|---|---|
| `android.yml` debug build (run log 114256824462) | **PASS** | `BUILD SUCCESSFUL in 5m 25s`, commit above |
| `release-audit.yml` (old version, run log 114256977812) | **PASS** (all steps) | release APK assembled with R8 (`BUILD SUCCESSFUL in 7m 38s`); iText scan: 4 DEX files, 0 iText descriptors (old shell scan, not the new Python scanner, no R8 mapping check); licence generator: 280 dependencies, 22 flagged for manual review; `:app:lintRelease` completed (HTML report written; XML report and error count NOT seen) |
| JVM unit tests (artifact `unit-test-reports (4)`) | **PASS 154/154**, 0 skipped | app 123 (incl. `RecycleBinCleanupRepositoryTest` 12, `RecycleBinPolicyTest` 8), core 25, viewer 6; commit not recorded in the artifact |
| Instrumented tests (artifact `instrumented-test-reports (4)`) | **PASS 274 run, 0 failed, 3 skipped** | app 198 (3 skipped), core 54 (incl. `RecycleBinLifecycleInstrumentedTest` 8/8), viewer 22. Skipped: one each in `InvisibleTextLayerInstrumentedTest`, `UnicodeAndSearchableInstrumentedTest`, `ViewerFlowTest`; commit not recorded |
| `signing-validation.yml` (artifact `signing-validation (4)`) | **PASS** | 18 instrumented signer tests; qpdf, Poppler pdfsig, pyHanko agreements per `signing-validation.md` |

Not covered by this run: the scan-lookup fix (`DocumentScanRunner`, 16 tests), the Python release tooling, the lint gate, schema export (the Room schema warnings still appear in the build log), migration tests, real-device tests. The two earlier compile errors (`DocumentRepositoryImpl.kt` leftover fragment; `RecycleBinViewModel.permanentDelete` self-call) are fixed in this commit.

---
## CI evidence: commit `6f248056e8d3ef6b09f03f1a48e83066ba7e5f4e` (stabilisation patch applied)
Source identity: the uploaded snapshot has **1,344 files, true fingerprint `acc7c6775da447c494f8897d2389bfc72150664253b6d46e0dd03b7de0d93d0c`**. CI recorded different values (`9fb9256b...`, `627dd5eb...`, `c07b9033...`; 1,345 files) because the fingerprint step piped through `tee reports/source-identity.txt`, which created a half-written output file inside the tree before hashing. Reproduced locally: the snapshot plus that partial file hashes to the signing run's `c07b9033...`. Fixed in the workflows (hash first, write the file afterwards). Treat the CI-recorded fingerprints of this run as invalid; the commit SHA is correct.

| Workflow | Result | Evidence |
|---|---|---|
| `android.yml` debug build | **PASS** | `BUILD SUCCESSFUL in 6m 31s` |
| `android-tests.yml` JVM | **PASS 168/168** | app 137, core 25, viewer 6; `DocumentScanWorkerRuleTest` 16/16, `RecycleBin*` 20/20 |
| `android-tests.yml` instrumented | **PASS 279, 0 failed, 3 skipped** (282 total) | app 198 (3 skipped), core 62, viewer 22; `RecentFilesRepositoryLookupInstrumentedTest` 8/8, `RecycleBinLifecycleInstrumentedTest` 8/8 |
| `signing-validation.yml` | **PASS** | 18 signer tests; qpdf, pdfsig, pyHanko consistent |
| `release-audit.yml` release build + R8 | **PASS** | `:app:assembleRelease` succeeded |
| dependency insight | **PASS 7/7** | iText absent from `:app` and `:core` release runtime classpaths; PDFBox-Android present (positive control); Bouncy Castle present; iText present only in `debugAndroidTestRuntimeClasspath` |
| iText release scan (new Python scanner) | **PASS (CLEAN)** | 4 DEX files, R8 mapping checked, 0 descriptors, 0 APK entries, 0 mapping classes |
| release lint gate | **FAIL (expected, real)** | 49 errors, 843 warnings: see `docs/RELEASE_LINT_FINDINGS.md` |
| licence inventory step | **FAIL (tooling bug, fixed)** | `ls -d tools/licence tools/license` returned 2 for the missing spelling and `pipefail` aborted the step; no CSV in this run |
Skipped (3, all instrumented, pre-existing): `InvisibleTextLayerInstrumentedTest` 1, `UnicodeAndSearchableInstrumentedTest` 1, `ViewerFlowTest` 1.
Not covered: schema JSON files (not inspected in the uploads), migration tests (BLOCKED), real-device tests (NOT RUN), the fixes made after this run (lint fixes, workflow fixes).

---
## CI evidence: commit `c14b50fbb5f61c05c0c53bc021976ed44a2c5848` (lint fixes applied)
**Source identity verified.** All three workflows (Android Tests run 42601206331, Signing run 42601317351, release-audit) recorded the same fingerprint `1903d075467854b1c5cce2225bbcdade332e28943b54bc2060215a2a48ea7add`, 1,345 files, and the same value is reproduced locally from the uploaded snapshot. The earlier fingerprint defect is therefore fixed: this is the first run whose recorded fingerprint is a true, reproducible tree identity.

| Workflow | Result | Evidence |
|---|---|---|
| `android.yml` debug build | **PASS** | `BUILD SUCCESSFUL in 7m 15s` |
| JVM tests | **PASS 168/168** | app 137, core 25, viewer 6 |
| Instrumented tests | **PASS 279, 0 failed, 3 skipped** | app 198, core 62, viewer 22 |
| `signing-validation.yml` | **PASS** | 18 tests; qpdf, pdfsig, pyHanko consistent |
| release build + R8 | **PASS** | |
| dependency insight | **PASS 7/7** | iText absent from release classpaths; PDFBox present; iText only in androidTest |
| iText scan | **PASS (CLEAN)** | 4 DEX, R8 mapping checked, 0 matches |
| licence inventory step | **PASS (step ran, artifact uploaded)** | artifact `release-audit` (ID 13665908607) not yet reviewed; the 22 flagged rows are NOT reviewed |
| release lint gate | **FAIL: 2 errors** (was 49) | both `NewApi` in `InstantConverter.kt:9,12` (`java.time`): a leftover, now unreferenced file that was not deleted. 843 warnings |
The 47 other lint errors from commit `6f248056...` are confirmed cleared by this run. The remaining two are removed by deleting `security/.../data/converter/InstantConverter.kt` (this patch).

---
## CI evidence: commit `578ab77ceab3ddc7096f473c8895431ad2b5c2f8` - ALL FOUR WORKFLOWS (current best evidence)
Source identity: `fba67ce14b0f05f9bd14e8a0cd8fd39080c189609e275d0a2a191cf1f320cf1b`, 1,344 files, recorded identically by the Android Tests (run 42725037242), Release audit (42725079976) and Signing (42725136375) workflows; the debug build log shows the same commit. This is the first fully green exact-commit set.

| Workflow | Result | Evidence |
|---|---|---|
| `android.yml` debug build | **PASS** | `BUILD SUCCESSFUL in 6m 59s` |
| `android-tests.yml` JVM | **PASS 168/168** | app 137, core 25, viewer 6; 0 skipped |
| `android-tests.yml` instrumented | **PASS 279, 0 failed, 3 skipped** | app 198, core 62, viewer 22 |
| `signing-validation.yml` | **PASS** | 18 tests; qpdf, pdfsig, pyHanko consistent |
| `release-audit.yml` release build + R8 | **PASS** | `BUILD SUCCESSFUL in 10m 54s` |
| release lint gate | **PASS** | `issues total: 843 | errors (Error+Fatal): 0 | warnings: 843` (report parsed, not merely present) |
| dependency insight | **PASS** | iText absent from `:app`/`:core` release runtime; PDFBox present |
| iText scan | **PASS (CLEAN)** | 4 DEX, R8 mapping checked, 0 matches |
| licence inventory | **GENERATED** | 280 dependencies, 22 flagged; artifact `release-audit` (ID 13722213288) holds the CSV; **rows not yet reviewed** |
Skipped (3, instrumented, pre-existing): `InvisibleTextLayerInstrumentedTest` 1, `UnicodeAndSearchableInstrumentedTest` 1, `ViewerFlowTest` 1.
This commit does not include the schema-upload step added afterwards. Any later commit must be re-run.

---
## CI evidence: commit `f4ce76a529e2b76cc766080ead39a3d80ee30adf` (schema upload step added)
Source identity `0e9c1b6133e087eb9ae8b9b07ff60caabe86e288bb8c165a9c887d14f29457f3`, 1,344 files, identical in Android Tests (run 42755984020), Signing (42756064512) and Release audit; the debug build log is on the same commit.
| Workflow | Result |
|---|---|
| debug build | PASS (`BUILD SUCCESSFUL in 6m 3s`) |
| JVM tests | PASS 168/168 (app 137, core 25, viewer 6) |
| instrumented tests | PASS 279, 0 failed, 3 skipped (app 198, core 62, viewer 22) |
| signing validation | PASS (18 tests; qpdf, pdfsig, pyHanko) |
| release build + R8 | PASS (`BUILD SUCCESSFUL in 10m 47s`) |
| lint gate | PASS (0 errors, 843 warnings) |
| iText scan / dependency inspection | PASS (CLEAN; 7/7 checks) |
| licence inventory | GENERATED (280 dependencies, 22 flagged; artifact ID 13735679481); rows unreviewed |
| Room schema export | PASS (13 files generated and validated in CI; artifact `room-schemas`) |
This is the current best exact-commit evidence. The schema drift check added in the next patch is not part of this run.
