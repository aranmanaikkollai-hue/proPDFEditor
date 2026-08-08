package com.propdfeditor.di

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InAppReviewManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // LAZY: Do not create ReviewManager during construction
    private val reviewManager by lazy {
        try {
            ReviewManagerFactory.create(context)
        } catch (e: Throwable) {
            Timber.e(e, "Play ReviewManager unavailable")
            null
        }
    }

    fun requestReview(activity: Activity) {
        val manager = reviewManager ?: run {
            Timber.d("ReviewManager not available")
            return
        }

        try {
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    manager.launchReviewFlow(activity, reviewInfo)
                        .addOnFailureListener { Timber.e(it, "Review flow failed") }
                } else {
                    Timber.e(task.exception, "Review request failed")
                }
            }
        } catch (e: Throwable) {
            Timber.e(e, "Unexpected error requesting review")
        }
    }
}
