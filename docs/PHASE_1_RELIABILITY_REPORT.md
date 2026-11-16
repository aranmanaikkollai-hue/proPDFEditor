# PHASE 1 - RELIABILITY REPORT

**BUILD NOT RUN - needs CI.** Nothing in this report was compiled, linted or executed. Source tree analysed:
`proPDFEditor-main__8_.zip`, sha256 `1a0896d2e77e2fb03857fb2134fc3492fdc5581f2815d313dd48441c974f5169` (no `.git`).
Only the repo zip was supplied: no CI logs, test reports, signing-validation zip or Room schema zip. The 843-warning
breakdown comes from `docs/RELEASE_LINT_FINDINGS.md` (an older commit) and may be stale.

## What changed

### 1. Accessibility (ClickableViewAccessibility, ContentDescription)
* Nine custom views that override `onTouchEvent` now also override `performClick()` and get a default content
  description (only when none is set from XML). Views: `SignaturePadView`, `PdfSignatureOverlayView`,
  `AnnotationCanvasView`, `FormFieldOverlayView`, `PageScrubberView`, `DocumentCropOverlay`,
  `RedactionOverlayView`, `SignatureDrawView` (app/viewer/scanner/security). `PremiumPageView` had an
  `onTouchEvent` that only called `super`, plus a lint suppression and an empty helper: all removed.
* `performClick()` is called on ACTION_UP in the simple drawing/drag views (signature pad, signature draw,
  redaction, crop overlay, page scrubber). It is only overridden, not called, in `AnnotationCanvasView`,
  `PdfSignatureOverlayView` and `FormFieldOverlayView`, whose touch code is more involved.
* Two touch listeners (`OcrCropActivity`, `UnifiedGestureCoordinator`) now call `performClick()` on ACTION_UP; the
  `@SuppressLint("ClickableViewAccessibility")` on the coordinator was removed.
* XML: all 7 icons that lacked a description were fixed (3 in `item_document_list.xml`, OCR crop image, two scanner
  previews, viewer thumbnail). Decorative icons use `importantForAccessibility="no"`. The favourite icon's
  description now reflects its state (add / remove).
* Compose `contentDescription = null` (62 sites): not changed. Four candidates were inspected and are icons next to
  a visible text label, where null is correct. The other 58 were classified by a heuristic and not read one by one.
* **Not done:** these free-form canvases still cannot be operated with TalkBack gestures (drawing a signature,
  dragging a crop corner). They now have names and click support only. A real alternative (for example numeric
  corner entry for crop) is a Phase 2+ design item.

### 2. INTERNET permission - removed
No reachable code opens a network connection: workers use `NetworkType.NOT_REQUIRED`; OkHttp/WebDAV exists only in
`:nas`, which no `:app` or `:viewer` code references; the cloud managers in `:app` are not referenced; `HtmlConverter`
uses a WebView on local HTML and is dormant; ML Kit uses the bundled recognisers. `INTERNET` is now removed with
`tools:node="remove"` so a library cannot re-add it. `ACCESS_NETWORK_STATE` is kept (WorkManager reads it; it is a
normal permission with no data access). `.github/workflows/release-audit.yml` gains a step that fails if the merged
release manifest contains INTERNET and stores the merged permission list as evidence.
**Risk:** if a dormant module is later activated (`:nas`, cloud sync) it will fail until the permission is re-added
deliberately.

### 3. Room: finding, hardened migrations, tests
* **Finding.** `ProPDFDatabaseMigrations.kt` and the `@Database` comment say the `pdf_documents` family was added to
  this database class late and "none of that migration path had ever run". An installed v2/v3 `propdf_database`
  may therefore not contain `pdf_documents`. The old `MIGRATION_2_3` did a bare `ALTER TABLE pdf_documents`, which
  throws on a missing table; Room does not fall back to a destructive migration for an exception thrown inside a
  migration, so such an upgrade would fail to open the database, and a v3 upgrade would fail schema validation because the
  family tables are missing. Whether any shipped build had a v2 or v3 database without these tables is not
  known from the repo: **please confirm which DB versions you actually released.**
* **Change.** `MIGRATION_2_3` adds each column only if the table and column state allow it; `MIGRATION_3_4` also
  creates, with `IF NOT EXISTS`, any missing table of the family from the exact v4 definitions (generated from
  `4.json`). Rows are never modified. `AnnotationDatabase.MIGRATION_1_2` was made public for testing.
* **Tests** (all in `app/src/androidTest/java/com/propdfeditor/db/`, because `:annotations` has no androidTest and
  CI only targets `:app :core :viewer`):
  * `ProPDFDatabaseMigrationInstrumentedTest`: 2->4 and 3->4, each with and without the family, row preservation,
    validation against `4.json`.
  * `AnnotationDatabaseMigrationInstrumentedTest`: 1->2, row preservation, validation against `2.json`.
  * `ExportedSchemaMatchesCompiledInstrumentedTest`: for the 13 databases that export a schema, the compiled
    identity hash must equal the committed JSON (catches a stale or missing schema file).
  * `RoomSchemaTestSupport`: helpers. `app/build.gradle` adds `room-testing` and the schema folders as androidTest assets.
* **Limits.** Only the latest schema JSON per database is committed, so older versions are **reconstructed** from
  the latest schema and the migration code; they are inferences, not captured databases. If you can supply older
  `<version>.json` files from git history, the tests should use them. The other 11 exported databases have no
  migrations (destructive fallback), so no data-preserving migration exists to test; four databases do not
  export a schema (`RecentFilesDatabase` in `:app`, `AnnotationDatabase` and `ViewerDatabase` in `:viewer`,
  `SearchDatabase` in `:core`).

### 4. Real-device checklist
`docs/REAL_DEVICE_TEST_CHECKLIST.md`: 30 rows, all NOT RUN.

## Other findings (not changed)
* The "pure ASCII Kotlin" rule is violated by 57 existing Kotlin files (em dashes, middle dots and similar). One was
  fixed because it was edited anyway (`DocumentListAdapter.kt`, middle dot now `\u00B7`). Say if you want a sweep.
* `docs/RELEASE_LINT_FINDINGS.md` describes an older lint run; send a fresh `lintRelease` result to confirm the
  `ClickableViewAccessibility` (12) and `ContentDescription` (7) counts go to zero.

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Nothing verified by a build or test log in this phase. | Nothing known: no logs were supplied for this tree. | Compilation of every changed file; lint counts; all new Room tests (they may expose problems, especially the reconstructed old schemas and in-memory opening of all 13 databases); manifest merge result; TalkBack behaviour; all 30 device-checklist rows. |

## Files
Changed: `.github/workflows/release-audit.yml`, `app/build.gradle`, `app/src/main/AndroidManifest.xml`,
`app/src/main/res/values/strings.xml`, `app/src/main/res/layout/{item_document_list,activity_ocr_crop,activity_modern_scanner}.xml`,
`scanner/src/main/res/layout/activity_modern_scanner.xml`, `viewer/src/main/res/layout/item_thumbnail.xml`,
`viewer/src/main/res/values/strings.xml`, `security/src/main/res/values/strings.xml`,
`annotations/.../AnnotationDatabase.kt`, `core/.../ProPDFDatabaseMigrations.kt`, the nine views, two listeners,
`DocumentListAdapter.kt`, `PremiumPageView.kt`.
New: `scanner/src/main/res/values/strings.xml`, `docs/REAL_DEVICE_TEST_CHECKLIST.md`,
`docs/PHASE_1_RELIABILITY_REPORT.md`, four files under `app/src/androidTest/java/com/propdfeditor/db/`.
Moves/deletions: none (no `mv`/`rm` needed).
