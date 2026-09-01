# Known Duplicate / Dormant Architecture Registry

This file used to be a one-off, dated audit of a specific set of wiring bugs (empty
FAB lambdas, unregistered nav routes, a wrong manifest application-class name) that
were fixed long ago. Those specific issues no longer exist and re-auditing them isn't
useful. What replaced it: a **living registry** of the duplicate/dormant code this
repo has accumulated, so the same "gap" doesn't get rediscovered and "fixed" more than
once, and so dormant code doesn't get resurrected/deleted by accident.

Rule of thumb: this repo has been built up over many incremental passes (often by an
AI agent working from a zip export, without a compiler in the loop). Several features
were implemented more than once, in different modules or packages, before the earlier
attempt's existence was known. **Reachability from
`app/src/main/java/com/propdfeditor/ui/navigation/AppNavigation.kt` is what determines
which copy is real** — not which one looks newer or better-written.

## Duplicate Room databases (17 total `@Database` classes)

Confirmed via `grep -rl "@Database(" --include=*.kt .`:

```
app/src/main/java/com/propdfeditor/batch/data/database/BatchDatabase.kt
app/src/main/java/com/propdf/editor/data/local/RecentFilesDatabase.kt
app/src/main/java/com/propdf/editor/data/local/AppDatabase.kt
app/src/main/java/com/propdf/editor/data/local/db/AppDatabase.kt
viewer/src/main/java/com/propdf/viewer/annotation/persistence/AnnotationDatabase.kt
viewer/src/main/java/com/propdf/viewer/data/database/ViewerDatabase.kt
nas/src/main/java/com/propdf/nas/data/local/NasDatabase.kt
annotations/src/main/java/com/propdf/annotations/persistence/AnnotationDatabase.kt
storage/src/main/java/com/propdf/storage/data/local/StorageDatabase.kt
sync/src/main/java/com/propdf/sync/data/local/SyncDatabase.kt
scanner/src/main/java/com/propdf/scanner/data/local/ScannerDatabase.kt
core/src/main/java/com/propdfeditor/core/database/AppDatabase.kt
core/src/main/java/com/propdf/core/data/database/ProPDFDatabase.kt
core/src/main/java/com/propdf/core/data/database/SearchDatabase.kt
core/src/main/java/com/propdf/core/data/local/OcrDatabase.kt
core/src/main/java/com/propdf/core/data/local/RecentFilesDatabase.kt
security/src/main/java/com/propdf/security/data/database/SecurityDatabase.kt
```

This has NOT been consolidated. It's a real architectural debt, not a false alarm —
treat any "which database is X actually in" question as something to verify by
tracing Hilt `@Provides`/`@Binds`, not by assuming the most obviously-named class.

**Specifically confirmed:**
- `core/.../data/database/ProPDFDatabase.kt` is the active database backing
  `RecentFileRepository` (singular — see below), `RecycleBinDao`, and several other
  active DAOs.
- `core/.../data/local/RecentFilesDatabase.kt` and
  `app/.../data/local/RecentFilesDatabase.kt` are separate, **not** the one above —
  don't assume the "obvious" name is the active one.

## Duplicate recent-files repositories

- **Active:** `com.propdf.core.data.database` / `com.propdf.core.data.repository`
  `RecentFileRepository` (singular) — Hilt-bound, backed by `ProPDFDatabase`. Has
  `clearRecentOnly()` (preserves favorites/pinned), favorite toggling, and recycle-bin
  insertion on delete.
- **Dormant:** `com.propdf.core.data.local.RecentFilesRepositoryImpl` (plural) — not
  Hilt-bound, backed by a different table. Has its own correct
  `clearRecentOnly()`/`clearAll()` split that was, at one point, mistakenly believed to
  be missing entirely from the active class (it wasn't missing, it just needed to be
  added to the *active* one — see `docs/implementation-report.md` changelog).

## Duplicate PDF viewer implementations

- **Active:** `com.propdf.viewer.ui.IntegratedPDFViewerScreen` +
  `com.propdf.viewer.ui.PDFViewerViewModel`. Manages its own `PdfRenderer` directly,
  correct try/finally page-closing throughout, mutex-shared with the tile renderer.
- **Dormant:**
  - `com.propdf.editor.ui.viewer.*` (app module) — an older XML-era duplicate. Has,
    among other things, three empty `IconButton(onClick = {})` calls that would look
    like bugs worth fixing if you didn't know this package has no nav route.
  - `com.propdf.viewer.presentation.ViewerViewModel` and `PremiumViewerViewModel` —
    consume the `PdfViewerRepository` interface, whose only Hilt-bound implementation
    (`viewer/.../data/repository/PdfViewerRepositoryImpl`) has a real bug (its
    `renderPage()` only calls `page.close()` on the success path, not in `finally` —
    a genuine `PdfRenderer.Page` leak on exception). This bug has been **found and
    deliberately left unpatched twice** because neither consumer is reachable — if you
    ever wire either of these ViewModels into `AppNavigation.kt`, fix this leak in the
    same change.

## Duplicate signature implementations

- **Active:** the legacy `ApplySignatureActivity` / `SignatureManagerActivity` /
  `SignatureVerificationActivity` stack (manifest-registered), launched via `Intent`
  from `SecurityHubScreen`'s "Digital Sign" / "Verify Sign" actions. Real
  BouncyCastle/PKCS7 signing and verification, X.509 cert handling, P12 import,
  drawn/typed/image signature capture, full history.
- **Previously wrong:** `SecurityHubScreen`'s Sign action used to route to the
  annotation module's plain freehand-drawing pen tool as a stand-in. Fixed to launch
  the real Activities above instead.

## Other confirmed-dormant clusters (leave alone unless deliberately migrating)

- `scanner/.../processing/{EdgeDetector,PerspectiveCorrector,ScanModeDetector}.kt` —
  zero live callers outside their own Hilt `@Provides`, confirmed by grep. The
  scanner's real edge-detection/enhancement path goes through
  `DocumentScannerEngine` and `ImageEnhancer` instead.
- `security/.../redaction/RedactionEngine.kt` — superseded by `SecurityRepository`'s
  real redaction implementation, which `RedactionScreen`/`RedactionViewModel` actually
  use.
- `:ads` module — included in `settings.gradle`, **not** a dependency of `:app`. Not
  part of the built app.

## How to use this file

- Before spending effort on a bug in a class, grep `AppNavigation.kt` (and any
  Activities it can launch via Intent) for a path to that class. If there isn't one,
  either (a) leave it alone and note it here if it's a new finding, or (b) if the task
  genuinely requires wiring it in, do that as an explicit, separate step — not a side
  effect of "fixing" the dormant code first.
- When you do wire a previously-dormant class into the active app, **delete its row
  from the dormant column above** (or move it) so this file stays accurate.
- When you find a *new* duplicate pair, add a row. The value of this file degrades
  quickly if it isn't kept current — an out-of-date registry is worse than none,
  because it gets trusted.
