package com.propdf.core.domain.result

sealed class AppException : Exception() {
    class FileNotFound : AppException()
    class InvalidPassword : AppException()
    class RenderFailed : AppException()
    class NetworkError : AppException()
    class Unknown(override val message: String? = null) : AppException()
}

fun Throwable.toAppException(): AppException {
    return when (this) {
        is AppException -> this
        is java.io.FileNotFoundException -> AppException.FileNotFound()
        is SecurityException -> AppException.InvalidPassword()
        else -> AppException.Unknown(this.message ?: "Unknown error")
    }
}
