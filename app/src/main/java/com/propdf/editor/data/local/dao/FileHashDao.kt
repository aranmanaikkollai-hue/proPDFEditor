package com.propdf.editor.data.local.dao

import androidx.room.*
import androidx.room.MapColumn
import com.propdf.editor.data.local.entity.FileHashEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileHashDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FileHashEntity)

    @Query("SELECT * FROM file_hashes WHERE documentId = :documentId")
    suspend fun getByDocumentId(documentId: String): FileHashEntity?

    @Query("SELECT * FROM file_hashes WHERE fastHash = :fastHash")
    suspend fun getByFastHash(fastHash: String): List<FileHashEntity>

    @Query("SELECT * FROM file_hashes WHERE strongHash = :strongHash")
    suspend fun getByStrongHash(strongHash: String): List<FileHashEntity>

    @Query("SELECT * FROM file_hashes WHERE duplicateGroupId = :groupId ORDER BY fileSize DESC")
    suspend fun getByGroupId(groupId: String): List<FileHashEntity>

    @Query("""
        SELECT strongHash, COUNT(*) as count 
        FROM file_hashes 
        WHERE strongHash IS NOT NULL 
        GROUP BY strongHash 
        HAVING count > 1
    """)
    suspend fun findDuplicateStrongHashes(): List<DuplicateHash>

    @Query("""
        SELECT fastHash, COUNT(*) as count 
        FROM file_hashes 
        GROUP BY fastHash 
        HAVING count > 1
    """)
    suspend fun findDuplicateFastHashes(): List<DuplicateHash>

    @Query("UPDATE file_hashes SET duplicateGroupId = :groupId WHERE documentId IN (:documentIds)")
    suspend fun assignGroup(groupId: String, documentIds: List<String>)

    @Query("UPDATE file_hashes SET duplicateGroupId = NULL WHERE duplicateGroupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("DELETE FROM file_hashes WHERE documentId = :documentId")
    suspend fun deleteByDocumentId(documentId: String)

    @Query("SELECT COUNT(*) FROM file_hashes WHERE duplicateGroupId IS NOT NULL")
    fun getDuplicateCount(): Flow<Int>

    @MapColumn(columnName = "duplicateGroupId")
    @Query("""
        SELECT fh.*, pd.fileName, pd.uri, pd.thumbnailUri 
        FROM file_hashes fh
        INNER JOIN pdf_documents pd ON fh.documentId = pd.id
        WHERE fh.duplicateGroupId IS NOT NULL
        ORDER BY fh.duplicateGroupId, fh.fileSize DESC
    """)
    fun getAllDuplicateGroups(): Flow<Map<String, List<DuplicateGroupItem>>>

    data class DuplicateHash(val strongHash: String?, val count: Int)

    data class DuplicateGroupItem(
        val documentId: String,
        val fileName: String,
        val uri: String,
        val thumbnailUri: String?,
        val fileSize: Long,
        val duplicateGroupId: String?
    )
}
