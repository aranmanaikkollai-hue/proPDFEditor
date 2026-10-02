# Phase 1 Forms PDFBox migration report

## 1. Executive summary

The active `:editor` Forms engine was changed from iText AcroForm calls to
`com.tom_roush:pdfbox-android:2.0.27.0`.  The Forms UI, navigation,
ViewModels, Room DAOs, and repository interface were retained.  iText remains
in other modules and intentionally was not removed.  Forms source has no
`com.itextpdf` imports and the editor module no longer declares iText.

## 2. Exact files changed

| File | Reason / significant change |
| --- | --- |
| `editor/build.gradle` | Removed the editor-only iText dependency; retained the existing PDFBox Android 2.0.27.0 dependency. |
| `editor/.../forms/engine/PdfFormEngine.kt` | Replaced iText field discovery, filling, XFDF value extraction, and flattening with PDFBox.  The engine initializes PDFBox, closes each `PDDocument`, saves through a partial file, and refuses flattening an already signed signature field. |
| `editor/.../forms/repository/PdfFormRepositoryImpl.kt` | Added explicit `file`/`content` URI validation and cache staging for content input, cleanup for staged input, cancellation checks after engine output, and propagation of engine errors.  Existing Flow collection and `first()` save snapshot behavior remain. |
| `editor/.../forms/worker/FlattenFormWorker.kt` | Replaced arbitrary URI-to-path conversion with scheme-checked input staging and temporary output publication/cleanup. |
| `app/src/main/java/com/propdf/editor/di/FormModule.kt` | Removed the obsolete zero-argument provider so Hilt uses the PDFBox engine's application-context constructor. |
| `PHASE_1_FORMS_PDFBOX_MIGRATION_REPORT.md` | This audit and validation record. |

## 3. Files intentionally not changed

No page editor, security, signature engine, batch processor, viewer,
scanner, OCR, Room schema/DAOs, navigation, or conversion code was changed.

## 4. Before/after engine

* **Before:** iText AcroForm.
* **After:** PDFBox Android 2.0.27.0 (`com.tom_roush.pdfbox`).

## 5. Form field support matrix

The implementation maps PDFBox terminal fields to the existing domain object.
`UNVERIFIED` entries were not exercised because Android instrumentation could
not be started in this environment.

| Field | Read | Write | Save/Reopen | Appearance | Flatten | Tested |
| --- | --- | --- | --- | --- | --- | --- |
| Text | Implemented | Implemented | UNVERIFIED | PDFBox value appearance requested | PDFBox `flatten()` | UNVERIFIED |
| Checkbox | Implemented (`onValue`) | Implemented | UNVERIFIED | PDFBox appearance update | PDFBox `flatten()` | UNVERIFIED |
| Radio | `/Opt` / appearance states discovered | PDFBox semantic value | UNVERIFIED | PDFBox appearance update | PDFBox `flatten()` | UNVERIFIED |
| Combo | Implemented | Implemented | UNVERIFIED | PDFBox appearance update | PDFBox `flatten()` | UNVERIFIED |
| List | Implemented | Implemented | UNVERIFIED | PDFBox appearance update | PDFBox `flatten()` | UNVERIFIED |
| Required | Implemented | Preserved on newly created fields | UNVERIFIED | N/A | N/A | UNVERIFIED |
| Read-only | Implemented | Preserved on newly created fields | UNVERIFIED | N/A | N/A | UNVERIFIED |
| Repeated names | Widget-per-field discovery retained | PDFBox field value semantics | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |
| Multi-page | Page identified by widget annotation membership | N/A | UNVERIFIED | UNVERIFIED | UNVERIFIED | UNVERIFIED |

## 6. Radio `/Opt` analysis

No committed real radio fixture existed in this repository, so no fixture
structure or save/reopen result can honestly be recorded.  The implementation
reads `/Opt` from the field COS dictionary; where absent, it reads each
widget's normal appearance-state keys and excludes `Off`.  It does not derive
radio values from UI labels.  Required two-option, three-option, non-English,
different-`/Opt`, and multi-page fixture tests are **UNVERIFIED**.

## 7. SAF analysis

`file://` is accepted only after a scheme check and a non-null path check.
`content://` is opened through `ContentResolver`, copied to a private Forms
cache file, and that local file is passed to PDFBox.  Staged repository input
is deleted in `finally`.  `FlattenFormWorker` stages both supported input
schemes and only publishes its staged output after a successful flatten; its
partial output is deleted in `finally`.  The public repository output contract
is still `File`, so SAF output URIs are handled by the existing UI host, not
redesigned here.

## 8. Resource/cancellation safety

All engine document loads use `use`.  Worker streams use `use`, staged files
are deleted in `finally`, and engine saves write a sibling partial file before
publishing.  Repository fill/save checks coroutine cancellation before
persisting values/copying output.  **UNVERIFIED:** forced-failure cleanup and
cancellation behavior on an Android device.

## 9. Tests

| Test / check | Purpose | Result |
| --- | --- | --- |
| `./gradlew :editor:compileDebugKotlin --no-daemon --stacktrace` | Compile and resolve PDFBox Android APIs | **BLOCKED** — Gradle failed while parsing settings due to `Unsupported class file major version 69`. |
| `rg -n 'com\\.itextpdf' editor/src editor/build.gradle` | Verify no Forms iText import/editor iText dependency | **PASS** — no matches. |
| `rg -n 'File\\(uri\\.path|File\\(Uri\\.parse|uri\\.path!!|requireNotNull\\(uri\\.path' editor/src/main/java/com/propdf/editor/feature/forms` | Review unsafe URI conversion | **PASS WITH REVIEW** — remaining `requireNotNull(uri.path)` calls are directly inside explicit `file` scheme branches. |
| Android instrumentation field, render, flatten, SAF, radio tests | Required behavioral validation | **UNVERIFIED** — no existing Forms fixtures/tests and the build environment blocked instrumentation. |

## 10. Build status

**BUILD NOT RUN — ENVIRONMENT BLOCKED.**

Exact attempted command: `./gradlew :editor:compileDebugKotlin --no-daemon --stacktrace`.
Gradle 8.2 failed before project configuration with:
`Unsupported class file major version 69` while opening the settings script
class cache.  No toolchain change was made.

## 11. Remaining iText inventory

iText intentionally remains in audited non-Forms locations, including app
page operations/converters, core signature-related code, security, and other
modules.  This change only removes the editor module declaration after Forms
source ceased importing it.

## 12. Known limitations

* Phase acceptance is **not fully met**: Android instrumentation tests and real
  PDF fixtures, including the mandated radio `/Opt` cases and visual
  `PdfRenderer` checks, could not be added/run and are UNVERIFIED.
* The PDFBox API compilation itself is UNVERIFIED because Gradle cannot reach
  project configuration in this environment.
* Existing visual-signature-image and button-image appearance operations now
  fail explicitly rather than attempting unsupported PDFBox behavior; no
  cryptographic signature functionality was modified.
* PDFBox appearance and flatten behavior must be verified with the required
  real fixtures before claiming production readiness.

## 13. Next phase recommendation

Restore a compatible Android/Gradle execution environment, then complete the
Forms instrumentation fixture suite and review its results before considering
any separately approved next migration phase.
