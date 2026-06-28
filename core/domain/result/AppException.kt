package com.propdf.core.domain.result

/**
 * Sealed exception hierarchy for ProPDF domain errors.
 * All exceptions are recoverable and contain user-friendly messages.
 */
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
    data class BiometricError(override val message: String) : AppException()
    data class CryptoError(override val message: String) : AppException()
    data class VaultError(override val message: String) : AppException()
    data class SessionExpired(override val message: String = "Session expired. Please authenticate.") : AppException()
    data class Unknown(override val message: String = "Unknown error") : AppException()
}

/** Convert any [Throwable] to the appropriate [AppException]. */
fun Throwable.toAppException(): AppException = when (this) {
    is SecurityException -> AppException.SecurityError(message ?: "Permission denied")
    is OutOfMemoryError -> AppException.OutOfMemory()
    is java.io.FileNotFoundException -> AppException.FileNotFound(message ?: "File not found")
    is java.io.IOException -> AppException.IOError(message ?: "IO error")
    is javax.crypto.AEADBadTagException -> AppException.CryptoError("Authentication failed - data may be corrupted or tampered with")
    is javax.crypto.BadPaddingException -> AppException.CryptoError("Decryption failed - invalid key or corrupted data")
    is android.security.keystore.KeyPermanentlyInvalidatedException -> AppException.BiometricError("Biometric credentials changed. Please re-enroll.")
    else -> AppException.Unknown(message ?: "Unknown error")
}
