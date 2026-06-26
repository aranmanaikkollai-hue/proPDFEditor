package com.propdf.security.encryption

import com.itextpdf.kernel.pdf.EncryptionConstants
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.WriterProperties
import java.io.File

class EncryptionManager {

    fun encrypt(inputFile: File, outputFile: File, password: String) {
        val reader = PdfReader(inputFile.absolutePath)
        val writer = PdfWriter(
            outputFile.absolutePath,
            WriterProperties().setStandardEncryption(
                password.toByteArray(),
                password.toByteArray(),
                EncryptionConstants.ALLOW_PRINTING,
                EncryptionConstants.ENCRYPTION_AES_256
            )
        )
        val pdfDoc = PdfDocument(reader, writer)
        pdfDoc.close()
    }

    fun decrypt(inputFile: File, outputFile: File, _password: String) {
        val reader = PdfReader(inputFile.absolutePath).apply {
            setUnethicalReading(true)
        }
        val writer = PdfWriter(outputFile.absolutePath)
        val pdfDoc = PdfDocument(reader, writer)
        pdfDoc.close()
    }
}
