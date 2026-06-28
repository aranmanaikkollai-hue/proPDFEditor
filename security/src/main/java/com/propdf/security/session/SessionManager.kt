package com.propdf.security.session

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.propdf.core.domain.model.SessionState
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
import javax.inject.Singleton

/**
 * Enterprise session manager with automatic timeout and lock enforcement.
 */
@Singleton
class SessionManager(
    private val context: Context
) {

    companion object {
        private const val PREFS_NAME = "propdf_session"
        private const val KEY_TIMEOUT_MS = "session_timeout_ms"
        private const val KEY_LOCKED = "session_locked"
        private const val DEFAULT_TIMEOUT_MS = 300_000L
        private const val WARNING_THRESHOLD_SECONDS = 30
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Locked)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timeoutJob: Job? = null

    init {
        val wasLocked = prefs.getBoolean(KEY_LOCKED, true)
        _sessionState.value = if (wasLocked) SessionState.Locked else SessionState.Unlocked
    }

    fun unlockSession() {
        _sessionState.value = SessionState.Unlocked
        prefs.edit { putBoolean(KEY_LOCKED, false) }
        startTimeoutCountdown()
    }

    fun lockSession() {
        timeoutJob?.cancel()
        _sessionState.value = SessionState.Locked
        prefs.edit { putBoolean(KEY_LOCKED, true) }
    }

    fun setTimeoutMs(timeoutMs: Long) {
        prefs.edit { putLong(KEY_TIMEOUT_MS, timeoutMs) }
        if (_sessionState.value is SessionState.Unlocked) {
            startTimeoutCountdown()
        }
    }

    fun getTimeoutMs(): Long = prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS)

    fun onUserActivity() {
        if (_sessionState.value !is SessionState.Locked) {
            startTimeoutCountdown()
        }
    }

    private fun startTimeoutCountdown() {
        timeoutJob?.cancel()
        val timeoutMs = getTimeoutMs()
        if (timeoutMs <= 0L) return

        timeoutJob = scope.launch {
            var remaining = timeoutMs / 1000L
            while (remaining > 0) {
                delay(1_000L)
                remaining--
                if (remaining <= WARNING_THRESHOLD_SECONDS && remaining > 0) {
                    _sessionState.value = SessionState.Expiring(remaining.toInt())
                }
            }
            lockSession()
        }
    }

    fun destroy() {
        scope.cancel()
    }
}
