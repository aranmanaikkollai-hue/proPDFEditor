package com.propdf.core.domain.usecase.ocr

import android.graphics.Bitmap
import com.propdf.core.domain.model.HandwritingResult
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class DetectHandwritingUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(bitmap: Bitmap): AppResult<HandwritingResult> {
        return repository.detectHandwriting(bitmap)
    }
}
