package com.propdf.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.propdf.core.data.database.dao.OcrRecordDao
import com.propdf.core.data.database.entity.OcrRecordEntity
import com.propdf.core.domain.model.OcrRecord
import com.propdf.core.domain.repository.OcrRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OcrRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrRecordDao: OcrRecordDao
) : OcrRepository {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun performOcr(uri: Uri, language: String): Result<OcrRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val image = InputImage.fromFilePath(context, uri)
            val result = recognizer.process(image).await()
            val text = result.text

            val record = OcrRecord(
                id = UUID.randomUUID().toString(),
                sourceUri = uri.toString(),
                extractedText = text,
                language = language,
                createdAt = System.currentTimeMillis()
            )

            ocrRecordDao.insert(record.toEntity())
            record
        }
    }

    override suspend fun getRecentOcr(limit: Int): List<OcrRecord> {
        return ocrRecordDao.getRecent(limit).map { it.toDomain() }
    }

    override suspend fun searchOcr(query: String): List<OcrRecord> {
        return ocrRecordDao.search(query).map { it.toDomain() }
    }

    private fun OcrRecordEntity.toDomain() = OcrRecord(
        id = id,
        sourceUri = sourceUri,
        extractedText = extractedText,
        language = language,
        createdAt = createdAt
    )

    private fun OcrRecord.toEntity() = OcrRecordEntity(
        id = id,
        sourceUri = sourceUri,
        extractedText = extractedText,
        language = language,
        createdAt = createdAt
    )
}
