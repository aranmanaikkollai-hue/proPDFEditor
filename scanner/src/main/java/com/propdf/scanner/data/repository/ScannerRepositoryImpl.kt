package com.propdf.scanner.data.repository

import com.propdf.scanner.data.local.*
import com.propdf.scanner.model.ScannedDocument
import com.propdf.scanner.model.ScannedPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScannerRepositoryImpl @Inject constructor(private val dao: ScannedDocumentDao) : ScannerRepository {
    override fun getAllDocuments(): Flow<List<ScannedDocument>> = dao.getAllDocuments().map { entities ->
        entities.map { entity ->
            val pages = dao.getPagesForDocument(entity.id).map { it.toModel() }
            entity.toModel(pages)
        }
    }

    override suspend fun getDocumentById(id: String): ScannedDocument? {
        val entity = dao.getDocumentById(id) ?: return null
        val pages = dao.getPagesForDocument(id).map { it.toModel() }
        return entity.toModel(pages)
    }

    override fun getDocumentByIdFlow(id: String): Flow<ScannedDocument?> = dao.getDocumentByIdFlow(id).map { entity ->
        entity?.let {
            val pages = dao.getPagesForDocument(it.id).map { page -> page.toModel() }
            it.toModel(pages)
        }
    }

    override suspend fun saveDocument(document: ScannedDocument) {
        dao.insertDocument(document.toEntity())
        dao.insertPages(document.pages.map { it.toEntity(document.id) })
    }

    override suspend fun updateDocument(document: ScannedDocument) {
        dao.updateDocument(document.toEntity())
        dao.deletePagesForDocument(document.id)
        dao.insertPages(document.pages.map { it.toEntity(document.id) })
    }

    override suspend fun deleteDocument(document: ScannedDocument) = dao.deleteDocument(document.toEntity())
    override suspend fun deleteDocumentById(id: String) = dao.deleteDocumentById(id)
    override suspend fun addPageToDocument(documentId: String, page: ScannedPage) = dao.insertPage(page.toEntity(documentId))
    override suspend fun removePageFromDocument(documentId: String, pageId: String) {
        dao.getPagesForDocument(documentId).find { it.id == pageId }?.let { dao.deletePage(it) }
    }

    override suspend fun updatePage(documentId: String, page: ScannedPage) = dao.insertPage(page.toEntity(documentId))

    override fun searchDocuments(query: String): Flow<List<ScannedDocument>> = dao.searchDocuments(query).map { entities ->
        entities.map { entity ->
            val pages = dao.getPagesForDocument(entity.id).map { it.toModel() }
            entity.toModel(pages)
        }
    }

    override suspend fun getDocumentCount(): Int = dao.getDocumentCount()
}
