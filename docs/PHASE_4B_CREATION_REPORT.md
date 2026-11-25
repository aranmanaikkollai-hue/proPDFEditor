# PHASE 4b - IMAGES TO PDF and TEXT TO PDF (engine and independent verification)

**CI status: green (see "Final CI result").** The extended Python verifier and its self-test (32 tests) were also run here.
Not wired into the app UI yet (that is Phase 4c). Phase 4a (PDF to images/text) is closed: CI green, Poppler comparison 18 entries, 0 failures.

## What was built (`:pdfedit`, package `convert`)
* **`ImagesToPdfConverter`**: one PDF page per image, in the given order.
  * Page modes: A4, Letter (landscape for a wide picture; picture scaled to fit inside the margin, centred, never distorted) or MATCH_IMAGE (page = picture at a chosen dpi, no margin).
  * **JPEG is embedded unchanged** (no recompression). Phone photos' **EXIF orientation (1-8) is applied by the drawing matrix**, so no pixels are re-encoded and nothing is rotated in memory.
  * Other formats are decoded and stored losslessly; transparency is kept on a white page; pictures over the pixel limit (25 million) are reduced and reported (`IMAGE_DOWNSAMPLED`).
  * PDFBox uses a temporary-file memory setting, so many large pictures do not have to fit in memory.
* **`TextToPdfConverter`**: UTF-8, UTF-16 (with BOM) or, if neither fits, Windows-1252 (reported as `ENCODING_GUESSED`); tabs become four spaces; lines wrapped at the margin by the **real font width**, split into pages; font embedded as a subset (bundled Liberation Sans regular/bold, or a device font with fallback). Characters the font lacks become `?` and are reported. **No shaping**: Arabic, Indic and other complex scripts, and CJK, are not supported with the bundled font.
* Both: the saved file is reopened and its page count checked before it reaches the sink; on failure or cancellation the output is removed; inputs are only read.
* Pure helpers with JVM tests: `ExifOrientation` (the 8 transforms), `ImagePagePlanner`, `TextLayout` (wrap, long-word split, paginate), `TextDecoder`.

## Rules I chose (tell me if you want them changed)
1. A4/Letter default margin 18 pt for pictures, 54 pt for text; text 11 pt, line spacing 1.25 (never less, so descenders stay inside the margin).
2. A broken image fails the whole job (nothing is written) instead of being skipped silently.
3. Empty or blank text is refused; text files over 20 MB are refused.
4. A square picture gets a portrait page.

## Verification
* **On device (CI, not run), 21 tests** in `PdfCreationInstrumentedTest.kt`: pages in order with planned sizes; MATCH_IMAGE size; **all eight EXIF orientations rendered with PdfRenderer and checked by quadrant colour**; JPEG bytes identical to the original inside the PDF; transparent PNG on white; margin stays white; broken image and empty list fail cleanly; sources unchanged; large PNG reduced; **cancellation removes output and temporary files** (images and text); text round trip by extraction; pagination; **text stays inside the margins (pixel check on every page)**; fonts embedded; UTF-16 and Windows-1252; unsupported characters; blank text; bold and Letter.
* **Against Poppler/qpdf (CI, informational until proven):** the same test also exports 7 PDFs (three image sets: A4, Letter with 36 pt margin, MATCH_IMAGE at 150 dpi with EXIF 3, 5, 6, 8 and an alpha PNG; four text files: Latin A4, Letter bold 14 pt, Windows-1252, Greek/Cyrillic). `tools/conversion/verify_conversion.py` checks each with `qpdf --check`, `pdfinfo` (pages, sizes), `pdfimages` (JPEG stays JPEG), `pdftoppm` (picture equals the upright source, EXIF read independently by Pillow, inside the planned rectangle, white margins), `pdffonts` (embedded subsets), `pdftotext` (text equals the source after whitespace removal) and `pdftotext -bbox` (every word inside the margins).
* **Verifier tested here:** 11 new self-tests (32 in total, all pass): faithful PDFs pass; ignored orientation, wrong page size, ignored margin, truncated PDF, wrong page count, text running past the margin, missing text, non-embedded font and wrong reported page count each fail. Two real bugs in the checker were found and fixed this way (Poppler labels JPEG streams "jpeg", not "dct").
* JVM: 31 new tests (ExifOrientation 4, ImagePagePlanner 9, TextLayout 12, TextDecoder 6), not run. The EXIF table was also re-derived independently from the EXIF definition in Python.

## Known limits
* The thresholds (picture difference 14 of 255, margins brightness 250) are my estimates.
* Pictures with CMYK or unusual JPEG variants fall back to decoding (and lose pass-through).
* EXIF orientation is read for JPEG only; HEIC/WebP/PNG orientation is not applied.
* Text uses one font and one size per file; no bold/italic runs, no line numbers, no right-to-left.
* PDFBox calls not yet seen compiling: `PDPageContentStream.drawImage(image, Matrix)`, `PDDocument(MemoryUsageSetting)`, `PDDocumentInformation`, `COSStream.createRawInputStream`.

## Final CI result (commit fea254e, after adding the missing TextSanitizer import)
* Compiled. Unit tests: pdfedit 97 (66 + 31 new), app 147, core 25, viewer 37 - 0 failures. Instrumented: app 291 (21 new creation tests, all passed; 3 skips as before), core 62, viewer 22 - 0 failures.
* Poppler/qpdf comparison: conversion 18 entries and creation 7 entries (3 image sets, 4 text files) - **0 failures**. Release lint gate: pass, 0 errors, 826 warnings.
* The first CI run failed only on the missing import (`TextSanitizer`). Phase 4b is closed; the CI verification step is now gating (a wrong conversion fails the build).

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Phase 4a on CI; the extended verifier (32 self-tests, run here); the EXIF and wrapping maths cross-checked in Python | Nothing known | All new Kotlin (compilation, 31 JVM tests, 21 device tests), the Poppler/qpdf comparison of the 7 exported PDFs on CI |

## Files
New: `pdfedit/src/main/java/com/propdf/pdfedit/convert/{ExifOrientation,ImagePagePlanner,TextLayout,TextDecoder,PdfPublisher,ImagesToPdfConverter,TextToPdfConverter}.kt`;
`pdfedit/src/test/java/com/propdf/pdfedit/{ExifOrientationTest,ImagePagePlannerTest,TextLayoutTest,TextDecoderTest}.kt`;
`app/src/androidTest/java/com/propdfeditor/pdfedit/PdfCreationInstrumentedTest.kt`; this report.
Changed: `ConvertModels.kt` (4 new warnings), `tools/conversion/verify_conversion.py` and `test_verify_conversion.py`, `.github/workflows/android-tests.yml` (the verification step handles both folders), `docs/PHASE_4A_CONVERSIONS_REPORT.md`, `docs/PDFEDIT_FILE_MANIFEST.txt`. Moves/deletions: none.

## Next
4c: the Convert screen in the Tools Hub for all four conversions (pick file(s), options, output folder, progress, cancel, clear error messages, signed/encrypted notices), then Phase 5 (compare PDFs, licence).
