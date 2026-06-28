package com.propdf.security.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.usecase.NoParamUseCase
import com.propdf.core.domain.usecase.UseCase
import com.propdf.core.domain.model.SessionState
import com.propdf.security.session.SessionManager
import javax.inject.Inject

class UnlockSessionUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : NoParamUseCase<Unit>(dispatchers) {

    override suspend fun execute() {
        sessionManager.unlockSession()
    }
}

class LockSessionUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : NoParamUseCase<Unit>(dispatchers) {

    override suspend fun execute() {
        sessionManager.lockSession()
    }
}

class CheckSessionStatusUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : NoParamUseCase<Boolean>(dispatchers) {

    override suspend fun execute(): Boolean {
        return sessionManager.sessionState.value !is SessionState.Locked
    }
}

class SetSessionTimeoutUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val sessionManager: SessionManager
) : UseCase<Long, Unit>(dispatchers) {

    override suspend fun execute(params: Long) {
        sessionManager.setTimeoutMs(params)
    }
}
