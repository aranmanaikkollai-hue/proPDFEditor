# Developer & AI Agent Working Guide

This file used to document a specific monolith-to-modules migration (creating `:core`,
`:viewer`, `:editor`, etc. from scratch). That migration finished long ago — the
"Known Limitations" table from that version (annotation save as a stub, watermark not
wired, digital signatures "not started", etc.) is now **false**; all of those have
since been implemented. Keeping that table around risked someone re-implementing a
feature that already exists. This file now covers what's actually useful going
forward: how this repo is developed, and the patterns/pitfalls that have caused real,
repeated problems.

## How this repo is actually developed

There is no local IDE in the loop. Changes are made by an AI agent working from:
- a zip export of the repository, and
- separately, build logs / Firebase Test Lab crash-and-crawl reports, when available.

There is **no compiler available in that working environment** — every fix has to be
verified by reading the actual source (imports, real method signatures, actual Gradle
module dependencies) rather than by trial-compiling. This has a few consequences worth
internalizing:

- **Read the current file before editing it.** Don't assume an earlier description of
  a file (including in this doc, or in a chat transcript) still matches its contents.
- **Trace the real reachability of a class before "fixing" it** — see
  `INTEGRATION_REPORT.md`. A huge fraction of wasted effort in this project's history
  came from patching dormant duplicates instead of the active implementation.
- **A build/log export, when provided, is ground truth** — prefer it over static
  reasoning about what "should" happen. Several real bugs here (an OpenCV
  `UnsatisfiedLinkError`, a `Bitmap.setPixels` crash on the B&W scanner filter, a
  CameraX teardown race) were only found because an actual Firebase Test Lab crawl
  caught them; static reading alone hadn't.
- **Don't manufacture changes.** If a described "defect" turns out to already be
  correctly implemented, say so and move on rather than making a cosmetic change to
  have something to report.

## Hard constraints (see also `README.md`)

- No JitPack — `google()`/`mavenCentral()` only.
- Kotlin files must be pure ASCII, including inside comments/KDoc.
- `android.graphics.pdf.PdfDocument` is not `Closeable` (try/finally, not `.use {}`).
  `PdfRenderer.Page` *is* `Closeable` (`.use {}` is fine/expected there).
- `registerForActivityResult` must be a class-level `val`.
- Never write a KDoc comment containing a literal `*/` mid-sentence (e.g. an API list
  like `Mat()/Imgproc.*/Utils.*`) — it closes the comment block early and the rest of
  the file parses as garbage. This single mistake once broke an entire CI build.

## The cross-module Hilt DI pattern (read this before concluding a repository is unreachable)

Every feature module depends only on `:core`; `:app` depends on all feature modules.
A ViewModel in, say, `:editor` can `@Inject` a repository **interface** declared in
`:core` even when the only `@Binds` **implementation** of that interface is compiled
inside `:app`'s own package. This works because Hilt aggregates every
`@InstallIn(SingletonComponent)` module into a single component graph at the point
where `:app` (the final, leaf application module) is assembled — it doesn't matter
which Gradle module physically compiles a given `@Module`, only that it ends up on
`:app`'s classpath, which it does by definition.

This has been mis-diagnosed as a real architectural gap more than once ("`:editor`
can't reach `PdfOperationsRepository` because it would need to depend on `:app`, which
would be circular"). It doesn't need that. If you hit this pattern, just inject the
interface directly and verify the `@Binds` module exists somewhere on `:app`'s
transitive classpath — don't restructure module dependencies to "fix" it.

## Common failure patterns encountered (learn from these, don't repeat them)

- **kapt → KSP migration for Room**, alongside Hilt 2.51.1 and careful Kotlin-daemon
  memory budgeting, was needed to resolve a persistent
  `kaptGenerateStubsDebugKotlin` / "Could not load module \<Error module\>" CI failure.
- **`uri.path` on a SAF `content://` URI is never a real filesystem path.** Every PDF
  operation needs to go through `ContentResolver` streams/file descriptors. This broke
  every PDF tool on API 29+ at one point.
- **`PdfRenderer` is not safe for concurrent access** from multiple coroutines/threads
  against the same instance. The tile-rendering pipeline and the full-page fallback
  renderer share one `Mutex` for exactly this reason — if you add a new code path that
  opens pages on an existing `PdfRenderer` instance, it needs to go through the same
  mutex, not a new one.
- **`Bitmap.setPixels()` throws `IllegalStateException` on immutable or
  `Config.HARDWARE` bitmaps.** Filter/processing functions should write into a freshly
  created mutable output bitmap rather than mutating the input in place — this is the
  pattern every filter function in `DocumentScannerEngine` follows except one, which
  crashed in production until it was fixed to match the others.
- **Compose's `AndroidView` `update` lambda re-runs on every recomposition of its
  parent**, not once. Expensive one-time setup (e.g. binding a CameraX
  `ProcessCameraProvider`) belongs in a `DisposableEffect` keyed appropriately, not in
  `AndroidView`'s trailing lambda — putting it there caused a real, repeatedly-observed
  bug where an in-flight photo capture got aborted by an unrelated recomposition
  re-running `unbindAll()`.

## Where to add things

| You want to... | Look at / extend |
|---|---|
| Add a PDF page operation (new kind of watermark, etc.) | `core/.../domain/repository/PdfOperationsRepository.kt` interface + its `:app`-side `@Binds` impl; wire the UI into `PdfEditorScreen` (`:editor`) or `PageEditorScreen` (`:app`), whichever fits |
| Add an annotation tool | `:annotations` module — extend the existing tool enum/handler set, not a new engine |
| Add a scanner filter/enhancement | `DocumentScannerEngine` (live filter path) or `ImageEnhancer` (batch/export path) — pick based on which call path you're extending, they're both active for different purposes |
| Add a new screen | Add the composable, then **register it in `AppNavigation.kt`** — a screen that compiles but isn't in the nav graph is exactly the kind of dormant code this repo already has too much of |
| Persist new data | Check `INTEGRATION_REPORT.md`'s database registry first — there is a good chance a relevant `@Database`/DAO already exists somewhere |

## Testing

| Layer | Test type | Framework |
|---|---|---|
| Domain / repositories | Unit / integration | JUnit, Room in-memory |
| ViewModel | Unit | kotlinx-coroutines-test (`DispatcherProvider` is injectable for this) |
| UI | Instrumented | Espresso / Compose test |

No CI-independent way to run these exists in the day-to-day AI-agent workflow
described above — treat test files as documentation of intended behavior to keep in
sync, and rely on real CI/Firebase Test Lab runs for actual verification.
