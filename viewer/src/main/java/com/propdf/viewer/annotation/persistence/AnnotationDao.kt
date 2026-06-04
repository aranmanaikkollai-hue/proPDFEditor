package com.propdf.viewer.annotation.persistence

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface AnnotationDao {
    @Query("SELECT * FROM annotations WHERE pdfUri = :pdfUri ORDER BY updatedAt ASC")
    suspend fun getByPdfUri(pdfUri: String): List<AnnotationEntity>

    @Query("DELETE FROM annotations WHERE pdfUri = :pdfUri")
    suspend fun deleteByPdfUri(pdfUri: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AnnotationEntity>)

    @Transaction
    suspend fun replaceDocumentAnnotations(pdfUri: String, rows: List<AnnotationEntity>) {
        deleteByPdfUri(pdfUri)
        if (rows.isNotEmpty()) upsertAll(rows)
    }
}
