> **HISTORICAL SNAPSHOT - SUPERSEDED.** This file describes an earlier source tree (the previous audit used archive `proPDFEditor-main__6__1.zip`, fingerprint `6d8bfe0f...04e7`; some files predate even that) and is kept as evidence, unedited below this banner. Statements here about iText in production code, run results and database state may no longer be true. Current status: see `docs/ARCHITECTURE_CURRENT_STATE.md`.

# Active iText call graph

The audit found these production paths:

* Page editor: `AppNavigation` → `PageEditorViewModel` → `PdfOperationWorker` /
  `PdfOperationsManager` → `PdfOperationsRepositoryImpl` → iText kernel/layout.
* Forms: form UI → `PdfFormViewModel` → form repository → `PdfFormEngine` → iText
  AcroForm APIs.
* Security: Security UI/ViewModel → `SecurityRepository` → iText encryption,
  metadata, sanitization and redaction paths.  Legacy fragments also construct iText
  permission bitmasks.
* Signing: signature UI/DI → `PdfSignatureEngine` → iText `PdfSigner` and CMS
  verification APIs.
* Creation/conversion: scanner export → `PdfCreator`; converter UI → text/image/
  Markdown/PDF converters.
* Watermark: security UI → `WatermarkEngine`; this implementation now uses PDFBox.

WorkManager and Hilt bindings were included in this trace.  The page-editor path is
active and must not be classified as dormant.
