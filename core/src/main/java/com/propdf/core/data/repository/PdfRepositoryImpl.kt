package com.propdf.core.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.repository.PdfRepository
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : PdfRepository {

    init {
        PDFBoxResourceLoader.init(context)
    }

    override suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { it.numberOfPages }
            } ?: 0
        }.getOrDefault(0)
    }

    override suspend fun renderThumbnail(uri: Uri, page: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val renderer = PDFRenderer(doc)
                    renderer.renderImage(page, width / doc.getPage(page).mediaBox.width, Bitmap.Config.ARGB_8888)
                }
            }
        }.getOrNull()
    }

    override suspend fun extractText(uri: Uri, page: Int): String = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
                    stripper.startPage = page + 1
                    stripper.endPage = page + 1
                    stripper.getText(doc)
                }
            } ?: ""
        }.getOrDefault("")
    }
}
