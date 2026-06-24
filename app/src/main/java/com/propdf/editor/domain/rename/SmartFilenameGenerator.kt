package com.propdf.editor.domain.rename

import com.propdf.core.domain.model.PdfDocument
import com.propdf.editor.domain.rename.DocumentTypeDetector.DocumentType // Add this import
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

class SmartFilenameGenerator @Inject constructor(
    private val documentTypeDetector: DocumentTypeDetector
) {
    fun generateFilename(document: PdfDocument, ocrText: String? = null): String {
        val baseName = document.name.substringBeforeLast(".")
        val extension = document.name.substringAfterLast(".", "pdf")
        
        val detectionResult = ocrText?.let { text ->
            documentTypeDetector.detect(text, document.name)
        }
        
        return when (detectionResult?.type) {
            DocumentType.INVOICE -> generateInvoiceFilename(baseName, detectionResult)
            DocumentType.RECEIPT -> generateReceiptFilename(baseName, detectionResult)
            DocumentType.CONTRACT -> generateContractFilename(baseName, detectionResult)
            DocumentType.REPORT -> generateReportFilename(baseName, detectionResult)
            DocumentType.ID_CARD -> "ID_${baseName}_$extension"
            DocumentType.PASSPORT -> "Passport_${baseName}_$extension"
            DocumentType.CERTIFICATE -> "Certificate_${baseName}_$extension"
            DocumentType.NOTE -> generateNoteFilename(baseName, detectionResult)
            else -> "${baseName}_${getTimestamp()}.$extension"
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
    
    private fun getTimestamp(): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
    }
}
