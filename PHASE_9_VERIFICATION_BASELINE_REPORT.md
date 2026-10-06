# PHASE 9 - VERIFICATION BASELINE AND P0 DEFECTS

Scope: stabilisation/verification gate. No feature work, no new dependencies, no AGP/Gradle/Kotlin changes, no iText removal, no database changes.

## Headline
**BUILD NOT RUN - ENVIRONMENT BLOCKED. No Android test was run, so Phase 9 is NOT PASSED.** There is no Android SDK, Gradle distribution or network in the authoring environment (Gradle services returned HTTP 403 in Phase 7). Everything labelled "written" below is unexecuted. The only things actually executed in this phase are the Python corpus generator and the independent corpus verifier (21/21 files pass; section 2).

Acceptance gate per your list: items 2 (corpus) done and independently verified; items 4, 5, 6, 7, 8, 9 implemented/written but unverified; items 1 (green build) and 3 (run existing tests) **not achievable here**; item 10 (judge pass): **not passed**.

## 1. Build/CI environment (item 1)
- Existing `android.yml` builds a debug APK on JDK 17; no test job existed. Log from the first turn showed the pipeline was failing on compile errors then.
- Added `.github/workflows/android-tests.yml` (manual `workflow_dispatch` only; does not affect the normal build): JVM unit tests (`testDebugUnitTest --continue`), instrumented tests on an API 34 x86_64 emulator (`connectedDebugAndroidTest --continue`, via `reactivecircus/android-emulator-runner@v2`, a third-party GitHub Action: **[LEGAL] licence/trust review of the action if policy requires**), report upload, and a corpus job that needs no Android (regenerates and re-verifies the corpus with poppler/qpdf/pdfium).
- **UNTESTED**: never run. No Gradle, AGP, Kotlin or dependency version was changed. YAML parses.

## 2. Regression corpus (item 2) - EXECUTED
`tools/corpus/make_corpus.py` generates everything from scratch (no third-party documents). `tools/corpus/verify_corpus.py` checks it with tools independent of PDFBox/iText. Result: **21/21 files pass**. Corpus copied to `app/src/androidTest/assets/corpus/` (6.6 MB, dominated by the 6 MB large-image PDF) with `MANIFEST.json` (sha256, expected pages/text/fields).

| File | Covers | Independent check that passed |
|---|---|---|
| 01_normal | 3 pages, keywords | qpdf clean, pdfium render, pdftotext keywords |
| 02_large_300pages | 300 pages | page count, first/last keywords |
| 03_scanned_2pages | image-only, no text layer | pdftotext returns empty; renders |
| 04_encrypted_aes256 / aes128 / owner_restricted_no_user_pw | encryption (test-only passwords `user-pass-123` / `owner-pass-456`) | qpdf encrypted, opens only with password, text after unlock |
| 05_form | AcroForm: text, checkbox, combo, multiline | field names via pikepdf |
| 06_annotated | Link, Highlight, Text, FreeText on page 1 | annotation counts |
| 07_signed_valid | Phase 5 fixture (CMS detached) | pdfsig "Signature is Valid" |
| 08_rotated_pages | /Rotate 0/90/180/270 | rotations read back |
| 09_mixed_sizes | A4, Letter, A5, Legal, 300x900; page 2 CropBox offset | media/crop boxes |
| 10_unicode_tamil, 11_unicode_arabic, 12_unicode_cjk | Unicode extraction | pdftotext (CJK via pdfium: poppler here lacks CJK packs) |
| 13_embedded_fonts_mixed | embedded vs non-embedded fonts | pdffonts counts |
| 14_large_image | 4000x5000 px JPEG (80 MB decoded as RGBA) | image dimensions, renders |
| 15_malformed x5 | truncated, zero-byte, garbage, bad startxref, not-a-PDF | qpdf reports problem; pdfium refuses where expected |

Corpus limits: Tamil/Arabic are **not shaped** (no shaping engine): they test extraction/search/round-trip only, not visual correctness. CJK uses non-embedded Adobe CID fonts (embedded-CJK case is not covered; ReportLab cannot embed CFF fonts). Fonts: FreeSans (GNU FreeFont, GPLv3 + font exception) and DejaVu Sans are embedded as subsets: **[LEGAL] confirm if the corpus is published**. Not covered: PDF 2.0, XFA, portfolios/attachments, linearized, PDF/A, JavaScript, huge xref streams.

## 3. Existing tests run (item 3)
**None. Not run.** Counts of `@Test` and suspected problems are in the premium audit. New finding while preparing this: `TestPdfProvider` (declared in the androidTest manifest) resolves files against its own context's cache dir, while tests write to the target app's cache dir; the provider was changed to prefer the target context (same process) so content:// tests use the directory the tests write to. Unverified; any existing content:// test could fail on first run for this reason.

## 4. Annotation flatten (item 4, P0) - fixed, unverified
Defects found in `PdfAnnotationExporter` and fixed:
1. **Data loss:** `flattenAnnotations` called `page.annotations.clear()`, deleting the page's existing links, form widgets and signature widgets. Removed; originals are preserved and the post-write check compares annotation counts and form-field counts before/after.
2. Signed documents were rewritten (invalidating the signature); encrypted ones were not handled. Now refused with typed errors (`SignedDocument`, `EncryptedDocument`).
3. Swallowed failures (`printStackTrace`, `false`). Now `Result<Unit>` with typed `FlattenException` (messages written for the user); `AnnotationViewModel` and `AnnotationWorker` updated to surface them; cancellation is rethrown.
4. Non-atomic output. Now `<name>.part` -> reopen/verify -> rename; failure deletes the partial and leaves any previous output untouched; the overlay marks are cleared **only after** a successful publish (previously also on partial runs).
5. **`burnAnnotationsIntoPdf` was broken for multi-page documents:** it added a new page and removed the original page inside a loop over a fixed range, so pages were skipped, burned twice or left unburned. Rebuilt: a new document is created page by page (one bitmap at a time, recycled; max 5000 px per side), input is read-only, output atomic, page count verified.
6. Text annotations with characters the built-in PDF fonts cannot encode (Tamil/Arabic/CJK) now fail with `UnsupportedCharacters` instead of throwing from deep inside PDFBox or being dropped; burn (image) export remains the route for them.

Not changed (documented): overlay coordinates still use the MediaBox only (no /Rotate, no CropBox origin); to be solved with the canonical coordinate system later. Tests: `AnnotationFlattenInstrumentedTest` (11 cases on the corpus: links/notes/free text/form fields kept, input hash unchanged, signed/encrypted/malformed refused, no `.part`, pending marks kept on failure, previous output survives, burn page count/images, burn refusals). **Not run.**

## 5. Viewer password handling (item 5) - implemented, unverified
- Old behaviour: `PdfRenderer` throws `SecurityException` for password-protected PDFs, which the viewer reported as "File access has expired".
- New: on `SecurityException` the viewer asks PDFBox to classify the file: user-password required -> new `ViewerState.PasswordRequired` + `PasswordPrompt` dialog (both viewer screens); unsupported security scheme -> clear error; otherwise the old access-error path is unchanged.
- The password only decrypts a **private working copy** in `cacheDir/viewer_unlocked` (PdfRenderer cannot take a password); the original is never modified; a wrong password returns to the prompt with an error; the password is a `CharArray` wiped after use, never logged, not saved in instance state. The decrypted copy is deleted when the document closes, on opening another document, and in `onCleared` (done synchronously: `viewModelScope` is cancelled there, so the old async close in `onCleared` may never have run).
- Risk to review: a decrypted copy exists in app cache while the document is open (and persists if the process is killed until the next open purges it); `file_paths.xml` exposes the whole cache through FileProvider.
- Owner-restricted PDFs with an empty user password open directly (no prompt), as before.
- Tests: `ViewerPasswordPremisesInstrumentedTest` verifies the premises on a real `PdfRenderer` (SecurityException for encrypted corpus files, PDFBox InvalidPasswordException, correct/wrong password, owner-restricted). The ViewModel/UI flow itself has **no automated test**. Not run.

## 6. Secure delete / SAF (item 6) - fixed, unverified
- `SecurityRepository.secureDelete` used `File(fileUri.path)` with no scheme check. Now `resolveOverwritableFile`: only `file://`, canonicalised (resolves `..` and symlinks), and only inside the app's own `filesDir`, `cacheDir`, external files/cache dirs; anything else, including every `content://` URI, is rejected with an explanatory message and the file is untouched.
- Wording: "Secure Delete" became "Overwrite and Delete"; the warning now says flash storage may keep older copies and the result is not guaranteed; success text "File overwritten and deleted".
- Consequence: files picked through the system picker cannot be overwritten (they were already failing silently). A documented limit, not a hidden one.

## 7. Redaction tests (item 7) - moved, not run
`PdfBoxRedactionInstrumentedTest` moved from `security/src/test` (JVM, would not run) to `app/src/androidTest/java/com/propdf/security/redaction/` (same package). Not run.

## 8. Unicode / searchable PDF (item 8) - tests written, one defect unresolved
- `UnicodeAndSearchableInstrumentedTest`: OCR text export and corpus Unicode PDFs are re-opened and their text **extracted** (NFC/NFKC compare; Arabic by letter set because order is not guaranteed); scripts the device lacks a font for are skipped via `Assume` (a skip = unverified on that device); rendering is checked non-blank with `PdfRenderer`; flatten with Tamil text must fail loudly; watermark with Tamil must either contain the text or fail with no output; scanner `SearchablePdfGenerator` output must yield OCR text when extracted.
- **Suspected defect (not fixed, not verified):** `SearchablePdfGenerator` and `OcrRepositoryImpl.exportToPdf` draw OCR text with an alpha-0 / transparent paint on Android `PdfDocument`. Skia's PDF backend may omit fully transparent draws, in which case the "searchable" PDF has **no text layer**. I cannot determine this without a device; the test above will show it. If it fails, the fix options (near-invisible alpha, or PDFBox text with render mode "invisible" plus a Unicode font) need an approval because the second requires bundling a font.
- Known gap (unchanged): no Unicode font is bundled, so PDFBox-written text (watermarks, page numbers, annotation text) is Latin-only; the watermark test records behaviour.

## 9. SAF matrix (item 9) - written, not run
`SafMatrixInstrumentedTest`: removeMetadata across {file, content} x {file, content} (pages, annotation dictionaries and text preserved after reopen), sanitize over content://, secure delete rejections (content://, outside app storage, `..` traversal) and success inside cache, malformed/encrypted inputs over content:// fail cleanly with an untouched destination. `TestPdfProvider` stands in for an external provider. **Real external providers (Drive, SD card, Downloads picker) are not covered.** Page ops, forms, compress, flatten and batch are not in this matrix yet.

## 10. Known Working / Known Failing / Untested matrix
Columns: Wired = reachable from the app's navigation (static); Build = Gradle build; Runtime = instrumented/unit test executed on device; Corpus = corpus-based test executed. "Written" = test code exists. Nothing in Build/Runtime/Corpus has been executed by anyone in this phase.

| Feature | Wired | Build | Runtime | Corpus | Status |
|---|---|---|---|---|---|
| PDF viewing / rendering | yes | not run | not run | not run | **Untested** |
| Viewer password prompt | yes (new) | not run | premises test written | not run | **Unverified (new code)** |
| Search | yes | not run | not run | not run | **Untested** |
| Forms (fill/save/flatten/create) | yes | not run | none written | corpus file exists | **Untested (no tests)** |
| Annotation flatten | yes | not run | written | written | **P0 defect fixed in code; Unverified** |
| Annotation burn | yes | not run | written | written | **Defect fixed in code; Unverified** |
| Redaction | yes (wired Phase 7) | not run | written (moved) | not run | **Unverified** |
| Encryption / decryption | yes | not run | written | not run | **Unverified** (test still builds fixtures with iText) |
| Metadata / sanitize | yes | not run | written | written | **Unverified** |
| Secure delete | yes (legacy UI) | not run | written | n/a | **Fixed in code; Unverified; wording corrected** |
| Digital signing / verification | yes | not run | written (Phase 5) | fixture verified externally | **Unverified; still iText** |
| OCR (ML Kit) | yes | not run | none | not run | **Untested** |
| OCR text export / searchable PDF | yes | not run | written | written | **Suspected defect (alpha-0 text); Unverified** |
| Scanner | yes | not run | none | not run | **Untested** |
| Compression | yes | not run | written | not run | **Untested; legacy iText path in editor** |
| Page operations | yes | not run | written (Phase 2) | not run | **Unverified** |
| Batch operations | **no UI reachable** | not run | written | not run | **Not reachable** |
| Tamil / Arabic / CJK writing via PDFBox | partial | not run | written | corpus files verified externally | **Gap: Latin-only fonts; now fails loudly in flatten** |
| SAF (file + content) | yes | not run | written | not run | **Unverified; external providers untested** |
| Existing-PDF text/image editing | no | - | - | - | **Not implemented** |
| Converters (text/markdown/images) | **no UI reachable** | - | - | - | **Not reachable** |

## 11. Files changed in Phase 9
Production: `annotations/.../export/PdfAnnotationExporter.kt`, `FlattenException.kt` (new), `ui/AnnotationViewModel.kt`, `worker/AnnotationWorker.kt`; `viewer/.../ui/PDFViewerViewModel.kt`, `PasswordPrompt.kt` (new), `IntegratedPDFViewerScreen.kt`, `PDFViewerScreen.kt`; `security/.../SecurityRepository.kt`, `security/src/main/res/values/strings.xml`.
Tests/tools/CI: `app/src/androidTest/assets/corpus/**` (new), `app/src/androidTest/java/com/propdf/{corpus,annotations,viewer,unicode,saf,security/redaction}/*`, `TestPdfProvider.kt`, `tools/corpus/{make_corpus,verify_corpus}.py`, `.github/workflows/android-tests.yml`.
Removed: `security/src/test/.../PdfBoxRedactionInstrumentedTest.kt` (moved).

## 12. Risks and open items
1. Nothing compiled. Likely first-build issues: new `Result` return types in annotations callers, `PDDocument.isAllSecurityToBeRemoved` property syntax, `Paint.hasGlyph` (API 23+), mockk `OcrRecordDao` constructor shape in `OcrRepositoryImpl(...)`, `HighlightAnnotation`/`TextAnnotation` constructor parameter names in the tests (guessed from the model files), `SecurityRepository` constructor order in tests.
2. Unicode searchable PDF defect (section 8) may be real.
3. Flatten placement ignores page rotation/CropBox.
4. Decrypted viewer copy in cache (section 5).
5. iText still shipped (Phase 7/10 scope, untouched here).
6. 17 databases and dormant-module surface untouched by design.

## 13. Recommended next step
Run `android-tests.yml` once (or provide a build log), triage honestly into Known Passing / Known Failing, fix compile errors and any test expectations that were guessed wrong, then re-judge Phase 9. Phase 10 should not start before that result.

STOP. No further phase will start without your approval.
