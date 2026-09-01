# Implementation Status

This replaces an earlier one-off, dated audit (2026-07-28) of a much older snapshot of
this codebase (Activity/XML-era: `ViewerActivity`, empty FAB lambdas, unregistered nav
routes, wrong manifest application-class name). Those specific issues were fixed long
ago and re-describing them isn't useful. This file is a **current, feature-by-feature
status snapshot plus a changelog of the significant fixes** that got it here, so a
developer or AI agent can tell at a glance what's real, what's dormant, and what
genuinely doesn't exist yet — see `ARCHITECTURE_DIAGRAM.md` and `INTEGRATION_REPORT.md`
for the underlying active/dormant map this is built on.

## Feature status

| Area | Status | Notes |
|---|---|---|
| PDF viewing | ✅ Real | Tiled rendering + true continuous vertical scroll (not page-switch-at-edge fakery), pinch/double-tap/focal-point zoom, thumbnails, search, bookmarks, page navigation. Mutex-shared `PdfRenderer` access between tile pipeline and fallback renderer. |
| Annotations | ✅ Real | Freehand, highlight/underline/strikeout/squiggly, all shape tools (rect/circle/line/arrow/**polygon/cloud**, with real vertex-aware hit-testing/move/resize), text, stamps, undo/redo, flatten. Coordinates stored in PDF-page space, correctly re-projected on zoom/pan/page-change. |
| Stamps | ✅ Real | Built-in stamps, custom text/image stamps, date stamps with a real, UI-exposed format picker (`DateFormatOption`) — not hardcoded to one format. |
| Digital signatures | ✅ Real | Legacy `ApplySignatureActivity`/`SignatureManagerActivity`/`SignatureVerificationActivity` stack — BouncyCastle/PKCS7 signing+verification, cert import, draw/type/image capture, history. Reached via `SecurityHubScreen`. |
| Page operations | ✅ Real | Delete/duplicate/move/rotate/crop/resize/mirror/insert/split/extract, via `PageEditorScreen`+`PdfOperationWorker` (app module) and, separately, extract/compress/watermark/page-numbers/crop via `PdfEditorScreen` (`:editor`, backed directly by `PdfOperationsRepository`). |
| Redaction | ✅ Real | `RedactionScreen`/`RedactionViewModel` use `:security`'s `SecurityRepository` (Room + iText) to actually remove underlying content — not just draw a black rectangle over it. |
| Scanner — capture | ✅ Real | CameraX capture, OpenCV edge/quad detection with a centralized `OpenCvAvailability` circuit breaker (manual capture still works if native OpenCV is unavailable — no crash), perspective correction, multi-page review (reorder/delete/retake/rotate). |
| Scanner — filters | ✅ Real | Original/Auto/Grayscale/B&W/color filters via `DocumentScannerEngine` (interactive path) and `ImageEnhancer` (OpenCV-based batch/export path) — two call paths, not duplicate implementations. |
| Scanner — export | ✅ Real | Scan → PDF preserves page order/dimensions, supports OCR-searchable output, and "Save as JPEGs" now actually saves to `Pictures/ProPDF` (MediaStore on API 29+, legacy public dir + media-scan below) instead of the old hardcoded "not yet implemented" error. |
| OCR | ✅ Real (export was buggy, now fixed) | ML Kit on-device recognition, multi-language support. `OcrRepositoryImpl.exportToPdf()` now word-wraps and paginates properly — it used to silently drop any recognized text past a fixed vertical cutoff on a single hardcoded Letter-size page. |
| File management | ✅ Real | Recent files, favorites, folders, search/sort, rename/delete, recycle bin (30-day metadata expiry via `RecycleBinCleanupWorker`). "Clear Recent Files" only clears history records — verified it never deletes actual files, favorites, or the recycle bin. |
| Tools Hub | ✅ Real | Categorized (Organize/Edit/Annotate/Security/Convert/Page Design); no dead buttons — an unsupported operation shows an honest unavailable state rather than a silent no-op. |
| PDF Compare | ❌ Not implemented | Confirmed, more than once, that there is no backend anywhere in the repo for this. Correctly shown as an honest "coming soon," not a fake button. |
| Form designer | ❌ Not implemented | Genuinely absent. |
| Cloud/NAS sync | ⚠️ Scaffolding only | `:sync`/`:nas`/`:backup` modules exist and are wired into the Gradle graph, but the actual sync/NAS behavior is not implemented — scaffolding, not a working feature. |
| `:ads` | ❌ Not built | Module exists in `settings.gradle`, not a dependency of `:app` — not part of the built app at all currently. |

## Recent significant fixes (most recent first)

- **CameraX teardown race (`ScannerScreen.kt`)** — the camera bind sequence lived
  inside `AndroidView`'s `update` lambda, which Compose re-runs on every recomposition
  of the screen, not once. Any unrelated state change re-ran `unbindAll()` +
  `bindToLifecycle()`, aborting any in-flight photo capture
  (`ImageCaptureException: Camera is closed`) — confirmed via two separate Firebase
  Test Lab Robo-crawl reports, 4 occurrences each. Fixed by moving the bind into a
  `DisposableEffect(Unit)` (binds once, unbinds once on disposal).
- **`Bitmap.setPixels` crash on the scanner's B&W filter** — `DocumentScannerEngine
  .applyBlackAndWhite()` mutated the *input* bitmap in place via `setPixels()`, unlike
  every sibling filter in the same file (which all write into a freshly created output
  bitmap). Threw `IllegalStateException` — a real, reproducible `FATAL EXCEPTION` —
  whenever the input bitmap was immutable or `Config.HARDWARE`. Found via a Firebase
  Test Lab crawl on a Pixel 5/API 30 emulator; fixed to match the established pattern.
- **OCR PDF export silently truncated text** — `OcrRepositoryImpl.exportToPdf()` used
  one hardcoded 612×792 page per source page and stopped writing (with no error) past
  a fixed `y` cutoff. Rewrote with real word-wrapping and automatic pagination.
- **`:editor` module's PDF tools were disabled behind a mis-diagnosed DI limitation** —
  Extract/Compress/Watermark/Page-Numbers/Crop buttons in `PdfEditorScreen` all showed
  "not available." The actual repository (`PdfOperationsRepository`) was reachable the
  whole time via the standard cross-module Hilt pattern (see `MIGRATION_GUIDE.md`); no
  architecture change was needed, just wiring the injection through and building the
  UI (small dialogs for Watermark/Crop, direct-apply for the rest).
- **Scanner's "Save as JPEGs" was a hardcoded stub** despite the bitmaps already being
  in memory — implemented real MediaStore/legacy-dir export.
- **`SecurityHubScreen`'s Sign/Verify actions routed to a fake stand-in** (the
  annotation module's plain drawing pen) instead of the real, already-implemented
  legacy signature Activity stack — fixed to launch the real Activities.
- **Redaction UI used a superseded engine class** — fixed to use `SecurityRepository`'s
  real Room+iText implementation, which actually removes content.
- Numerous earlier passes fixed: SAF `content://` handling across every PDF operation
  (previously several used `File(uri.path)`, which silently fails on real content
  URIs), `PdfRenderer` concurrent-access crashes (added the shared mutex), the OpenCV
  `UnsatisfiedLinkError` crash path (added `OpenCvAvailability`), recycle-bin/favorites
  data-loss risks in "Clear Recent," and the kapt→KSP Room migration needed to fix a
  persistent CI build failure.

## Known genuine limitations (not fixable without larger product/API decisions)

- No in-place PDF text editing (this is a fundamentally different, much larger feature
  than the current page/annotation/operation model).
- No PDF comparison/diff.
- No form designer / fillable-form authoring.
- Cloud/NAS sync modules are scaffolding, not working integrations.
- `viewer/.../data/repository/PdfViewerRepositoryImpl.renderPage()` leaks a
  `PdfRenderer.Page` on exception (missing `finally`) — real bug, but its only
  consumers are dormant (see `INTEGRATION_REPORT.md`). Fix this if you ever wire
  `ViewerViewModel`/`PremiumViewerViewModel` into the nav graph; not worth touching
  before then.
