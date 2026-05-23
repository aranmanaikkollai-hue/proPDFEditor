package com.propdf.editor.domain.repository

import com.propdf.editor.domain.model.Folder
import kotlinx.coroutines.flow.Flow

interface FolderRepository {
    fun getAllFolders(): Flow<List<Folder>>
    suspend fun createFolder(name: String, color: Int, icon: String): Long
    suspend fun updateFolder(folder: Folder)
    suspend fun deleteFolder(id: Long)
    suspend fun getFolder(id: Long): Folder?
}
