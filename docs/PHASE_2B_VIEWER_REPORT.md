# PHASE 2b - PAGE LAYOUTS (two-page and horizontal)

**BUILD NOT RUN - needs CI.** Nothing here was compiled or executed. Base: the Phase 2a tree. This is the riskiest
part of Phase 2: it rewrites `PDFCanvas` (drawing, pinch, fling, tile viewport) so it can scroll along either axis.
Only the geometry is covered by automated tests; the gestures rely on the device checklist.

## What changed
* **Layout modes.** Single column (unchanged default), Two pages, Horizontal. Chosen in More > Page layout and saved on the device.
* **`PageLayoutEngine`** (new, pure Kotlin, JVM-testable) computes every page's position:
  * Single column: identical formula to the old inline loop (first page's width is the shared width).
  * Two pages: rows of two at the shared width, top-aligned, row height = taller page. Cover rule: page 1 alone on the
    right of the first row, then 2-3, 4-5 ... (a last unpaired page sits on the left). `coverAlone = false` pairs 1-2, 3-4;
    it exists in the engine and tests but has no on-screen switch.
  * Horizontal: one row, all pages scaled to the first page's height, fitted to the screen height.
  * Also: nearest-page choice (axis-only for single column and horizontal, both axes for two pages) and scroll targets.
* **`PDFViewerViewModel`**: `PageLayout` gains `docLeft`/`docWidth` (defaults keep old callers valid); keeps page sizes so
  switching mode re-lays out without reopening; new `layoutMode`, `preferredLayoutMode`, `documentScrollX`,
  `updateViewportScroll(...)`; `goToPage`, bookmarks, thumbnails and search jump through one `scrollToPageInternal`.
  `updateDocumentScroll(scrollY, h)` still exists and delegates.
* **`PDFCanvas`**: fit-to-width (single, two pages) or fit-to-height (horizontal); horizontal scrolls and flings along X,
  pans in pixels along Y; single column and two pages keep the old vertical scroll + pixel pan-X behaviour; the sharp-tile
  viewport is clipped horizontally only in Horizontal. `onScrollChange` still works through the default of the new
  `onViewportScroll`, so the other two callers (`PDFViewerScreen`, `AccessiblePDFCanvas`) compile unchanged.
* **Annotation rule (my default, you did not answer):** while annotating, the layout is held at single column and the
  chips are disabled with a note; the saved choice returns afterwards.
* **Page scrubber** is hidden in Horizontal (it is a vertical control); page jump, bookmarks and thumbnails still work.
* Workflow: `release-audit.yml` prints, as informational log lines, the locations of `ClickableViewAccessibility`,
  `ContentDescription`, `LabelFor` and `TrustAllX509TrustManager` lint findings.

## Tests added (not run)
`viewer/src/test/java/com/propdf/viewer/ui/PageLayoutEngineTest.kt`: 18 tests (original-formula equivalence, cover and
no-cover pairing, unequal row heights, mixed sizes, horizontal scaling, empty and zero-size input, nearest page in each
mode, scroll targets and end clamping, mode-name fallback). Expected numbers were re-derived by hand. Device checklist rows 44-60 added.

## Known limits and risks
* No automated test touches `PDFCanvas`, the gestures or the view model wiring; a mistake there compiles but misbehaves.
* Two pages on a portrait phone shows each page at about half width; zoom in to read.
* Pages without a rendered bitmap show as blank page rectangles, as in single column; in Horizontal and when zoomed far out
  more pages are visible at once than the render window (current page and its neighbours) covers.
* Sharp tiles are still produced only for the current page.
* Annotation coordinates still assume the single column and are untouched.
* Left-to-right only; no right-to-left reading order.

## CI evidence (run on a tree of 1373 files = Phase 2a tree + the 3 new 2b files)
* Builds succeeded; release lint gate PASS, 0 errors.
* Unit tests: viewer 37 (19 before + 18 new `PageLayoutEngineTest`), app 147, core 25 - 0 failures. Instrumented: app 232, core 62, viewer 22 - 0 failures (3 skipped in app, as before).
* This proves the layout geometry code and that everything compiles. It does not exercise `PDFCanvas`, the gestures or the view-model wiring; checklist rows 44-60 are still the only check on those.

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Build, lint gate, 18 new layout-geometry tests, no regressions in 147 + 25 + 37 unit and 232 + 62 + 22 instrumented tests | Nothing known | Canvas drawing, pinch, fling, mode switching, annotation lock, scrubber hiding; checklist rows 44-60 |

## Files
Changed: `viewer/.../ui/PDFViewerViewModel.kt`, `.../ui/PDFViewerScreen.kt`, `.../ui/IntegratedPDFViewerScreen.kt`,
`.../ui/ViewerPreferences.kt`, `viewer/src/main/res/values/strings.xml`, `.github/workflows/release-audit.yml`,
`docs/REAL_DEVICE_TEST_CHECKLIST.md`, `docs/PHASE_2A_VIEWER_REPORT.md`.
New: `viewer/.../ui/PageLayoutEngine.kt`, `viewer/src/test/java/com/propdf/viewer/ui/PageLayoutEngineTest.kt`,
`docs/PHASE_2B_VIEWER_REPORT.md`. Moves/deletions: none.
