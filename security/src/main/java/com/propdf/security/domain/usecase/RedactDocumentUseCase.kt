// security/src/main/java/com/propdf/security/domain/usecase/RedactDocumentUseCase.kt
package com.propdf.security.domain.usecase

import android.graphics.RectF
import android.net.Uri
import com.propdf.security.data.repository.SecurityRepository
import javax.inject.Inject

class RedactDocumentUseCase @Inject constructor(
    private val repository: SecurityRepository
) {
    suspend fun addRedaction(documentUri: String, pageNumber: Int, rect: RectF, overlayText: String?) =
        repository.addRedaction(documentUri, pageNumber, rect, overlayText)

    suspend fun applyRedactions(sourceUri: Uri, outputUri: Uri, permanent: Boolean = false) =
        if (permanent) repository.applyPermanentRedactions(sourceUri, outputUri)
        else repository.applyRedactions(sourceUri, outputUri)
}
