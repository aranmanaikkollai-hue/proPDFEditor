package com.propdf.core.domain.usecase.ocr

import android.graphics.Bitmap
import com.propdf.core.domain.model.OcrConfig
import com.propdf.core.domain.model.OcrTable
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class DetectTablesUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        config: OcrConfig
    ): AppResult<List<OcrTable>> {
        return repository.detectTables(bitmap, config)
    }
}
