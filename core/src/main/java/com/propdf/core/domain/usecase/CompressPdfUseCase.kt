package com.propdf.core.domain.usecase

import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class CompressPdfUseCase @Inject constructor(
    private val repository: CompressionRepository
) {

    data class Params(
        val sourceUri: String,
        val outputUri: String,
        val config: CompressionConfig
    )

    operator fun invoke(params: Params): Flow<AppResult<CompressionResult>> =
        repository.compress(params.sourceUri, params.outputUri, params.config)
}
