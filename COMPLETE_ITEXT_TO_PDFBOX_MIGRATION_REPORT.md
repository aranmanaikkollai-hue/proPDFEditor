# Complete iText to PDFBox migration report

## Status

**NOT COMPLETE.** This report is intentionally interim: the repository-wide audit
identified active iText paths that must not be removed blindly.

## Initial inventory and active paths

See `ITEXT_ACTIVE_PATH_INVENTORY.md` and `ITEXT_ACTIVE_CALL_GRAPH.md`.

## Migration performed

* `scanner` PDF creation changed from iText layout/image APIs to Android
  `android.graphics.pdf.PdfDocument`.  It retains page sizing, image fitting, bitmap
  cleanup, and cancellation dispatcher behavior.
* `PdfConverter.toText` changed from iText extraction to PDFBox `PDFTextStripper`.
* `WatermarkEngine` changed from iText canvas/layout to PDFBox content streams.  It
  now stages `content://` input through a cache file rather than using `Uri.path`.

## Forms, page editor, and security

Forms and page-editor repository paths remain active iText code and require a
method-by-method PDFBox migration with fixture tests.  Security encryption/permissions
also remain iText paths.  No security behavior has been weakened or bypassed.

## Signatures and redaction

Digital signatures remain iText-backed; a PDFBox CMS implementation must be proved
cryptographically valid before replacement.  The existing redaction implementations
only paint over content and are therefore not eligible to be called secure redaction;
they require a dedicated content-removal implementation.  These are stop conditions,
not candidates for superficial replacement.

## Tests/build

* NOT RUN: full Gradle build and Android tests.
* STATICALLY VERIFIED: the migrated scanner and watermark code use structured document
  and stream closure, and watermark input uses ContentResolver staging.

## Dependency and license status

iText Gradle declarations are intentionally still present because active iText source
paths remain.  Consequently dependency-tree zero-iText verification and license cleanup
are **NOT RUN**.  PDFBox Android was added to the security module for watermark work.

## Remaining risks and acceptance

The final zero-iText acceptance criteria are not met.  Do not remove iText dependencies
until forms, page editing, security, signing, redaction, creation converters, and their
tests are migrated and a final dependency scan is clean.
