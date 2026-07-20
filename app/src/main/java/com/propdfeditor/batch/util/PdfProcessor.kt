package com.propdfeditor.batch.util

import android.content.Context
import android.net.Uri
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.utils.PdfMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfProcessor @Inject constructor() {

    suspend fun mergePdfs(
        context: Context,
        inputUris: List<Uri>,
        outputUri: Uri,
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(outputUri)?.use { output ->
            val writer = PdfWriter(output)
            val mergedDoc = PdfDocument(writer)
            val merger = PdfMerger(mergedDoc)

            inputUris.forEachIndexed { index, uri ->
                if (!isActive) return@withContext

                context.contentResolver.openInputStream(uri)?.use { input ->
                    val reader = PdfReader(input)
                    val sourceDoc = PdfDocument(reader)
                    merger.merge(sourceDoc, 1, sourceDoc.numberOfPages)
                    sourceDoc.close()
                }

                onProgress(index + 1)
            }

            merger.close()
            mergedDoc.close()
        }
    }

    suspend fun splitPdf(
        context: Context,
        inputUri: Uri,
        outputDirUri: Uri,
        pageRanges: List<String>?,
        splitEvery: Int?,
        onProgress: (Int, Int) -> Unit
    ): List<Uri> = withContext(Dispatchers.IO) {
        val resultUris = mutableListOf<Uri>()
        
        context.contentResolver.openInputStream(inputUri)?.use { input ->
            val reader = PdfReader(input)
            val sourceDoc = PdfDocument(reader)
            val totalPages = sourceDoc.numberOfPages

            val ranges = when {
                pageRanges != null -> parsePageRanges(pageRanges
