package com.propdf.editor.data.index

import android.content.Context
import com.propdf.editor.data.local.dao.SearchIndexDao
import com.propdf.editor.data.local.entity.IndexingStatus
import com.propdf.editor.data.local.entity.SearchIndexEntity
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local full-text indexing engine for PDF documents.
 *
 * Uses PDFBox to extract text from PDFs and stores it in SearchIndexEntity
 * for fast full-text search.
 */
@Singleton
class DocumentIndexEngine @Inject constructor(
    private val context: Context,
    private val searchIndexDao: SearchIndexDao
) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    /**
     * Index a PDF file by extracting its full text content.
     * Stores the result in SearchIndexEntity keyed by documentId (String).
     */
    suspend fun indexDocument(documentId: String, pdfFile: File): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Mark as processing
                searchIndexDao.updateStatus(documentId, IndexingStatus.PROCESSING)

                val textBuilder = StringBuilder()
                val stripper = PDFTextStripper()
                var pageCount = 0

                PDDocument.load(pdfFile).use { document ->
                    pageCount = document.numberOfPages
                    for (pageNum in 1..pageCount) {
                        ensureActive()
                        stripper.startPage = pageNum
                        stripper.endPage = pageNum
                        val pageText = stripper.getText(document)
                        if (pageText.isNotBlank()) {
                            textBuilder.append(pageText)
                            textBuilder.append("\n")
                        }
                    }
                }

                val entity = SearchIndexEntity(
                    documentId = documentId,
                    fileName = pdfFile.nameWithoutExtension,
                    contentText = textBuilder.toString().take(50_000), // cap size
                    pageCount = pageCount,
                    indexingStatus = IndexingStatus.COMPLETED
                )
                searchIndexDao.insertOrUpdate(entity)
                true
            } catch (e: Exception) {
                try {
                    searchIndexDao.updateStatus(documentId, IndexingStatus.FAILED)
                } catch (_: Exception) {}
                false
            }
        }

    /**
     * Search for documents matching the query.
     * Returns list of documentIds that match.
     */
    suspend fun search(query: String): List<String> {
        val normalized = query.trim()
        if (normalized.length < 3) return emptyList()
        return searchIndexDao.search(normalized).map { it.documentId }
    }
}
