package com.propdfeditor.scanner.domain.usecase

import android.content.Context
import android.graphics.Bitmap
import com.propdfeditor.scanner.domain.model.ScanMode
import com.propdfeditor.scanner.domain.repository.BatchProcessingProgress
import com.propdfeditor.scanner.domain.repository.ScannerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Use case: Batch process multiple scanned images.
 * Memory-optimized with sequential processing to avoid OOM.
 */
class BatchScanUseCase @Inject constructor(
    private val repository: ScannerRepository
) {
    operator fun invoke(
        context: Context,
        bitmaps: List<Bitmap>,
        mode: ScanMode
    ): Flow<BatchProcessingProgress> {
        return repository.batchProcess(context, bitmaps, mode)
    }
}
