# ProPDF Editor

A multi-module Android PDF editor built with **Jetpack Compose + Hilt + Coroutines**.
`applicationId com.propdfeditor`, `versionName 3.0.0` (see `app/build.gradle`).

> **Note for anyone (human or AI) picking this repo up:** the docs in this folder were
> out of date for a long time — they described an early Activity/XML-based skeleton
> (`MainActivity` + `ViewerActivity`, "8 modules", "programmatic Views") that no longer
> exists. This file and its siblings (`ARCHITECTURE_DIAGRAM.md`, `INTEGRATION_REPORT.md`,
> `MIGRATION_GUIDE.md`, `docs/implementation-report.md`) were rewritten from a direct
> inspection of the current source tree to describe what's actually here today. If you
> change the architecture, please update these alongside the code — an AI agent working
> from a stale doc will waste real effort rediscovering facts that are already known.

## What this actually is today

- **UI:** Jetpack Compose throughout the active app (not XML layouts/Activities — those
  exist for a few specific legacy flows, see `ARCHITECTURE_DIAGRAM.md`'s active/dormant
  map). Single-Activity navigation via `androidx.navigation.compose`.
- **Entry point:** `com.propdfeditor.ProPDFApplication` (`@HiltAndroidApp`) →
  `com.propdfeditor.ui.main.MainActivity` → `com.propdfeditor.ui.navigation.AppNavigation`
  (`app/src/main/java/com/propdfeditor/ui/navigation/AppNavigation.kt`). **That file is
  the single source of truth for what's actually reachable in the app** — if a screen or
  ViewModel isn't referenced from it (directly, or via an Intent from a screen that is),
  treat it as dormant until proven otherwise.
- **DI:** Hilt 2.51.1, `@Binds`-based repository interfaces. Interfaces commonly live in
  `:core`; implementations and their `@Binds` modules can live in a downstream module
  (e.g. `:app`) without the *consuming* module needing to depend on that downstream
  module — Hilt resolves all `@InstallIn(SingletonComponent)` bindings once, in the
  final `:app` module's merged graph. See `MIGRATION_GUIDE.md` for the fuller
  explanation; this pattern has been mis-diagnosed as a "gap" more than once.
- **PDF engines:** `PdfRenderer` (Android platform) for on-screen viewing/tiling,
  iText7 for structural PDF operations (merge/split/watermark/page-numbers/crop/etc.
  and digital signatures), PDFBox-Android for OCR PDF export and some text extraction.
- **Persistence:** Room. **There are 17 separate `@Database` classes across the repo**
  (several literally named `AppDatabase`/`RecentFilesDatabase` in different packages) —
  this is a known, not-yet-consolidated architectural problem, not a design choice. See
  `INTEGRATION_REPORT.md` for the current registry of which ones are active.
- **Scanning:** CameraX 1.3.x + an OpenCV-based edge-detection/perspective-correction
  pipeline, guarded by a centralized native-availability circuit breaker
  (`OpenCvAvailability`) so a native-library failure degrades to manual capture instead
  of crashing.
- **Development workflow:** built entirely through CI (Codemagic / GitHub Actions) —
  there is no local IDE in the loop day to day. Changes are typically made by an AI
  agent working from a repo zip export and a build/log export, not by stepping through
  a debugger. Keep this in mind when leaving comments/docs: the next person to read a
  piece of code may not be able to just "run it and see."

## Modules

`settings.gradle` declares 14 modules:

| Module | Responsibility | Wired into `:app`? |
|---|---|---|
| `:app` | Application shell, navigation, most active screens | — |
| `:core` | Domain models, repository interfaces, `AppResult`, shared DB/dispatcher/logger infra | yes |
| `:viewer` | PDF rendering (tiled + continuous-scroll), search, bookmarks, thumbnails | yes |
| `:editor` | PDF page operations (extract/compress/watermark/page-numbers/crop) — `PdfEditorViewModel`/`PdfEditorScreen` | yes |
| `:annotations` | Full annotation engine: ink, shapes, stamps, highlight/markup, undo/redo, flatten | yes |
| `:scanner` | CameraX capture, OpenCV edge detection, image enhancement, scan→PDF | yes |
| `:security` | Encryption, redaction, password protection | yes |
| `:storage` | SAF storage helpers | yes |
| `:sync` | Cloud sync scaffolding | yes |
| `:backup` | Encrypted backup/restore | yes |
| `:ocr` | OCR feature module scaffold (the OCR implementation that's actually wired into the UI lives in `:core`, not here — see `docs/implementation-report.md`) | yes |
| `:share` | Sharing helpers | yes |
| `:nas` | NAS integration scaffolding | yes |
| `:ads` | AdMob integration | **no** — included in `settings.gradle` but not in `app/build.gradle`'s dependencies. Currently dead weight in the build graph. |

## Hard constraints

These have caused real, repeated build/runtime failures — respect them:

- **No JitPack.** All dependencies must resolve via `google()` or `mavenCentral()`.
- **Kotlin source files must be pure ASCII.** Non-ASCII characters (including inside
  comments/KDoc) have broken CI before.
- **`android.graphics.pdf.PdfDocument` (the PDF-*creation* class) is not `Closeable`.**
  Use try/finally, not `.use {}`. (Careful: `PdfRenderer.Page`, a *different* class used
  for rendering, *is* `Closeable` — `.use {}` is correct and expected for that one.)
- **`registerForActivityResult` must be declared as a class-level `val`**, not created
  inside a function.
- **Watch for `*/` inside KDoc comments.** A KDoc block containing the literal text
  `*/` (e.g. describing OpenCV API calls like `Mat()/Imgproc.*/Utils.*`) closes the
  comment early and turns the rest of the file into a cascade of parse errors. This has
  broken a full CI build before over a single doc-comment wording choice.

## Building

```bash
./gradlew :app:assembleDebug
```

Requires a JDK 17 runtime for Gradle/AGP compatibility (this has been a real blocker in
sandboxed/CI-less environments running newer JDKs).

## Further reading

- `ARCHITECTURE_DIAGRAM.md` — module graph, navigation graph, and the active-vs-dormant
  code map (read this before touching any viewer/annotation/scanner class you haven't
  seen referenced from `AppNavigation.kt`).
- `INTEGRATION_REPORT.md` — registry of known duplicate/dormant architecture (multiple
  Room databases, duplicate viewer/repository implementations, etc.) and the rule for
  safely working around them.
- `MIGRATION_GUIDE.md` — practical guide for developers and AI agents working in this
  repo: constraints, DI patterns, and lessons learned from real build failures.
- `docs/implementation-report.md` — current feature-by-feature implementation status
  and a changelog of significant fixes.

## License

MIT License — see `LICENSE` file.
