package com.propdfeditor

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.propdf.scanner.data.processing.OpenCvAvailability
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltAndroidApp
class ProPDFApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    // Lazy so the batch database is not opened on the main thread during startup.
    @Inject
    lateinit var batchJobRepository: dagger.Lazy<com.propdfeditor.batch.repository.BatchJobRepository>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .build()

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        
        // Safe startup diagnostics
        val startupStage = "Application.onCreate"
        try {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            }
            Timber.d("ProPDFApplication starting")

            // The scanner module's edge detection/perspective correction/
            // enhancement pipeline (OpenCvDocumentProcessor and friends)
            // all construct org.opencv.core.Mat directly and never called
            // any explicit native-library load anywhere in the codebase --
            // confirmed by inspection, nothing called System.loadLibrary
            // or OpenCVLoader anywhere. That relied entirely on the Maven
            // Central "full" AAR's implicit auto-load, which a Firebase
            // Test Lab Robo crawl on a real redfin/Pixel-4a device showed
            // failing with UnsatisfiedLinkError on Mat.n_Mat() the first
            // time the scanner's live edge detection touched OpenCV --
            // i.e. this was a real crash on real hardware, not a
            // theoretical gap. OpenCvAvailability owns the explicit
            // OpenCVLoader.initLocal() load plus the runtime circuit
            // breaker every OpenCV call site now routes through; this call
            // must happen here, once, before any scanner screen can be
            // opened. safeInit() means a genuinely unsupported ABI
            // degrades to "scanner edge detection unavailable" rather than
            // crashing app launch.
            safeInit("OpenCV") { OpenCvAvailability.initialize() }

            // Decrypted copies of protected PDFs left in the cache by a killed process. Only entries
            // older than this process are removed, so a copy a viewer creates after launch is safe.
            val processStart = System.currentTimeMillis()
            safeInit("PurgeDecryptedCopies") {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
                    .launch {
                        try {
                            val removed = com.propdf.viewer.ui.ViewerUnlockedCache.purge(cacheDir, null, processStart)
                            if (removed > 0) Timber.i("Removed %d stale decrypted working copies", removed)
                        } catch (e: Exception) {
                            Timber.w(e, "Stale decrypted copy cleanup failed")
                        }
                    }
            }

            // Copies made for sharing (cache/shared/) are only needed briefly; remove old ones.
            safeInit("PurgeSharedStaging") {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
                    .launch {
                        try {
                            val removed = com.propdf.core.share.ShareStaging.purgeOlderThan(this@ProPDFApplication)
                            if (removed > 0) Timber.i("Removed %d old shared copies", removed)
                        } catch (e: Exception) {
                            Timber.w(e, "Shared copy cleanup failed")
                        }
                    }
            }

            // Batch-job passwords written by earlier versions are stored in the clear; protect or
            // scrub them once at startup, off the main thread. Counts only are logged.
            safeInit("BatchSecretMigration") {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
                    .launch {
                        try {
                            val result = batchJobRepository.get().migrateLegacyPlaintextSecrets()
                            if (result.sealed + result.scrubbed + result.failed > 0) {
                                Timber.i(
                                    "Batch secret migration: sealed=%d scrubbed=%d failed=%d",
                                    result.sealed, result.scrubbed, result.failed
                                )
                            }
                        } catch (e: Exception) {
                            Timber.w(e, "Batch secret migration failed")
                        }
                    }
            }

            Timber.d("ProPDFApplication initialized successfully")
        } catch (e: Throwable) {
            // Last-resort logging if even Timber fails
            Log.e("ProPDF", "Fatal error in $startupStage", e)
            throw e
        }
    }

    private fun safeInit(name: String, block: () -> Unit) {
        try {
            block()
            Timber.d("$name initialized")
        } catch (e: Throwable) {
            Timber.e(e, "$name initialization failed — continuing without it")
            // Do not rethrow; optional services must not block startup
        }
    }
}
