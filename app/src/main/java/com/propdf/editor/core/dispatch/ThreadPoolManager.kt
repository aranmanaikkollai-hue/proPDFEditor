package com.propdf.editor.core.dispatch

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.*
import kotlin.math.max
import kotlin.math.min

/**
 * Centralized thread pool manager with optimized configurations for different workloads.
 * 
 * Pools:
 * - PDF_RENDER: CPU-bound, limited to core count to prevent thrashing
 * - PDF_IO: IO-bound, larger pool for file operations
 * - BACKGROUND: Long-running background tasks (WorkManager delegates here)
 * - ANNOTATION: UI-adjacent drawing operations
 * - CACHE: Disk cache operations
 */
object ThreadPoolManager {

    private val CPU_COUNT = Runtime.getRuntime().availableProcessors()
    private val MEMORY_CLASS = (Runtime.getRuntime().maxMemory() / (1024 * 1024)).toInt()

    // CPU-bound: render, image processing
    private val renderExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            max(2, CPU_COUNT - 1),
            CPU_COUNT,
            30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(64), // Backpressure: queue fills, rejects trigger caller-handling
            ThreadFactory { r ->
                Thread(r, "pdf-render-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 1
                }
            }
        ).apply {
            rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
        }
    }

    // IO-bound: file read/write, network
    private val ioExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            CPU_COUNT * 2,
            CPU_COUNT * 4,
            60L, TimeUnit.SECONDS,
            LinkedBlockingQueue(128),
            ThreadFactory { r ->
                Thread(r, "pdf-io-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY - 2
                }
            }
        ).apply {
            rejectedExecutionHandler = ThreadPoolExecutor.CallerRunsPolicy()
        }
    }

    // Background: compression, merge, split
    private val backgroundExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1,
            2,
            120L, TimeUnit.SECONDS,
            LinkedBlockingQueue(16),
            ThreadFactory { r ->
                Thread(r, "pdf-bg-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        )
    }

    // Cache: disk I/O for bitmap cache
    private val cacheExecutor: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1,
            2,
            30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(32),
            ThreadFactory { r ->
                Thread(r, "pdf-cache-${threadCounter.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                }
            }
        )
    }

    private val threadCounter = java.util.concurrent.atomic.AtomicInteger(0)

    val RenderDispatcher: CoroutineDispatcher by lazy {
        renderExecutor.asCoroutineDispatcher()
    }

    val IoDispatcher: CoroutineDispatcher by lazy {
        ioExecutor.asCoroutineDispatcher()
    }

    val BackgroundDispatcher: CoroutineDispatcher by lazy {
        backgroundExecutor.asCoroutineDispatcher()
    }

    val CacheDispatcher: CoroutineDispatcher by lazy {
        cacheExecutor.asCoroutineDispatcher()
    }

    /**
     * Emergency shutdown for low memory conditions.
     */
    fun shutdownAll() {
        renderExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        backgroundExecutor.shutdownNow()
        cacheExecutor.shutdownNow()
    }

    fun getActiveCount(): Int = 
        renderExecutor.activeCount + ioExecutor.activeCount + 
        backgroundExecutor.activeCount + cacheExecutor.activeCount

    fun getQueueSize(): Int = 
        renderExecutor.queue.size + ioExecutor.queue.size + 
        backgroundExecutor.queue.size + cacheExecutor.queue.size
}
