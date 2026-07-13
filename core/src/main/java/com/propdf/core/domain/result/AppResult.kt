package com.propdf.core.domain.result

import kotlin.Result

/**
 * Sealed class representing the result of an operation.
 * Use this instead of throwing exceptions across module boundaries.
 */
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: AppException) : AppResult<Nothing>() {
        constructor(message: String) : this(AppException.Unknown(message))
        constructor(message: String, cause: Throwable) : this(AppException.Unknown(message)) {
            exception.initCause(cause)
        }
        val message: String? get() = exception.message
    }
    object Loading : AppResult<Nothing>() {
        operator fun invoke(): Loading = this
    }
}

/** Execute action only if result is Success. */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

/** Execute action only if result is Error. */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> AppResult<T>.onError(action: (AppResult.Error) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(this)
    return this
}

/** Convert Kotlin [Result] to [AppResult]. */
@Suppress("NOTHING_TO_INLINE")
inline fun <T> Result<T>.toAppResult(): AppResult<T> {
    return fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(it.toAppException()) }
    )
}
