package com.propdfeditor

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.ktx.analytics
import com.google.firebase.crashlytics.ktx.crashlytics
import com.google.firebase.ktx.Firebase
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ProPDFApplication : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

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

            // Safe Firebase initialization — must not crash startup
            safeInit("Firebase") { FirebaseApp.initializeApp(this) }
            safeInit("FirebaseAnalytics") { Firebase.analytics.setAnalyticsCollectionEnabled(true) }
            safeInit("FirebaseCrashlytics") { Firebase.crashlytics.setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG) }

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
