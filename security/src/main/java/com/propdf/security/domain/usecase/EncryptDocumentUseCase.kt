// security/src/main/java/com/propdf/security/domain/usecase/EncryptDocumentUseCase.kt
package com.propdf.security.domain.usecase

import android.net.Uri
import com.propdf.security.data.entity.EncryptionType
import com.propdf.security.data.repository.SecurityRepository
import javax.inject.Inject

class EncryptDocumentUseCase @Inject constructor(
    private val repository: SecurityRepository
) {
    suspend operator fun invoke(
        sourceUri: Uri,
        userPassword: String?,
        ownerPassword: String?,
        permissions: Int,
        encryptionType: EncryptionType,
        outputUri: Uri
    ) = repository.applyPasswordProtection(
        sourceUri, userPassword, ownerPassword, permissions, encryptionType, outputUri
    )
}
