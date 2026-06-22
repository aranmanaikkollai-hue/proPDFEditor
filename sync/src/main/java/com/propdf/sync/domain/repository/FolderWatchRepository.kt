package com.propdf.sync.domain.repository

import com.propdf.core.domain.result.AppResult
import com.propdf.sync.domain.model.WatchedFolder
import kotlinx.coroutines.flow.Flow

interface FolderWatchRepository {
    suspend fun addWatchedFolder(treeUri: android.net.Uri, displayName: String): AppResult<WatchedFolder>
    suspend fun removeWatchedFolder(id: Long): AppResult<Unit>
    suspend fun updateFolderState(folder: WatchedFolder): AppResult<Unit>
    fun getWatchedFolders(): Flow<List<WatchedFolder>>
    suspend fun performScan(folderId: Long): AppResult<Int> // Returns new import count
    fun isFolderWatched(uri: android.net.Uri): Boolean
}
