package com.propdf.core.domain.usecase.ocr

import com.propdf.core.domain.model.OcrLanguage
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class CorrectOcrTextUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(
        text: String,
        language: OcrLanguage = OcrLanguage.ENGLISH
    ): AppResult<String> {
        return repository.correctText(text, language)
    }
}
