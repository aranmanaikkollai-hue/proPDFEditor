package com.propdf.core.domain.usecase.ocr

import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class SearchOcrTextUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    suspend operator fun invoke(
        text: String,
        query: String,
        caseSensitive: Boolean = false
    ): AppResult<List<IntRange>> {
        return repository.searchInText(text, query, caseSensitive)
    }
}
