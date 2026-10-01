# iText active-path inventory

Audit date: 2026-10-01.  This inventory was produced with `rg -n -i
'com\\.itextpdf|\\bitext\\b'` across tracked sources and Gradle files.  `PdfDocument`
alone is deliberately not classified as iText because Android and PDFBox both use that
generic name.

| File | Module | iText API | Function | Caller | Active? | Replacement | Migration phase |
|---|---|---|---|---|---|---|---|
| `editor/.../PdfFormEngine.kt` | editor | AcroForm/fields/canvas | read, create, fill, flatten forms | forms repository/view model | ACTIVE | PDAcroForm/PDField | Forms |
| `app/.../PdfOperationsRepositoryImpl.kt` | app | kernel/layout/merger | page editor and batch operations | navigation → view model → repository | ACTIVE | PDFBox | Page editor |
| `app/.../PdfOperationsManager.kt` | app | kernel/layout/merger | legacy operational facade | workers/facades | ACTIVE | PDFBox | Page editor/batch |
| `security/.../SecurityRepository.kt` | security | kernel/forms/layout | password, metadata, sanitization, redaction | SecurityViewModel | ACTIVE | PDFBox; redaction requires separate safe design | Security |
| `security/.../EncryptionManager.kt` | security | kernel | encrypt/decrypt utility | security callers | ACTIVE | StandardProtectionPolicy | Security |
| `security/.../SecurityValidator.kt` | security | reader | password validation | security callers | ACTIVE | PDDocument.load | Security |
| `security/.../WatermarkEngine.kt` | security | canvas/layout | text/image/tiled watermark | security UI | ACTIVE | PDFBox (migrated in this change) | Watermark |
| `security/.../RedactionEngine.kt` | security | canvas/parser | visual/text redaction | redaction UI | ACTIVE | BLOCKED: secure content removal | Redaction |
| `core/.../PdfSignatureEngine.kt` | core | PdfSigner/signatures | visual and CMS signatures | signature UI/DI | ACTIVE | PDFBox signing + CMS | Signatures |
| `scanner/.../PdfCreator.kt` | scanner | layout/image | new scanned PDF creation | scanner export | ACTIVE | Android PdfDocument (migrated) | Creation |
| `app/.../TextConverter.kt` | app | layout | new text PDF creation | converter UI | ACTIVE | Android PdfDocument | Creation |
| `app/.../ImageConverter.kt` | app | layout/image | image-to-PDF | converter UI | ACTIVE | Android PdfDocument/PDFBox | Creation |
| `app/.../MarkdownConverter.kt` | app | layout | Markdown-to-PDF | converter UI | ACTIVE | PDFBox/native layout implementation | Creation |
| `app/.../PdfConverter.kt` | app | parser | PDF text extraction | converter UI | ACTIVE | PDFTextStripper (migrated) | Conversion |
| `security/.../EncryptDocumentUseCase.kt` and fragments | security | EncryptionConstants | permission bit constants | legacy fragment / Compose use case | ACTIVE | local PDF permission constants | Security |
| `docs/implementation-report.md`, `ARCHITECTURE_DIAGRAM.md`, comments | root | prose | historical references | none | COMMENT/DOCUMENTATION | update after migration | Final cleanup |
| Gradle iText declarations in app/core/editor/scanner/security | Gradle | dependencies | compile/runtime dependency | module builds | ACTIVE | remove only after source paths migrate | Dependency removal |

No occurrence found in test sources.  No occurrence was classified dormant merely because
it lacks an adjacent caller; the active classifications above are based on repository,
worker, UI, and DI references found in the audit.
