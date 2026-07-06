package com.propdf.core.domain.usecase.ocr

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.model.OcrConfig
import com.propdf.core.domain.model.OcrPageResult
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class RecognizeImageUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(bitmap: Bitmap, config: OcrConfig): AppResult<OcrPageResult> {
        return repository.recognizeImage(bitmap, config)
    }

    suspend operator fun invoke(uri: Uri, config: OcrConfig): AppResult<OcrPageResult> {
        return repository.recognizeImageUri(uri, config)
    }
}
