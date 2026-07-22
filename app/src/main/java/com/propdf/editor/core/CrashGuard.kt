package com.propdf.editor.core

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext

/**
 * Production-grade crash and ANR prevention utilities.
 * 
 * Features:
 * - ANR watchdog: detects main thread blocking and logs stack traces
 * - Coroutine safety wrappers with timeout and exception isolation
 * - OOM recovery strategies
 * - StrictMode helper for debug builds
 * - Safe execution wrappers that never crash the app
 */
object CrashGuard {
    @PublishedApi internal const val TAG = "CrashGuard"
    @PublishedApi internal const val ANR_THRESHOLD_MS = 5000L

    @PublishedApi internal val mainHandler = Handler(Looper.getMainLooper())
    private val anrWatchdog = ANRWatchdog()

    /**
     * Initialize crash guards. Call from Application.onCreate()
     */
    fun initialize() {
        // Set default uncaught exception handler
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e(TAG, "FATAL: Uncaught exception on ${thread.name}", throwable)
            // Attempt graceful cleanup before crash
            emergencyCleanup()
            defaultHandler?.uncaughtException(thread, throwable)
        }

        // Start ANR watchdog
        anrWatchdog.start()
    }

    /**
     * Safe coroutine execution with automatic timeout and error isolation.
     * Never crashes — exceptions are caught and logged.
     */
    fun safeLaunch(
        scope: CoroutineScope,
        context: CoroutineContext = Dispatchers.Main,
        timeoutMs: Long = 30000L,
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ): Job = scope.launch(context + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "Coroutine error (handled): ${e.message}", e)
        onError?.invoke(e)
    }) {
        try {
            withTimeout(timeoutMs) {
                block()
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Coroutine timed out after ${timeoutMs}ms")
            onError?.invoke(TimeoutException("Operation timed out"))
        } catch (e: CancellationException) {
            // Normal cancellation, ignore
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM in coroutine — triggering emergency cleanup")
            emergencyCleanup()
            onError?.invoke(oom)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in safe coroutine", e)
            onError?.invoke(e)
        }
    }

    /**
     * Execute block with ANR detection. If block takes too long, logs diagnostic.
     */
    inline fun <T> withAnrGuard(crossinline block: () -> T): T {
        val startTime = System.currentTimeMillis()
        val thread = Thread.currentThread()

        // Schedule ANR check
        mainHandler.postDelayed({
            if (thread.isAlive) {
                Log.w(TAG, "ANR WARNING: ${thread.name} blocked for > ${ANR_THRESHOLD_MS}ms")
                Log.w(TAG, "Stack trace:\n${thread.stackTrace.joinToString("\n")}")
            }
        }, ANR_THRESHOLD_MS)

        return try {
            block()
        } finally {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > ANR_THRESHOLD_MS) {
                Log.w(TAG, "Slow operation completed in ${elapsed}ms on ${thread.name}")
            }
        }
    }

    /**
     * Emergency cleanup under memory pressure.
     * Clears all caches, triggers GC, reduces process priority.
     */
    fun emergencyCleanup() {
        Log.w(TAG, "EMERGENCY CLEANUP triggered")

        // Reduce priority to let system reclaim memory
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        // Clear all static caches
        com.propdf.editor.core.cache.LruBitmapCache.getInstance(
            android.app.Application()
        ).clear()
        com.propdf.editor.core.pool.BitmapPool.getDefaultInstance().clear()

        // Suggest GC
        System.gc()
        System.runFinalization()
        System.gc()
    }

    /**
     * Wrap a potentially dangerous operation with OOM recovery.
     */
    inline fun <T> withOomRecovery(fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM recovered with fallback")
            emergencyCleanup()
            fallback
        }
    }

    // ─── ANR Watchdog ──────────────────────────────────────────────

    private class ANRWatchdog : Thread("ANR-Watchdog") {
        private var lastTick = System.currentTimeMillis()
        private val handler = Handler(Looper.getMainLooper())

        init { isDaemon = true }

        override fun run() {
            while (!isInterrupted) {
                lastTick = System.currentTimeMillis()
                handler.post { lastTick = System.currentTimeMillis() }

                sleep(ANR_THRESHOLD_MS)

                val delta = System.currentTimeMillis() - lastTick
                if (delta >= ANR_THRESHOLD_MS - 100) {
                    Log.e(TAG, "ANR DETECTED: Main thread unresponsive for ${delta}ms")
                    val mainThread = Looper.getMainLooper().thread
                    Log.e(TAG, mainThread.stackTrace.joinToString("\n") { "\tat $it" })
                }
            }
        }
    }
}
