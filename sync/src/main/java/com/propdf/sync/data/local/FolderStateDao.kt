package com.propdf.sync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.propdf.sync.data.local.entity.FolderStateEntity

@Dao
interface FolderStateDao {
    @Query("SELECT * FROM folder_states WHERE folder_id = :folderId")
    suspend fun getByFolderId(folderId: Long): List<FolderStateEntity>

    @Query("SELECT document_uri FROM folder_states WHERE folder_id = :folderId")
    suspend fun getDocumentUris(folderId: Long): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<FolderStateEntity>)

    @Query("DELETE FROM folder_states WHERE folder_id = :folderId")
    suspend fun deleteByFolderId(folderId: Long)
}
