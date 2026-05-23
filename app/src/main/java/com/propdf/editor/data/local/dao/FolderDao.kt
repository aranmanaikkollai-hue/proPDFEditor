package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY name ASC")
    fun getAllFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folder: FolderEntity): Long

    @Update
    suspend fun update(folder: FolderEntity)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("UPDATE folders SET documentCount = (SELECT COUNT(*) FROM pdf_documents WHERE folderId = :folderId AND isDeleted = 0) WHERE id = :folderId")
    suspend fun updateDocumentCount(folderId: Long)
}
