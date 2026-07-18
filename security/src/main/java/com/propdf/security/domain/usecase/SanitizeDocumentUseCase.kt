// security/src/main/java/com/propdf/security/domain/usecase/SanitizeDocumentUseCase.kt
package com.propdf.security.domain.usecase

import android.net.Uri
import com.propdf.security.data.repository.SecurityRepository
import javax.inject.Inject

class SanitizeDocumentUseCase @Inject constructor(
    private val repository: SecurityRepository
) {
    suspend fun removeMetadata(sourceUri: Uri, outputUri: Uri) =
        repository.removeMetadata(sourceUri, outputUri)

    suspend fun fullSanitize(sourceUri: Uri, outputUri: Uri) =
        repository.sanitizeDocument(sourceUri, outputUri)

    suspend fun secureDelete(fileUri: Uri) =
        repository.secureDelete(fileUri)
}
