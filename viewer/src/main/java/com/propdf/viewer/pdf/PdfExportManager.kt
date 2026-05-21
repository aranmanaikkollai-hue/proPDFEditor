package com.propdf.viewer.pdf

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PdfExportManager(
    private val context: Context,
    private val engine: PdfToolEngine = PdfToolEngine(context),
    private val tempManager: TempFileManager = TempFileManager(context)
) {

    private val _operations = MutableStateFlow<Map<String, PdfOperationResult>>(emptyMap())
    val operations: StateFlow<Map<String, PdfOperationResult>> = _operations.asStateFlow()

    private val activeCancellations = ConcurrentHashMap<String, () -> Boolean>()

    suspend fun merge(sources: List<Uri>, outputName: String = "merged_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, sources.size))
        val isCancelled = { activeCancellations.containsKey(operationId) }
        val result = engine.mergePdfs(sources, outputFile) { current, total ->
            if (isCancelled()) {
                updateOperation(PdfOperationResult.Cancelled(operationId, current))
                return@mergePdfs
            }
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        activeCancellations.remove(operationId)
        return when {
            result.isSuccess -> {
                val file = result.getOrThrow()
                updateOperation(PdfOperationResult.Success(operationId, file, sources.size, sources.sumOf { getUriSize(it) }, file.length(), 0))
                operationId
            }
            else -> {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                updateOperation(PdfOperationResult.Failure(operationId, error))
                operationId
            }
        }
    }

    suspend fun split(source: Uri, outputDir: File = File(context.filesDir, "split").apply { mkdirs() }): String {
        val operationId = UUID.randomUUID().toString()
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, 1))
        val result = engine.splitPdf(source, outputDir) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return when {
            result.isSuccess -> {
                val files = result.getOrThrow()
                val totalOutputSize = files.sumOf { it.length() }
                val originalSize = getUriSize(source)
                updateOperation(PdfOperationResult.Success(operationId, files.first(), files.size, originalSize, totalOutputSize, 0))
                operationId
            }
            else -> {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                updateOperation(PdfOperationResult.Failure(operationId, error))
                operationId
            }
        }
    }

    suspend fun extract(source: Uri, pageIndices: List<Int>, outputName: String = "extracted_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, pageIndices.size))
        val result = engine.extractPages(source, pageIndices, outputFile) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleResult(operationId, result, getUriSize(source))
    }

    suspend fun reorder(source: Uri, newOrder: List<Int>, outputName: String = "reordered_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, newOrder.size))
        val result = engine.reorderPages(source, newOrder, outputFile) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleResult(operationId, result, getUriSize(source))
    }

    suspend fun duplicate(source: Uri, pageIndex: Int, outputName: String = "duplicated_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, 1))
        val result = engine.duplicatePage(source, pageIndex, outputFile)
        return handleResult(operationId, result, getUriSize(source))
    }

    suspend fun rotate(source: Uri, pageIndices: List<Int>, degrees: Int, outputName: String = "rotated_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, pageIndices.size))
        val result = engine.rotatePages(source, pageIndices, degrees, outputFile) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleResult(operationId, result, getUriSize(source))
    }

    suspend fun delete(source: Uri, pageIndices: List<Int>, outputName: String = "deleted_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        val totalPages = getPageCount(source)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, totalPages))
        val result = engine.deletePages(source, pageIndices, outputFile) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleResult(operationId, result, getUriSize(source))
    }

    suspend fun compress(source: Uri, options: CompressionOptions = CompressionOptions(), outputName: String = "compressed_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        val originalSize = getUriSize(source)
        val totalPages = getPageCount(source)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, totalPages))
        val result = engine.compressPdf(source, outputFile, options) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleResult(operationId, result, originalSize)
    }

    fun cancelOperation(operationId: String) {
        activeCancellations[operationId] = { true }
    }

    suspend fun cleanup() {
        tempManager.cleanAll()
    }

    fun getOperationResult(operationId: String): PdfOperationResult? {
        return _operations.value[operationId]
    }

    private fun handleResult(operationId: String, result: Result<File>, originalSize: Long): String {
        return when {
            result.isSuccess -> {
                val file = result.getOrThrow()
                updateOperation(PdfOperationResult.Success(operationId, file, 1, originalSize, file.length(), 0))
                operationId
            }
            else -> {
                val error = result.exceptionOrNull() ?: Exception("Unknown error")
                updateOperation(PdfOperationResult.Failure(operationId, error))
                operationId
            }
        }
    }

    private fun updateOperation(result: PdfOperationResult) {
        _operations.value = _operations.value + (result.operationId to result)
    }

    private fun getUriSize(uri: Uri): Long {
        return try { File(uri.path ?: return 0).length() } catch (e: Exception) { 0 }
    }

    private fun getPageCount(uri: Uri): Int {
        return try {
            val file = File(uri.path ?: return 0)
            if (!file.exists()) return 0
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { it.numberOfPages }
        } catch (e: Exception) { 0 }
    }
}
