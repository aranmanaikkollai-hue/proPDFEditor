# PHASE 3b - `:pdfedit` EDITING ENGINE (add text/images, move/delete images)

**BUILD NOT RUN - needs CI.** This is a new module written without a compiler. The first CI run is likely to surface compile errors,
especially at the PDFBox-Android calls listed under "Least certain". Nothing here is wired into the app UI yet.

## Your decisions, as implemented
| Decision | What was built |
|---|---|
| Scope A + B | Operations `AddText`, `AddImage`, `TransformImage`, `DeleteImage`. No in-place text editing. |
| Device + bundled font | `FontChoice.BundledRegular/BundledBold` (Liberation Sans 2.1.5 in `pdfedit/src/main/assets/fonts/`, with its OFL 1.1 licence text) and `FontChoice.DeviceFont` (any TrueType file; falls back to the bundled font with a warning if it cannot be loaded). Fonts are embedded as subsets. |
| Signed documents allowed | `EditWarning.SIGNATURE_WILL_BE_INVALID` (and `CERTIFIED_DOCUMENT` for DocMDP). No UI yet. |
| Canonical coordinates | `PageGeometry`: Page Display Space (points, origin bottom-left of the displayed CropBox, after /Rotate, y up), with the exact display-to-user matrices of the three existing copies (unit-tested), plus UserUnit, bitmap-pixel conversion and image placement. |
| New `:pdfedit` module | Added to `settings.gradle`; Android library, depends only on PDFBox-Android. The app does not depend on it yet (only its instrumented tests do), so APK size is unchanged. |
| Corpus / analyzer | `tools/feasibility/analyze_text_editability.py --summary` and the manual workflow `feasibility-analyzer.yml`, which reads `tools/feasibility/corpus/*.pdf`. Add PDFs there and run it; the report contains font names, counts and flags, not document text. |

## Design in code
* `ops/EditOperation.kt`, `ops/EditLog.kt`: operations are plain data; **undo/redo is a cursor on a log** and the output is always a replay on the untouched base file, so no operation needs an inverse.
* `content/ContentModel.kt`: pure-Kotlin tracker of the current matrix through `q`, `Q` and `cm`, and a rewriter. To move an image it wraps the original `Do` as `q X cm Do Q` with `X = placement x inverse(CTM)`; nothing else in the stream changes. Delete drops the `Do`. Images are addressed by drawing order (`ImageRef`).
* `engine/PdfEditEngine.kt`: `inspect()` (read-only: pages, editable images with bounds in PDS, inline-image and form-XObject counts, signed/certified flags), `materialize()` (replay, save to a new file, then **reopen and verify**: page count, image count per touched page, text-operator count; on failure the output is deleted and `EditVerificationException` is thrown). Encrypted files are refused.
* `engine/EditSession.kt` + `AtomicFilePublisher.kt`: `save()` writes a temp file, verifies, copies next to the target and renames; the target may never be the source file.
* New text and images are appended inside `q <display-to-user matrix> cm ... Q`, so they are drawn in PDS and appear upright on rotated and cropped pages.

## Tests (none run)
* JVM, in `pdfedit/src/test/...`: `Matrix2DTest` (6), `PageGeometryTest` (10: matches the existing four matrices, all rotations, crop offset, UserUnit, hand-computed rotated bounds), `EditLogTest` (5), `ContentRewriterTest` (10: matrix tracking, nested/rotated matrices, singular matrix, delete/place), `TextSanitizerTest` (6). The key numbers were re-derived independently in Python.
* Instrumented, in `app/src/androidTest/java/com/propdfeditor/pdfedit/PdfEditEngineInstrumentedTest.kt` (16 tests): added text is extractable and the original file hash is unchanged; text is **upright at the requested position on all four rotations, checked by rendering with PdfRenderer**; bold/multi-line; unsupported characters replaced and reported; broken device font falls back; added image bounds on every rotation and with a CropBox; existing image moved on every rotation and under a nested matrix; delete one of two images; stale reference skipped; undo/redo; atomic save; original can never be the output; encrypted refused; page out of range refused; invalid geometry skipped.
* Not covered: signed-document warning (needs a signed fixture in the `:app` test assets), inline images, images inside forms, very large images, device-font files.

## Least certain (first things to check in the CI log)
`PDType0Font.load(doc, InputStream, true)`, `JPEGFactory.createFromStream`, `ContentStreamWriter.writeTokens(List)`, `PDDocument.signatureDictionaries`,
`COSDictionary.getFloat`, `InvalidPasswordException`'s package, Kotlin overload choice for `writeTokens`, and whether PdfRenderer reports `/Rotate`-adjusted page sizes (the test asserts it).

## Known limits
* Moving an image makes it upright and axis-aligned (rotation/skew of the original is dropped).
* Images inside form XObjects and inline images are listed by `inspect()` but cannot be edited.
* Text is Latin/Greek/Cyrillic and other scripts the chosen font covers; no shaping, so Arabic/Indic scripts will look wrong. Anything the font cannot draw becomes `?` and is reported.
* A page whose content stream is rewritten is re-written as one Flate stream; other pages are untouched but the whole file is saved again (so a signature is invalidated, as warned).
* The bundled font's licence text was copied from the Debian `fonts-liberation` package; the project-wide notices file belongs to Phase 5. Fonts are binary files: the `.zip` carries them, and the `.patch` is a `git apply` patch made with `--binary`.

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Phase 1c/2b CI run you sent: build and lint gate pass, no `ClickableViewAccessibility` left, unit app 147 / core 25 / viewer 37, instrumented 232 / 62 / 22, 0 failures | `LabelFor` x1 was still reported (that run predates the fix, now `tools:ignore` with the TextInputLayout supplying the label) | Everything in `:pdfedit`: compilation, all 37 JVM tests and 16 device tests, bundled-font loading, rendering checks, the analyzer workflow |

## Next (needs your go)
1. Run CI; send logs so I can fix compile/test failures.
2. Wire the engine into the app (an "Edit" mode in the viewer: add text, add image, select/move/delete image, undo/redo, save as a new file with the signed-document warning). Needs UI decisions (where the tools live; save location behaviour).
3. Move the existing display-to-user copies and the MediaBox-only exporters onto `PageGeometry`.
4. Run the analyzer on your PDFs to size the optional in-place text editing.

## Files
New module `pdfedit/` (build.gradle, consumer-rules.pro, 11 Kotlin files in 4 packages, 6 test files, 2 TTFs + licence); `app/src/androidTest/.../PdfEditEngineInstrumentedTest.kt`; `.github/workflows/feasibility-analyzer.yml`; `tools/feasibility/corpus/README.md`.
Changed: `settings.gradle`, `app/build.gradle` (androidTest dependency), `app/src/main/res/layout/fragment_typed_signature.xml`, `tools/feasibility/analyze_text_editability.py`, `docs/TEXT_EDITING_FEASIBILITY_REPORT.md`. Moves/deletions: none.
