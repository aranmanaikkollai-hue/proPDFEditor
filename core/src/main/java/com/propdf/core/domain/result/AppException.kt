// core/src/main/java/com/propdf/core/domain/result/AppException.kt
sealed class AppException : Exception() {
    data class FileNotFound(override val message: String = "File not found") : AppException()
    data class FileTooLarge(override val message: String) : AppException()
    data class UnsupportedUri(override val message: String) : AppException()
    data class IOError(override val message: String) : AppException()
    data class InvalidPdf(override val message: String = "Invalid or corrupted PDF") : AppException()
    data class SecurityError(override val message: String) : AppException()
    data class OutOfMemory(override val message: String = "Out of memory") : AppException()
    data class RenderingError(override val message: String) : AppException()
    data class AnnotationError(override val message: String) : AppException()
    data class Unknown(override val message: String = "Unknown error") : AppException()
}

fun Throwable.toAppException(): AppException = when (this) {
    is SecurityException -> AppException.SecurityError(message ?: "Permission denied")
    is OutOfMemoryError -> AppException.OutOfMemory()
    is java.io.FileNotFoundException -> AppException.FileNotFound(message ?: "File not found")
    is java.io.IOException -> AppException.IOError(message ?: "IO error")
    else -> AppException.Unknown(message ?: "Unknown error")
}
