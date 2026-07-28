# ProPDF Editor Implementation Audit

Date: 2026-07-28

## Scope inspected

This audit reviewed the Gradle project graph, app manifest, startup classes, main Compose navigation, home entry points, file/search/tools screens, viewer entry point, scanner activity registration, module manifests, and repository/module layout.

## Implemented features found

- Hilt application startup exists in `ProPDFApp` with PDFBox initialization, crash guard, GPU optimization, theme restore, bitmap caches, and WorkManager configuration.
- Compose-based `ui.main.MainActivity` exists as the launcher shell with splash screen, theme collection, SAF open-document picker, and handling for `ACTION_VIEW` PDF intents.
- A legacy XML `MainActivity` and a modern Compose `ui.main.MainActivity` both exist, indicating duplicate main-entry implementations.
- `ViewerActivity` provides an activity-based PDF viewer with renderer, search, annotation state, PDF operations integration, OCR manager injection, cache integration, and explicit `start` helper.
- File, search, settings, tools, scanner, conversion, OCR, forms, security, recent, favorites, recycle bin, and document manager UI classes exist.
- Core repositories and databases exist for recent files, OCR jobs, compression, search, forms, document metadata, collections, tags, signatures, storage access, sync, and NAS.
- Android Storage Access Framework helper/repository code exists in core/storage modules.
- CameraX dependencies and scanner activities exist.
- ML Kit on-device text recognition dependency and OCR workers/managers exist.

## Partially implemented or disconnected features

- The manifest pointed at a non-existent application class (`.ProPDFApplication`) while the Hilt application class is `ProPDFApp`; this prevents correct startup and dependency injection.
- The Home screen's primary add/open PDF FAB was wired to an empty lambda, so the visible Open/Add action was decorative.
- `MainViewModel.openPdf` recorded selected/opened documents and set `launchViewerUri`, but `ui.main.MainActivity` cleared that state without launching the viewer, so SAF selections did not actually open in the PDF viewer.
- Main bottom-navigation routes for Files, Scanner, and Tools were placeholder comments; these tabs were reachable but empty.
- Home action routes such as `search`, `recent`, and `folders` were referenced but not registered in the main navigation graph, which would crash when tapped.
- Scanner navigation did not launch the registered scanner activity from the Compose navigation graph.
- The OpenDocument picker was limited to `application/pdf`; the product requirements call for PDFs, images, Office documents, text, and HTML to be selectable.

## Duplicate implementations and dead-code risks

- Two main activity implementations exist: `com.propdf.editor.MainActivity` (legacy XML) and `com.propdf.editor.ui.main.MainActivity` (Compose launcher). The manifest uses the Compose launcher; the legacy activity is currently not externally reachable.
- Multiple database classes named `AppDatabase` exist under app and core package trees. This raises DI ambiguity and migration-risk concerns.
- Forms implementation appears duplicated between app and editor module package paths.
- Some included modules in `settings.gradle` are not all consumed by `app/build.gradle` (for example storage/sync/nas are present in the repository but not currently app dependencies), so features in those modules may be disconnected from the runnable app.

## Broken navigation found and repaired

- Home Open/Add PDF now launches Android's Storage Access Framework through the existing activity-result launcher.
- SAF selections now start `ViewerActivity` immediately after `MainViewModel` persists the recent-file record.
- Files tab now displays the existing `FilesScreen` instead of a blank route.
- Tools tab now displays the existing `ToolsScreen` instead of a blank route.
- Scanner tab now launches the existing `DocumentScannerActivity` and returns the navigation shell to Home.
- Search, Recent, and Folders routes referenced by Home now resolve to real screens rather than missing destinations.

## Remaining production-readiness risks

- Full compile verification is blocked in this container because Gradle 8.2 cannot run on the installed Java 25 runtime (`Unsupported class file major version 69`). A Java 17 runtime or a Gradle/AGP upgrade is required before exhaustive build validation can continue.
- Manual Android-device verification was not possible in this non-interactive container.
- Many advanced requested capabilities (full Office conversion, certificate-backed signing, permanent redaction, cloud-provider sync, OCR language-pack management, SD/USB OTG validation, and every PDF editing operation) require deeper feature-by-feature validation once the project can compile and run.
- Several UI actions inside feature screens may still navigate to unregistered tool-specific routes; those need the next audit pass after build validation is unblocked.
