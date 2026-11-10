# RELEASE READINESS CHECKLIST

Tree: `proPDFEditor-main1.zip` + this patch. Pristine fingerprint `5e49861e...388a`; no commit SHA exists.
Statuses: PASS (executed, passed, evidence) / FAIL / PARTIAL / NOT RUN / BLOCKED / NOT IMPLEMENTED.
**Nothing Android-related was executed. No PASS exists for this tree except the local Python tooling.**

| Requirement | Status | Evidence / note |
|---|---|---|
| Source baseline recorded | PASS | archive SHA-256, 1,325 files, fingerprint reproduced by script |
| Exact final commit tested by CI | NOT RUN | no commit; run all 4 workflows |
| Debug build | NOT RUN | |
| Release build + R8 | NOT RUN | |
| JVM tests | NOT RUN | 16 scan tests written, not compiled |
| Instrumented tests | NOT RUN | 8 Room tests written |
| Document scan: lookup failure cannot insert/overwrite | PARTIAL | implemented (3-way lookup, insert-if-absent); not compiled or run; worker class and MediaStore source untested |
| Existing favourite/category/last-opened preserved | PARTIAL | same; covered by unit + Room tests, not run |
| iText scanner fails closed | PASS (tool only) | 16/16 local tests |
| iText absent from exact release APK | NOT RUN | |
| Production iText imports | PASS (source grep) | none outside `androidTest`; only the test-only `androidTestImplementation` declaration remains |
| Dependency inspection not suppressed | PARTIAL | the `or true` suppression removed, classifier 8/8 local; workflow not run |
| Release lint gate | PARTIAL | gate 9/9 local; lint never run; current error count UNKNOWN |
| Android 14 foreground-service declaration | PARTIAL | declared in manifest (`dataSync`); lint result unknown |
| Database inventory (17) | PASS (source trace) | `docs/DATABASE_SAFETY_REVIEW.md`; UI reachability not fully traced |
| Schema export | PARTIAL | configured; no JSON generated |
| Migration tests (2->3, 3->4) | BLOCKED | v2/v3 schemas unavailable |
| Destructive fallback removed on user data | NOT IMPLEMENTED | deliberately; plan documented |
| Signature validation (qpdf/pdfsig/pyHanko) | NOT RUN | verdict step now fails closed |
| FileProvider/SAF preserved | PASS (source unchanged) | `file_paths.xml` narrow; `ShareStaging` present. Behaviour NOT RUN |
| Real-device share/camera/crop/OCR/export tests | NOT RUN | `docs/MANUAL_DEVICE_TEST_CHECKLIST.md` |
| WorkManager scheduling unchanged | PASS (source) | legacy initializer and legacy BootReceiver unregistered; active BootReceiver schedules recycle-bin cleanup only |
| Recycle-bin cleanup acts on the bin the UI shows | NOT RUN | possible two-database mismatch (unverified) |
| Third-party licence review | BLOCKED | CSV/insight reports absent |
| Third-party notices shipped | NOT IMPLEMENTED | packaging excludes `META-INF` licence files |
| Project licence | BLOCKED | owner: decide later; README claims MIT with no LICENSE file |
| Documentation reconciled | PARTIAL | new docs + banners; older reports not rewritten |

## Follow-up round (status changes)
| Requirement | Status | Note |
|---|---|---|
| Recycle-bin authoritative DB identified, flow traced | PASS (source trace) | `docs/RECYCLE_BIN_DATA_FLOW.md` |
| Recycle-bin consistency (screen, restore, cleanup on same data) | PARTIAL | corrected in source; tests written, not run; behaviour change pending owner confirmation |
| `Int` overflow in bin age cleanup | PARTIAL | fixed in source; not compiled |
| README licence claim | PASS (source) | now states "not chosen yet" |
| Repository identity (SHA, branch, 55-file patch present) | NOT RUN | no repository access |
| ProPDFDatabase migration safety | BLOCKED | no Git history or schema artifacts available |
| Room schema JSON generated | NOT RUN | needs Gradle |
| Four workflows on exact commit | NOT RUN | |

## Owner decisions applied (Nov 2026)
| Requirement | Status | Note |
|---|---|---|
| Auto-delete (A): 30 default, 1..90, Long math, reboot-triggered only | PARTIAL | implemented in source; tests written; not compiled/run |
| Cleanup timing documented (runs on next worker run after reboot, not on day N) | PASS (documentation) | `docs/RECYCLE_BIN_DATA_FLOW.md` |
| Permanent deletion / Empty Bin / auto cleanup are rows-only; originals preserved | PARTIAL | implemented in source; file-preservation tests written, not run |
| Storage is not reclaimed by permanent delete (consequence of rows-only) | NOT IMPLEMENTED | revisit when file ownership is established |
| Real-device recycle-bin test (delete, restore, empty, reboot cleanup) | NOT RUN | |
| Kotlin compilation, JVM tests, Room instrumented tests, 4 workflows on exact final commit | NOT RUN | release condition; Android implementation unverified |

## CI evidence update (commit `73775cff108ee0b51ea25055a01814e92c9446ec`, pre-stabilisation tree)
| Requirement | Status | Note |
|---|---|---|
| Debug build | PASS | for that commit only |
| Release build + R8 | PASS | for that commit only |
| JVM tests | PASS (154/154) | artifact commit not recorded |
| Instrumented tests | PASS (274 run, 3 skipped, 0 failed) | artifact commit not recorded |
| Recycle-bin tests (JVM 20, Room 8) | PASS | the earlier "NOT RUN" rows are superseded by this evidence |
| Signature validation (qpdf/pdfsig/pyHanko) | PASS | 18 signer tests + independent validators |
| iText absent from release DEX | PASS (old scan) | 0 descriptors in 4 DEX files; new scanner + R8 mapping check NOT RUN |
| Release lint ran | PARTIAL | task completed; XML/error count not seen; gate NOT RUN |
| Licence inventory generated | PARTIAL | 280 dependencies, 22 flagged; CSV not yet reviewed |
| Scan-lookup fix, release tooling, schema export | NOT RUN | not part of that commit |
| Exact final commit tested | NOT RUN | must be re-run after the stabilisation patch is committed |

## CI evidence update 2 (commit `6f248056e8d3ef6b09f03f1a48e83066ba7e5f4e`)
| Requirement | Status | Note |
|---|---|---|
| Debug build | PASS | that commit |
| JVM tests | PASS (168/168) | that commit (artifact carries the SHA) |
| Instrumented tests | PASS (279 + 3 skipped, 0 failed) | that commit |
| Document scan: lookup failure cannot insert/overwrite; favourite/category/last-opened preserved | PASS | 16 JVM + 8 Room tests executed and passed. The worker class and MediaStore source are still not instantiated in a test |
| Recycle-bin consistency and rows-only deletion | PASS (tests) / NOT RUN (real device) | JVM 20, Room 8 |
| Signing validation | PASS | 18 tests + independent validators |
| iText absent from exact release APK, new scanner | PASS | CLEAN, 4 DEX + mapping |
| Dependency inspection not suppressed | PASS | 7/7 checks ran, Gradle errors would fail the step |
| Release lint | **FAIL** | 49 errors (real); fixes implemented, re-run required |
| Exact-commit fingerprint recorded correctly | FAIL (fixed in workflows) | recorded values included an output file; true value in `docs/TEST_RESULTS.md` |
| Licence inventory generated | NOT RUN (this commit) | step bug fixed; earlier older-commit run: 280 deps, 22 flagged |
| Room schema JSON generated and committed | NOT RUN / unverified | artifacts did not include schema files |
| Exact final commit tested | NOT RUN | re-run all four workflows after committing the fixes |

## CI evidence update 3 (commit `c14b50fbb5f61c05c0c53bc021976ed44a2c5848`)
| Requirement | Status | Note |
|---|---|---|
| Exact-commit fingerprint recorded correctly | PASS | identical across 3 workflows, reproduced locally |
| Debug build / JVM 168 / instrumented 279+3 skipped / signing 18 | PASS | that commit |
| Release lint | FAIL (2 errors) | `InstantConverter.kt` leftover; fix = delete file; gate re-run required |
| iText absent, dependency inspection | PASS | |
| Licence inventory generated | PARTIAL | artifact exists; rows not reviewed |
| Exact final commit tested | NOT RUN | next commit (file deletion) must be re-run through all four workflows |

## FINAL CI status (commit `578ab77ceab3ddc7096f473c8895431ad2b5c2f8`, fingerprint `fba67ce1...cf1b`)
| Area | Status | Note |
|---|---|---|
| Android build (debug) | PASS | |
| Android tests (JVM 168, instrumented 279 + 3 skipped) | PASS | |
| Release audit (release build, lint gate 0 errors, iText clean, dependency inspection) | PASS | 843 lint warnings remain, not gating |
| Signing validation | PASS | |
| Document scan safety, recycle-bin consistency, rows-only deletion | PASS (JVM + Room tests) | real-device test NOT RUN |
| Room schema export configured | PARTIAL | no schema-related build warnings remain; JSON files not yet seen or committed; upload step added (next run) |
| Migration tests (ProPDFDatabase 2->3, 3->4) | BLOCKED | authentic v2/v3 schemas unavailable |
| Destructive fallback on user-critical databases | NOT IMPLEMENTED | deliberate; plan in `docs/DATABASE_SAFETY_REVIEW.md` |
| Third-party licence review | PARTIAL | CSV generated (280 / 22 flagged), rows unreviewed; notices not shipped |
| Project licence | BLOCKED | owner decision pending; README states this |
| Real-device checklist | NOT RUN | `docs/MANUAL_DEVICE_TEST_CHECKLIST.md` |
| Worker-class test (DocumentScanWorker, RecycleBinCleanupWorker) | NOT IMPLEMENTED | logic tested through extracted runner/DAO tests |
