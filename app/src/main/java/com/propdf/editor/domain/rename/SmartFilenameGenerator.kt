package com.propdf.editor.domain.rename

import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartFilenameGenerator @Inject constructor() {

    data class FilenameSuggestion(
        val suggestedName: String,
        val originalName: String,
        val confidence: Float,
        val reason: String
    )

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val shortDateFormat = SimpleDateFormat("yyyyMMdd", Locale.US)

    fun generate(
        detectionResult: DocumentTypeDetector.DetectionResult,
        originalFileName: String,
        existingNames: Set<String> = emptySet()
    ): FilenameSuggestion {
        val baseName = buildBaseName(detectionResult)
        val sanitized = sanitizeFilename(baseName)
        val uniqueName = ensureUnique(sanitized, existingNames)

        return FilenameSuggestion(
            suggestedName = "$uniqueName.pdf",
            originalName = originalFileName,
            confidence = detectionResult.confidence,
            reason = buildReason(detectionResult)
        )
    }

    private fun buildBaseName(result: DocumentTypeDetector.DetectionResult): String {
        val parts = mutableListOf<String>()

        when (result.type) {
            DocumentTypeDetector.DocumentType.INVOICE -> {
                parts.add("Invoice")
                result.extractedTitle?.let { parts.add(it) }
                result.extractedDate?.let { parts.add(formatDate(it)) }
                result.extractedAmount?.let { parts.add("${it}USD") }
            }
            DocumentTypeDetector.DocumentType.RECEIPT -> {
                parts.add("Receipt")
                result.extractedTitle?.let { parts.add(it) }
                result.extractedDate?.let { parts.add(formatDate(it)) }
            }
            DocumentTypeDetector.DocumentType.CONTRACT -> {
                parts.add("Contract")
                result.extractedTitle?.let { parts.add(it) }
                result.extractedDate?.let { parts.add(formatDate(it)) }
            }
            DocumentTypeDetector.DocumentType.NOTE -> {
                parts.add("Note")
                result.extractedTitle?.let { parts.add(it) }
                    ?: run { parts.add(dateFormat.format(Date())) }
            }
            DocumentTypeDetector.DocumentType.REPORT -> {
                parts.add("Report")
                result.extractedTitle?.let { parts.add(it) }
                result.extractedDate?.let { parts.add(formatDate(it)) }
            }
            DocumentTypeDetector.DocumentType.ID_CARD -> {
                parts.add("ID")
                result.extractedTitle?.let { parts.add(it) }
            }
            DocumentTypeDetector.DocumentType.PASSPORT -> {
                parts.add("Passport")
                result.extractedDate?.let { parts.add(formatDate(it)) }
            }
            DocumentTypeDetector.DocumentType.CERTIFICATE -> {
                parts.add("Certificate")
                result.extractedTitle?.let { parts.add(it) }
                result.extractedDate?.let { parts.add(formatDate(it)) }
            }
            DocumentTypeDetector.DocumentType.UNKNOWN -> {
                return "Document_${shortDateFormat.format(Date())}"
            }
        }
        return parts.joinToString("_")
    }

    private fun formatDate(dateStr: String): String {
        val parsers = listOf(
            SimpleDateFormat("MM/dd/yyyy", Locale.US),
            SimpleDateFormat("dd/MM/yyyy", Locale.US),
            SimpleDateFormat("yyyy-MM-dd", Locale.US),
            SimpleDateFormat("dd-MM-yyyy", Locale.US)
        )
        for (parser in parsers) {
            try { return dateFormat.format(parser.parse(dateStr)!!) } catch (e: Exception) { continue }
        }
        return dateStr.replace(Regex("[/\\.\-]"), "")
    }

    private fun sanitizeFilename(input: String): String {
        return input
            .replace(Regex("[<>:"/\\|?*]"), "_")
            .replace(Regex("\s+"), "_")
            .replace(Regex("_+"), "_")
            .replace(Regex("^_|_$"), "")
            .take(100)
    }

    private fun ensureUnique(baseName: String, existingNames: Set<String>): String {
        if (!existingNames.any { it.startsWith(baseName, ignoreCase = true) }) return baseName
        var counter = 1
        var candidate = "${baseName}_$counter"
        while (existingNames.any { it.startsWith(candidate, ignoreCase = true) }) {
            counter++
            candidate = "${baseName}_$counter"
        }
        return candidate
    }

    private fun buildReason(result: DocumentTypeDetector.DetectionResult): String {
        return when {
            result.extractedTitle != null && result.extractedDate != null ->
                "Detected ${result.type.name.lowercase()}: '${result.extractedTitle}' dated ${result.extractedDate}"
            result.extractedTitle != null ->
                "Detected ${result.type.name.lowercase()}: '${result.extractedTitle}'"
            result.extractedDate != null ->
                "Detected ${result.type.name.lowercase()} with date ${result.extractedDate}"
            else ->
                "Detected ${result.type.name.lowercase()} (low confidence)"
        }
    }
}
