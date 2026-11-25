package com.propdf.pdfedit.convert

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Runs one [ConvertJob]: copies the inputs into a private work folder, runs the converter for the mode, writes the result
 * into the chosen folder without overwriting anything, and always removes the work folder (staged inputs, temporary and
 * decrypted copies). Cancelling the calling coroutine removes any partial output. The caller must have initialized
 * PDFBox-Android (PDFBoxResourceLoader.init) once.
 */
class ConvertRunner(context: Context) {
    private val appContext = context
    private val toImages = PdfToImagesConverter()
    private val toText = PdfToTextConverter()
    private val imagesToPdf = ImagesToPdfConverter()
    private val textToPdf = TextToPdfConverter(context)

    suspend fun run(job: ConvertJob, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ConvertResult =
        withContext(Dispatchers.IO) {
            val work = File(appContext.cacheDir, "convert-" + UUID.randomUUID().toString())
            try {
                if (job.inputs.isEmpty()) {
                    throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "Nothing was chosen to convert")
                }
                if (job.mode != ConvertMode.IMAGES_TO_PDF && job.inputs.size != 1) {
                    throw ConvertException(ConvertException.Reason.EMPTY_SELECTION, "Choose exactly one file")
                }
                val files = InputStager.stage(appContext, job.inputs, File(work, "in"))
                val temp = File(work, "tmp")
                val sink = DocumentTreeSink(appContext, job.outputRoot)
                val report = when (job.mode) {
                    ConvertMode.PDF_TO_IMAGES -> toImages.convert(files[0], job.outputName, job.pdfToImages, sink, temp, job.password, onProgress)
                    ConvertMode.PDF_TO_TEXT -> toText.convert(files[0], job.outputName, job.pdfToText, sink, job.password, onProgress)
                    ConvertMode.IMAGES_TO_PDF -> imagesToPdf.convert(files, job.outputName, job.imagesToPdf, sink, temp, onProgress)
                    ConvertMode.TEXT_TO_PDF -> textToPdf.convert(files[0], job.outputName, job.textToPdf, sink, temp, onProgress)
                }
                ConvertResult(sink.createdNames, report)
            } finally {
                work.deleteRecursively()
            }
        }
}
