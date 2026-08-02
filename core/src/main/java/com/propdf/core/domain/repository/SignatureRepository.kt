package com.propdf.core.domain.repository

import android.graphics.Bitmap

interface SignatureRepository {
    suspend fun saveSignature(bitmap: Bitmap, name: String)
    suspend fun getSignatures(): List<SavedSignature>
    suspend fun deleteSignature(id: String)
}

data class SavedSignature(
    val id: String,
    val name: String,
    val path: String,
    val createdAt: Long
)
