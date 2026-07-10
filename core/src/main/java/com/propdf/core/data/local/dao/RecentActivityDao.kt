package com.propdf.core.data.local.dao

import androidx.room.*
import com.propdf.core.data.entity.RecentActivityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentActivityDao {

    @Query("SELECT * FROM recent_activities ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentActivities(limit: Int): Flow<List<RecentActivityEntity>>

    @Insert
    suspend fun insert(activity: RecentActivityEntity)

    @Query("DELETE FROM recent_activities WHERE timestamp < :cutoffTime")
    suspend fun deleteOldActivities(cutoffTime: Long)

    @Query("SELECT * FROM recent_activities WHERE document_id = :documentId ORDER BY timestamp DESC")
    fun getActivitiesForDocument(documentId: Long): Flow<List<RecentActivityEntity>>

    @Query("""
        SELECT * FROM recent_activities 
        WHERE action = :action 
        ORDER BY timestamp DESC 
        LIMIT :limit
    """)
    fun getActivitiesByAction(action: String, limit: Int): Flow<List<RecentActivityEntity>>
}
