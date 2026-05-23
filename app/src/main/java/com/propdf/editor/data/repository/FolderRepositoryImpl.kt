package com.propdf.editor.data.repository

import com.propdf.editor.data.local.dao.FolderDao
import com.propdf.editor.data.local.entity.FolderEntity
import com.propdf.editor.domain.model.Folder
import com.propdf.editor.domain.repository.FolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderRepositoryImpl @Inject constructor(
    private val folderDao: FolderDao
) : FolderRepository {

    override fun getAllFolders(): Flow<List<Folder>> {
        return folderDao.getAllFolders().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun createFolder(name: String, color: Int, icon: String): Long {
        return folderDao.insert(FolderEntity(name = name, color = color, icon = icon))
    }

    override suspend fun updateFolder(folder: Folder) {
        folderDao.update(folder.toEntity())
    }

    override suspend fun deleteFolder(id: Long) {
        folderDao.getById(id)?.let { folderDao.delete(it) }
    }

    override suspend fun getFolder(id: Long): Folder? {
        return folderDao.getById(id)?.toDomain()
    }

    private fun FolderEntity.toDomain() = Folder(
        id = id, name = name, color = color, icon = icon,
        documentCount = documentCount, createdAt = createdAt, isSystem = isSystem
    )

    private fun Folder.toEntity() = FolderEntity(
        id = id, name = name, color = color, icon = icon,
        documentCount = documentCount, createdAt = createdAt, isSystem = isSystem
    )
}
