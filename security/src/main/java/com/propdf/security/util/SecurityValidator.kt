// security/src/main/java/com/propdf/security/util/SecurityValidator.kt
package com.propdf.security.util

import android.content.Context
import com.propdf.security.encryption.PdfBoxPasswordEngine
import java.io.File

/**
 * Password-strength scoring plus PDF protection checks on PDFBox.
 *
 * The PDF checks need a [Context] (PDFBox temp storage / resource loader), so they live on
 * [PdfChecks]; [validatePasswordStrength] is pure and unchanged.
 */
object SecurityValidator {

    fun validatePasswordStrength(password: String): PasswordStrength {
        var score = 0

        if (password.length >= 8) score++
        if (password.length >= 12) score++
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        return when (score) {
            0, 1, 2 -> PasswordStrength.WEAK
            3, 4 -> PasswordStrength.MEDIUM
            5, 6 -> PasswordStrength.STRONG
            else -> PasswordStrength.WEAK
        }
    }

    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }

    class PdfChecks(context: Context) {
        private val engine = PdfBoxPasswordEngine(context)

        /**
         * True when the file declares encryption or needs a password to open. (The iText version
         * returned false for PDFs that needed a user password, because opening them threw.)
         */
        fun isPdfEncrypted(file: File): Boolean = engine.isEncrypted(file)

        /** True when [password] opens the file at any access level (user or owner). */
        fun canOpen(file: File, password: String): Boolean = engine.canOpen(file, password)

        /** Same as [canOpen]; kept under its old name. Note it does NOT imply owner rights. */
        fun canDecrypt(file: File, password: String): Boolean = engine.canOpen(file, password)
    }
}
