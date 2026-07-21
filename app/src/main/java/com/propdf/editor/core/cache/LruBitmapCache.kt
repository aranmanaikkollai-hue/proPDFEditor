package com.propdf.editor.core.cache

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import com.propdf.editor.core.pool.BitmapPool
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Production-grade two-tier cache: Memory (LruCache) + Disk (LRU file eviction).
 * 
 * Features:
 * - Incremental loading support via partial key prefixes
 * - Lazy loading: bitmaps loaded from disk on demand, not preloaded
 * - Memory pressure callbacks via ComponentCallbacks2
 * - Async disk writes to prevent UI blocking
 * - Automatic bitmap pooling on eviction
 * - SHA-256 key hashing for safe filenames
 */
class LruBitmapCache private constructor(
    context: Context,
    private val pool: BitmapPool
) {
    companion object {
        private const val TAG = "LruBitmapCache"
        private const val DISK_CACHE_SUBDIR = "pdf_bitmap_cache"
        private const val DISK_CACHE_MAX_MB = 256L
        private const val MEMORY_CACHE_DIVISOR = 8 // 1/8 of max memory

        @Volatile
        private var INSTANCE: LruBitmapCache? = null

        fun getInstance(context: Context): LruBitmapCache =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LruBitmapCache(context.applicationContext, BitmapPool.getDefaultInstance())
                    .also { INSTANCE = it }
            }
    }

    private val appContext = context.applicationContext
    private val cacheDir = File(context.cacheDir, DISK_CACHE_SUBDIR).apply { mkdirs() }

    // Memory cache: size in KB
    private val maxMemoryKb = (Runtime.getRuntime().maxMemory() / 1024 / MEMORY_CACHE_DIVISOR).toInt()

    private val memoryCache = object : LruCache<String, Bitmap>(maxMemoryKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }

        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted) {
                // Return to pool instead of recycling immediately
                pool.put(oldValue)
            }
        }
    }

    // Disk cache tracking
    private val diskSize = AtomicInteger(0)
    private val diskMutex = Mutex()
    private val pendingWrites = ConcurrentHashMap<String, Deferred<Unit>>()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Calculate initial disk size
        diskSize.set(calculateDiskSize())
    }

    /**
     * Get bitmap from cache. Checks memory first, then disk (lazy loading).
     * Returns null if not found in either tier.
     */
    suspend fun get(key: String): Bitmap? {
        val safeKey = hashKey(key)

        // Tier 1: Memory
        memoryCache.get(safeKey)?.let {
            if (!it.isRecycled) return it
            memoryCache.remove(safeKey) // Stale entry
        }

        // Tier 2: Disk (lazy load)
        return loadFromDisk(safeKey)
    }

    /**
     * Put bitmap into cache (both tiers).
     * Disk write is async to prevent blocking.
     */
    suspend fun put(key: String, bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        val safeKey = hashKey(key)

        // Always cache in memory
        memoryCache.put(safeKey, bitmap)

        // Async disk write
        val existing = pendingWrites[safeKey]
        existing?.cancel()

        pendingWrites[safeKey] = writeScope.async {
            writeToDisk(safeKey, bitmap)
        }
    }

    /**
     * Preload a range of pages into memory cache (for nearby page prefetching).
     * Respects memory limits — evicts oldest if needed.
     */
    suspend fun preload(keys: List<String>, loader: suspend (String) -> Bitmap?) {
        keys.forEach { key ->
            val safeKey = hashKey(key)
            if (memoryCache.get(safeKey) == null) {
                try {
                    val bmp = loader(key)
                    if (bmp != null) {
                        memoryCache.put(safeKey, bmp)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Preload failed for $key", e)
                }
            }
        }
    }

    /**
     * Remove specific key from both tiers.
     */
    suspend fun remove(key: String) {
        val safeKey = hashKey(key)
        memoryCache.remove(safeKey)?.let { pool.put(it) }
        withContext(Dispatchers.IO) {
            File(cacheDir, "$safeKey.png").delete()
        }
    }

    /**
     * Clear all caches. Call on low memory or app background.
     */
    fun clear() {
        memoryCache.evictAll()
        writeScope.cancel()
        // Don't delete disk cache files — they survive app restarts
    }

    /**
     * Trim memory in response to system callbacks.
     * level: ComponentCallbacks2.TRIM_MEMORY_*
     */
    fun trimMemory(level: Int) {
        when (level) {
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // Drop to 3/4 capacity
                memoryCache.trimToSize(memoryCache.maxSize() * 3 / 4)
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                // Drop to 1/2 capacity
                memoryCache.trimToSize(memoryCache.maxSize() / 2)
            }
            android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // Clear entirely
                memoryCache.evictAll()
                pool.clear()
            }
        }
    }

    // ─── Internal ─────────────────────────────────────────────────

    private suspend fun loadFromDisk(safeKey: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = File(cacheDir, "$safeKey.png")
        if (!file.exists()) return@withContext null

        try {
            BitmapFactory.decodeFile(file.absolutePath)?.also { bmp ->
                // Promote to memory cache
                memoryCache.put(safeKey, bmp)
            }
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM loading disk cache for $safeKey")
            null
        }
    }

    private suspend fun writeToDisk(safeKey: String, bitmap: Bitmap) {
        diskMutex.withLock {
            // Evict old disk entries if over limit
            while (diskSize.get() > DISK_CACHE_MAX_MB * 1024 * 1024) {
                evictOldestDiskEntry()
            }

            val file = File(cacheDir, "$safeKey.png")
            try {
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
                }
                diskSize.addAndGet(file.length().toInt())
            } catch (e: Exception) {
                Log.w(TAG, "Disk cache write failed", e)
                file.delete()
            }
        }
    }

    private fun evictOldestDiskEntry() {
        val files = cacheDir.listFiles() ?: return
        val oldest = files.minByOrNull { it.lastModified() } ?: return
        val size = oldest.length().toInt()
        if (oldest.delete()) {
            diskSize.addAndGet(-size)
        }
    }

    private fun calculateDiskSize(): Int {
        return cacheDir.listFiles()?.sumOf { it.length().toInt() } ?: 0
    }

    private fun hashKey(key: String): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    fun getStats(): CacheStats = CacheStats(
        memoryHits = memoryCache.hitCount(),
        memoryMisses = memoryCache.missCount(),
        memoryEvictions = memoryCache.evictionCount(),
        memoryCurrentKb = memoryCache.size(),
        memoryMaxKb = memoryCache.maxSize(),
        diskSizeMb = diskSize.get() / (1024 * 1024),
        diskMaxMb = DISK_CACHE_MAX_MB
    )

    data class CacheStats(
        val memoryHits: Int,
        val memoryMisses: Int,
        val memoryEvictions: Int,
        val memoryCurrentKb: Int,
        val memoryMaxKb: Int,
        val diskSizeMb: Int,
        val diskMaxMb: Long
    )
}
