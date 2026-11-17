# PHASE 2a - VIEWER POLISH (bookmarks, reading modes, page jump, tablet/landscape)

**BUILD NOT RUN - needs CI.** Nothing here was compiled or executed. Base: the Phase 1 tree (including the
`performClick` follow-up). Horizontal and two-page modes are **not** in 2a; they are Phase 2b.

## Audit summary
The live viewer is `IntegratedPDFViewerScreen` + `PDFViewerViewModel` + `PDFCanvas` in `:viewer` (routes
`viewer/{uri}` and `annotate/{uri}`). The older `com.propdf.editor.ui.viewer` package already contains
bookmark, night/sepia and tablet code but nothing references it; it was used for ideas (the colour matrices) only.
The `bookmarks` table (database v4) and `core/.../local/dao/BookmarkDao.kt` already existed and were unused by the viewer.

## What changed
* **Bookmarks.** Bookmark button in the top bar (toggles the current page), a Bookmarks list (More sheet) with jump and
  delete. Stored in the existing `bookmarks` table, keyed by document URI string; for password-protected PDFs the key is
  the original file's URI, not the temporary unlocked copy. Pages beyond the page count are hidden. No titles (the table
  has none; adding one would need a v5 migration). Write failures are logged and ignored.
* **Reading modes.** Normal / Night / Sepia chips in the More sheet; choice is saved on the device
  (`ViewerPreferences`, SharedPreferences). Applied only as a draw-time colour filter in `PDFCanvas` on page
  bitmaps and sharp tiles, plus matching page and background colours. It does not touch the PDF file, saved or exported output.
* **Go to page.** Tap the "n / N" title or use More > Go to page. Numeric entry, validated against the page count
  (`PageJump.parse`: plain ASCII digits only, 1..N). The existing drag scroller is unchanged.
* **Tablet / landscape.** At 600 dp width or more the thumbnail sidebar is docked beside the page (page and annotation
  overlay share the same shifted bounds) and stays open after choosing a page; on narrower screens it overlays as before.
  The More sheet now scrolls, so every item is reachable in landscape on a phone.
* New UI text is in string resources (`reader_*` in `viewer/src/main/res/values/strings.xml`); every new icon button
  has a content description.

## Tests added (not run)
* `viewer/src/test/java/com/propdf/viewer/ui/PageJumpTest.kt` (7 tests)
* `viewer/src/test/java/com/propdf/viewer/ui/ReaderColorModeTest.kt` (6 tests; expected values checked by hand arithmetic)
* `app/src/androidTest/java/com/propdfeditor/db/BookmarkDaoInstrumentedTest.kt` (6 tests: ordering, duplicate add,
  per-document remove and clear, missing remove, observe)
* Device checklist rows 31-43 added to `docs/REAL_DEVICE_TEST_CHECKLIST.md`.

## Not covered by any automated test
The bookmark button, dialogs, chips, colour filter drawing and the docked sidebar are Compose UI and have no UI test;
they rely on the device checklist. `PDFViewerViewModel` has no unit tests for the new bookmark functions.

## Decisions taken without your answer (say if you want them changed)
* Bookmarks have no titles. * Annotation mode: nothing needed in 2a; the switch-to-vertical rule applies in 2b.
* Two-page cover rule: undecided, needed for 2b.

## Matrix
| Known Working | Known Failing | Untested |
|---|---|---|
| Phase 1 results from the CI logs you sent (they do not cover this code) | Nothing known | Everything in this phase: compilation, the 13 new automated tests, Hilt injection of `BookmarkDao` and `ViewerPreferences` into `PDFViewerViewModel`, all UI behaviour, checklist rows 31-43 |

## Files
Changed: `viewer/src/main/java/com/propdf/viewer/ui/PDFViewerViewModel.kt`, `.../ui/PDFViewerScreen.kt`,
`.../ui/IntegratedPDFViewerScreen.kt`, `viewer/src/main/res/values/strings.xml`, `docs/REAL_DEVICE_TEST_CHECKLIST.md`.
New: `viewer/src/main/java/com/propdf/viewer/ui/{ReaderColorMode,PageJump,ViewerPreferences}.kt`, the three test files above,
`docs/PHASE_2A_VIEWER_REPORT.md`. Moves/deletions: none.
