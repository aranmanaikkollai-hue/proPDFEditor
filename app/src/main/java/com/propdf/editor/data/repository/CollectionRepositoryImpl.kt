package com.propdf.editor.data.repository

import com.propdf.core.data.entity.DocumentCollectionEntity
import com.propdf.core.data.local.dao.DocumentCollectionDao
import com.propdf.core.domain.model.DocumentCollection
import com.propdf.core.domain.repository.CollectionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CollectionRepositoryImpl @Inject constructor(
    private val collectionDao: DocumentCollectionDao
) : CollectionRepository {

    override fun getAllCollections(): Flow<List<DocumentCollection>> {
        return collectionDao.getAllCollections().map { entities ->
            entities.map { entity ->
                DocumentCollection(
                    id = entity.id,
                    name = entity.name,
                    description = entity.description,
                    color = entity.color,
                    createdAt = entity.createdAt,
                    coverUri = entity.coverUri
                )
            }
        }
    }

    override suspend fun getCollectionById(id: Long): DocumentCollection? {
        return collectionDao.getById(id)?.let {
            DocumentCollection(
                id = it.id,
                name = it.name,
                description = it.description,
                color = it.color,
                createdAt = it.createdAt,
                coverUri = it.coverUri
            )
        }
    }

    override suspend fun createCollection(name: String, description: String?, color: Int): Long {
        return collectionDao.insert(
            DocumentCollectionEntity(
                name = name,
                description = description,
                color = color
            )
        )
    }

    override suspend fun updateCollection(collection: DocumentCollection) {
        collectionDao.update(
            DocumentCollectionEntity(
                id = collection.id,
                name = collection.name,
                description = collection.description,
                color = collection.color,
                createdAt = collection.createdAt,
                coverUri = collection.coverUri
            )
        )
    }

    override suspend fun deleteCollection(id: Long) {
        collectionDao.getById(id)?.let {
            collectionDao.delete(it)
        }
    }

    override suspend fun getDocumentCount(collectionId: Long): Int {
        return collectionDao.getDocumentCount(collectionId)
    }
}
