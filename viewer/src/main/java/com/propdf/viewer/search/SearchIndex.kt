package com.propdf.viewer.search

import android.content.Context
import android.graphics.RectF
import android.util.Log
import com.propdf.viewer.model.SearchResult
import com.propdf.core.data.database.SearchDatabase
import com.propdf.core.data.entity.SearchIndexEntity
import com.propdf.core.data.entity.RecentSearchEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Offline PDF text indexing and search using Room FTS4.
 */
class SearchIndex(
    private val context: Context,
    private val database: SearchDatabase
) {
    companion object {
        private const val TAG = "SearchIndex"
        private const val BATCH_SIZE = 50
    }

    private val searchDao = database.searchDao()
    private val ocrEngine = OcrSearchEngine(context)
    private val resultCache = ConcurrentHashMap<String, List<SearchResult>>()

    suspend fun indexDocument(documentId: String, pageTexts: Map<Int, String>) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Indexing document: $documentId, pages: ${pageTexts.size}")
        searchDao.deleteDocumentIndex(documentId)

        pageTexts.entries.chunked(BATCH_SIZE).forEach { batch ->
            val entities = batch.map { (pageIndex, text) ->
                SearchIndexEntity(
                    documentId = documentId,
                    pageIndex = pageIndex,
                    pageText = text
                )
            }
            searchDao.insertIndexBatch(entities)
        }
        Log.i(TAG, "Indexing complete for $documentId")
    }

    suspend fun search(documentId: String, query: String): List<SearchResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val cacheKey = "$documentId:$query"
        resultCache[cacheKey]?.let { return@withContext it }

        val results = searchDao.searchFts(documentId, query)

        val searchResults = results.map { entity ->
            SearchResult(
                pageIndex = entity.pageIndex,
                textSnippet = extractSnippet(entity.pageText, query),
                matchCount = countOccurrences(entity.pageText, query),
                boundingBoxes = calculateBoundingBoxes(entity.pageText, query)
            )
        }

        searchDao.insertRecentSearch(
            RecentSearchEntity(
                query = query,
                documentId = documentId,
                timestamp = System.currentTimeMillis(),
                resultCount = searchResults.size
            )
        )

        resultCache[cacheKey] = searchResults
        searchResults
    }

    fun searchStream(documentId: String, query: String): Flow<SearchResult> = flow {
        if (query.isBlank()) return@flow

        val results = searchDao.searchFts(documentId, query)
        results.forEach { entity ->
            emit(SearchResult(
                pageIndex = entity.pageIndex,
                textSnippet = extractSnippet(entity.pageText, query),
                matchCount = countOccurrences(entity.pageText, query),
                boundingBoxes = calculateBoundingBoxes(entity.pageText, query)
            ))
        }
    }.flowOn(Dispatchers.IO)

    suspend fun getRecentSearches(documentId: String, limit: Int = 10): List<String> = withContext(Dispatchers.IO) {
        searchDao.getRecentSearches(documentId, limit)
    }

    suspend fun clearDocumentIndex(documentId: String) = withContext(Dispatchers.IO) {
        searchDao.deleteDocumentIndex(documentId)
        resultCache.keys.filter { it.startsWith("$documentId:") }.forEach {
            resultCache.remove(it)
        }
    }

    suspend fun indexOcrText(documentId: String, pageIndex: Int, ocrText: String) = withContext(Dispatchers.IO) {
        searchDao.insertIndexBatch(listOf(SearchIndexEntity(
            documentId = documentId,
            pageIndex = pageIndex,
            pageText = ocrText
        )))
    }

    private fun extractSnippet(text: String, query: String, maxLength: Int = 120): String {
        val index = text.indexOf(query, ignoreCase = true)
        if (index == -1) return text.take(maxLength)
        val start = maxOf(0, index - maxLength / 2)
        val end = minOf(text.length, index + query.length + maxLength / 2)
        return text.substring(start, end)
    }

    private fun countOccurrences(text: String, query: String): Int {
        var count = 0
        var index = text.indexOf(query, ignoreCase = true)
        while (index != -1) {
            count++
            index = text.indexOf(query, index + 1, ignoreCase = true)
        }
        return count
    }

    private fun calculateBoundingBoxes(text: String, query: String): List<RectF> {
        val count = countOccurrences(text, query)
        return List(count) { RectF(0f, 0f, 100f, 20f) }
    }
}
