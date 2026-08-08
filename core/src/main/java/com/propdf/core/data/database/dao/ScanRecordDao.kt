package com.propdf.core.data.database.dao

import androidx.room.*
import com.propdf.core.data.database.entity.ScanRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {
    @Query("SELECT * FROM scan_records ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScanRecordEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ScanRecordEntity)

    @Query("DELETE FROM scan_records WHERE uri = :uri")
    suspend fun delete(uri: String)
}
