package com.propdf.security.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.NoParamUseCase
import com.propdf.core.domain.usecase.UseCase
import com.propdf.security.biometric.BiometricAuthManager
import com.propdf.security.session.SessionManager
import javax.inject.Inject

// ── UnlockSessionUseCase ──────────────────────────────────────────────────

class UnlockSessionUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : NoParamUseCase<Unit>(dispatchers) {

    override suspend fun execute() {
        sessionManager.unlockSession()
    }
}

// ── LockSessionUseCase ────────────────────────────────────────────────────

class LockSessionUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : NoParamUseCase<Unit>(dispatchers) {

    override suspend fun execute() {
        sessionManager.lockSession()
    }
}

// ── CheckSessionStatusUseCase ─────────────────────────────────────────────

class CheckSessionStatusUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : NoParamUseCase<Boolean>(dispatchers) {

    override suspend fun execute(): Boolean {
        return sessionManager.isUnlocked()
    }
}

// ── SetSessionTimeoutUseCase ──────────────────────────────────────────────

class SetSessionTimeoutUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : UseCase<Long, Unit>(dispatchers) {

    override suspend fun execute(params: Long) {
        sessionManager.setTimeout(params)
    }
}
