package com.propdf.viewer.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.propdf.viewer.rendering.BitmapPool
import com.propdf.viewer.rendering.ThumbnailManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Monitors system memory pressure and adapts viewer behavior.
 *
 * Triggers:
 * - Trim bitmap pool on moderate pressure
 * - Clear thumbnail cache on high pressure
 * - Disable preloading on critical pressure
 * - Reduce tile quality on sustained pressure
 */
class MemoryPressureHandler(
    context: Context,
    private val bitmapPool: BitmapPool,
    private val thumbnailManager: ThumbnailManager,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "MemoryPressureHandler"

        /** Memory pressure thresholds (percentage of total RAM) */
        private const val MODERATE_THRESHOLD = 0.85f
        private const val HIGH_THRESHOLD = 0.90f
        private const val CRITICAL_THRESHOLD = 0.95f

        /** Check interval in ms */
        private const val CHECK_INTERVAL_MS = 5000L
    }

    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var isMonitoring = false

    /** Callbacks for pressure level changes */
    private var onPressureChange: ((PressureLevel) -> Unit)? = null

    enum class PressureLevel {
        NORMAL, MODERATE, HIGH, CRITICAL
    }

    /**
     * Start monitoring memory pressure.
     */
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

    /**
     * Stop monitoring.
     */
    fun stopMonitoring() {
        isMonitoring = false
    }

    /**
     * Set callback for pressure level changes.
     */
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
            PressureLevel.NORMAL -> {
                // Normal operation
            }
            PressureLevel.MODERATE -> {
                Log.w(TAG, "Moderate memory pressure: trimming pool 30%")
                bitmapPool.trim(0.3f)
            }
            PressureLevel.HIGH -> {
                Log.w(TAG, "High memory pressure: clearing thumbnail cache")
                bitmapPool.trim(0.6f)
                thumbnailManager.clearMemoryCache()
            }
            PressureLevel.CRITICAL -> {
                Log.e(TAG, "Critical memory pressure: emergency cleanup")
                bitmapPool.clear()
                thumbnailManager.clearAllCaches()
            }
        }

        onPressureChange?.invoke(level)
    }

    /**
     * Get current memory status for diagnostics.
     */
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
