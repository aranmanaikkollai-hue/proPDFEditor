package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.OcrRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrRecordDao {
    @Query("SELECT * FROM ocr_records ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<OcrRecordEntity>>

    @Query("SELECT * FROM ocr_records ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<OcrRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: OcrRecordEntity)

    @Query("DELETE FROM ocr_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM ocr_records WHERE extractedText LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun search(query: String): List<OcrRecordEntity>
}
