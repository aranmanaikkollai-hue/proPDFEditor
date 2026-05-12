package com.propdf.annotations.domain.usecase

import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.AnnotationStroke
import com.propdf.core.domain.model.AnnotationText
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.UseCase
import java.io.File
import javax.inject.Inject

class ExportAnnotationsUseCase @Inject constructor(
    dispatchers: DispatcherProvider,
    private val pdfOps: PdfOperationsRepository
) : UseCase<ExportAnnotationsUseCase.Params, File>(dispatchers) {

    data class Params(
        val inputFile: File,
        val outputFile: File,
        val pageAnnotations: Map<Int, Pair<List<AnnotationStroke>, Float>>,
        val pageTextAnnotations: Map<Int, Pair<List<AnnotationText>, Float>> = emptyMap()
    )

    override suspend fun execute(params: Params): File {
        val result = pdfOps.saveAnnotations(
            params.inputFile,
            params.outputFile,
            params.pageAnnotations,
            params.pageTextAnnotations
        )
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }
}
