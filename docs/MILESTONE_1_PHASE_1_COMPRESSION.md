# Milestone 1, Phase 1: Active Compression Screen

Status: implemented against the audited archive, NOT BUILT, NOT RUN. Nothing below has been compiled or executed; compile errors are possible (no Kotlin compiler or Android SDK available here).

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
2. Images with a stencil mask, /Mask, /SMask or /SMaskInData are left untouched (re-encoding dropped the mask, making the image opaque).
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
Instrumented (CompressionPhase1InstrumentedTest, 21 tests): normal compress (sizes equal real files, original sha256 unchanged, no temp leftovers), content->content, 20-page larger PDF, no-image PDF, invalid PDF, empty file, user-password PDF, owner-only encrypted PDF, signed PDF, typed exceptions from inspectForCompression, destination == source, unwritable content destination (SAF failure), missing destination directory, shared image re-encoded once, soft-masked image untouched, already-small image not replaced by a larger one, and five CompressedOutputVerifier cases (good, truncated, wrong page count, garbage/byte mismatch, missing).

## 11. Tests executed / passed / failed
None executed. Executed: 0. Passed: 0. Failed: 0. NOT RUN: all 31 new tests, all existing tests, debug build, release build, lint. Only static checks were done: bracket balance of every changed Kotlin file and an ASCII-only check.

## 12. Tests not written or not meaningful here
- Genuine low-storage: not simulated (an IOException path is covered by the unwritable-destination tests).
- Cancellation timing and process death: not tested.
- Very large (hundreds of MB) PDFs: not tested; the 20-page test is deliberately modest for low-end emulators.
- Real SAF providers (Drive, Downloads UI): not tested. Real device: none.
- Compose UI test of the new screen: none.

## 13. Remaining limitations and risks
- Compile risk: unverified PDFBox-Android API usage in new code: `PDDocument.load(InputStream, MemoryUsageSetting)`, `PDStream.cosObject` cast, `PDImageXObject.isStencil`. If any fails to compile, it is a one-line fix.
- Savings depend on the PDF: text-only or already-optimised PDFs may not shrink (reported honestly).
- Lossy: JPEG re-encoding and downsampling reduce image quality (stated on the screen).
- Re-encoded images lose any colour profile detail beyond what PDFBox's JPEGFactory preserves (not assessed).
- Three cache copies of a large input can exist briefly (inspection, signed-check, engine); all are deleted afterwards. Heavy on low-storage devices.
- The singleton repository allows one compression at a time ("Another compression is in progress").
- Verification re-reads the whole output once more (extra I/O).
- Page Editor and batch callers inherit the engine changes; their tests are NOT RUN.
