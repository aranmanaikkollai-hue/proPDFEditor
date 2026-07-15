package com.propdf.editor.data.local

import androidx.room.*
import com.propdf.editor.domain.model.ConversionStatus
import com.propdf.editor.domain.model.ConversionTask
import com.propdf.editor.domain.model.ConversionType
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversionTaskDao {
    
    @Query("SELECT * FROM conversion_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<ConversionTask>>
    
    @Query("SELECT * FROM conversion_tasks WHERE status = :status ORDER BY createdAt DESC")
    fun getTasksByStatus(status: ConversionStatus): Flow<List<ConversionTask>>
    
    @Query("SELECT * FROM conversion_tasks WHERE id = :taskId")
    suspend fun getTaskById(taskId: String): ConversionTask?
    
    @Query("SELECT * FROM conversion_tasks WHERE conversionType = :type ORDER BY createdAt DESC LIMIT 10")
    fun getRecentTasksByType(type: ConversionType): Flow<List<ConversionTask>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTask(task: ConversionTask)
    
    @Update
    suspend fun updateTask(task: ConversionTask)
    
    @Query("UPDATE conversion_tasks SET status = :status, progress = :progress, errorMessage = :errorMessage WHERE id = :taskId")
    suspend fun updateTaskProgress(taskId: String, status: ConversionStatus, progress: Int, errorMessage: String?)
    
    @Query("UPDATE conversion_tasks SET status = :status, completedAt = :completedAt, outputUri = :outputUri WHERE id = :taskId")
    suspend fun completeTask(taskId: String, status: ConversionStatus, completedAt: Long?, outputUri: String?)
    
    @Query("DELETE FROM conversion_tasks WHERE id = :taskId")
    suspend fun deleteTask(taskId: String)
    
    @Query("DELETE FROM conversion_tasks WHERE status = :status AND createdAt < :olderThan")
    suspend fun deleteOldTasks(status: ConversionStatus, olderThan: Long)
    
    @Query("SELECT COUNT(*) FROM conversion_tasks WHERE status = 'RUNNING'")
    suspend fun getRunningTaskCount(): Int
}
