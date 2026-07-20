package com.propdfeditor.batch.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.utils.PdfMerger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber
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

                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val reader = PdfReader(input)
                        val sourceDoc = PdfDocument(reader)
                        merger.merge(sourceDoc, 1, sourceDoc.numberOfPages)
                        sourceDoc.close()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to merge PDF: $uri")
                    throw e
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
                pageRanges != null -> parsePageRanges(pageRanges, totalPages)
                splitEvery != null -> generateRanges(splitEvery, totalPages)
                else -> listOf(1..totalPages)
            }

            val parentDir = DocumentFile.fromTreeUri(context, outputDirUri)
                ?: throw IllegalStateException("Cannot access output directory")

            val sourceName = DocumentFile.fromSingleUri(context, inputUri)?.name
                ?.substringBeforeLast(".") ?: "document"

            ranges.forEachIndexed { index, range ->
                if (!isActive) return@withContext

                val startPage = range.first
                val endPage = range.last
                val outputFile = parentDir.createFile(
                    "application/pdf",
                    "${sourceName}_pages_${startPage}-${endPage}.pdf"
                ) ?: throw IllegalStateException("Cannot create output file")

                context.contentResolver.openOutputStream(outputFile.uri)?.use { output ->
                    val writer = PdfWriter(output)
                    val newDoc = PdfDocument(writer)
                    sourceDoc.copyPagesTo(startPage, endPage, newDoc)
                    newDoc.close()
                }

                resultUris.add(outputFile.uri)
                onProgress(index + 1, ranges.size)
            }

            sourceDoc.close()
        }

        resultUris
    }

    private fun parsePageRanges(ranges: List<String>, totalPages: Int): List<IntRange> {
        return ranges.mapNotNull { rangeStr ->
            when {
                rangeStr.contains("-") -> {
                    val parts = rangeStr.split("-")
                    val start = parts[0].trim().toIntOrNull()?.coerceIn(1, totalPages) ?: return@mapNotNull null
                    val end = parts[1].trim().toIntOrNull()?.coerceIn(start, totalPages) ?: return@mapNotNull null
                    start..end
                }
                else -> {
                    val page = rangeStr.trim().toIntOrNull()?.coerceIn(1, totalPages) ?: return@mapNotNull null
                    page..page
                }
            }
        }
    }

    private fun generateRanges(splitEvery: Int, totalPages: Int): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var current = 1
        while (current <= totalPages) {
            val end = (current + splitEvery - 1).coerceAtMost(totalPages)
            ranges.add(current..end)
            current = end + 1
        }
        return ranges
    }
}
