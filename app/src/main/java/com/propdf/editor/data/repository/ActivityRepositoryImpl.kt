package com.propdf.editor.data.repository

import com.propdf.core.data.entity.RecentActivityEntity
import com.propdf.core.data.local.dao.RecentActivityDao
import com.propdf.core.domain.model.ActivityAction
import com.propdf.core.domain.model.RecentActivity
import com.propdf.core.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ActivityRepositoryImpl @Inject constructor(
    private val activityDao: RecentActivityDao
) : ActivityRepository {

    override fun getRecentActivities(limit: Int): Flow<List<RecentActivity>> {
        return activityDao.getRecentActivities(limit).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun logActivity(documentId: Long, documentName: String, action: ActivityAction, details: String?) {
        activityDao.insert(
            RecentActivityEntity(
                documentId = documentId,
                documentName = documentName,
                action = action.name,
                details = details
            )
        )
    }

    override suspend fun cleanOldActivities(days: Int) {
        val cutoff = System.currentTimeMillis() - (days * 24 * 60 * 60 * 1000)
        activityDao.deleteOldActivities(cutoff)
    }

    override fun getActivitiesForDocument(documentId: Long): Flow<List<RecentActivity>> {
        return activityDao.getActivitiesForDocument(documentId).map { list ->
            list.map { it.toDomain() }
        }
    }

    private fun RecentActivityEntity.toDomain(): RecentActivity {
        return RecentActivity(
            id = id,
            documentId = documentId,
            documentName = documentName,
            action = ActivityAction.valueOf(action),
            timestamp = timestamp,
            details = details
        )
    }
}
