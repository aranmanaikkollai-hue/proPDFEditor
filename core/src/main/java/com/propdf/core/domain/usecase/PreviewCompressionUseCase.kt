package com.propdf.core.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionPreview
import com.propdf.core.domain.repository.CompressionRepository
import com.propdf.core.domain.result.AppResult
import javax.inject.Inject

class PreviewCompressionUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val repository: CompressionRepository
) : UseCase<PreviewCompressionUseCase.Params, CompressionPreview>(dispatchers) {

    data class Params(
        val sourceUri: String,
        val config: CompressionConfig
    )

    override suspend fun execute(params: Params): CompressionPreview {
        val result = repository.preview(params.sourceUri, params.config)
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Preview should not emit loading")
        }
    }
}
