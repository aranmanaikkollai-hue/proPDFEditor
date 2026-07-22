package com.propdf.editor.data.smartfolder

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.entity.PdfDocumentEntity
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmartFolderEngine @Inject constructor(
    private val pdfDocumentDao: PdfDocumentDao,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class SmartRules(
        val matchAll: Boolean = true,
        val conditions: List<Condition>
    )

    @Serializable
    data class Condition(
        val field: String,
        val operator: String,
        val value: String
    )

    suspend fun evaluateRules(rulesJson: String): List<PdfDocumentEntity> = withContext(dispatchers.io) {
        try {
            val rules = json.decodeFromString<SmartRules>(rulesJson)
            val allDocs = pdfDocumentDao.getAllDocuments()

            allDocs.filter { doc ->
                if (rules.matchAll) {
                    rules.conditions.all { evaluateCondition(it, doc) }
                } else {
                    rules.conditions.any { evaluateCondition(it, doc) }
                }
            }
        } catch (e: Exception) {
            logger.e("SmartFolder", "Failed to evaluate rules: $rulesJson", e)
            emptyList()
        }
    }

    private fun evaluateCondition(condition: Condition, doc: PdfDocumentEntity): Boolean {
        val fieldValue = when (condition.field) {
            "fileName" -> doc.fileName
            "fileSize" -> doc.fileSize.toString()
            "pageCount" -> doc.pageCount.toString()
            "documentType" -> doc.documentType
            "isScanned" -> doc.isScanned.toString()
            "createdAt" -> doc.createdAt.toString()
            else -> return false
        } ?: return false

        return when (condition.operator) {
            "contains" -> fieldValue.contains(condition.value, ignoreCase = true)
            "equals" -> fieldValue.equals(condition.value, ignoreCase = true)
            "startsWith" -> fieldValue.startsWith(condition.value, ignoreCase = true)
            "endsWith" -> fieldValue.endsWith(condition.value, ignoreCase = true)
            "matches" -> Regex(condition.value, RegexOption.IGNORE_CASE).matches(fieldValue)
            "greaterThan" -> {
                val numValue = fieldValue.toLongOrNull() ?: return false
                val compareValue = condition.value.toLongOrNull() ?: return false
                numValue > compareValue
            }
            "lessThan" -> {
                val numValue = fieldValue.toLongOrNull() ?: return false
                val compareValue = condition.value.toLongOrNull() ?: return false
                numValue < compareValue
            }
            else -> false
        }
    }

    fun buildRulesJson(conditions: List<Condition>, matchAll: Boolean = true): String {
        return json.encodeToString(SmartRules(matchAll, conditions))
    }

    object Templates {
        fun scannedDocuments() = listOf(Condition("isScanned", "equals", "true"))
        fun largeDocuments(minSizeMb: Int = 10) = listOf(
            Condition("fileSize", "greaterThan", (minSizeMb * 1024 * 1024).toString())
        )
        fun recentDocuments(days: Int = 7) = listOf(
            Condition("createdAt", "greaterThan", (System.currentTimeMillis() - days * 86400000).toString())
        )
        fun invoices() = listOf(Condition("documentType", "equals", "INVOICE"))
    }
}
