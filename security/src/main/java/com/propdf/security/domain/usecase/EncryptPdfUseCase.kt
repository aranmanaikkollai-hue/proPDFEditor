package com.propdf.security.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.UseCase
import java.io.File
import javax.inject.Inject

class EncryptPdfUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val pdfOps: PdfOperationsRepository
) : UseCase<EncryptPdfUseCase.Params, File>(dispatchers) {

    data class Params(
        val inputFile: File,
        val outputFile: File,
        val config: SecurityConfig
    )

    override suspend fun execute(params: Params): File {
        val result = pdfOps.encrypt(params.inputFile, params.outputFile, params.config)
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }
}
