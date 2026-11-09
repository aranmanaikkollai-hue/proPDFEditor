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
