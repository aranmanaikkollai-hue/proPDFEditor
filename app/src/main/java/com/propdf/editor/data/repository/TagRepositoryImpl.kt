package com.propdf.editor.data.repository

import com.propdf.core.data.entity.DocumentTagCrossRef
import com.propdf.core.data.entity.DocumentTagEntity
import com.propdf.core.data.local.dao.DocumentTagDao
import com.propdf.core.domain.model.DocumentTag
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.repository.TagRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepositoryImpl @Inject constructor(
    private val tagDao: DocumentTagDao
) : TagRepository {

    override fun getAllTags(): Flow<List<DocumentTag>> {
        return tagDao.getAllTags().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getTagsForDocument(documentId: Long): Flow<List<DocumentTag>> {
        return tagDao.getTagsForDocument(documentId).map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getDocumentsByTag(tagId: Long): Flow<List<PdfDocument>> {
        return tagDao.getDocumentsByTag(tagId).map { list ->
            list.map { entity ->
                PdfDocument(
                    id = entity.id,
                    uriString = entity.uriString,
                    fileName = entity.fileName,
                    filePath = entity.filePath,
                    sizeBytes = entity.sizeBytes,
                    pageCount = entity.pageCount,
                    lastModified = entity.lastModified,
                    lastOpened = entity.lastOpened,
                    isFavorite = entity.isFavorite,
                    isHidden = entity.isHidden,
                    isInRecycleBin = entity.isInRecycleBin
                )
            }
        }
    }

    override suspend fun createTag(name: String, color: Int): Long {
        return tagDao.insertTag(DocumentTagEntity(name = name, color = color))
    }

    override suspend fun updateTag(tag: DocumentTag) {
        tagDao.insertTag(DocumentTagEntity(id = tag.id, name = tag.name, color = tag.color))
    }

    override suspend fun deleteTag(tagId: Long) {
        // Note: Cross refs will cascade delete if configured, otherwise handle manually
        val tag = DocumentTagEntity(id = tagId, name = "", color = 0)
        tagDao.deleteTag(tag)
    }

    override suspend fun addTagToDocument(documentId: Long, tagId: Long) {
        tagDao.addTagToDocument(DocumentTagCrossRef(documentId, tagId))
    }

    override suspend fun removeTagFromDocument(documentId: Long, tagId: Long) {
        tagDao.removeTagFromDocument(documentId, tagId)
    }

    override suspend fun setDocumentTags(documentId: Long, tagIds: List<Long>) {
        tagDao.removeAllTagsFromDocument(documentId)
        tagIds.forEach { tagId ->
            tagDao.addTagToDocument(DocumentTagCrossRef(documentId, tagId))
        }
    }

    private fun DocumentTagEntity.toDomain(): DocumentTag {
        return DocumentTag(
            id = id,
            name = name,
            color = color
        )
    }
}
