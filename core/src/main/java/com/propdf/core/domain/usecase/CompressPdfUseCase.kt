package com.propdf.core.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class CompressPdfUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val repository: CompressionRepository
) : FlowUseCase<CompressPdfUseCase.Params, CompressionResult>(dispatchers) {

    data class Params(
        val sourceUri: String,
        val outputUri: String,
        val config: CompressionConfig
    )

    override suspend fun execute(params: Params): Flow<AppResult<CompressionResult>> =
        repository.compress(params.sourceUri, params.outputUri, params.config)
}
