package com.propdf.pdfedit.convert

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Saves a finished document, checks the saved file, and only then copies it to the sink. */
internal object PdfPublisher {

    fun publish(doc: PDDocument, tempDir: File, expectedPages: Int, name: String, sink: OutputSink) {
        tempDir.mkdirs()
        val temp = File.createTempFile("convert-", ".pdf", tempDir)
        try {
            doc.save(temp)
            PDDocument.load(temp).use { check ->
                if (check.numberOfPages != expectedPages) {
                    throw ConvertException(
                        ConvertException.Reason.WRITE_FAILED,
                        "The saved PDF has " + check.numberOfPages + " pages, expected " + expectedPages
                    )
                }
            }
            sink.open(name, "application/pdf").use { out -> temp.inputStream().use { it.copyTo(out) } }
        } finally {
            temp.delete()
        }
    }
}
