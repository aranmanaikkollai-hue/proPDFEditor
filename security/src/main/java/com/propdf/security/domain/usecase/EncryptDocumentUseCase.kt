// security/src/main/java/com/propdf/security/domain/usecase/EncryptDocumentUseCase.kt
package com.propdf.security.domain.usecase

import android.net.Uri
import com.itextpdf.kernel.pdf.EncryptionConstants
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

    companion object {
        /**
         * "Password protect" / "AES encrypt" only mean to gate opening the file --
         * they were passing permissions = 0, which additionally denies printing,
         * copying, editing, and accessibility extraction even to whoever has the
         * (single, identical user/owner) password. Every other permission-bitmask
         * caller in this module (the dormant EncryptionFragment, EncryptionManager)
         * defaults to allowing normal use; this restores that default for the
         * active Compose path.
         */
        const val FULL_PERMISSIONS =
            EncryptionConstants.ALLOW_PRINTING or
                EncryptionConstants.ALLOW_MODIFY_CONTENTS or
                EncryptionConstants.ALLOW_COPY or
                EncryptionConstants.ALLOW_MODIFY_ANNOTATIONS or
                EncryptionConstants.ALLOW_FILL_IN or
                EncryptionConstants.ALLOW_SCREENREADERS or
                EncryptionConstants.ALLOW_ASSEMBLY or
                EncryptionConstants.ALLOW_DEGRADED_PRINTING
    }
}
