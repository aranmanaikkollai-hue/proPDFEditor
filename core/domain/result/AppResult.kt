package com.propdf.core.domain.result

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

/**
 * Sealed class representing the result of any operation.
 * Replaces raw exceptions with typed outcomes.
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: AppException) : AppResult<Nothing>()
    data class Loading(val progress: Float = 0f) : AppResult<Nothing>()
}

/**
 * Typed exceptions for centralized error handling.
 */
sealed class AppException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NetworkError(message: String = "Network unavailable", cause: Throwable? = null) : AppException(message, cause)
    class FileNotFound(message: String = "File not found", cause: Throwable? = null) : AppException(message, cause)
    class InvalidFormat(message: String = "Invalid file format", cause: Throwable? = null) : AppException(message, cause)
    class PermissionDenied(message: String = "Permission denied", cause: Throwable? = null) : AppException(message, cause)
    class PasswordRequired(message: String = "Password required", cause: Throwable? = null) : AppException(message, cause)
    class WrongPassword(message: String = "Wrong password", cause: Throwable? = null) : AppException(message, cause)
    class OutOfMemory(message: String = "Out of memory", cause: Throwable? = null) : AppException(message, cause)
    class Unknown(message: String = "Unknown error", cause: Throwable? = null) : AppException(message, cause)
}

fun <T> Result<T>.toAppResult(): AppResult<T> =
    fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(it.toAppException()) }
    )

fun Throwable.toAppException(): AppException = when (this) {
    is AppException -> this
    is java.io.FileNotFoundException -> AppException.FileNotFound(cause = this)
    is java.security.GeneralSecurityException -> AppException.WrongPassword(cause = this)
    is OutOfMemoryError -> AppException.OutOfMemory(cause = this)
    is SecurityException -> AppException.PermissionDenied(cause = this)
    else -> AppException.Unknown(cause = this)
}

fun <T> Flow<T>.asAppResult(): Flow<AppResult<T>> =
    map<T, AppResult<T>> { AppResult.Success(it) }
        .onStart { emit(AppResult.Loading()) }
        .catch { emit(AppResult.Error(it.toAppException())) }

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (AppException) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(exception)
    return this
}

inline fun <T> AppResult<T>.onLoading(action: (Float) -> Unit): AppResult<T> {
    if (this is AppResult.Loading) action(progress)
    return this
}

fun <T, R> AppResult<T>.map(transform: (T) -> R): AppResult<R> = when (this) {
    is AppResult.Success -> AppResult.Success(transform(data))
    is AppResult.Error -> this
    is AppResult.Loading -> this
}
