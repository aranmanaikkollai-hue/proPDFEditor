package com.propdf.core.domain.repository

import com.propdf.core.domain.model.ActivityAction
import com.propdf.core.domain.model.RecentActivity
import kotlinx.coroutines.flow.Flow

interface ActivityRepository {
    fun getRecentActivities(limit: Int = 100): Flow<List<RecentActivity>>
    suspend fun logActivity(documentId: Long, documentName: String, action: ActivityAction, details: String? = null)
    suspend fun cleanOldActivities(days: Int = 90)
    fun getActivitiesForDocument(documentId: Long): Flow<List<RecentActivity>>
}
