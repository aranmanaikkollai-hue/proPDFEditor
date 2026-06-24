package com.propdf.editor.domain.rename

import com.propdf.core.domain.logger.AppLogger
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentTypeDetector @Inject constructor(
    private val logger: AppLogger
) {
    data class DetectionResult(
        val type: DocumentType,
        val confidence: Float,
        val extractedTitle: String?,
        val extractedDate: String?,
        val extractedAmount: String?
    )

    enum class DocumentType {
        INVOICE, RECEIPT, CONTRACT, NOTE, REPORT, 
        ID_CARD, PASSPORT, CERTIFICATE, UNKNOWN
    }

    private val patterns = mapOf(
        DocumentType.INVOICE to listOf(
            Pattern.compile("(?i)invoice\s*#?\s*[:\-]?\s*(\w+[\w\-]*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)bill\s*to\s*[:\-]?", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)total\s*amount\s*[:\-]?\s*[\$€£]?\s*([\d,]+\.?\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)payment\s*due", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)invoice\s*date\s*[:\-]?\s*(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4})", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.RECEIPT to listOf(
            Pattern.compile("(?i)receipt\s*#?\s*[:\-]?\s*(\w+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)thank\s*you\s*for\s*your\s*purchase", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)change\s*[:\-]?\s*[\$€£]?\s*([\d,]+\.?\d*)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)cashier\s*[:\-]?", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.CONTRACT to listOf(
            Pattern.compile("(?i)agreement\s*between", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)this\s*contract\s*is\s*made", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)terms\s*and\s*conditions", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)party\s*of\s*the\s*first\s*part", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)signed\s*on\s*[:\-]?\s*(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4})", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.NOTE to listOf(
            Pattern.compile("(?im)^\s*note\s*[:\-]?\s*(.+)$", Pattern.CASE_INSENSITIVE or Pattern.MULTILINE),
            Pattern.compile("(?im)^\s*title\s*[:\-]?\s*(.+)$", Pattern.CASE_INSENSITIVE or Pattern.MULTILINE),
            Pattern.compile("(?im)^\s*subject\s*[:\-]?\s*(.+)$", Pattern.CASE_INSENSITIVE or Pattern.MULTILINE),
            Pattern.compile("(?im)^\s*meeting\s*notes?\s*[:\-]?", Pattern.CASE_INSENSITIVE or Pattern.MULTILINE)
        ),
        DocumentType.REPORT to listOf(
            Pattern.compile("(?i)quarterly\s*report", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)annual\s*report", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)executive\s*summary", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)confidential\s*report", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)prepared\s*by\s*[:\-]?", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.ID_CARD to listOf(
            Pattern.compile("(?i)national\s*id", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)driver['\s]?s?\s*license", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)identification\s*card", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)date\s*of\s*birth\s*[:\-]?", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.PASSPORT to listOf(
            Pattern.compile("(?i)passport\s*no[.\s]*[:\-]?\s*(\w+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)republic\s*of\s*.+\s*passport", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)place\s*of\s*birth", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)nationality\s*[:\-]?", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.CERTIFICATE to listOf(
            Pattern.compile("(?i)certificate\s*of\s*(\w+)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)this\s*is\s*to\s*certify", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)certifies\s*that", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)issued\s*on\s*[:\-]?\s*(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4})", Pattern.CASE_INSENSITIVE)
        )
    )

    private val titleExtractors = mapOf(
        DocumentType.INVOICE to listOf(
            Pattern.compile("(?i)invoice\s*(?:to|from)\s*[:\-]?\s*([A-Z][A-Za-z\s&]{2,50})"),
            Pattern.compile("(?i)([A-Z][A-Za-z\s&]{2,50})\s*invoice", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.RECEIPT to listOf(
            Pattern.compile("(?i)([A-Z][A-Za-z\s&]{2,50})\s*receipt", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)from\s*[:\-]?\s*([A-Z][A-Za-z\s&]{2,50})")
        ),
        DocumentType.CONTRACT to listOf(
            Pattern.compile("(?i)between\s+([A-Z][A-Za-z\s&]{2,50})\s+and", Pattern.CASE_INSENSITIVE)
        ),
        DocumentType.NOTE to listOf(
            Pattern.compile("(?im)^\s*(?:note|title|subject)\s*[:\-]?\s*(.+)$")
        ),
        DocumentType.REPORT to listOf(
            Pattern.compile("(?i)([A-Z][A-Za-z\s&]{2,50})\s*report", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(?i)report\s*[:\-]?\s*([A-Z][A-Za-z\s&]{2,50})")
        ),
        DocumentType.CERTIFICATE to listOf(
            Pattern.compile("(?i)presented\s*to\s+([A-Z][A-Za-z\s\.]{2,50})")
        )
    )

    private val datePattern = Pattern.compile("(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{2,4}|\d{4}[/.\-]\d{1,2}[/.\-]\d{1,2})")
    private val amountPattern = Pattern.compile("[\$€£]\s*([\d,]+\.?\d*)|total\s*[:\-]?\s*[\$€£]?\s*([\d,]+\.?\d*)", Pattern.CASE_INSENSITIVE)

    fun detect(ocrText: String, fileName: String): DetectionResult {
        val scores = mutableMapOf<DocumentType, Int>()

        for ((type, typePatterns) in patterns) {
            var score = 0
            for (pattern in typePatterns) {
                val matcher = pattern.matcher(ocrText)
                var matches = 0
                while (matcher.find() && matches < 5) {
                    matches++
                    score += when (matches) { 1 -> 3; 2 -> 2; else -> 1 }
                }
            }
            if (fileName.contains(type.name, ignoreCase = true)) score += 2
            scores[type] = score
        }

        val bestType = scores.maxByOrNull { it.value }?.let { (type, score) ->
            if (score >= 3) type else DocumentType.UNKNOWN
        } ?: DocumentType.UNKNOWN

        val confidence = scores[bestType]?.toFloat()?.div(scores.values.sum().coerceAtLeast(1)) ?: 0f
        val title = extractTitle(bestType, ocrText)
        val date = extractDate(ocrText)
        val amount = extractAmount(ocrText)

        return DetectionResult(bestType, confidence, title, date, amount)
    }

    private fun extractTitle(type: DocumentType, text: String): String? {
        val extractors = titleExtractors[type] ?: return null
        for (pattern in extractors) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) return matcher.group(1)?.trim()?.take(50)
        }
        return null
    }

    private fun extractDate(text: String): String? {
        val matcher = datePattern.matcher(text)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractAmount(text: String): String? {
        val matcher = amountPattern.matcher(text)
        return if (matcher.find()) matcher.group(1) ?: matcher.group(2) else null
    }
}
