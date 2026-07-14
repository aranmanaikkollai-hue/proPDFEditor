package com.propdfeditor.core.repository

import com.propdfeditor.core.database.dao.SignatureHistoryDao
import com.propdfeditor.core.database.entity.SignatureHistoryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignatureHistoryRepository @Inject constructor(
    private val historyDao: SignatureHistoryDao
) {
    fun getAllHistory(): Flow<List<SignatureHistoryEntity>> = historyDao.getAllHistory()

    fun getHistoryForDocument(documentPath: String): Flow<List<SignatureHistoryEntity>> =
        historyDao.getHistoryForDocument(documentPath)

    fun getHistoryForSignature(signatureId: Long): Flow<List<SignatureHistoryEntity>> =
        historyDao.getHistoryForSignature(signatureId)

    suspend fun addHistoryEntry(entry: SignatureHistoryEntity): Long =
        historyDao.insertHistory(entry)

    suspend fun updateVerificationStatus(
        id: Long,
        verified: Boolean,
        status: SignatureHistoryEntity.VerificationStatus
    ) {
        historyDao.updateVerificationStatus(id, verified, status)
    }

    suspend fun deleteHistoryEntry(entry: SignatureHistoryEntity) {
        historyDao.deleteHistory(entry)
    }

    suspend fun clearOldHistory(olderThanMillis: Long) {
        val cutoff = System.currentTimeMillis() - olderThanMillis
        historyDao.deleteHistoryOlderThan(cutoff)
    }
}
