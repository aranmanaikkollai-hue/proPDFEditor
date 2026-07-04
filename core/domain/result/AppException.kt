package com.propdf.core.domain.result

/**
 * Sealed class representing application-specific exceptions.
 * Used across module boundaries instead of raw exceptions.
 */
sealed class AppException(
    open val code: String,
    override val message: String
) : Exception(message) {

    data class PdfProcessingError(override val message: String) : AppException("PDF_PROCESSING_ERROR", message)
    data class FileNotFound(override val message: String) : AppException("FILE_NOT_FOUND", message)
    data class InvalidPage(override val message: String) : AppException("INVALID_PAGE", message)
    data class InvalidInput(override val message: String) : AppException("INVALID_INPUT", message)
    data class SecurityError(override val message: String) : AppException("SECURITY_ERROR", message)
    data class StorageError(override val message: String) : AppException("STORAGE_ERROR", message)
    data class NetworkError(override val message: String) : AppException("NETWORK_ERROR", message)
    data class Unknown(override val message: String) : AppException("UNKNOWN", message)
    data class Cancelled(override val message: String = "Operation cancelled") : AppException("CANCELLED", message)
}

/**
 * Convert a Throwable to an AppException.
 */
fun Throwable.toAppException(): AppException = when (this) {
    is AppException -> this
    is java.io.FileNotFoundException -> AppException.FileNotFound(this.message ?: "File not found")
    is SecurityException -> AppException.SecurityError(this.message ?: "Security error")
    else -> AppException.Unknown(this.message ?: "Unknown error")
}
