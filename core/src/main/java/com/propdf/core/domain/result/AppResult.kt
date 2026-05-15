package com.propdf.core.domain.result

sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val exception: AppException) : AppResult<Nothing>() {
        constructor(message: String) : this(AppException.Unknown(message))
        val message: String? get() = exception.message
    }
    object Loading : AppResult<Nothing>() {
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

/**
 * Extension function to convert Kotlin [Result] to [AppResult].
 * Used by scanner, editor, and other modules.
 */
inline fun <T> Result<T>.toAppResult(): AppResult<T> {
    return fold(
        onSuccess = { AppResult.Success(it) },
        onFailure = { AppResult.Error(it.toAppException()) }
    )
}
