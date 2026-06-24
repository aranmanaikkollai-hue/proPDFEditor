package com.propdf.editor.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.BaseUseCase
import com.propdf.editor.data.local.dao.PdfDocumentDao
import com.propdf.editor.data.local.dao.SearchIndexDao
import com.propdf.editor.domain.rename.DocumentTypeDetector
import com.propdf.editor.domain.rename.SmartFilenameGenerator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject

class AutoRenameDocumentUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentTypeDetector: DocumentTypeDetector,
    private val filenameGenerator: SmartFilenameGenerator,
    private val searchIndexDao: SearchIndexDao,
    private val pdfDocumentDao: PdfDocumentDao,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : BaseUseCase<AutoRenameDocumentUseCase.Params, AutoRenameDocumentUseCase.Result>() {

    data class Params(
        val documentId: String,
        val documentUri: Uri,
        val currentFileName: String,
        val existingNamesInFolder: Set<String> = emptySet()
    )

    data class Result(
        val success: Boolean,
        val newFileName: String?,
        val originalFileName: String,
        val confidence: Float,
        val reason: String,
        val applied: Boolean
    )

    override suspend fun execute(params: Params): AppResult<Result> = withContext(dispatchers.io) {
        try {
            val searchIndex = searchIndexDao.getByDocumentId(params.documentId)
            val ocrText = searchIndex?.ocrText ?: searchIndex?.contentText 
                ?: return@withContext AppResult.Error("Document not indexed")

            val detection = documentTypeDetector.detect(ocrText, params.currentFileName)
            val suggestion = filenameGenerator.generate(
                detectionResult = detection,
                originalFileName = params.currentFileName,
                existingNames = params.existingNamesInFolder
            )

            val shouldApply = detection.confidence >= 0.4f && detection.type != DocumentTypeDetector.DocumentType.UNKNOWN

            if (shouldApply) {
                applyRename(params.documentUri, suggestion.suggestedName)
            }

            AppResult.Success(
                Result(
                    success = true,
                    newFileName = suggestion.suggestedName,
                    originalFileName = params.currentFileName,
                    confidence = detection.confidence,
                    reason = suggestion.reason,
                    applied = shouldApply
                )
            )
        } catch (e: Exception) {
            logger.e("AutoRename", "Failed to rename ${params.documentId}", e)
            AppResult.Error("Rename failed: ${e.message}")
        }
    }

    private suspend fun applyRename(uri: Uri, newName: String): Boolean = withContext(dispatchers.io) {
        try {
            val docFile = DocumentFile.fromSingleUri(context, uri) ?: return@withContext false
            docFile.renameTo(newName)
        } catch (e: Exception) {
            logger.e("AutoRename", "Rename operation failed", e)
            false
        }
    }
}
