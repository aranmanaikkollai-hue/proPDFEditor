package com.propdf.core.domain.usecase.ocr

import android.net.Uri
import com.propdf.core.domain.model.OcrConfig
import com.propdf.core.domain.model.OcrPageResult
import com.propdf.core.domain.repository.OcrRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class BatchOcrUseCase @Inject constructor(
    private val repository: OcrRepository
) {
    operator fun invoke(
        uris: List<Uri>,
        config: OcrConfig
    ): Flow<AppResult<OcrPageResult>> {
        return repository.recognizeBatch(uris, config)
    }
}
