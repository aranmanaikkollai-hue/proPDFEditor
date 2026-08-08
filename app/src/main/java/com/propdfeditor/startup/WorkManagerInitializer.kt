package com.propdfeditor.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer

/**
 * CRITICAL FIX: This initializer previously called WorkManager.getInstance(context),
 * which crashed because the default androidx.work.WorkManagerInitializer was removed
 * from the manifest. WorkManager is now initialized automatically by the default
 * androidx.work.WorkManagerInitializer, which reads Configuration from
 * ProPDFApplication (which implements Configuration.Provider).
 *
 * Any work scheduling that was previously done here has been moved to
 * ProPDFApplication.onCreate() (safe post-initialization) or to MainActivity.
 */
class WorkManagerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // DO NOT call WorkManager.getInstance(context) here.
        // WorkManager will be initialized by androidx.work.WorkManagerInitializer
        // after all custom initializers complete.
        Log.d("Startup", "WorkManagerInitializer complete (no-op)")
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
