package com.propdf.core.domain.usecase.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.propdf.core.domain.model.OcrPreprocessConfig
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class PreprocessImageUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        config: OcrPreprocessConfig
    ): AppResult<Bitmap> {
        return repository.preprocessImage(bitmap, config)
    }

    suspend operator fun invoke(
        bitmap: Bitmap,
        cropRect: Rect
    ): AppResult<Bitmap> {
        return repository.cropImage(bitmap, cropRect)
    }
}
