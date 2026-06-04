package com.propdf.viewer.pdf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.AppException
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfViewerRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PdfViewerRepository {

    private var currentPfd: FileDescriptor? = null

    override suspend fun getPageText(file: File, pageIndex: Int): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            PDDocument.load(file).use { document ->
                if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
                    return@withContext AppResult.Error(AppException.IOError("Page index out of bounds"))
                }
                val stripper = PDFTextStripper()
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                val text = stripper.getText(document)
                AppResult.Success(text)
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to extract text: ${e.message}"))
        }
    }

    suspend fun getPageText(uri: Uri, pageIndex: Int): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            currentCoroutineContext().ensureActive()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: return@withContext AppResult.Error(AppException.IOError("Cannot open URI"))
            pfd.use {
                PDDocument.load(BufferedInputStream(FileInputStream(it.fileDescriptor))).use { document ->
                    if (pageIndex < 0 || pageIndex >= document.numberOfPages) {
                        return@withContext AppResult.Error(AppException.IOError("Page index out of bounds"))
                    }
                    val stripper = PDFTextStripper()
                    stripper.startPage = pageIndex + 1
                    stripper.endPage = pageIndex + 1
                    val text = stripper.getText(document)
                    AppResult.Success(text)
                }
            }
        } catch (e: Exception) {
            AppResult.Error(AppException.IOError("Failed to extract text: ${e.message}"))
        }
    }

    fun openDocumentFile(uri: Uri): DocumentFile? {
        return DocumentFile.fromSingleUri(context, uri)
    }
}
