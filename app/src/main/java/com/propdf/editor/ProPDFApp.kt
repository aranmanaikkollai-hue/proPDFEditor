package com.propdf.editor

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import android.os.StrictMode
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.propdf.editor.core.CrashGuard
import com.propdf.editor.core.GpuOptimizer
import com.propdf.editor.core.cache.LruBitmapCache
import com.propdf.editor.core.pool.BitmapPool
import com.propdf.editor.worker.CleanupWorker
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ProPDFApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    companion object {
        private const val TAG = "ProPDFApp"
        private var initialized = false
    }

    override fun onCreate() {
        // Fast startup: defer non-critical initialization
        super.onCreate()

        // Critical path: PDFBox init (required before any PDF operation)
        PDFBoxResourceLoader.init(applicationContext)

        // Crash prevention
        CrashGuard.initialize()

        // GPU capability detection
        GpuOptimizer.initialize(this)

        // Restore theme (fast, no I/O blocking)
        restoreTheme()

        // Defer heavy initialization to background
        if (!initialized) {
            initialized = true
            applicationContext.mainExecutor.execute {
                initializeBackground()
            }
        }

        // Register memory pressure callbacks
        registerComponentCallbacks(MemoryPressureCallbacks())

        // Enable StrictMode in debug builds for ANR detection
        if (BuildConfig.DEBUG) {
            enableStrictMode()
        }
    }

    override fun getWorkManagerConfiguration(): Configuration {
        return Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()
    }

    private fun restoreTheme() {
        try {
            val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
            when (prefs.getInt("theme_mode", 0)) {
                1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Theme restore failed", e)
        }
    }

    private fun initializeBackground() {
        // Initialize caches (can be slow)
        BitmapPool.getDefaultInstance()
        LruBitmapCache.getInstance(this)

        // Schedule periodic cleanup
        CleanupWorker.schedulePeriodic(this)

        // Reduce process priority for background work
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    }

    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build()
        )
    }

    // ─── Memory Pressure Callbacks ───────────────────────────────

    inner class MemoryPressureCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            Log.w(TAG, "onTrimMemory level=$level")
            LruBitmapCache.getInstance(this@ProPDFApp).trimMemory(level)

            when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                    BitmapPool.getDefaultInstance().clear()
                    System.gc()
                }
                ComponentCallbacks2.TRIM_MEMORY_MODERATE -> {
                    BitmapPool.getDefaultInstance().trimToSize(16L * 1024 * 1024)
                }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}

        override fun onLowMemory() {
            Log.e(TAG, "onLowMemory — emergency cleanup")
            CrashGuard.emergencyCleanup()
        }
    }
}
