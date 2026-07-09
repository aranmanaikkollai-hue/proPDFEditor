package com.propdf.scanner.data.repository

import com.propdf.scanner.model.*
import kotlinx.coroutines.flow.Flow

interface ScannerRepository {
    fun getAllDocuments(): Flow<List<ScannedDocument>>
    suspend fun getDocumentById(id: String): ScannedDocument?
    fun getDocumentByIdFlow(id: String): Flow<ScannedDocument?>
    suspend fun saveDocument(document: ScannedDocument)
    suspend fun updateDocument(document: ScannedDocument)
    suspend fun deleteDocument(document: ScannedDocument)
    suspend fun deleteDocumentById(id: String)
    suspend fun addPageToDocument(documentId: String, page: ScannedPage)
    suspend fun removePageFromDocument(documentId: String, pageId: String)
    suspend fun updatePage(documentId: String, page: ScannedPage)
    fun searchDocuments(query: String): Flow<List<ScannedDocument>>
    suspend fun getDocumentCount(): Int
}
