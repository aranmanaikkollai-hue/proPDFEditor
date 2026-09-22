package com.propdf.security.domain.usecase

import android.net.Uri
import com.propdf.security.data.repository.SecurityRepository
import javax.inject.Inject

class DecryptDocumentUseCase @Inject constructor(
    private val repository: SecurityRepository
) {
    suspend operator fun invoke(
        sourceUri: Uri,
        password: String,
        outputUri: Uri
    ) = repository.decryptPdf(sourceUri, password, outputUri)
}
