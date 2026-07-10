package com.propdf.core.domain.repository

import com.propdf.core.domain.model.DocumentCollection
import kotlinx.coroutines.flow.Flow

interface CollectionRepository {
    fun getAllCollections(): Flow<List<DocumentCollection>>
    suspend fun getCollectionById(id: Long): DocumentCollection?
    suspend fun createCollection(name: String, description: String?, color: Int): Long
    suspend fun updateCollection(collection: DocumentCollection)
    suspend fun deleteCollection(id: Long)
    suspend fun getDocumentCount(collectionId: Long): Int
}
