package com.propdf.core.domain.result

sealed class AppException : Exception() {
    class FileNotFound(override val message: String? = null) : AppException()
    class InvalidFormat(override val message: String? = null) : AppException()
    class InvalidPassword(override val message: String? = null) : AppException()
    class RenderFailed(override val message: String? = null) : AppException()
    class NetworkError(override val message: String? = null) : AppException()
    class Unknown(override val message: String? = null) : AppException()
}

fun Throwable.toAppException(): AppException {
    return when (this) {
        is AppException -> this
        is java.io.FileNotFoundException -> AppException.FileNotFound(this.message)
        is SecurityException -> AppException.InvalidPassword(this.message)
        else -> AppException.Unknown(this.message ?: "Unknown error")
    }
}
