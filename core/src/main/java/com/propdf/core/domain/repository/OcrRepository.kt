package com.propdf.core.domain.repository

import android.net.Uri
import com.propdf.core.domain.model.OcrRecord

interface OcrRepository {
    suspend fun performOcr(uri: Uri, language: String): Result<OcrRecord>
    suspend fun getRecentOcr(limit: Int): List<OcrRecord>
    suspend fun searchOcr(query: String): List<OcrRecord>
}
