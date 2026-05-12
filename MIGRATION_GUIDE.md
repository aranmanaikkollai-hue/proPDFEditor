# ProPDF Editor v3.0 — Migration Guide

## Overview

This refactor transforms the monolithic proPDFEditor app into a **production-grade modular architecture** following Clean Architecture + MVVM principles.

---

## New Folder Structure

```
proPDFEditor/
├── app/                              # Application module (thin)
│   ├── src/main/java/com/propdf/editor/
│   │   ├── ProPDFApp.kt              # Hilt Application class (unchanged)
│   │   ├── di/
│   │   │   └── RepositoryModule.kt   # Binds all repo implementations
│   │   ├── ui/
│   │   │   ├── main/
│   │   │   │   ├── MainActivity.kt   # Refactored: MVVM + StateFlow
│   │   │   │   └── MainViewModel.kt  # NEW: All business logic here
│   │   │   ├── viewer/
│   │   │   │   └── ViewerActivity.kt # Refactored: delegates to ViewerViewModel
│   │   │   ├── tools/
│   │   │   │   └── ToolsActivity.kt  # Unchanged (UI-only)
│   │   │   └── scanner/
│   │   │       └── DocumentScannerActivity.kt # Unchanged (UI-only)
│   │   └── utils/
│   │       └── FileHelper.kt         # Unchanged
│   └── build.gradle.kts
│
├── core/                             # Domain + shared infrastructure
│   ├── src/main/java/com/propdf/core/
│   │   ├── domain/
│   │   │   ├── model/                # Immutable data classes
│   │   │   │   ├── PdfDocument.kt
│   │   │   │   ├── RecentFile.kt
│   │   │   │   ├── AnnotationModels.kt
│   │   │   │   ├── ScannerModels.kt
│   │   │   │   └── ToolModels.kt
│   │   │   ├── repository/           # Repository INTERFACES
│   │   │   │   ├── RecentFilesRepository.kt
│   │   │   │   ├── PdfOperationsRepository.kt
│   │   │   │   ├── OcrRepository.kt
│   │   │   │   ├── PdfViewerRepository.kt
│   │   │   │   ├── ScannerRepository.kt
│   │   │   │   └── BookmarkRepository.kt
│   │   │   ├── usecase/              # Base UseCase classes
│   │   │   │   └── BaseUseCase.kt
│   │   │   ├── result/               # AppResult<T> wrapper
│   │   │   │   └── AppResult.kt
│   │   │   ├── dispatcher/           # DispatcherProvider (testable)
│   │   │   │   └── DispatcherProvider.kt
│   │   │   └── logger/               # AppLogger interface
│   │   │       └── AppLogger.kt
│   │   ├── data/
│   │   │   └── local/
│   │   │       ├── RecentFilesRepositoryImpl.kt
│   │   │       ├── RecentFilesDao.kt
│   │   │       └── RecentFilesDatabase.kt
│   │   └── di/
│   │       └── CoreModule.kt         # Binds dispatcher + logger
│   └── build.gradle.kts
│
├── viewer/                           # PDF rendering feature
│   ├── src/main/java/com/propdf/viewer/
│   │   ├── data/
│   │   │   └── repository/
│   │   │       └── PdfViewerRepositoryImpl.kt
│   │   └── presentation/
│   │       └── ViewerViewModel.kt
│   └── build.gradle.kts
│
├── editor/                           # PDF operations feature
│   ├── src/main/java/com/propdf/editor/
│   │   └── data/
│   │       └── repository/
│   │           └── PdfOperationsRepositoryImpl.kt
│   └── build.gradle.kts
│
├── annotations/                      # Annotation engine
│   ├── src/main/java/com/propdf/annotations/
│   │   └── domain/
│   │       └── usecase/
│   │           └── ExportAnnotationsUseCase.kt
│   └── build.gradle.kts
│
├── scanner/                          # Document scanning
│   ├── src/main/java/com/propdf/scanner/
│   │   └── data/
│   │       └── repository/
│   │           └── ScannerRepositoryImpl.kt
│   └── build.gradle.kts
│
├── security/                         # Encryption/decryption
│   ├── src/main/java/com/propdf/security/
│   │   └── domain/
│   │       └── usecase/
│   │           ├── EncryptPdfUseCase.kt
│   │           └── DecryptPdfUseCase.kt
│   └── build.gradle.kts
│
├── ads/                              # Ad monetization (stub)
│   ├── src/main/java/com/propdf/ads/
│   │   └── AdsManager.kt
│   └── build.gradle.kts
│
├── ocr/                              # OCR feature module
│   ├── src/main/java/com/propdf/ocr/
│   │   └── data/
│   │       └── repository/
│   │           └── OcrRepositoryImpl.kt
│   └── build.gradle.kts
│
├── gradle/
│   └── wrapper/
├── build.gradle.kts                  # Root build script
├── settings.gradle.kts               # Module declarations
├── gradle.properties               # JVM args
└── codemagic.yaml                    # CI/CD (updated)
```

---

## Migration Steps (Incremental, Buildable at Every Step)

### Phase 1: Foundation (Buildable)
1. Create `:core` module with domain models + interfaces
2. Move `RecentFilesDatabase.kt` + DAO into `:core`
3. Create `AppResult<T>`, `DispatcherProvider`, `AppLogger`
4. Update root `settings.gradle.kts` to include `:core`
5. **Verify build passes**

### Phase 2: Repository Layer (Buildable)
1. Create `:viewer` module, implement `PdfViewerRepositoryImpl`
2. Create `:editor` module, implement `PdfOperationsRepositoryImpl`
3. Create `:ocr` module, implement `OcrRepositoryImpl`
4. Create `:scanner` module, implement `ScannerRepositoryImpl`
5. Wire repositories in `RepositoryModule.kt`
6. **Verify build passes**

### Phase 3: ViewModels (Buildable)
1. Create `MainViewModel` in `:app`
2. Create `ViewerViewModel` in `:viewer`
3. Refactor `MainActivity` to use `MainViewModel` + `StateFlow`
4. Refactor `ViewerActivity` to use `ViewerViewModel` + `StateFlow`
5. **Verify build passes**

### Phase 4: Feature Modules (Buildable)
1. Create `:annotations` with `ExportAnnotationsUseCase`
2. Create `:security` with `EncryptPdfUseCase` + `DecryptPdfUseCase`
3. Create `:ads` with `AdsManager` stub
4. **Verify build passes**

### Phase 5: Cleanup (Buildable)
1. Delete `PdfPageAdapter.kt` (orphan code)
2. Delete `RecentFilesDao.kt` (duplicate)
3. Delete old `AppModule.kt`
4. Delete old `PdfOperationsManager.kt` (replaced by repository)
5. Delete old `OcrManager.kt` (replaced by repository)
6. **Verify build passes**

---

## What Changed vs. What Stayed

### Unchanged (Zero Regression)
| File | Status | Reason |
|------|--------|--------|
| `ProPDFApp.kt` | Unchanged | Already Hilt-ready |
| `FileHelper.kt` | Unchanged | Pure utility, no DI needed |
| `ToolsActivity.kt` | Unchanged | UI-only, no business logic |
| `DocumentScannerActivity.kt` | Unchanged | UI-only, delegates to ViewModel |
| `AnnotationCanvasView.kt` | Unchanged | Custom view, framework code |
| `AnnotatedPageView.kt` | Unchanged | Custom view, framework code |
| `AndroidManifest.xml` | Unchanged | Already correct |
| `activity_main.xml` | Unchanged | Layout files untouched |
| `activity_viewer_simple.xml` | Unchanged | Layout files untouched |
| `bottom_nav_menu.xml` | Unchanged | Layout files untouched |
| `file_paths.xml` | Unchanged | Already correct |
| `gradlew` / `gradlew.bat` | Unchanged | Gradle wrapper |
| `gradle-wrapper.properties` | Unchanged | Already correct |
| `PrivacyPolicy.md` | Unchanged | Documentation |
| `LICENSE` | Unchanged | Documentation |
| `README.md` | Updated | Architecture docs added |

### Deleted (Safe Removal)
| File | Replacement |
|------|-------------|
| `PdfPageAdapter.kt` | None (orphan code, ViewerActivity has own rendering) |
| `RecentFilesDao.kt` | Merged into `RecentFilesDatabase.kt` in `:core` |
| `AppModule.kt` | Replaced by `RepositoryModule.kt` + `CoreModule.kt` |
| `PdfOperationsManager.kt` | Replaced by `PdfOperationsRepositoryImpl` in `:editor` |
| `OcrManager.kt` | Replaced by `OcrRepositoryImpl` in `:ocr` |

### Refactored (Behavior Preserved)
| File | Change |
|------|--------|
| `MainActivity.kt` | All business logic → `MainViewModel`. UI is purely reactive. |
| `ViewerActivity.kt` | All rendering/search → `ViewerViewModel`. UI is purely reactive. |
| `build.gradle.kts` (app) | Added feature module dependencies |
| `build.gradle` (root) | Updated to `build.gradle.kts` with module plugins |
| `settings.gradle.kts` | Added all feature modules |

---

## Dependency Graph

```
:app depends on :core, :viewer, :editor, :annotations, :scanner, :security, :ads, :ocr
:viewer depends on :core
:editor depends on :core
:annotations depends on :core
:scanner depends on :core
:security depends on :core
:ads depends on :core
:ocr depends on :core
:core depends on NOTHING (foundation)
```

---

## Codemagic CI/CD Changes

The `codemagic.yaml` requires no changes — the build commands remain:
```yaml
- ./gradlew assembleDebug
```

Gradle automatically resolves all module dependencies.

---

## Testing Strategy

| Layer | Test Type | Framework |
|-------|-----------|-----------|
| Domain (UseCases) | Unit tests | JUnit + Mockito |
| Repository | Integration tests | Room in-memory + iText7 |
| ViewModel | Unit tests | kotlinx-coroutines-test |
| UI | Instrumented | Espresso |

All repositories use `DispatcherProvider` interface — inject `TestDispatcher` for synchronous tests.

---

## Known Limitations (Preserved from v2.0)

| Feature | Status | Notes |
|---------|--------|-------|
| Annotation save (native vector) | Stub | Burns as bitmap overlay only |
| Image insert display | Stored but not rendered | `AnnotationCanvasView` lacks IMAGE tool |
| Watermark | Stub | UI exists, not wired |
| Delete page | Stub | UI exists, not wired |
| Digital signatures | Not started | Requires BouncyCastle + iText7 |
| Google Drive sync | Not started | No Drive API |
| Select & copy text overlay | Stub | No selectable text overlay |
| PDF page reorder | Not started | No iText7 page manipulation |
| ML Kit OCR | Partial | Shows info dialog only |

These limitations are **documented and preserved** — the refactor does not attempt to implement them.
