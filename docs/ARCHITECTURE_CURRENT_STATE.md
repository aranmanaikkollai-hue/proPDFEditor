# ARCHITECTURE - CURRENT STATE

Verified by source search of `proPDFEditor-main1.zip` (not by running the app). Legend: ACTIVE (reachable path found), DORMANT (compiled, no registered/navigable entry), REMOVED, UNVERIFIED (not traced), NOT IMPLEMENTED.

## PDF libraries in production
PDFBox-Android 2.0.27.0 (`:app :core :security :viewer :annotations :scanner :editor`); Bouncy Castle 1.72 (`:core`, `:security`); `android.graphics.pdf.PdfRenderer` (viewer); `android.graphics.pdf.PdfDocument` (scanner output); ML Kit, OpenCV, CameraX. **iText: no production import, no production dependency.** Only `app` `androidTestImplementation` (password-fixture generator). Statements in older reports that signing, forms, security or compression still use iText are **superseded**.

## Verified chains (UI -> ViewModel/Activity -> repository/engine -> library)
| Feature | Chain (files found) | Library | Status |
|---|---|---|---|
| Crypto signing / verification | `SecurityHubScreen` -> legacy `ApplySignatureActivity` / `SignatureViewModel` -> `PdfSignatureEngine` -> `PdfBoxCmsSigner`, `PdfBoxSignatureVerifier` | PDFBox + BC | ACTIVE; also `BatchSignatureWorker` |
| Visual signature | `PdfSignatureEngine` -> `PdfBoxVisualSignatureEngine` | PDFBox | ACTIVE |
| Passwords / permissions | `PdfOperationsRepositoryImpl`, `SecurityRepository`, `EncryptionManager` -> `PdfBoxPasswordEngine` | PDFBox | ACTIVE |
| Redaction | `RedactionViewModel` -> `SecurityRepository` -> `RedactionEngine` -> `PdfBoxRedactionEngine` | PDFBox (rasterise + verify) | ACTIVE |
| Page editor | `PdfOperationsManager`/`PdfOperationsRepositoryImpl` -> `PdfBoxPageEngine` | PDFBox | ACTIVE |
| Compression | `PageEditorCompression` -> `PdfBoxCompressionRepository` | PDFBox | ACTIVE |
| Forms | `FormScreen`/`FormsHostViewModel` -> `PdfFormRepositoryImpl`/`FlattenFormWorker` -> `PdfFormEngine` | PDFBox | ACTIVE |
| Viewer | `IntegratedPDFViewerScreen`/`PDFViewerViewModel` | `PdfRenderer` | ACTIVE |
| Scanner | CameraX + OpenCV; `DocumentScannerEngine`, `PdfCreator` | `android.graphics.pdf` | ACTIVE |
| Share / SAF | `ShareStaging` -> narrow `file_paths.xml`; `SafRepositoryImpl` | FileProvider/SAF | ACTIVE, unchanged |
| `LegacyITextPdfOperations` | no file found | - | REMOVED |
| iText converters (Image/Markdown/Text) | not present; `ConversionRepository` comments say removed | - | REMOVED |

## Dormant / duplicate (preserved; nothing deleted)
Premium viewer cluster; conversion workspace (`conversion_tasks_database`); `:share :backup :sync :nas :ads :ocr`; legacy `WorkManagerInitializer` (two copies, unregistered); legacy `com.propdf.editor.receiver.BootReceiver`; workers `DocumentScanWorker`, duplicate scan, smart-folder refresh, backfill, storage analysis (not scheduled by any registered path); duplicate databases (see DB review); several merge implementations; Note: `RecycleBinCleanupWorker` (`com.propdfeditor.batch`) is ACTIVE, scheduled only by the registered `com.propdfeditor.receiver.BootReceiver` (after reboot).

## Implemented but unverified / not implemented
Implemented, unverified: everything above (no build or test this round); the lookup-safe scan (latent until the scan worker is scheduled). Not implemented: Room migration tests, schema history, third-party notices, project licence, MediaStore worker integration test, real-device test results.

## Follow-up round
Recycle bin: authoritative store is `:core` `ProPDFDatabase.pdf_documents`; the `:app` `AppDatabase.pdf_documents` is a separate table with no active soft-delete writer (see `docs/RECYCLE_BIN_DATA_FLOW.md`). `RecycleBinCleanupWorker` (ACTIVE, after reboot only) now purges the `:core` table (implemented, unverified).
