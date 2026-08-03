package com.propdf.core.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.repository.RecentFileRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface OpenDocumentUseCase {
    suspend operator fun invoke(uri: Uri): Result<OpenedDocument>
}

class OpenDocumentUseCaseImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recentFileRepository: RecentFileRepository
) : OpenDocumentUseCase {
    override suspend fun invoke(uri: Uri): Result<OpenedDocument> {
        return runCatching {
            val doc = DocumentFile.fromSingleUri(context, uri)
                ?: throw IllegalArgumentException("Cannot open URI")
            if (!doc.exists() || doc.type != "application/pdf") {
                throw IllegalArgumentException("Not a valid PDF")
            }
            val opened = OpenedDocument(
                uri = uri.toString(),
                name = doc.name ?: "Unknown",
                size = doc.length()
            )
            recentFileRepository.addToRecent(opened.toRecentFile())
            opened
        }
    }
}

data class OpenedDocument(
    val uri: String,
    val name: String,
    val size: Long
)

fun OpenedDocument.toRecentFile(): com.propdf.core.domain.model.RecentFile =
    com.propdf.core.domain.model.RecentFile(
        uri = uri,
        name = name,
        size = size,
        lastOpened = System.currentTimeMillis()
    )
