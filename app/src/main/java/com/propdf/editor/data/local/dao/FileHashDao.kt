package com.propdf.editor.data.local.dao

import androidx.room.*
import com.propdf.editor.data.local.entity.FileHashEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
    suspend fun findDuplicateStrongHashes(): List<StrongDuplicateHash>

    @Query("""
        SELECT fastHash, COUNT(*) as count
        FROM file_hashes
        GROUP BY fastHash
        HAVING count > 1
    """)
    suspend fun findDuplicateFastHashes(): List<FastDuplicateHash>

    @Query("UPDATE file_hashes SET duplicateGroupId = :groupId WHERE documentId IN (:documentIds)")
    suspend fun assignGroup(groupId: String, documentIds: List<String>)

    @Query("UPDATE file_hashes SET duplicateGroupId = NULL WHERE duplicateGroupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("DELETE FROM file_hashes WHERE documentId = :documentId")
    suspend fun deleteByDocumentId(documentId: String)

    @Query("SELECT COUNT(*) FROM file_hashes WHERE duplicateGroupId IS NOT NULL")
    fun getDuplicateCount(): Flow<Int>

    @Query("""
        SELECT fh.documentId, pd.fileName, pd.uri, pd.thumbnailUri,
               fh.fileSize, fh.duplicateGroupId
        FROM file_hashes fh
        INNER JOIN pdf_documents pd ON fh.documentId = pd.id
        WHERE fh.duplicateGroupId IS NOT NULL
        ORDER BY fh.duplicateGroupId, fh.fileSize DESC
    """)
    fun getAllDuplicateGroupItems(): Flow<List<DuplicateGroupItem>>

    /**
     * Returns a Flow of Map<groupId, List<DuplicateGroupItem>> for easy consumption
     * in FilesViewModel.
     */
    fun getAllDuplicateGroups(): Flow<Map<String, List<DuplicateGroupItem>>> =
        getAllDuplicateGroupItems().map { items ->
            items.groupBy { it.duplicateGroupId ?: "" }.filterKeys { it.isNotEmpty() }
        }

    data class StrongDuplicateHash(val strongHash: String?, val count: Int)
    data class FastDuplicateHash(val fastHash: String, val count: Int)

    data class DuplicateGroupItem(
        val documentId: String,
        val fileName: String,
        val uri: String,
        val thumbnailUri: String?,
        val fileSize: Long,
        val duplicateGroupId: String?
    )
}
