# Milestone 1, Phase 1: Active Compression Screen

Status: implemented; first CI run (commit fc2cfe25138ec9320236c5f44cfcdbc0c672789d, GitHub Actions run_id 43010512697, workflow "Android Tests (manual)") compiled and passed except 4 instrumented tests; this revision fixes those 4 and has NOT been re-run (NOT RUN).

## Source identity
- Base: proPDFEditor-main__1_.zip, sha256 7d99cad14f7cb808a7730e9f05763010fdddac0729a9e356f424bccc2287763b (1347 files, tree fp 7f870d41...4b0e). No .git: branch and commit SHA BLOCKED. Git commit SHA of this change: none (not committed).
- Tree fingerprint after the change: 725b54f58fe24c3b26a1b04648072bc8c33d8c46676aeb315bba369073ed0cf8.
- The current GitHub state was not compared (BLOCKED, see Phase 0 report). If newer commits touch the files below, merge by hand.

## 1. Root cause (VERIFIED)
Route `compression` -> CompressionScreen -> `ui/compression/CompressionViewModel` never called the compression engine. It loaded a PDDocument at pick time, then `doc.save(output)` on the chosen destination. Quality, Remove Images and Flatten Forms were ignored; "Estimated" was `size * 0.7`; nothing was validated; the Done screen's original size was always 0 (read from a state that had already changed to Compressing); encrypted and signed PDFs were not checked; the document stayed open until reset.

## 2. Files inspected
PdfBoxCompressionRepository (all 754 lines), CompressionModels.kt (config, presets), PdfProcessor (compressPdf, assertNotSigned, load, isSigned/rejectIfSigned, saveTo), PageEditorCompression, PdfOperationWorker (compress branch), CompressionScreen, CompressionViewModel, CompressionModule, TestPdfProvider, CorpusAssets, CompressionOutputInstrumentedTest, PdfProcessorSignedAndCompressionTest, UserFacingError.

## 3. Files changed
Modified (4):
- core/src/main/java/com/propdf/core/data/repository/PdfBoxCompressionRepository.kt
- app/src/main/java/com/propdfeditor/batch/util/PdfProcessor.kt
- app/src/main/java/com/propdfeditor/ui/compression/CompressionViewModel.kt (rewritten)
- app/src/main/java/com/propdfeditor/ui/compression/CompressionScreen.kt (rewritten)
New (4):
- core/src/main/java/com/propdf/core/data/repository/CompressedOutputVerifier.kt
- app/src/main/java/com/propdfeditor/ui/compression/CompressionSettings.kt
- app/src/test/java/com/propdfeditor/ui/compression/CompressionSettingsTest.kt
- app/src/androidTest/java/com/propdfeditor/ui/compression/CompressionPhase1InstrumentedTest.kt
Deleted: none. Not touched: navigation (screen signature unchanged), manifest, Gradle, databases, scanner, annotations, viewer, other engines, the legacy Fragment and presentation compression classes.

## 4. Existing engine reuse
The screen now calls `PdfProcessor.compressPdf` -> `CompressionRepository` -> `PdfBoxCompressionRepository` (the same path as the Page Editor and batch). No second engine. Minimal engine changes, all additive or stricter:
1. Shared images: the same image XObject used on several pages is re-encoded once (identity-keyed), not once per page, which previously could multiply the data.
2. Masks: images with a stencil mask, /Mask or /SMaskInData are left untouched. A plain /SMask is preserved: the original soft mask object is re-attached to the replacement image (the image factories otherwise generate their own mask from the bitmap's alpha channel, which would lose real transparency). A first version skipped every image carrying /SMask; CI showed that skipped all fixture images, because Android ARGB bitmaps get an opaque SMask from PDFBox, so no image was ever re-encoded.
3. A re-encoded image replaces the original only if its encoded data is smaller. `imagesProcessed` now counts real replacements only.
4. Output checks: non-empty and page count > 0 and equal; then the published file is read back (file:// and content://) by the new `CompressedOutputVerifier`, which checks it opens as a PDF, page count, and that the byte count equals what was written. `compressedSizeBytes` is now that measured size (previously `statSize`, with 0 on any failure, which would read as 100% saved). Failures surface as a ProcessingFailed error with a user-safe message.
5. PdfProcessor: optional `onProgress` callback on compressPdf (default no-op; existing callers unchanged) and a read-only `inspectForCompression`.
Existing callers (Page Editor, batch) get items 1-4. Behavioural risk for them: stricter validation, and images that previously "processed" but did not shrink are now kept as they were. The existing test `compression_optimizeImagesTrue_...` (imagesProcessed >= 1) should still hold (q75 vs q95 noisy JPEGs) but is NOT RUN.

## 5. Supported options (what is exposed)
| Option | Basis |
|---|---|
| Image quality 10-100 | JPEG re-encode in optimizeImages; existing tests: CompressionOutputInstrumentedTest, PdfProcessorSignedAndCompressionTest |
| Image size (Keep, 2400, 1600, 1024 px longest side) | maxImageDimension downsampling in optimizeSingleImage |
| Remove document metadata | removeMetadata (Info, XMP, PieceInfo) |
Fixed off, same policy as the Page Editor: removeUnusedObjects, optimizeFonts, linearize, compressStreams.

## 6. Unsupported / not exposed
- Remove Images and Flatten Forms: shown disabled ("not available"); no engine support exists.
- DPI: `downsampleDpi` is never read by the engine, so no DPI control is shown. (Preset names such as "Screen (72 DPI)" in QualityPreset are misleading; not changed in this phase.)
- compressStreams: rewrites raw stream bytes without recompressing (no effect). optimizeFonts: its FONT_FILE2 lookup targets the CIDFont dictionary, not the FontDescriptor, so it does nothing but still counts "fonts optimized"; the code path also removes /Filter while keeping filtered bytes, which would corrupt a stream if it ever matched. linearize: sets a flag only. removeUnusedObjects: unverified. All left off, not fixed.
- Images inside form XObjects or annotation appearances are not processed (pre-existing; only page-level image resources are).
- No target-size mode, no estimate, no percentage before the run.

## 7. Behaviour of the screen
Select (OpenDocument) -> read-only inspection (page count, refusals) -> options -> CreateDocument destination -> compress with the engine's own stage progress (not fake; it is stage-based, not per-byte) and a Cancel button -> result with measured original and output sizes and saved bytes/percent. If the output is not smaller the result says so ("not smaller", "keep the original") instead of reporting success. Failure shows a message and "Your original PDF was not changed." A destination created for a failed or cancelled run is deleted (never the input; refused if destination == input).

## 8. SAF behaviour
Input: read through ContentResolver into a cache copy (existing PdfProcessor/repository behaviour). Output: always a new document from CreateDocument; engine writes "wt" for content://, `.part` + rename for file://. No rename is assumed for providers. Read-back verification requires the provider to allow reading the document it just created (CreateDocument grants this); if it refuses, the run fails with an explicit message rather than claiming success. Tested only against TestPdfProvider; a real third-party provider is NOT covered.

## 9. Signed and encrypted PDFs
- Signed or certified (DocMDP): refused before anything is written (PdfProcessor.rejectIfSigned; the engine's full rewrite would invalidate signatures; no preservation guarantee exists).
- Password-protected: refused, including owner-restricted files that open with an empty password. Protection is never bypassed or re-applied. The compression engine has no password parameter, so the existing password architecture (Security tools: decrypt with the owner password) is the route: remove protection first, then compress. Adding in-flow password entry would need an engine and interface change; left for a later, approved phase.

## 10. Tests added
JVM (CompressionSettingsTest, 10 tests): config flags off, mapping, clamping, KEEP means no downsampling, size comparison (smaller/larger/same/zero), byte formatting.
Instrumented (CompressionPhase1InstrumentedTest, 22 tests after the fix): normal compress (sizes equal real files, original sha256 unchanged, no temp leftovers), content->content, 20-page larger PDF, no-image PDF, invalid PDF, empty file, user-password PDF, owner-only encrypted PDF, signed PDF, typed exceptions from inspectForCompression, destination == source, unwritable content destination (SAF failure), destination under a regular file, shared image re-encoded once, soft mask preserved byte-for-byte, colour-key /Mask image untouched, already-small image not replaced by a larger one, and five CompressedOutputVerifier cases.

## 11. Tests executed (CI run on commit fc2cfe25..., source tree sha256 d4a429bc2446f0fca7ea978ecabd1ca3fbeb73d57493f0f315d86e09b174b961, 1354 files; Android 14 emulator only)
| Check | Result |
|---|---|
| Debug APK build and compile of all modules | PASS (new PDFBox API calls compiled) |
| JVM unit tests, all modules | PASS 178/178 (incl. CompressionSettingsTest 10/10, PageEditorCompressionTest 6/6) |
| Instrumented tests | 303 run (app 219, viewer 22, core 62): 296 passed, 4 FAILED, 3 skipped |
| Signing validation (core instrumented) | PASS 18/18 |
| Release lint gate | PASS (BUILD SUCCESSFUL) |
| iText scan of release APK | CLEAN (0 DEX descriptors, 0 entries, 0 in R8 mapping) |
| Room schemas | exported and uploaded; no schema or version change in this phase |
Skipped (not from this phase): InvisibleTextLayer tamil_isExtractableWhenSystemFontExists, UnicodeAndSearchable ocrExport_tamil_extractsSameText (emulator has no Tamil system font), ViewerFlowTest navigateToTools_showsToolGrid.

Failures and causes:
1. PdfProcessorSignedAndCompressionTest.compression_optimizeImagesTrue (existing test, imagesProcessed >= 1): my regression. Cause: the first mask rule skipped every fixture image (opaque SMask). Fixed in the engine (section 4, item 2).
2. normalPdf_producesSmallerVerifiedCopy: outcome SAME, imagesRecompressed 0. Same cause.
3. sharedImage_isReencodedOnce: imagesProcessed 0. Same cause.
4. destinationInMissingDirectory: returned Done. Test error, not a product bug: Kotlin File.copyTo creates missing parent directories, so the copy legitimately succeeded. Replaced by destinationUnderAFile (a regular file as parent), which cannot succeed.
The earlier statement that the existing imagesProcessed test "should still hold" was wrong.

## 11b. After the fix
NOT RUN. Expected to pass: failures 1-3 (masks now preserved instead of skipped) and 4 (test replaced). Two new tests (soft mask byte-for-byte, colour-key mask) have never run.

## 12. Tests not written or not meaningful here
- Genuine low-storage: not simulated (an IOException path is covered by the unwritable-destination tests).
- Cancellation timing and process death: not tested.
- Very large (hundreds of MB) PDFs: not tested; the 20-page test is deliberately modest.
- Real SAF providers (Drive, Downloads UI): not tested; only TestPdfProvider. Real device: none (emulator API 34 only; API 23/29/31/33 not run).
- Compose UI test of the new screen: none.

## 13. Remaining limitations and risks
- Savings depend on the PDF: text-only or already-optimised PDFs may not shrink (reported honestly). The no-image test and the SAME outcome seen in CI confirm the screen reports "same size" rather than inventing a saving.
- Lossy: JPEG re-encoding and downsampling reduce image quality (stated on the screen).
- Colour profile fidelity of re-encoded images was not assessed.
- Soft-masked images are re-encoded with their original mask re-attached; visual equality of the result was not checked by rendering.
- Three cache copies of a large input can exist briefly (inspection, signed-check, engine); all are deleted afterwards.
- One compression at a time (singleton repository guard).
- Verification re-reads the whole output once more (extra I/O).
- Page Editor and batch callers inherit the engine changes; existing tests for them passed in CI except the one fixed above.
