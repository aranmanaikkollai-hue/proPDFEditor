package com.propdf.security.encryption

import android.content.Context
import java.io.File

/**
 * Small synchronous-style facade over [PdfBoxPasswordEngine] for callers that hold plain Files.
 *
 * Replaces the former iText implementation. Notably, `decrypt` previously ignored its password and
 * opened the file with iText's unethical-reading override, i.e. it removed protection from any PDF
 * without credentials. That behaviour is gone: [decrypt] now needs a password PDFBox accepts, with
 * owner rights, exactly like [PdfBoxPasswordEngine.decrypt].
 */
class EncryptionManager(context: Context) {

    private val engine = PdfBoxPasswordEngine(context)

    /** AES-256, same password for opening and owning, printing allowed (as before). */
    suspend fun encrypt(inputFile: File, outputFile: File, password: String) {
        engine.encrypt(
            inputFile, outputFile,
            PdfBoxPasswordEngine.EncryptionRequest(
                userPassword = password,
                ownerPassword = password,
                permissions = PdfPermissions.ALLOW_PRINTING,
                algorithm = PdfBoxPasswordEngine.Algorithm.AES_256
            )
        )
    }

    suspend fun decrypt(inputFile: File, outputFile: File, password: String) {
        engine.decrypt(inputFile, outputFile, password)
    }
}
