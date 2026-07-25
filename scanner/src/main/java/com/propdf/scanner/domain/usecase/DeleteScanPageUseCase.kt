package com.propdf.scanner.domain.usecase

import com.propdf.scanner.domain.model.ScannedPage
import com.propdf.scanner.domain.repository.ScannerRepository
import javax.inject.Inject

/**
 * Use case: Delete a scanned page and clean up storage.
 */
class DeleteScanPageUseCase @Inject constructor(
    private val repository: ScannerRepository
) {
    suspend operator fun invoke(page: ScannedPage): Boolean {
        return repository.deletePage(page)
    }
}
