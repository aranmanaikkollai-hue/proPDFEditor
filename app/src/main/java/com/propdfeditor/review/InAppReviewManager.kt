package com.propdfeditor.review

import android.app.Activity
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.play.core.review.ReviewManagerFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.reviewDataStore: DataStore<Preferences> by preferencesDataStore(name = "review_prefs")

@Singleton
class InAppReviewManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val dataStore = context.reviewDataStore
    private val manager = ReviewManagerFactory.create(context)

    companion object {
        private val KEY_LAUNCH_COUNT = intPreferencesKey("launch_count")
        private val KEY_LAST_REVIEW_TIME = longPreferencesKey("last_review_time")
        private val KEY_PDF_OPEN_COUNT = intPreferencesKey("pdf_open_count")
        private const val MIN_LAUNCHES = 5
        private const val MIN_PDF_OPENS = 3
        private const val MIN_DAYS_BETWEEN_REVIEWS = 30L * 24 * 60 * 60 * 1000
    }

    suspend fun onAppLaunch() {
        dataStore.edit { prefs ->
            val current = prefs[KEY_LAUNCH_COUNT] ?: 0
            prefs[KEY_LAUNCH_COUNT] = current + 1
        }
    }

    suspend fun onPdfOpened() {
        dataStore.edit { prefs ->
            val current = prefs[KEY_PDF_OPEN_COUNT] ?: 0
            prefs[KEY_PDF_OPEN_COUNT] = current + 1
        }
    }

    suspend fun shouldShowReview(): Boolean {
        val prefs = dataStore.data.first()
        val launches = prefs[KEY_LAUNCH_COUNT] ?: 0
        val pdfOpens = prefs[KEY_PDF_OPEN_COUNT] ?: 0
        val lastReview = prefs[KEY_LAST_REVIEW_TIME] ?: 0L
        val now = System.currentTimeMillis()

        if (launches < MIN_LAUNCHES) return false
        if (pdfOpens < MIN_PDF_OPENS) return false
        if (now - lastReview < MIN_DAYS_BETWEEN_REVIEWS) return false

        return true
    }

    suspend fun requestReview(activity: Activity, onComplete: () -> Unit) {
        if (!shouldShowReview()) {
            onComplete()
            return
        }

        val request = manager.requestReviewFlow()
        request.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val reviewInfo = task.result
                val flow = manager.launchReviewFlow(activity, reviewInfo)
                flow.addOnCompleteListener {
                    // Update last review time regardless of result
                    kotlinx.coroutines.runBlocking {
                        dataStore.edit { prefs ->
                            prefs[KEY_LAST_REVIEW_TIME] = System.currentTimeMillis()
                            prefs[KEY_LAUNCH_COUNT] = 0
                            prefs[KEY_PDF_OPEN_COUNT] = 0
                        }
                    }
                    onComplete()
                }
            } else {
                onComplete()
            }
        }
    }
}
