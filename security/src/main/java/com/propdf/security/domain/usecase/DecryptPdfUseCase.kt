package com.propdf.security.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.UseCase
import java.io.File
import javax.inject.Inject

class DecryptPdfUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val pdfOps: PdfOperationsRepository
) : UseCase<DecryptPdfUseCase.Params, File>(dispatchers) {

    data class Params(
        val inputFile: File,
        val outputFile: File,
        val password: String
    )

    override suspend fun execute(params: Params): File {
        val result = pdfOps.decrypt(params.inputFile, params.outputFile, params.password)
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }
}
