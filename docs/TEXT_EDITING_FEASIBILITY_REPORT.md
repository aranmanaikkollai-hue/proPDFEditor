# TEXT AND IMAGE EDITING - FEASIBILITY REPORT (Phase 3, study only)

**BUILD NOT RUN - needs CI.** No app code was changed or built in this phase. The only new files are this report and a
read-only analysis script (`tools/feasibility/analyze_text_editability.py`).

**What the evidence is, and is not.**
* Repo facts below come from reading your source (file names given).
* PDF-structure facts come from experiments I ran here with qpdf 11.9, pikepdf, Poppler (`pdffonts`, `pdftotext`) and
  fontTools on **synthetic** PDFs (reportlab and LibreOffice output, plus your 5 signature fixtures). They are not a
  representative sample of real-world PDFs.
* PDFBox-Android 2.0.27.0 itself was **not run**. Statements about its API come from my knowledge of PDFBox 2.0.x and are
  marked "(unverified here)". Nothing in this report claims that a PDFBox call works on a device.

---------------------------------------------------------------------------------------------------

## 1. Verdict

| Capability | Feasible with PDFBox-Android alone? | Confidence | Main risk |
|---|---|---|---|
| **A. Add new text** (new lines, new font) as real content | Yes | High: you already write real text with PDFBox (`InvisibleTextLayer`, `WatermarkEngine`) | Fonts: only system fonts, no bundled font; no complex-script shaping |
| **B. Add / move / resize / delete an image** that the editor placed | Yes | High | Memory on large images |
| **C. Move / resize / delete an image already in the PDF** | Yes for images drawn directly on the page; partial otherwise | Medium | Images inside form XObjects, inline images, shared XObjects, clipping |
| **D. Edit existing text in place** (same font, glyphs available, one run) | Only for a subset of PDFs, found by an analyzer | Medium-low | Embedded subset fonts lack most letters (see 3.2); text is split into fragments |
| **E. Change text freely / reflow paragraphs** | **No**, not in a trustworthy way | High that it is not | No paragraph model in PDF; justification and kerning are baked into numbers |
| **F. "White box + new text on top"** | Excluded by your rule | - | Leaves old text selectable and searchable |
| **G. Delete existing text** | Mechanically possible, **not a security feature** | Medium | Verification; your redaction engine rasterizes for this reason |

Recommendation: build **A + B + C-for-simple-cases** first, with **D** behind an analyzer gate, and do not promise **E**.
Section 6 asks for your decision.

---------------------------------------------------------------------------------------------------

## 2. What the repo already has

* PDFBox is used in about 30 files. There is **no text-editing engine**: the editor module (`:editor`) only handles
  forms, page duplication and safe document replacement (`SafeDocumentReplacer.kt`).
* **Content-stream parsing already exists once**: `PdfBoxRedactionEngine.pruneUnusedXObjects` uses `PDFStreamParser`
  to find drawn XObjects.
* **Your redaction design decision is the best precedent.** `PdfBoxRedactionEngine` KDoc: removing glyphs, images and
  vectors from a content stream "cannot be verified without running real PDFs through every font type, and a failed removal is a silent leak",
  so it renders the page and rebuilds it from pixels. Anything we build for editing existing text inherits the same verification problem.
* **Text positions**: `PdfSearchEngine.kt` uses `TextPosition` (`yDirAdj`, `heightDir`). Good for finding text runs.
* **Undo/redo** exists only for annotations: `AnnotationManager.kt` keeps snapshot stacks of `List<Annotation>`.
  Page-level tools say "cannot be undone".
* **Coordinate spaces are already mixed** (see section 5): this is the biggest hidden cost.

---------------------------------------------------------------------------------------------------

## 3. Evidence from experiments

### 3.1 How real text is stored
LibreOffice-generated page (61 words, 8 lines, 4 fonts):

| Fact | Result |
|---|---|
| Words / lines / word boxes (`pdftotext`) | 61 / 8 / 61 |
| Text-showing operators in the content stream | **15** (13 `Tj`, 2 `TJ`) in **15** `BT` blocks |
| Bytes inside a `Tj` | `\x01\x02\x03\x04...`: **font-specific codes, not Unicode** |
| Justified lines | Encoded as `TJ` arrays with per-gap numbers (about -104 and -49 thousandths of an em) |
| Operators for char/word spacing (`Tc`/`Tw`) | 0 in this file (justification is in `TJ`), but other producers use them |

Meaning: there is no "paragraph" or "word" object. A run is a handful of bytes that only the font's own encoding can
decode, and line widths are fixed by numbers computed when the file was made.

### 3.2 Font coverage: the decisive obstacle for editing existing text
`analyze_text_editability.py` on the same page (letters and digits, 62 possible, that the **embedded subset** can still draw):

| Font | Type | Embedded | Subset | ToUnicode | A-Za-z0-9 available |
|---|---|---|---|---|---|
| Inter-Bold | Type 1 | yes | yes | yes | **10 / 62** |
| LiberationSerif | TrueType | yes | yes | yes | **36 / 62** |
| LiberationSerif-Bold | TrueType | yes | yes | yes | **4 / 62** |
| LiberationSerif-Italic | TrueType | yes | yes | yes | **5 / 62** |

Typing a replacement word with the Serif subset: "council", "budget", "proposal" are drawable by luck; "Dr. Zhang" is not
(missing D, Z) and "quarter" is not (missing q). In bold or italic almost any new word fails.
Conclusion: in-place editing of text in subset fonts needs either **adding glyphs to the embedded font** (PDFBox 2.0.x cannot
modify an embedded font program) or **swapping the whole run to another font**, which changes how the document looks.

Counter-example: reportlab's embedded DejaVu subset covered 62/62, and un-embedded Helvetica (base-14) has all glyphs.
Whether a given PDF is editable depends entirely on the producer. I could not measure the real-world split here.

### 3.3 Edits that do work
* Same-length replacement in a base-14 Helvetica `Tj` string (`1,234.50 EUR` to `9,999.99 USD`) with pikepdf: `pdftotext`
  returned the new text and `qpdf --check` found no errors. Evidence that stream rewriting is mechanically simple when the font has the glyphs.
* This does **not** show that the visual result is right (width, overlap), and it was not done with PDFBox.

### 3.4 Other obstacles seen or known
* Text inside form XObjects (the analyzer counts these) needs the XObject stream rewritten, and the XObject may be shared by other pages.
* Signed files: `valid_signed.pdf` has a signature field; any rewrite breaks the signature unless done as an incremental update (and then
  a certified-document policy may still forbid it). Encrypted files need the password.
* Tagged PDFs (`sample.pdf` is tagged) carry a structure tree that would no longer match edited content.
* Type 3 fonts, text render modes other than 0, clipping and text inside patterns are not safely editable.
* Right-to-left and complex scripts need shaping that PDFBox does not do (also stated in your `InvisibleTextLayer` KDoc).

---------------------------------------------------------------------------------------------------

## 4. PDFBox-Android 2.0.27.0: what it offers for this (unverified here)

* Reading runs: `PDFTextStripper` with `TextPosition` (unicode, font, size, text matrix, width) - you already use it.
* Rewriting: `PDFStreamParser` to tokens, modify, `ContentStreamWriter` to write back; `PDPageContentStream` in APPEND/PREPEND mode.
* Fonts: `PDType0Font.load(doc, file or stream, embedSubset)`, `PDFont.encode`, `getStringWidth`; standard 14 fonts via `PDType1Font`.
  No font-program editing; no bundled fonts in your app; `.ttc` collections are not supported (your own `InvisibleTextLayer` notes this).
* Images: `PDImageXObject`, `JPEGFactory`, `LosslessFactory`.
* Saving: full save; `saveIncremental` exists in 2.0.x but has restrictions (unverified).
* Rendering to verify: the app displays with the platform `PdfRenderer`; verification of an edit should render with the same engine, under the existing renderer mutex.
* PDFBox-Android needs `PDFBoxResourceLoader.init` (your `PDFBoxInitializer`).
* Licensing: PDFBox is Apache 2.0 (consistent with your "no iText / AGPL" rule). System fonts used at runtime are the device's own.

---------------------------------------------------------------------------------------------------

## 5. One canonical coordinate system

### 5.1 What exists today (from source)
| Where | Space |
|---|---|
| `PdfBoxRedactionEngine` (`RedactionArea` KDoc) | Display space: points, origin bottom-left of the **displayed** page (after `/Rotate`, inside the CropBox) |
| `PdfAnnotationExporter` (the display-to-user matrix path, `AnnotationPageGeometry`) | Same display space, converted once per page to user space |
| `PdfAnnotationExporter` (older code around lines 295-336) | `pageHeight - rect.bottom` using the **MediaBox** height: ignores CropBox origin and rotation |
| `AnnotationFlattener` | Sizes pages from the **MediaBox** only |
| `PdfSearchEngine` | PDFBox `TextPosition.yDirAdj`: origin top, in text direction |
| `PdfFormEngine` | Raw widget rectangle: PDF user space |
| `InvisibleTextLayer` | Bitmap pixels, origin top-left, plus a `displayToUser` affine |
| Viewer (`PDFCanvas`) | Document units: width-normalized, origin top-left, plus screen pixels |

### 5.2 Proposal: Page Display Space (PDS)
Points (1/72 in), origin at the **bottom-left of the page as displayed** (CropBox, after `/Rotate`), y up. It is already your
documented convention for redaction and annotation export, and it matches what `PdfRenderer` shows.

* Every edit operation stores geometry **only in PDS**.
* One class, `PageGeometry(page)`, owns all conversions: PDS <-> content-stream user space (CropBox origin and rotation), PDS <-> bitmap
  pixels (flip y, scale), PDS <-> viewer document units, and `TextPosition` -> PDS.
* Conversions happen at exactly three boundaries: user input, content-stream write, and text extraction import.
* Required tests: round trips for rotations 0/90/180/270, CropBox not at the origin, MediaBox larger than CropBox, UserUnit.
* Migration: the two MediaBox-only code paths above should move onto `PageGeometry` (they are probably wrong on rotated or cropped pages; I have not tested that).

---------------------------------------------------------------------------------------------------

## 6. Proposed `PdfEditingEngine` (design only, nothing implemented)

Principles: the original file is never written; edits are an **operation log**; output is a **new** file written to a temp path,
verified, then published through the existing `SafeDocumentReplacer`/SAF path.

```
interface EditSession {                        // one open document
    val pageGeometry: (pageIndex: Int) -> PageGeometry
    fun apply(op: EditOperation)               // appends to the log, drops any redo tail
    fun undo(); fun redo()                     // move a cursor; no inverse code
    fun canUndo(): Boolean; fun canRedo(): Boolean
    suspend fun materialize(): File            // replay ops [0, cursor) on a copy of the base, return temp PDF
    suspend fun save(target: OutputTarget): SaveReport   // materialize + verify + atomic publish
}
sealed interface EditOperation { val id: String; val pageIndex: Int; val bounds: RectPds }
    AddText(text, font, sizePt, color, origin: PointPds)
    AddImage(source, bounds: RectPds)
    TransformImage(target: ObjectRef, newBounds: RectPds)
    DeleteImage(target: ObjectRef)
    ReplaceTextRun(target: RunRef, newText)    // only offered when the analyzer says the run is editable
    DeleteTextRun(target: RunRef)              // labelled "not secure redaction"
```
* **Undo/redo by replay**: state = base file + ordered operations + a cursor. Undo moves the cursor and re-materializes
  (with cached checkpoints). This avoids writing an inverse for every operation, which is where editors usually go wrong.
  It replaces the snapshot-stack style in `AnnotationManager` for editing; annotations can migrate later.
* **Preview is WYSIWYG**: show the materialized temp PDF through `PdfRenderer` (all access under the existing mutex), debounced.
  A Compose overlay may be used only for the selection handles, never to draw "fake" content.
* **Editability gate**: a Kotlin port of `analyze_text_editability.py` produces, for each run, `Editable` or a list of reasons
  (Type 3 font, no ToUnicode, glyph missing in subset, inside shared form XObject, signed, encrypted, tagged-structure mismatch, clipped).
* **Verification before publishing** (fail closed): reopen with PDFBox; page count unchanged; text of edited runs equals the requested text
  by extraction; unedited pages byte-identical in content; render comparison against a Poppler-rendered reference in CI.
* **Fonts**: reuse the run's own font if it covers the glyphs; otherwise ask the user to choose a substitute and show the preview.
  Embedded subset only; system fonts only; `.ttc` not supported; dropped characters reported, never silent.
* **Refusals**: encrypted without password; signed (warn that the signature will be invalid, or refuse); XFA; certified documents.
* **Module**: a new `:pdfedit` module (pure Kotlin + PDFBox, no UI) so it can be unit-tested on the JVM; UI stays in `:viewer`/`:editor`.
* **Testing plan** (CI only): PageGeometry round-trip property tests; golden PDFs per scenario; `qpdf --check`, `pdftotext`
  comparison and Poppler render diff on every produced file; mutation of PDFs from several producers (Word, LibreOffice, Chrome, InDesign, scanners).

---------------------------------------------------------------------------------------------------

## 7. Risks and what would change the verdict

* If your users' PDFs are mostly produced with **full (non-subset) embedded fonts or base-14 fonts**, capability D becomes much more useful.
  If they are mostly subset-embedded (typical for office exports), D will often be refused. **Run the analyzer on a real set of your PDFs**
  (the script is read-only; instructions below) before committing to D.
* PDFBox-Android on low-memory devices: large pages and images; needs the memory settings already used elsewhere (`MemoryUsageSetting`).
* A text editor that "mostly works" is worse than a clear refusal: every failure mode above must be detected up front.
* Not evaluated: OCR-assisted editing of scanned pages (outside this phase), cloud and AI features (excluded by your rules).

---------------------------------------------------------------------------------------------------

## 8. Decisions I need from you (stopping here)

1. **Scope.** (a) Add-only: new text and images, plus move/resize/delete of things the editor added; (b) a + move/delete of existing images;
   (c) b + in-place text edits where the analyzer says the run is safe; (d) full text reflow (I recommend against).
   My recommendation: build a and b first, then c behind the analyzer.
2. **Fonts.** OK to rely on device system fonts only (no bundled font), as `InvisibleTextLayer` does? Or should the project bundle one
   open-licence font (for example an SIL OFL family) so editing works on every device?
3. **Signed documents.** Refuse editing, or allow with a clear "signature will be invalid" warning?
4. **Canonical coordinates.** Adopt Page Display Space and migrate the two MediaBox-only exporters onto `PageGeometry`?
5. **Module.** New `:pdfedit` module as proposed?
6. **Corpus.** Can you provide 20-50 real PDFs (or run the analyzer on them with CI and send the output) so capability D can be sized with real data?

## 9. How to run the analyzer (read-only)
`pip install pikepdf` then `python3 tools/feasibility/analyze_text_editability.py a.pdf b.pdf --json out.json`.
It prints, per page and per font: type, embedded, subset, ToUnicode, encoding, how many of the 62 letters and digits a subset font can
still draw, operator counts, spacing operators, text render modes, clipping, text in form XObjects, signature and encryption flags.

## 10. Matrix (Phase 3)
| Known Working | Known Failing | Untested |
|---|---|---|
| Experiments on synthetic PDFs: same-length edit in base-14 text passes `qpdf --check` and `pdftotext`; analyzer runs on 5 files without error | Nothing known (no code in the app changed) | Every PDFBox-Android 2.0.27.0 call named here; real-world PDF mix; device behaviour; anything about rotated/cropped pages (not tested) |

## 11. Files
New: `docs/TEXT_EDITING_FEASIBILITY_REPORT.md`, `tools/feasibility/analyze_text_editability.py`. Changed/moved/deleted: none.

## 12. Decisions received (Nov 18) and one correction

1. Scope: **A + B** (add text/images; move/resize/delete images, including images already in the PDF). In-place text editing is **not** started.
2. Fonts: device fonts **and** one bundled open-source font (Liberation Sans Regular and Bold, SIL OFL 1.1).
3. Signed documents: **allowed**, with a warning that the signature becomes invalid.
4. Coordinates: adopt Page Display Space; migration order left to me.
5. Module: new `:pdfedit`.
6. Corpus: analyzer output; you can supply real PDFs. A manual workflow now runs the analyzer on `tools/feasibility/corpus/*.pdf`.

**Correction to section 5.** I wrote that the display-to-user conversion was possibly inconsistent across the repo. Reading further:
app `PdfPageGeometry`, annotations `AnnotationPageGeometry` and core `VisualSignatureGeometry` use the **same** four matrices (and the new
`PageGeometry` in `:pdfedit` reproduces them, with a unit test). The MediaBox-only paths (`AnnotationFlattener`, the older block in
`PdfAnnotationExporter`) are the only suspect ones, and I have not checked whether they are reachable. Implementation of decisions 1-5 is described in
`docs/PHASE_3B_PDFEDIT_ENGINE_REPORT.md`.

