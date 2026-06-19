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
        val pageAnnotations: Map<Int, List<AnnotationStroke>>,
        val pageTextAnnotations: Map<Int, List<AnnotationText>> = emptyMap()
    )

    override suspend fun execute(params: Params): File {
        val result = pdfOps.saveAnnotations(
            params.inputFile,
            params.outputFile,
            params.pageAnnotations.mapValues { (_, list) -> Pair(list, 1.0f) },
            params.pageTextAnnotations.mapValues { (_, list) -> Pair(list, 1.0f) }
        )
        return when (result) {
            is AppResult.Success -> result.data
            is AppResult.Error -> throw result.exception
            is AppResult.Loading -> throw IllegalStateException("Unexpected loading state")
        }
    }
}
