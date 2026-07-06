package com.propdf.core.domain.usecase.ocr

import com.propdf.core.domain.model.OcrLanguage
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class DownloadOcrModelUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    operator fun invoke(language: OcrLanguage): Flow<AppResult<Int>> {
        return repository.downloadModel(language)
    }

    suspend fun isDownloaded(language: OcrLanguage): Boolean {
        return repository.isModelDownloaded(language)
    }

    suspend fun delete(language: OcrLanguage): AppResult<Unit> {
        return repository.deleteModel(language)
    }
}
