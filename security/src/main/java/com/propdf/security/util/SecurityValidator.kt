// security/src/main/java/com/propdf/security/util/SecurityValidator.kt
package com.propdf.security.util

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import java.io.File

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

    fun isPdfEncrypted(file: File): Boolean {
        return try {
            PdfDocument(PdfReader(file.absolutePath)).use { doc ->
                doc.reader.isEncrypted
            }
        } catch (e: Exception) {
            false
        }
    }

    fun canDecrypt(file: File, password: String): Boolean {
        return try {
            PdfDocument(PdfReader(file.absolutePath, 
                com.itextpdf.kernel.pdf.ReaderProperties().setPassword(password.toByteArray()))).use { 
                true 
            }
        } catch (e: Exception) {
            false
        }
    }

    enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }
}
