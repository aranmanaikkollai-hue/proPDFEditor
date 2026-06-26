package com.propdf.editor.domain.rename

import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.domain.rename.DocumentTypeDetector.DocumentType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class SmartFilenameGenerator @Inject constructor(
    private val documentTypeDetector: DocumentTypeDetector
) {

    data class Suggestion(
        val suggestedName: String,
        val reason: String
    )

    /** Called by AutoRenameDocumentUseCase */
    fun generate(
        detectionResult: DocumentTypeDetector.DetectionResult,
        originalFileName: String,
        existingNames: Set<String> = emptySet()
    ): Suggestion {
        val baseName = originalFileName.substringBeforeLast(".")
        val extension = originalFileName.substringAfterLast(".", "pdf")

        val rawName = when (detectionResult.type) {
            DocumentType.INVOICE -> generateInvoiceFilename(baseName, detectionResult)
            DocumentType.RECEIPT -> generateReceiptFilename(baseName, detectionResult)
            DocumentType.CONTRACT -> generateContractFilename(baseName, detectionResult)
            DocumentType.REPORT -> generateReportFilename(baseName, detectionResult)
            DocumentType.ID_CARD -> "ID_${baseName}.$extension"
            DocumentType.PASSPORT -> "Passport_${baseName}.$extension"
            DocumentType.CERTIFICATE -> "Certificate_${baseName}.$extension"
            DocumentType.NOTE -> generateNoteFilename(baseName, detectionResult)
            else -> "${baseName}_${getTimestamp()}.$extension"
        }

        val finalName = deduplicateName(rawName, existingNames)
        val reason = "Detected as ${detectionResult.type.name.lowercase()}" +
            " (confidence: ${"%.0f".format(detectionResult.confidence * 100)}%)"

        return Suggestion(suggestedName = finalName, reason = reason)
    }

    /** Convenience for callers that have a core PdfDocument */
    fun generateFilename(document: PdfDocument, ocrText: String? = null): String {
        val detectionResult = ocrText?.let { text ->
            documentTypeDetector.detect(text, document.displayName)
        } ?: DocumentTypeDetector.DetectionResult(
            type = DocumentType.UNKNOWN,
            confidence = 0f,
            extractedTitle = null,
            extractedDate = null,
            extractedAmount = null
        )
        return generate(detectionResult, document.displayName).suggestedName
    }

    private fun deduplicateName(name: String, existing: Set<String>): String {
        if (!existing.contains(name)) return name
        val base = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "pdf")
        var counter = 1
        while (true) {
            val candidate = "${base}_$counter.$ext"
            if (!existing.contains(candidate)) return candidate
            counter++
        }
    }

    private fun generateInvoiceFilename(baseName: String, result: DocumentTypeDetector.DetectionResult): String {
        val title = result.extractedTitle?.let { "_${it.replace(" ", "_")}" } ?: ""
        val date = result.extractedDate?.replace("/", "-") ?: getTimestamp()
        val amount = result.extractedAmount?.let { "_$it" } ?: ""
        return "Invoice${title}_${date}${amount}.pdf"
    }

    private fun generateReceiptFilename(baseName: String, result: DocumentTypeDetector.DetectionResult): String {
        val date = result.extractedDate?.replace("/", "-") ?: getTimestamp()
        val amount = result.extractedAmount?.let { "_$it" } ?: ""
        return "Receipt_${date}${amount}.pdf"
    }

    private fun generateContractFilename(baseName: String, result: DocumentTypeDetector.DetectionResult): String {
        val title = result.extractedTitle?.let { "_${it.replace(" ", "_")}" } ?: ""
        val date = result.extractedDate?.replace("/", "-") ?: getTimestamp()
        return "Contract${title}_${date}.pdf"
    }

    private fun generateReportFilename(baseName: String, result: DocumentTypeDetector.DetectionResult): String {
        val title = result.extractedTitle?.let { "_${it.replace(" ", "_")}" } ?: ""
        val date = result.extractedDate?.replace("/", "-") ?: getTimestamp()
        return "Report${title}_${date}.pdf"
    }

    private fun generateNoteFilename(baseName: String, result: DocumentTypeDetector.DetectionResult): String {
        val title = result.extractedTitle?.let { "_${it.replace(" ", "_")}" } ?: ""
        val date = result.extractedDate?.replace("/", "-") ?: getTimestamp()
        return "Note${title}_${date}.pdf"
    }

    private fun getTimestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}
