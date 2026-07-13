package com.propdf.core.data.local.dao

import androidx.room.*
import com.propdf.core.data.entity.FormFieldEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FormFieldDao {

    @Query("SELECT * FROM form_fields WHERE documentUri = :documentUri ORDER BY pageIndex, fieldName")
    fun getFieldsForDocument(documentUri: String): Flow<List<FormFieldEntity>>

    @Query("SELECT * FROM form_fields WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun getFieldsForPage(documentUri: String, pageIndex: Int): List<FormFieldEntity>

    @Query("SELECT * FROM form_fields WHERE id = :id")
    suspend fun getFieldById(id: Long): FormFieldEntity?

    @Query("SELECT * FROM form_fields WHERE documentUri = :documentUri AND fieldName = :fieldName LIMIT 1")
    suspend fun getFieldByName(documentUri: String, fieldName: String): FormFieldEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertField(field: FormFieldEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFields(fields: List<FormFieldEntity>)

    @Update
    suspend fun updateField(field: FormFieldEntity)

    @Delete
    suspend fun deleteField(field: FormFieldEntity)

    @Query("DELETE FROM form_fields WHERE documentUri = :documentUri")
    suspend fun deleteAllForDocument(documentUri: String)

    @Query("DELETE FROM form_fields WHERE documentUri = :documentUri AND pageIndex = :pageIndex")
    suspend fun deleteFieldsForPage(documentUri: String, pageIndex: Int)

    @Query("SELECT COUNT(*) FROM form_fields WHERE documentUri = :documentUri")
    suspend fun getFieldCount(documentUri: String): Int
}
