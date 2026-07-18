// security/src/main/java/com/propdf/security/data/dao/SecureDocumentDao.kt
package com.propdf.security.data.dao

import androidx.room.*
import com.propdf.security.data.entity.SecureDocumentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SecureDocumentDao {
    @Query("SELECT * FROM secure_documents ORDER BY lastAccessed DESC")
    fun getAllSecureDocuments(): Flow<List<SecureDocumentEntity>>

    @Query("SELECT * FROM secure_documents WHERE uri = :uri")
    suspend fun getDocument(uri: String): SecureDocumentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: SecureDocumentEntity)

    @Update
    suspend fun update(document: SecureDocumentEntity)

    @Delete
    suspend fun delete(document: SecureDocumentEntity)

    @Query("DELETE FROM secure_documents WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
