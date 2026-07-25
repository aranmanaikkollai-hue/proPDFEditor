package com.propdf.scanner.domain.usecase

import android.graphics.Bitmap
import com.propdf.scanner.domain.model.EdgeDetectionResult
import com.propdf.scanner.domain.repository.ScannerRepository
import javax.inject.Inject

/**
 * Use case: Detect document edges in a captured image.
 * Single responsibility: delegates to repository, no business logic.
 */
class DetectEdgesUseCase @Inject constructor(
    private val repository: ScannerRepository
) {
    suspend operator fun invoke(bitmap: Bitmap): EdgeDetectionResult {
        return repository.detectEdges(bitmap)
    }
}
