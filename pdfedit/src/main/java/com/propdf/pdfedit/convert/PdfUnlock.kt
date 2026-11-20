package com.propdf.pdfedit.convert

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import java.io.File
import java.io.IOException

/** Makes a temporary decrypted copy of a password-protected PDF so the platform renderer can open it. */
object PdfUnlock {

    /**
     * Writes an unencrypted copy of [pdf] into [tempDir] using [password]. The caller deletes the copy.
     * Throws [ConvertException] with WRONG_PASSWORD or UNREADABLE.
     */
    fun decryptToTemp(pdf: File, password: String, tempDir: File): File {
        tempDir.mkdirs()
        val out = File.createTempFile("unlocked-", ".pdf", tempDir)
        try {
            PDDocument.load(pdf, password).use { doc ->
                doc.setAllSecurityToBeRemoved(true)
                doc.save(out)
            }
            return out
        } catch (e: InvalidPasswordException) {
            out.delete()
            throw ConvertException(ConvertException.Reason.WRONG_PASSWORD, "The password is not correct", e)
        } catch (e: IOException) {
            out.delete()
            throw ConvertException(ConvertException.Reason.UNREADABLE, "The PDF could not be read", e)
        }
    }
}
