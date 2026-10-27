package com.propdfeditor.startup

import android.annotation.SuppressLint
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
// Intentionally NOT registered in the manifest (project decision: left as is for now). Lint rule
// EnsureInitializerMetadata is suppressed here so that a real, new lint error is not hidden by this known one.
// Note: this means the schedules in the legacy copy (document index, duplicate scan, smart folders, backfill,
// recycle-bin cleanup) are not started by this class.
@SuppressLint("EnsureInitializerMetadata")
class WorkManagerInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // DO NOT call WorkManager.getInstance(context) here.
        // WorkManager will be initialized by androidx.work.WorkManagerInitializer
        // after all custom initializers complete.
        Log.d("Startup", "WorkManagerInitializer complete (no-op)")
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
