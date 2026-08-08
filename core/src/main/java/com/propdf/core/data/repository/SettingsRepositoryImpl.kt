package com.propdf.core.data.repository

import android.content.Context
import android.os.StatFs
import com.propdf.core.data.database.dao.RecycleBinDao
import com.propdf.core.domain.repository.SettingsRepository
import com.propdf.core.domain.repository.StorageStats
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recycleBinDao: RecycleBinDao
) : SettingsRepository {

    override suspend fun getStorageStats(): StorageStats {
        val stat = StatFs(context.filesDir.path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        return StorageStats(
            used = (totalBlocks - availableBlocks) * blockSize,
            total = totalBlocks * blockSize,
            recycleBinCount = recycleBinDao.count()
        )
    }
}
