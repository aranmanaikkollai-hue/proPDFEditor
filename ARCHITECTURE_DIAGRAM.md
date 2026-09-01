# ProPDF Editor — Architecture

This replaces an earlier version of this file that described an Activity/XML-based
skeleton (`ViewerActivity`, "programmatic Views", 8 modules) which no longer matches
the codebase. Everything below was verified directly against the current source tree.

## The most important rule in this repo

**`app/src/main/java/com/propdfeditor/ui/navigation/AppNavigation.kt` is the single
source of truth for what's reachable.** This codebase has accumulated multiple
generations of duplicate implementations for the same feature (viewer, annotations,
recent-files, page editor, signatures — see the table below). Before you:

- "fix" a bug in a class — check it's actually reachable from `AppNavigation.kt`
  (directly, or via an `Intent` launched from a screen that is).
- delete a class because it "looks unused" — check nothing outside its own package
  references it (Kotlin refs, Hilt bindings, manifest, resources).
- add a feature by writing a new viewer/annotation/database/repository — search first;
  it may already exist, just not wired in.

Getting this wrong is the single most common source of wasted effort across this
project's history (see `INTEGRATION_REPORT.md` for the specific list of known
duplicates and which side of each pair is active).

## Module dependency graph

```
                              :app
                  (application shell, most screens,
                   navigation, many @Binds modules)
                                │
      ┌──────────┬──────────┬──┴───────┬──────────┬──────────┬──────────┐
      │          │          │          │          │          │          │
 :viewer    :editor   :annotations  :scanner  :security  :storage   :sync/:backup/
      │          │          │          │          │          │      :ocr/:share/:nas
      └──────────┴──────────┴──────────┴──────────┴──────────┴──────────┘
                                │
                              :core
                 (domain models, repository interfaces,
                  AppResult, DispatcherProvider, shared DB infra)

:ads exists in settings.gradle but is NOT a dependency of :app — it's not part of
the built app at all right now.
```

Every feature module depends only on `:core`. `:app` depends on all of them. This
means a feature module (e.g. `:editor`) can `@Inject` a repository *interface* that
lives in `:core` even when the only `@Binds` implementation of it is compiled inside
`:app` — Hilt merges every `@InstallIn(SingletonComponent)` module into one graph at
the point where `:app` is actually assembled, regardless of which Gradle module
physically compiles the `@Module`/`@Binds` code. This was previously mis-diagnosed
more than once as "module X can't reach this repository, would need a circular
dependency on :app to fix" — it doesn't need that. See `MIGRATION_GUIDE.md`.

## Real navigation graph (from `AppNavigation.kt`)

```
MainActivity
  └─ AppNavigation (NavHost)
       ├─ Home
       ├─ Files (FileManagerScreen)
       ├─ Scanner
       ├─ Tools (ToolsHubScreen)
       │    ├─ Organize Pages  → PageEditorScreen (:app)
       │    ├─ Protect / Sign / Redact / Verify → launches legacy signature/security
       │    │                                       Activities via Intent (see below)
       │    └─ OCR, etc.
       ├─ "editor/{uri}"       → PdfEditorScreen (:editor) — extract/compress/
       │                          watermark/page-numbers/crop
       ├─ "viewer/{uri}"       → IntegratedPDFViewerScreen (:viewer) — THE active
       │                          PDF viewer (tiled rendering + continuous scroll)
       ├─ "redact/{uri}"       → RedactionScreen (:app), backed by :security's real
       │                          Room+iText redaction engine
       ├─ "page-editor/{uri}"  → PageEditorScreen (:app) — full page management
       ├─ "document_manager" / "folder_browser" / "recent_activity" → :app Library
       │                          screens (Collections/Folders/activity log)
       └─ Settings
```

## Active vs. dormant implementations (verified, keep this updated)

| Feature area | ACTIVE (reachable) | DORMANT (compiled, not reachable — do not "fix" without wiring it in first) |
|---|---|---|
| PDF viewer | `com.propdf.viewer.ui.IntegratedPDFViewerScreen` + `PDFViewerViewModel` (own `PdfRenderer`, own mutex-guarded tile pipeline) | `com.propdf.editor.ui.viewer.*` (app module, XML-era duplicate); `com.propdf.viewer.presentation.ViewerViewModel` / `PremiumViewerViewModel` (consume `PdfViewerRepository`, whose only impl — `viewer/.../data/repository/PdfViewerRepositoryImpl` — has a real page-leak bug in `renderPage()` that's moot because nothing reaches it) |
| Recent files | `com.propdf.core.data.database/repository.RecentFileRepository` (singular) — Hilt-bound, has the real `clearRecentOnly()`/favorite/recycle-bin wiring | `com.propdf.core.data.local.RecentFilesRepositoryImpl` (plural) — not Hilt-bound, has its own separate `recent_files` table |
| Signatures | Legacy `ApplySignatureActivity` / `SignatureManagerActivity` / `SignatureVerificationActivity` (manifest-registered, launched via Intent from `SecurityHubScreen`) — real BouncyCastle/PKCS7 signing+verification, cert import, history | The annotation module's plain drawing "signature" was previously (wrongly) the entry point for "Sign"; fixed to launch the real Activities instead |
| Page management | `com.propdf.editor.ui.tools.page.PageEditorScreen`/`PageEditorViewModel` (app module) — full delete/duplicate/move/extract/rotate/crop/resize/mirror/insert/split, backed by `PdfOperationWorker` | none known — this was dormant (no nav route) until it was wired in; check `AppNavigation.kt` before assuming otherwise |
| Redaction | `RedactionScreen`/`RedactionViewModel` (app module) using `:security`'s real `SecurityRepository` (Room + iText, actually removes content) | `security/.../redaction/RedactionEngine.kt` — an unused, superseded class; do not resurrect |
| Scanner image processing | `com.propdf.scanner.engine.DocumentScannerEngine` (used by `ScannerViewModel`'s live filter/enhance flow) and `com.propdf.scanner.processing.ImageEnhancer` (OpenCV-based, used by `ScanProcessingWorker`/`BatchProcessor`/`PdfCreator`) — **both active, for different call paths, not duplicates of each other** | `scanner/.../processing/{EdgeDetector,PerspectiveCorrector,ScanModeDetector}.kt` — confirmed zero live callers outside their own Hilt provider |
| Main activity | `com.propdfeditor.ui.main.MainActivity` (Compose, manifest launcher) | a legacy XML `MainActivity` variant — not the manifest's launcher, not reachable |

If you find another pair like this, add a row here rather than leaving the discovery
only in a commit message or a chat transcript.

## Cross-cutting infrastructure (all active, shared by every feature module)

- **Tile rendering pipeline** (`:viewer`): `TileGrid` → `TileRenderer` (semaphore- and
  mutex-guarded `PdfRenderer` access, shared with the fallback full-page renderer to
  avoid concurrent-access crashes) → `renderedTiles` `StateFlow` → composited by
  `PDFCanvas` over the full-page fallback bitmap. `ViewportManager.updateViewport()` is
  driven by a `LaunchedEffect` in `PDFCanvas` computing the visible page-pixel range.
- **OpenCV safety**: `OpenCvAvailability` (scanner module) is a circuit breaker
  initialized once in `ProPDFApplication.onCreate()`. Every native-OpenCV call site in
  `DocumentScannerEngine`/`OpenCvDocumentProcessor` routes through
  `OpenCvAvailability.runSafely(tag, fallback) { ... }` — a native failure disables
  OpenCV for the rest of the process (falls back to manual capture / original bitmap)
  instead of crashing or being retried every frame.
- **Recycle bin**: `RecycleBinDao`/`RecycleBinEntity` (part of the active
  `ProPDFDatabase`), populated by `RecentFileRepositoryImpl.deleteFile()`, cleaned up
  daily by `RecycleBinCleanupWorker`. Only metadata is preserved (30-day expiry), not
  the underlying PDF bytes — these are SAF `content://` URIs the app doesn't own
  storage for.

## Clean Architecture layering (still applies, per module)

```
Presentation (Compose screen + ViewModel exposing StateFlow<UiState>)
        │
Domain (repository interfaces in :core, AppResult<T> wrapper)
        │
Data (repository implementations wrapping iText7 / PdfRenderer / PDFBox / Room / CameraX)
```
