# PHASE 2 - Page Editor / PDF Operations: iText -> PDFBox

PHASE: 2 (Page Editor path only). Stopped after this phase.
BUILD STATUS: BUILD NOT RUN - ENVIRONMENT BLOCKED (no Android SDK, emulator or network here).
Nothing below is RUNTIME VERIFIED. Labels used: STATICALLY VERIFIED = read/brace-balance/geometry-math
checked by script; NOT RUN = written but not executed.

## 0. Phase 1 (Forms) prerequisite
Not assumed. The only evidence available is the CI logs from this engagement: the last Forms log showed
4 compile errors in `:editor` (PdfFormEngine.kt x3, FlattenFormWorker.kt x1), fixed earlier in this
session. No newer log has confirmed `:editor` compiles; Phase 1 behaviour is NOT RUN. No Phase 1 file was
modified in Phase 2 (`PdfFormEngine.kt`, `FlattenFormWorker.kt` untouched).

## 1. Active call graph (as found, not as assumed)

    AppNavigation "page-editor/{uri}"  ->  PageEditorScreen  ->  PageEditorViewModel
      (A) WorkManager path: delete / duplicate / move / extract / rotate / crop / resize / mirror
          PageEditorViewModel.executeOperation -> PdfOperationWorker (KEY_SOURCE_URI path)
            -> PdfOperationsRepository (PdfOperationsRepositoryImpl) -> PdfBoxPageEngine
      (B) Direct repository path: loadPdf/getPageCount, thumbnails, insertBlankPage, insertPdfPages,
          insertImagePage, applyReorder (extractPages), page numbers / header-footer / watermark / background
            -> PdfOperationsRepositoryImpl -> PdfBoxPageEngine

Findings that differ from the brief's graph:
* `PdfOperationsManager` is NOT on the Page Editor path. It is reached only by the File-based branch of
  `PdfOperationWorker` (ToolsActivity-style ops) and is provided by `AppModule`. It was migrated anyway
  because the brief requires it to contain no active iText processing.
* `PdfEditorViewModel` / `PageDuplicator` live in `:editor`, are not wired into AppNavigation (dormant) and
  were left unchanged. `PageDuplicator` already has the Phase 2A single-add fix.
* **Pre-existing functional bug fixed:** `PageEditorViewModel` enqueues `PdfOperationWorker` with only
  `KEY_SOURCE_URI`, but `doWork()` required `KEY_INPUT_URIS` + `KEY_OUTPUT_URI`, so every worker-driven
  Page Editor operation returned `Result.failure()` immediately with no message; duplicate/move/extract/
  rotate/crop/resize/mirror had no implementation in the worker at all.
* Pre-existing: `runCatching{}` in the repository and `catch (e: Exception)` in the worker converted
  coroutine cancellation into ordinary failures.

## 2. Engine before/after (every operation was iText-backed before)

| Operation | Before | After |
|---|---|---|
| merge (File + Uri) | iText PdfMerger, `it.path` only | PDFBox PDFMergerUtility, SAF-safe |
| split (ranges / every N / by size / by bookmark) | iText PdfMerger | PDFBox, fresh document per part (importPage) |
| reorder / move / duplicate | iText PdfMerger copy | PDFBox, in-place page-tree rebuild |
| delete / extract | iText removePage / PdfMerger | PDFBox, fresh document (importPage) |
| insert PDF pages / blank / image page / combine images | iText | PDFBox |
| rotate | iText `% 360` (could go negative) | PDFBox, floorMod, multiples of 90 enforced |
| crop | iText cropBox from mediaBox, rotation ignored | PDFBox, margins mapped through page rotation, base = visible box |
| resize / reshape | iText form XObject, rotation ignored; `scaleContent=false` produced BLANK pages | PDFBox content wrap, rotation-aware; `scaleContent=false` now keeps content |
| mirror | iText form XObject, origin assumed 0,0 | PDFBox content wrap, uses real box, rotation-aware |
| watermark | iText, text only, fixed grey, rotation ignored, config.position/pages/color ignored | PDFBox, honours text/image, colour, opacity, rotation, position (incl. TILE), pages |
| page numbers / header-footer / background | iText, unrotated page assumed | PDFBox, display-space placement |
| image on page / images to PDF | iText `File(uri.path)` | PDFBox, ContentResolver, downsampled |

Still iText (NOT Page Editor, intentionally moved verbatim, see section 11):
compress/optimize, encrypt, decrypt/remove-password, saveAnnotations.

## 3. Files

CHANGED (replace at exact path):
* `app/src/main/java/com/propdf/editor/data/repository/PdfOperationsRepositoryImpl.kt` - rewritten; no iText
* `app/src/main/java/com/propdf/editor/data/repository/PdfOperationsManager.kt` - rewritten; no iText
* `app/src/main/java/com/propdf/editor/worker/PdfOperationWorker.kt` - Uri-based Page Editor branch, cancellation rethrow
* `app/src/main/java/com/propdf/editor/ui/tools/page/PageEditorViewModel.kt` - error text via `pageEditorMessage()` only

NEW:
* `app/src/main/java/com/propdf/editor/data/repository/PdfBoxPageEngine.kt` - the PDFBox engine
* `app/src/main/java/com/propdf/editor/data/repository/PageGeometry.kt` - pure geometry + page-order planning
* `app/src/main/java/com/propdf/editor/data/repository/LegacyITextPdfOperations.kt` - remaining iText, moved verbatim
* `app/src/test/java/com/propdf/editor/data/repository/PageGeometryTest.kt` - JVM unit tests
* `app/src/androidTest/java/com/propdf/editor/data/repository/PdfBoxPageEngineInstrumentedTest.kt` - instrumented regression suite

INTENTIONALLY UNCHANGED: security module, redaction, signatures (`PdfSignatureEngine`), converters,
`PdfFormEngine`/forms, `PdfProcessor` + batch workers (Phase 2A), `PageDuplicator`, `PdfEditorViewModel`,
`PdfOperationsRepository` interface (contract preserved), all Gradle files, duplicate processors
(`PdfExportManager`, `PdfToolEngine`, ... not deleted).

## 4. SAF verification (STATICALLY VERIFIED; instrumented tests NOT RUN)
* No `File(uri.path)` / `uri.path` file access remains in the migrated files for sources or images.
* Source resolution: `file://` or scheme-less -> used in place (read only); anything else ->
  ContentResolver -> private cache copy -> PDFBox -> temp sibling -> atomic rename. The cache copy is
  deleted in a `finally` (also on failure and cancellation).
* Images (watermark, background, image page, combine, insert on page) are read with
  `ContentResolver.openInputStream`, so both schemes work; decoded with `inSampleSize` to <= 2400 px.
* Results are always new cache files returned as `file://` Uris; originals are never opened for writing.
* Behaviour change: an unreadable/missing source in a merge now fails the merge instead of being silently
  skipped (old code `filter { it.exists() }` could drop inputs without telling the user).

## 5. Resource / cancellation verification (STATICALLY VERIFIED)
* Every `PDDocument` is opened inside `withDoc`/explicit `try/finally` and closed with `closeQuietly`.
* `publish()` writes to `.<name>.<nanoTime>.tmp`, checks `ensureActive()`, renames only on success, and
  deletes the temp file on any `Throwable` (including `CancellationException`). No partial output is published.
* Splits are all-or-nothing: parts already written are deleted if a later part fails or is cancelled.
* `ensureActive()` is called per page / per part in every loop.
* The repository `io{}` and the manager `op{}` rethrow `CancellationException`; the worker does too.
* Encrypted or signed inputs are rejected before any output file is created.

## 6. Merge / split verification
Audit of every `importPage` / `addPage` site in the repo (grep, STATICALLY VERIFIED):
* `PageDuplicator`, `SplitViewModel`, `BatchOperationsWorker`: single-add (fixed in Phase 2A, comments in place).
* `PdfProcessor` (Phase 2A) uses `PDFMergerUtility` / per-page import with no extra `addPage`.
* `PdfExportManager.kt` lines 92-93 call `importPage` twice for the same page; the class is DORMANT
  (not reachable from navigation) and was NOT changed. Flagged for the dormant-code phase.
* New engine: `importPage` already appends to the document, so it is never followed by `addPage` on a
  *different* document. The in-place paths replace the page tree and add each page object once.
  `checkOutput()` asserts, before every save, that the page count equals the expected count AND that all
  page dictionaries are distinct objects (identity set), so a double-add throws instead of saving.
* Merge additionally asserts `dest.numberOfPages == sum(source pages)`.
* Regression tests written (NOT RUN): merge order/count, split partition exactly-once, duplicate = exactly one
  copy per selected page with independent rotation, move/reorder permutation, delete/extract order.
* Pure partition logic (every split chunking covers each page exactly once) is covered by JVM tests.

Known limitations of the page-assembly strategy:
* Operations that DROP pages (delete, extract, split) build a fresh document so removed content cannot
  linger; interactive form fields and bookmarks are not carried across those operations.
  Reorder/duplicate/insert/rotate/crop/resize/mirror/watermark/etc. edit in place and keep forms/outlines.
* Duplicated pages share annotation objects with the original page (shallow clone).

## 7. Watermark verification
Design (STATICALLY VERIFIED; geometry also verified by a Python mirror of the same formulas):
* All drawing is positioned in display space (origin = bottom-left of the visible CropBox after /Rotate)
  and mapped to user space by `PageGeometry.toUserX/Y`; text/image angle = requested angle + page rotation.
* Uses CropBox (visible area), real MediaBox/CropBox origin (not assumed 0,0), portrait/landscape,
  rotation 0/90/180/270 (including inherited /Rotate and /MediaBox, which are materialised before re-parenting).
* Instrumented tests (NOT RUN) render blank pages of every rotation, a landscape+rotated page, an offset
  MediaBox and a smaller CropBox, then assert the ink centroid is at the page centre (+/-12%).
* Signed PDFs (signature dictionary or DocMDP) are rejected with `SignedPdfException`; the original is
  never touched (tested, NOT RUN).
* Behaviour changes: colour now comes from `WatermarkConfig.color` (old code forced grey), and
  `position`/`pages`/image watermarks are now honoured. Helvetica is WinAnsi: unencodable characters are
  dropped rather than failing the page.

## 8. Image verification
* Scaling: `ImageLayout.fit` reproduces the previous FILL / FIT_WIDTH / FIT_HEIGHT / ORIGINAL / FIT_CENTER
  maths (JVM-tested). Images are sized from their ORIGINAL pixel size even when downsampled for embedding.
* Page dimensions: image pages use `ImageInsertionConfig.pageWidth/Height`; combine-images uses the same;
  `imagesToPdf` (File API) keeps native image size per page.
* Rotation: insert-on-page and header/footer images are drawn upright on rotated pages (display-space matrix).
* Transparency: bitmaps with alpha use `LosslessFactory`, opaque ones `JPEGFactory(0.9)`. That
  `LosslessFactory.createFromImage(doc, Bitmap)` preserves alpha in PDFBox-Android 2.0.27.0 was NOT verified
  on a device; the transparent-PNG test only asserts a valid, renderable page.
* Not claimed: EXIF orientation is not applied; no full object/image editing.

## 9. Tests (all NOT RUN - environment blocked)
JVM (`PageGeometryTest.kt`): rotation normalisation, display<->user mapping for all rotations on an offset box,
axis angles, crop-margin mapping, resize plan (centred, box swap, no-scale), image fit modes, delete/duplicate/
move/extract/insert planning, partition-exactly-once for every/size/bookmark chunking.
Instrumented (`PdfBoxPageEngineInstrumentedTest.kt`, 28 tests): corpus built with PDFBox - single page,
multi-page, portrait, landscape, rotated 90/180/270, mixed sizes, offset MediaBox, CropBox, inherited
MediaBox/Rotate, link annotations, AcroForm, images (opaque + transparent PNG), encrypted, signed (dummy CMS
fixture as in Phase 2A), garbage and empty files. Every page carries a unique text label so order and
exactly-once are asserted from extracted text; every output is opened with Android `PdfRenderer`. Sources are
served through both `file://` and the existing `TestPdfProvider` `content://` provider. Also covers: input
never modified, deleted-page text absent from the file bytes, no temp/cache leftovers after failures, and
cancellation publishing nothing.
Not covered: password-protected PDFs processed WITH a password (unsupported by design, see below);
very large documents / memory pressure on a device; AcroForm survival through delete/extract/split (known gap).

## 10. Build status
BUILD NOT RUN - ENVIRONMENT BLOCKED. Gradle/AGP/Kotlin/PDFBox versions unchanged; no dependency added.
Static checks: brace/paren balance and ASCII-only source on all new/changed files; no `com.itextpdf` import
in `PdfOperationsRepositoryImpl`, `PdfOperationsManager`, `PdfBoxPageEngine`, `PageGeometry`,
`PdfOperationWorker`, `PageEditorViewModel`. Expect possible first-compile surprises; upload the next CI log.
PDFBox calls that were not independently API-verified against the 2.0.27.0 sources here:
`PDPageTree()` + `PDDocumentCatalog.setPages`, `PDDocument.importPage` return type, `PDAnnotation.setPage(null)`,
`PDPageContentStream.drawImage(image, Matrix)`, `PDOutlineNode.firstChild/nextSibling`.

## 11. Remaining iText inventory (Kotlin sources referencing com.itextpdf)
* `app/.../data/repository/LegacyITextPdfOperations.kt` - compress/optimize, encrypt, decrypt, saveAnnotations (moved verbatim; Compress/Security phases)
* `app/.../data/converter/ImageConverter.kt`, `MarkdownConverter.kt`, `TextConverter.kt` - converters (dormant)
* `core/.../signature/PdfSignatureEngine.kt` - signatures (own phase)
* `core/.../util/UserFacingError.kt` - only a class-name string check in the error mapper (no iText API use)
* `security/.../SecurityRepository.kt`, `EncryptDocumentUseCase.kt`, `EncryptionManager.kt`, `RedactionEngine.kt`,
  `EncryptionFragment.kt`, `PermissionsFragment.kt`, `SecurityValidator.kt` - security phase
iText Gradle dependencies are still present (global removal is out of scope).

## 12. Known limitations / behaviour changes
* Password-protected PDFs are refused (clear message) rather than silently decrypted or re-encrypted.
  Previously iText failed on user-password files and could drop owner-password protection silently.
* Compress and Optimize in the Page Editor still go through iText (not in the required list).
* `extractPagesAsImages` previously returned blank white bitmaps (stub); it now renders real pages with
  Android `PdfRenderer` (no callers found).
* Crop margins are relative to the currently visible box (CropBox), so repeated crops compound; before, they
  were always relative to MediaBox.
* `resize` with `scaleContent=false` keeps and centres content (before: blank page).
* Header/footer text is still rendered as a bitmap with Android fonts and black colour (kept as before).
* Split-by-bookmark keeps its existing heuristic (chunk count = top-level bookmark count).
* Only top-level worker operations the Page Editor enqueues are supported on the new worker branch; split,
  compress and optimize are called directly on the repository by their own screens.
* Page-dropping operations lose AcroForm fields and bookmarks (section 6).

## 13. Next phase
Phase 3 per the master prompt: Page Operations follow-ups are done; proceed to compression
(`LegacyITextPdfOperations.compress*` + existing `PdfBoxCompressionRepository`), then encrypt/decrypt.
First action next session: run CI + `connectedAndroidTest` for `PdfBoxPageEngineInstrumentedTest` and fix whatever
the real compiler and device report.
