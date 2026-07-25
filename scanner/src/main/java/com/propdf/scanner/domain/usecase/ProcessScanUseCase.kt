package com.propdf.scanner.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import com.propdf.scanner.domain.model.ScanMode
import com.propdf.scanner.domain.repository.ProcessingProgress
import com.propdf.scanner.domain.repository.ScannerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: Process a single scanned image through the full pipeline.
 * Returns a Flow for reactive progress updates.
 */
class ProcessScanUseCase @Inject constructor(
    private val repository: ScannerRepository
) {
    operator fun invoke(
        context: Context,
        bitmap: Bitmap,
        mode: ScanMode,
        pageId: String
    ): Flow<ProcessingProgress> {
        return repository.processImage(context, bitmap, mode, pageId)
    }
}
