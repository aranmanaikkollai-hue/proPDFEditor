# ProPDF Editor

**Free Android PDF Editor -- All Premium Features, No Ads, No Watermarks**

[![Platform](https://img.shields.io/badge/Platform-Android%205.0%2B-green.svg)](https://android.com)
[![Version](https://img.shields.io/badge/Version-3.0-blue.svg)](https://github.com/aranmanaikkollai-hue/proPDFEditor)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Build](https://img.shields.io/badge/Build-Codemagic-orange.svg)](https://codemagic.io)

---

## Features

### PDF Viewer
- Opens PDFs from any source: Gmail, WhatsApp, Google Drive, file managers
- Dual renderer: Android PdfRenderer (fast) + PDFBox (universal fallback)
- Day / Night mode
- Share any PDF

### Annotations
- **Freehand pen** with smooth bezier curves, adjustable size and color
- **Highlight** text with adjustable width
- **Text notes** -- type text, then tap page to place it
- **Rectangle shapes**
- **Eraser** tool
- **Undo / Redo** per page
- Save annotated PDF to Downloads

### Text Extraction
- Extract and copy all text from any PDF
- Share extracted text
- Handles multi-page text extraction

### PDF Tools
| Tool | Description |
|------|-------------|
| Merge | Combine multiple PDFs into one |
| Split | Extract a page range to new PDF |
| Compress | 3 quality levels (structure-level compression) |
| Extract Pages | Save specific pages as a new PDF |
| Password Protect | AES-256 encryption |
| Remove Password | Decrypt with owner password |
| Watermark | Add diagonal text watermark with live preview |
| Rotate | 90/180/270 degrees with visual preview |
| Delete Pages | Remove specific page numbers |
| Page Numbers | Add "Page X of Y" with live format preview |
| Header/Footer | Add custom text with live preview |
| Images to PDF | Convert JPG/PNG files to PDF |
| PDF to Images | Export each page as a JPG file |

### Document Scanner
- CameraX live camera preview
- 4 scan modes: Auto-enhance, Color, Grayscale, Black & White
- Gallery import
- Flash toggle
- Saves scanned PDF to Downloads

### File Management
- All output files saved to public Downloads folder
- Custom filename dialog before every save
- Recent files list (last 20 files with date and size)
- Share any PDF via any app

---

## Building the App

### Requirements
- GitHub account (free)
- Codemagic account (free tier -- 500 min/month)
- Android device (Android 5.0+)

### Steps
1. Fork or upload all files to a GitHub repository
2. Connect repository to Codemagic (codemagic.io)
3. Start build -- takes 15-25 minutes
4. Download APK from Artifacts tab
5. Install on Android device

See `codemagic.yaml` for the complete build pipeline.

---

## Technical Stack

| Component | Library | Version |
|-----------|---------|---------|
| Language | Kotlin | 1.9.22 |
| Min SDK | Android 5.0 | API 21 |
| Target SDK | Android 14 | API 34 |
| PDF View (primary) | Android PdfRenderer | Built-in |
| PDF View (fallback) | PDFBox Android | 2.0.27 |
| PDF Editing | iText 7 Community | 7.2.5 |
| Camera | CameraX | 1.3.1 |
| OCR | Google ML Kit | 16.0.0 |
| DI | Hilt/Dagger | 2.50 |
| Database | Room | 2.6.1 |
| Async | Kotlin Coroutines | 1.7.3 |

---

## File Structure

All source files live in the **repository root** (flat structure).
`codemagic.yaml` moves them to correct Android paths during build.

```
repository-root/
+-- AndroidManifest.xml
+-- app_build.gradle
+-- build.gradle
+-- settings.gradle
+-- gradle.properties
+-- gradle-wrapper.properties
+-- gradlew
+-- codemagic.yaml
+-- setup_project.py
+-- activity_main.xml
+-- bottom_nav_menu.xml
+-- file_paths.xml
+-- proguard-rules.pro
+-- MainActivity.kt
+-- ProPDFApp.kt
+-- AppModule.kt
+-- FileHelper.kt
+-- RecentFilesDatabase.kt
+-- OcrManager.kt
+-- ScannerProcessor.kt
+-- ViewerActivity.kt
+-- AnnotationCanvasView.kt
+-- AnnotatedPageView.kt
+-- PdfPageAdapter.kt
+-- ToolsActivity.kt
+-- DocumentScannerActivity.kt
+-- PdfOperationsManager.kt
+-- README.md
+-- LICENSE
+-- PrivacyPolicy.md
```

---

## Known Limitations

- Annotation coordinates use screen-pixel-to-PDF-point conversion. On non-standard page sizes the position may be slightly off.
- Compress tool works at PDF structure level. Already-compressed PDFs (e.g., scanned with JPEG images) will show 0% reduction.
- Very large PDFs (200+ pages) load all bitmaps -- may use significant RAM.
- Text extraction requires PDFs with embedded text. Scanned image-only PDFs return no text.
- Digital signatures are not preserved when editing.

---

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE) for details.

**Third-party libraries:**
- iText 7 Community: AGPL-3.0 (free for open source use)
- PDFBox Android: Apache 2.0
- Google ML Kit: Google APIs Terms of Service

---

## Privacy

ProPDF Editor does not collect, store, or transmit any personal data.
All processing happens on-device. See [PrivacyPolicy.md](PrivacyPolicy.md) for details.
