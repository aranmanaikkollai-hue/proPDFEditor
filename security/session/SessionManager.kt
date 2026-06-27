package com.propdf.security.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.propdf.core.domain.model.SessionState
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enterprise session manager with automatic timeout and lock enforcement.
 *
 * Architecture:
 * - Tracks user activity across the app via [onUserActivity]
 * - Counts down from configured timeout; locks when expired
 * - Exposes [SessionState] as StateFlow for UI observation
 * - Persists lock state across process death
 * - Memory-optimized: single coroutine scope, no background services
 *
 * Thread-safe: all state mutations happen on a dedicated scope.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "propdf_session"
        private const val KEY_TIMEOUT_MS = "session_timeout_ms"
        private const val KEY_LOCKED = "session_locked"
        private const val DEFAULT_TIMEOUT_MS = 300_000L // 5 minutes
        private const val WARNING_THRESHOLD_SECONDS = 30
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var countdownJob: Job? = null

    private val _sessionState = MutableStateFlow<SessionState>(
        if (prefs.getBoolean(KEY_LOCKED, false)) SessionState.Locked else SessionState.Unlocked
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var currentTimeoutMs: Long = prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)

    init {
        // Restore lock state on initialization
        if (prefs.getBoolean(KEY_LOCKED, false)) {
            _sessionState.value = SessionState.Locked
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Sets the auto-lock timeout. Persists across app restarts.
     */
    fun setTimeout(timeoutMs: Long) {
        currentTimeoutMs = timeoutMs.coerceIn(30_000L, 3_600_000L) // 30s to 1h
        prefs.edit { putLong(KEY_TIMEOUT_MS, currentTimeoutMs) }
    }

    fun getTimeoutMs(): Long = currentTimeoutMs

    // ═══════════════════════════════════════════════════════════════════════
    // SESSION LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Call when user successfully authenticates. Unlocks the session
     * and starts the inactivity countdown.
     */
    fun unlockSession() {
        prefs.edit { putBoolean(KEY_LOCKED, false) }
        _sessionState.value = SessionState.Unlocked
        restartCountdown()
    }

    /**
     * Immediately locks the session (e.g., user pressed lock button).
     */
    fun lockSession() {
        prefs.edit { putBoolean(KEY_LOCKED, true) }
        _sessionState.value = SessionState.Locked
        countdownJob?.cancel()
    }

    /**
     * Call on any user interaction to reset the countdown.
     */
    fun onUserActivity() {
        if (_sessionState.value is SessionState.Unlocked) {
            restartCountdown()
        }
    }

    /**
     * Checks if the session is currently unlocked.
     */
    fun isUnlocked(): Boolean = _sessionState.value is SessionState.Unlocked

    // ═══════════════════════════════════════════════════════════════════════
    // COUNTDOWN LOGIC
    // ═══════════════════════════════════════════════════════════════════════

    private fun restartCountdown() {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            val warningAt = currentTimeoutMs - (WARNING_THRESHOLD_SECONDS * 1000)
            
            if (warningAt > 0) {
                delay(warningAt)
                if (isActive && _sessionState.value is SessionState.Unlocked) {
                    _sessionState.value = SessionState.Expiring(WARNING_THRESHOLD_SECONDS)
                    
                    // Count down the last 30 seconds
                    var remaining = WARNING_THRESHOLD_SECONDS
                    while (isActive && remaining > 0) {
                        delay(1000)
                        remaining--
                        _sessionState.value = SessionState.Expiring(remaining)
                    }
                }
            } else {
                delay(currentTimeoutMs)
            }

            // Timeout reached
            if (isActive) {
                lockSession()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════

    fun shutdown() {
        countdownJob?.cancel()
        scope.cancel()
    }
}
