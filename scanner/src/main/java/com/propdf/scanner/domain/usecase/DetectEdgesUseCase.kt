package com.propdfeditor.scanner.domain.usecase

import android.graphics.Bitmap
import com.propdfeditor.scanner.domain.model.EdgeDetectionResult
import com.propdfeditor.scanner.domain.repository.ScannerRepository
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
