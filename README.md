# ProPDF Editor v3.0

A production-grade, open-source PDF editor for Android built with **Clean Architecture + MVVM**.

## Architecture

- **Modular**: 8 feature modules (`:core`, `:viewer`, `:editor`, `:annotations`, `:scanner`, `:security`, `:ads`, `:ocr`)
- **Clean Architecture**: Domain → Data → Presentation layers
- **MVVM**: ViewModels expose `StateFlow<UiState>` to Activities
- **Dependency Injection**: Hilt with `@Binds` for repository interfaces
- **Error Handling**: Centralized `AppResult<T>` sealed class
- **Testing**: Injectable `DispatcherProvider` for coroutine testability

## Tech Stack

| Layer | Technology |
|-------|-----------|
| UI | Programmatic Views (no XML inflation overhead) |
| Architecture | MVVM + Clean Architecture |
| DI | Hilt 2.50 |
| Async | Kotlin Coroutines + StateFlow |
| PDF Engine | iText7 7.2.5 + PdfRenderer (Android) |
| Text Extraction | PDFBox-Android 2.0.27.0 |
| OCR | ML Kit Text Recognition |
| Camera | CameraX 1.3.1 |
| Database | Room 2.6.1 |
| Crypto | BouncyCastle 1.72 |

## Modules

| Module | Responsibility |
|--------|---------------|
| `:core` | Domain models, repository interfaces, base use cases, error handling |
| `:viewer` | PDF rendering, page caching, search, zoom |
| `:editor` | Merge, split, compress, rotate, watermark, page numbers |
| `:annotations` | Stroke/text annotation engine, export to PDF |
| `:scanner` | CameraX capture, edge detection, color filters, export |
| `:security` | Encryption, decryption, password validation |
| `:ads` | AdMob integration (stub for open-source) |
| `:ocr` | ML Kit text recognition, multi-page OCR |

## Building

```bash
./gradlew :app:assembleDebug
```

All modules compile automatically via Gradle dependency resolution.

## License

MIT License — see LICENSE file.
