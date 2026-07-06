package com.propdf.core.domain.usecase.ocr

import android.net.Uri
import com.propdf.core.domain.model.OcrOutputFormat
import com.propdf.core.domain.model.OcrPageResult
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class ExportOcrUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(
        results: List<OcrPageResult>,
        outputUri: Uri,
        format: OcrOutputFormat
    ): AppResult<Uri> {
        return when (format) {
            OcrOutputFormat.PDF -> repository.exportToPdf(results, outputUri)
            OcrOutputFormat.TXT -> repository.exportToTxt(results, outputUri)
            OcrOutputFormat.DOCX -> repository.exportToDocx(results, outputUri)
            OcrOutputFormat.TEXT -> repository.exportToTxt(results, outputUri)
        }
    }
}
