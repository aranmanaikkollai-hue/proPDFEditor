package com.propdfeditor.scanner.domain.usecase

import android.graphics.Bitmap
import com.propdfeditor.scanner.domain.repository.ScannerRepository
import javax.inject.Inject

/**
 * Use case: Generate a memory-efficient thumbnail.
 */
class GenerateThumbnailUseCase @Inject constructor(
    private val repository: ScannerRepository
) {
    suspend operator fun invoke(bitmap: Bitmap, maxDimension: Int = 256): Bitmap {
        return repository.generateThumbnail(bitmap, maxDimension)
    }
}
