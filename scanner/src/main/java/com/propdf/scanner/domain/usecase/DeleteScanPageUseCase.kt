package com.propdfeditor.scanner.domain.usecase

import com.propdfeditor.scanner.domain.model.ScannedPage
import com.propdfeditor.scanner.domain.repository.ScannerRepository
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
