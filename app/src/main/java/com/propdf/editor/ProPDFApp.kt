package com.propdf.editor

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration as ResConfiguration
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
import java.util.concurrent.Executors
import javax.inject.Inject

@HiltAndroidApp
class ProPDFApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    companion object {
        private const val TAG = "ProPDFApp"
        private var initialized = false
        private val backgroundExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "propdf-startup").apply { isDaemon = true }
        }
    }

    override fun onCreate() {
        // Fast startup: defer non-critical initialization
        super.onCreate()

        // Crash prevention must be installed before any optional startup work.
        safeStartup("CrashGuard") { CrashGuard.initialize(this) }

        // Critical path: PDFBox init (required before any PDF operation). Keep this
        // non-fatal so a bad/missing PDFBox asset never blocks the Home screen.
        safeStartup("PDFBox") { PDFBoxResourceLoader.init(applicationContext) }

        // GPU capability detection is useful but optional for first draw.
        safeStartup("GPU optimizer") { GpuOptimizer.initialize(this) }

        // Restore theme (fast, no I/O blocking)
        restoreTheme()

        // Defer heavy initialization to background
        if (!initialized) {
            initialized = true
            backgroundExecutor.execute {
                initializeBackground()
            }
        }

        // Register memory pressure callbacks
        safeStartup("memory callbacks") { registerComponentCallbacks(MemoryPressureCallbacks()) }

        // Enable StrictMode in debug builds for ANR detection
        if (BuildConfig.DEBUG) {
            safeStartup("StrictMode") { enableStrictMode() }
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()

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
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

        safeStartup("bitmap pool") { BitmapPool.getDefaultInstance() }
        safeStartup("bitmap cache") { LruBitmapCache.getInstance(this) }
        safeStartup("cleanup worker") { CleanupWorker.schedulePeriodic(this) }
    }

    private fun safeStartup(name: String, block: () -> Unit) {
        try {
            block()
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "Startup step failed because of low memory: $name", oom)
            CrashGuard.emergencyCleanup(this)
        } catch (throwable: Throwable) {
            Log.e(TAG, "Startup step failed and was deferred/skipped: $name", throwable)
        }
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
            safeStartup("trim bitmap cache") { LruBitmapCache.getInstance(this@ProPDFApp).trimMemory(level) }

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

        override fun onConfigurationChanged(newConfig: ResConfiguration) {}

        override fun onLowMemory() {
            Log.e(TAG, "onLowMemory — emergency cleanup")
            CrashGuard.emergencyCleanup(this@ProPDFApp)
        }
    }
}
