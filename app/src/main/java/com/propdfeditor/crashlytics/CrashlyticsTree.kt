package com.propdfeditor.crashlytics

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics
import timber.log.Timber

/**
 * Timber tree that forwards errors to Firebase Crashlytics.
 * Only planted in release builds.
 */
class CrashlyticsTree : Timber.Tree() {

    private val crashlytics = FirebaseCrashlytics.getInstance()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority == Log.VERBOSE || priority == Log.DEBUG) {
            return
        }

        crashlytics.setCustomKey("log_tag", tag ?: "unknown")
        crashlytics.log("$tag: $message")

        t?.let { throwable ->
            crashlytics.recordException(throwable)
        }
    }
}
