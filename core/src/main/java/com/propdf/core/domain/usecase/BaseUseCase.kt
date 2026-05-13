package com.propdf.core.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Base class for all UseCases.
 * Ensures consistent error handling and dispatcher usage.
 */
abstract class UseCase<P, R>(private val dispatchers: DispatcherProvider) {
    suspend operator fun invoke(params: P): AppResult<R> = withContext(dispatchers.io) {
        try {
            AppResult.Success(execute(params))
        } catch (e: Exception) {
            AppResult.Error(e.toAppException())
        }
    }

    protected abstract suspend fun execute(params: P): R
}

abstract class FlowUseCase<P, R>(private val dispatchers: DispatcherProvider) {
    operator fun invoke(params: P): Flow<<AppResult<R>> = flow {
        emit(AppResult.Loading())
        emit(AppResult.Success(execute(params)))
    }.catch { emit(AppResult.Error(it.toAppException())) }
        .flowOn(dispatchers.io)

    protected abstract suspend fun execute(params: P): R
}

abstract class NoParamUseCase<R>(private val dispatchers: DispatcherProvider) {
    suspend operator fun invoke(): AppResult<R> = withContext(dispatchers.io) {
        try {
            AppResult.Success(execute())
        } catch (e: Exception) {
            AppResult.Error(e.toAppException())
        }
    }

    protected abstract suspend fun execute(): R
}
