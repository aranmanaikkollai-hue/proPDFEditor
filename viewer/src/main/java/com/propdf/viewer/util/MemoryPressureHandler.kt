package com.propdf.viewer.util

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.propdf.viewer.rendering.BitmapPool
import com.propdf.viewer.rendering.ThumbnailManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Monitors system memory pressure and adapts viewer behavior.
 */
@Singleton
class MemoryPressureHandler @Inject constructor(
    @ApplicationContext context: Context,
    private val bitmapPool: BitmapPool
) {
    // ThumbnailManager registered after creation to avoid circular DI dependency
    private var thumbnailManager: ThumbnailManager? = null
    private val coroutineScope: CoroutineScope = GlobalScope

    fun setThumbnailManager(manager: ThumbnailManager) {
        thumbnailManager = manager
    }
    companion object {
        private const val TAG = "MemoryPressureHandler"
        private const val MODERATE_THRESHOLD = 0.85f
        private const val HIGH_THRESHOLD = 0.90f
        private const val CRITICAL_THRESHOLD = 0.95f
        private const val CHECK_INTERVAL_MS = 5000L
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var isMonitoring = false
    private var onPressureChange: ((PressureLevel) -> Unit)? = null

    enum class PressureLevel {
        NORMAL, MODERATE, HIGH, CRITICAL
    }

    fun startMonitoring() {
        if (isMonitoring) return
        isMonitoring = true

        coroutineScope.launch(Dispatchers.Default) {
            while (isMonitoring) {
                checkMemoryPressure()
                kotlinx.coroutines.delay(CHECK_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring() {
        isMonitoring = false
    }

    fun setOnPressureChangeListener(listener: (PressureLevel) -> Unit) {
        onPressureChange = listener
    }

    private suspend fun checkMemoryPressure() {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val usedPercent = if (memoryInfo.totalMem > 0) {
            1f - (memoryInfo.availMem.toFloat() / memoryInfo.totalMem.toFloat())
        } else 0f

        val level = when {
            usedPercent >= CRITICAL_THRESHOLD -> PressureLevel.CRITICAL
            usedPercent >= HIGH_THRESHOLD -> PressureLevel.HIGH
            usedPercent >= MODERATE_THRESHOLD -> PressureLevel.MODERATE
            else -> PressureLevel.NORMAL
        }

        when (level) {
            PressureLevel.NORMAL -> {}
            PressureLevel.MODERATE -> {
                Log.w(TAG, "Moderate memory pressure: trimming pool 30%")
                bitmapPool.trim(0.3f)
            }
            PressureLevel.HIGH -> {
                Log.w(TAG, "High memory pressure: clearing thumbnail cache")
                bitmapPool.trim(0.6f)
                thumbnailManager?.clearMemoryCache()
            }
            PressureLevel.CRITICAL -> {
                Log.e(TAG, "Critical memory pressure: emergency cleanup")
                bitmapPool.clear()
                thumbnailManager?.clearAllCaches()
            }
        }

        onPressureChange?.invoke(level)
    }

    fun getMemoryStatus(): MemoryStatus {
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        return MemoryStatus(
            totalMemory = memoryInfo.totalMem,
            availableMemory = memoryInfo.availMem,
            threshold = memoryInfo.threshold,
            lowMemory = memoryInfo.lowMemory
        )
    }

    data class MemoryStatus(
        val totalMemory: Long,
        val availableMemory: Long,
        val threshold: Long,
        val lowMemory: Boolean
    )
}
