package com.propdf.pdfedit.convert

import android.net.Uri
import androidx.documentfile.provider.DocumentFile

enum class ConvertMode { PDF_TO_IMAGES, PDF_TO_TEXT, IMAGES_TO_PDF, TEXT_TO_PDF }

/** One file chosen by the user: where to read it and what to call it. */
class ConvertInput(val uri: Uri, val displayName: String)

/**
 * Everything needed to run one conversion. PDF modes take exactly one input; IMAGES_TO_PDF takes one or more (page order =
 * list order); TEXT_TO_PDF takes one. [outputName] is the base name of the result (without extension).
 */
class ConvertJob(
    val mode: ConvertMode,
    val inputs: List<ConvertInput>,
    val outputRoot: DocumentFile,
    val outputName: String,
    val password: String? = null,
    val pdfToImages: PdfToImagesOptions = PdfToImagesOptions(),
    val pdfToText: PdfToTextOptions = PdfToTextOptions(),
    val imagesToPdf: ImagesToPdfOptions = ImagesToPdfOptions(),
    val textToPdf: TextToPdfOptions = TextToPdfOptions()
)

/** [outputNames] are the names actually created in the folder (they differ from the requested ones when a name was taken). */
class ConvertResult(val outputNames: List<String>, val report: ConvertReport)
