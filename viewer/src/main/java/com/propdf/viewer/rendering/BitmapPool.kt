package com.propdf.viewer.rendering

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.WorkerThread
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Production-grade reusable bitmap pool for PDF tile rendering.
 * Reduces GC pressure by recycling bitmaps of matching dimensions.
 *
 * Design:
 * - LRU-style eviction with size budget
 * - Thread-safe via Mutex (coroutine-safe)
 * - Config-aware pooling (ARGB_8888, RGB_565)
 * - Memory-pressure adaptive shrinking
 */
class BitmapPool private constructor() {

    companion object {
        private const val TAG = "BitmapPool"

        /** Max pool memory in bytes (~48MB default, adaptive) */
        private const val DEFAULT_MAX_POOL_BYTES = 48L * 1024 * 1024

        /** Max individual bitmap dimension to prevent OOM on huge tiles */
        private const val MAX_TILE_DIMENSION = 4096

        /** Singleton instance */
        @Volatile
        private var instance: BitmapPool? = null

        fun getInstance(): BitmapPool = instance ?: synchronized(this) {
            instance ?: BitmapPool().also { instance = it }
        }
    }

    private val mutex = Mutex()
    private val pool = mutableMapOf<BitmapConfigKey, MutableList<PooledBitmap>>()
    private val totalPooledBytes = AtomicLong(0L)
    private val hitCount = AtomicInteger(0)
    private val missCount = AtomicInteger(0)
    private val evictionCount = AtomicInteger(0)

    /** Adaptive max pool size based on device RAM */
    private var maxPoolBytes: Long = DEFAULT_MAX_POOL_BYTES

    /** Track active (checked-out) bitmaps for leak detection */
    private val activeBitmaps = java.util.concurrent.ConcurrentLinkedQueue<PooledBitmap>()

    data class BitmapConfigKey(
        val width: Int,
        val height: Int,
        val config: Bitmap.Config
    ) {
        val byteSize: Long = width.toLong() * height * config.bytesPerPixel()
    }

    data class PooledBitmap(
        val bitmap: Bitmap,
        val key: BitmapConfigKey,
        val createdAt: Long = System.currentTimeMillis(),
        @Volatile var isCheckedOut: Boolean = false
    ) {
        var lastUsedAt: Long = createdAt
            private set

        fun markUsed() { lastUsedAt = System.currentTimeMillis() }
    }

    fun initialize(totalDeviceRamBytes: Long) {
        val ramClass = when {
            totalDeviceRamBytes < 2L * 1024 * 1024 * 1024 -> RamClass.LOW
            totalDeviceRamBytes < 4L * 1024 * 1024 * 1024 -> RamClass.MEDIUM
            else -> RamClass.HIGH
        }
        maxPoolBytes = when (ramClass) {
            RamClass.LOW -> 16L * 1024 * 1024
            RamClass.MEDIUM -> 32L * 1024 * 1024
            RamClass.HIGH -> 64L * 1024 * 1024
        }
        Log.i(TAG, "Initialized for $ramClass RAM, maxPool=${maxPoolBytes / 1024 / 1024}MB")
    }

    @WorkerThread
    suspend fun acquire(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        require(width > 0 && height > 0) { "Invalid dimensions: $width x $height" }
        require(width <= MAX_TILE_DIMENSION && height <= MAX_TILE_DIMENSION) {
            "Tile too large: $width x $height > $MAX_TILE_DIMENSION"
        }

        val key = BitmapConfigKey(width, height, config)

        mutex.withLock {
            val list = pool[key]
            if (list != null && list.isNotEmpty()) {
                val pooled = list.removeLast()
                pooled.isCheckedOut = true
                pooled.markUsed()
                activeBitmaps.add(pooled)
                hitCount.incrementAndGet()
                return pooled.bitmap
            }
        }

        missCount.incrementAndGet()
        trimIfNeeded(key.byteSize)

        val bitmap = try {
            Bitmap.createBitmap(width, height, config)
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OOM creating bitmap $width x $height", e)
            emergencyTrim()
            Bitmap.createBitmap(width, height, config)
        }

        val pooled = PooledBitmap(bitmap, key, isCheckedOut = true)
        activeBitmaps.add(pooled)
        return bitmap
    }

    @WorkerThread
    suspend fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return

        val pooled = activeBitmaps.find { it.bitmap === bitmap } ?: return
        activeBitmaps.remove(pooled)
        pooled.isCheckedOut = false
        bitmap.eraseColor(0)

        val key = pooled.key
        mutex.withLock {
            if (totalPooledBytes.get() + key.byteSize > maxPoolBytes) {
                bitmap.recycle()
                evictionCount.incrementAndGet()
                return@withLock
            }

            pool.getOrPut(key) { mutableListOf() }.add(pooled)
            totalPooledBytes.addAndGet(key.byteSize)
        }
    }

    @WorkerThread
    suspend fun releaseAll(bitmaps: List<Bitmap>) {
        bitmaps.forEach { release(it) }
    }

    private suspend fun trimIfNeeded(requiredBytes: Long) {
        mutex.withLock {
            while (totalPooledBytes.get() + requiredBytes > maxPoolBytes && pool.isNotEmpty()) {
                evictOldest()
            }
        }
    }

    private suspend fun emergencyTrim() {
        Log.w(TAG, "Emergency trim triggered")
        mutex.withLock {
            while (pool.isNotEmpty()) {
                evictOldest()
            }
        }
        System.gc()
    }

    private fun evictOldest() {
        var oldestKey: BitmapConfigKey? = null
        var oldestIndex = -1
        var oldestTime = Long.MAX_VALUE

        pool.forEach { (key, list) ->
            list.forEachIndexed { index, pooled ->
                if (pooled.lastUsedAt < oldestTime) {
                    oldestTime = pooled.lastUsedAt
                    oldestKey = key
                    oldestIndex = index
                }
            }
        }

        if (oldestKey != null && oldestIndex >= 0) {
            val list = pool[oldestKey]!!
            val removed = list.removeAt(oldestIndex)
            removed.bitmap.recycle()
            totalPooledBytes.addAndGet(-removed.key.byteSize)
            evictionCount.incrementAndGet()

            if (list.isEmpty()) {
                pool.remove(oldestKey)
            }
        }
    }

    suspend fun trim(percent: Float = 0.5f) {
        require(percent in 0f..1f)
        val target = (totalPooledBytes.get() * (1 - percent)).toLong()
        mutex.withLock {
            while (totalPooledBytes.get() > target && pool.isNotEmpty()) {
                evictOldest()
            }
        }
    }

    suspend fun clear() {
        mutex.withLock {
            pool.values.flatten().forEach { it.bitmap.recycle() }
            pool.clear()
            totalPooledBytes.set(0)
        }
        activeBitmaps.clear()
    }

    fun getStats(): PoolStats = PoolStats(
        pooledBytes = totalPooledBytes.get(),
        maxPoolBytes = maxPoolBytes,
        hitCount = hitCount.get(),
        missCount = missCount.get(),
        evictionCount = evictionCount.get(),
        activeCount = activeBitmaps.size,
        pooledCount = pool.values.sumOf { it.size }
    )

    data class PoolStats(
        val pooledBytes: Long,
        val maxPoolBytes: Long,
        val hitCount: Int,
        val missCount: Int,
        val evictionCount: Int,
        val activeCount: Int,
        val pooledCount: Int
    ) {
        val hitRate: Float = if (hitCount + missCount > 0) {
            hitCount.toFloat() / (hitCount + missCount)
        } else 0f
    }

    private enum class RamClass { LOW, MEDIUM, HIGH }

    private fun Bitmap.Config.bytesPerPixel(): Int = when (this) {
        Bitmap.Config.ARGB_8888 -> 4
        Bitmap.Config.RGB_565 -> 2
        Bitmap.Config.ARGB_4444 -> 2
        Bitmap.Config.ALPHA_8 -> 1
        else -> 4
    }
}
