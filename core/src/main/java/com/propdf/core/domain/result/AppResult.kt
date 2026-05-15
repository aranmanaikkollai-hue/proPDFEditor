package com.propdf.core.domain.result

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: AppException) : AppResult<Nothing>() {
        // Backward compatibility: allow Error(exception) constructor
        constructor(message: String) : this(AppException.Unknown(message))
    }
    object Loading : AppResult<Nothing>() {
        // Backward compatibility: allow Loading() syntax
        operator fun invoke(): Loading = this
    }
}

inline fun <T> AppResult<T>.onSuccess(action: (T) -> Unit): AppResult<T> {
    if (this is AppResult.Success) action(data)
    return this
}

inline fun <T> AppResult<T>.onError(action: (AppResult.Error) -> Unit): AppResult<T> {
    if (this is AppResult.Error) action(this)
    return this
}
