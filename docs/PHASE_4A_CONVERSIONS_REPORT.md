# PHASE 4a - PDF TO IMAGES and PDF TO TEXT (engine and independent verification)

**BUILD NOT RUN - needs CI** for all Kotlin in this phase. The Python verifier and its self-test were run here (results below).
Not wired into the app UI yet. Phase 4b (images and text to PDF) and 4c (the Convert screen) follow after you review this.

## Audit
* The existing converters (`app/.../data/converter/PdfConverter.kt`, `ImageConverter`, `HtmlConverter`, `ConversionRepository`,
  `ConversionFragment`, `ConversionWorker`) are **not reachable** from `AppNavigation` or the Tools Hub, so no user can run them today.
* `PdfConverter.toImages` has problems that matter for a premium converter: no white background (transparent pages), a fixed 2x scale
  with no memory limit (large pages can run out of memory), no renderer mutex, no password or permission handling, partial output
  left behind on failure, no page selection, and no independent verification.
* The live place for a new tool is the Tools Hub (`ToolsHubScreen` + a route in `AppNavigation`), following the Split/Merge screens.
  That wiring is Phase 4c.
* A regression corpus and an independent Poppler/qpdf check already exist (`tools/corpus`, CI job `corpus-independent-check`);
  this phase reuses that corpus as test input.

## What was built (in the `:pdfedit` module, package `convert`)
* **`PdfToImagesConverter`**: PNG or JPEG per page with the platform `PdfRenderer`; every renderer call under a `Mutex`; opaque white
  background; resolution 36-600 dpi, automatically lowered when a page would exceed a pixel limit (default 25 million) and reported as
  `RESOLUTION_REDUCED`; page ranges ("1-3,5,8-"); progress; password-protected files are unlocked into a temporary copy that is always
  deleted; on any failure or cancellation every file already written is deleted through the sink; the source file is only read.
* **`PdfToTextConverter`**: PDFBox text extraction into one UTF-8 file (no BOM, `\n` line ends), page separator form feed
  (pdftotext convention), header line or none, optional position sorting, page ranges, passwords. Scanned pages give empty text and a
  `NO_TEXT_LAYER` / `SOME_PAGES_WITHOUT_TEXT` warning (no OCR here). Files whose permissions forbid extraction are refused (`NOT_PERMITTED`).
* Pure helpers with JVM tests: `PageRanges`, `RenderSizing`, `OutputNaming` (zero-padded `name_page_001.png`, safe names, no path traversal).
* `OutputSink`: where files go. Tests use a directory; the app will adapt it to a Storage Access Framework folder in 4c.

## Decisions I took (tell me if you want them changed)
1. **Permissions:** text extraction is refused when the PDF's permission flags forbid it (the owner password is needed). Image conversion is
   treated like viewing and is allowed. This respects the file's restrictions without blocking normal reading.
2. **No OCR in this step.** The repo has OCR elsewhere; wiring it in as "scanned PDF to text" is a possible later addition.
3. **Defaults:** PNG, 150 dpi, form-feed separators, no position sorting.

## Verification
* **On device (CI, not run):** 22 instrumented tests in `app/.../PdfConversionInstrumentedTest.kt` on the corpus: names and order, page ranges,
  opaque white background, JPEG magic bytes, rotated pages come out as displayed, pixel limit, huge image page, source file unchanged,
  invalid ranges, **cancellation removes written files**, failing write removes written files, encrypted (required / wrong / right password,
  no temp copy left), owner-restricted (text refused, images allowed), five kinds of broken file, text markers and form feeds, header lines,
  Tamil/Arabic text, scanned document, text page ranges.
* **Against Poppler (CI, informational until proven):** `exportVerificationSet()` converts 8 image sets and 10 text sets and writes them with a
  `manifest.json` to the emulator's additional test output. `tools/conversion/verify_conversion.py` then compares with `pdftoppm` and
  `pdftotext`: file type, decodability, no transparency, size equal to Poppler's, picture difference after reducing, ink bounding box
  (catches rotation, crop and page-order mistakes), page separators, per-page text similarity, corpus markers, empty text for scans, and no stray files.
  The new CI step is `continue-on-error` because I could not confirm that the emulator collects the folder; once a run shows it works, remove that line.
* **Verifier tested here:** `tools/conversion/test_verify_conversion.py` (18 tests, also run in CI job `corpus-independent-check`): faithful output passes;
  rotated, mirrored, blank, transparent, wrong-type, wrong-size, over-limit, leftover-file, missing-text, missing-marker, wrong-separator, wrong-header,
  BOM and invented-scan-text output each fail. A smoke run on the real 21-file corpus with Poppler-made stand-in output gave 15 entries, 0 failures
  (the CJK file has no Poppler text here because poppler-data is missing; that case is a note, not a failure).
* JVM: 29 new tests (PageRanges 13, RenderSizing 7, OutputNaming 9), not run.

## Known limits
* The thresholds (mean difference 12 of 255 on a 200-pixel-wide copy, ink box within 3%) are my estimate for PdfRenderer versus Poppler; the first CI run may need tuning.
* CJK text in non-embedded fonts depends on PDFBox's bundled CMaps; the on-device test does not assert it, the Poppler check only reports it.
* Right-to-left text order from PDFBox is not guaranteed; checks compare characters as a set for Arabic.
* `PdfRenderer` rounds page sizes to whole points, so image sizes can differ from Poppler's by 1 pixel (tolerated up to 2 pixels or 1.5%).
* Not yet covered: annotations and form fields rendering, very large page counts at high dpi on low-memory devices.

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Phase 3b engine on CI; the verifier and its 18-test self-test (run here); verifier smoke run on the real corpus (15 entries, 0 failures) | Nothing known | All new Kotlin (compilation, 29 JVM tests, 22 device tests), the CI collection of emulator output, real PdfRenderer-versus-Poppler differences |

## Files
New: `pdfedit/src/main/java/com/propdf/pdfedit/convert/{ConvertModels,PageRanges,RenderSize,OutputNaming,PdfUnlock,PdfToImagesConverter,PdfToTextConverter}.kt`;
`pdfedit/src/test/java/com/propdf/pdfedit/{PageRangesTest,RenderSizingTest,OutputNamingTest}.kt`;
`app/src/androidTest/java/com/propdfeditor/pdfedit/PdfConversionInstrumentedTest.kt`; `tools/conversion/{verify_conversion,test_verify_conversion}.py`;
this report. Changed: `.github/workflows/android-tests.yml` (two steps and one artifact path), `docs/PDFEDIT_FILE_MANIFEST.txt`. Moves/deletions: none.

## Next
4b: images to PDF and text to PDF (PDFBox, bundled font, EXIF orientation, page-size options), verified with `qpdf --check`, `pdfinfo`, `pdftoppm` and `pdftotext`.
4c: the Convert screen in the Tools Hub (file pick, format and range options, folder output, progress, cancel, error messages).
