package com.propdf.core.data.local.dao

import androidx.room.*
import com.propdf.core.data.entity.FormDataEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FormDataDao {

    @Query("SELECT * FROM form_data WHERE documentUri = :documentUri")
    fun getFormDataForDocument(documentUri: String): Flow<List<FormDataEntity>>

    @Query("SELECT * FROM form_data WHERE documentUri = :documentUri AND fieldName = :fieldName LIMIT 1")
    suspend fun getFieldValue(documentUri: String, fieldName: String): FormDataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(data: FormDataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(data: List<FormDataEntity>)

    @Query("DELETE FROM form_data WHERE documentUri = :documentUri")
    suspend fun clearFormData(documentUri: String)

    @Query("DELETE FROM form_data WHERE documentUri = :documentUri AND fieldName = :fieldName")
    suspend fun clearFieldValue(documentUri: String, fieldName: String)
}
