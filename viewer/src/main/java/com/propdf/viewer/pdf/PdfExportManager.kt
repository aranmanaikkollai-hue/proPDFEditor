package com.propdf.viewer.pdf

import android.content.Context
import android.net.Uri
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PdfExportManager(
    private val context: Context,
    private val engine: PdfToolEngine = PdfToolEngine(context,
        com.propdf.core.saf.SafEngine(context)),
    private val tempManager: TempFileManager = TempFileManager(context)
) {

    private val _operations = MutableStateFlow<Map<String, PdfOperationResult>>(emptyMap())
    val operations: StateFlow<Map<String, PdfOperationResult>> = _operations.asStateFlow()

    private val activeCancellations = ConcurrentHashMap<String, () -> Boolean>()

    suspend fun merge(
        sources: List<Uri>,
        outputName: String = "merged_${System.currentTimeMillis()}.pdf"
    ): String {
        val operationId = UUID.randomUUID().toString()
        val outputUri = uriForName(outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, sources.size))
        val isCancelled = { activeCancellations.containsKey(operationId) }
        val result = engine.mergePdfs(sources, outputUri) { current, total ->
            if (isCancelled()) {
                updateOperation(PdfOperationResult.Cancelled(operationId, current))
                return@mergePdfs
            }
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        activeCancellations.remove(operationId)
        return handleUriResult(operationId, result, sources.sumOf { getUriSize(it) })
    }

    suspend fun split(
        source: Uri,
        outputDir: File = File(context.filesDir, "split").apply { mkdirs() }
    ): String {
        val operationId = UUID.randomUUID().toString()
        val outputDirUri = Uri.fromFile(outputDir)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, 1))
        val result = engine.splitPdf(source, outputDirUri) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriListResult(operationId, result, getUriSize(source))
    }

    suspend fun rotate(
        source: Uri,
        pageIndices: List<Int>,
        degrees: Int,
        outputName: String = "rotated_${System.currentTimeMillis()}.pdf"
    ): String {
        val operationId = UUID.randomUUID().toString()
        val outputUri = uriForName(outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, pageIndices.size))
        val result = engine.rotatePages(source, pageIndices, degrees, outputUri) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriResult(operationId, result, getUriSize(source))
    }

    suspend fun compress(
        source: Uri,
        options: CompressionOptions = CompressionOptions(),
        outputName: String = "compressed_${System.currentTimeMillis()}.pdf"
    ): String {
        val operationId = UUID.randomUUID().toString()
        val outputUri = uriForName(outputName)
        val originalSize = getUriSize(source)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, 1))
        val result = engine.compressPdf(source, outputUri, options) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriResult(operationId, result, originalSize)
    }

    // Stubs for operations not yet in PdfToolEngine -- return error so callers degrade gracefully
    suspend fun extract(source: Uri, pageIndices: List<Int>,
        outputName: String = "extracted_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        updateOperation(PdfOperationResult.Failure(operationId,
            UnsupportedOperationException("extract not yet implemented")))
        return operationId
    }

    suspend fun reorder(source: Uri, newOrder: List<Int>,
        outputName: String = "reordered_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        updateOperation(PdfOperationResult.Failure(operationId,
            UnsupportedOperationException("reorder not yet implemented")))
        return operationId
    }

    suspend fun duplicate(source: Uri, pageIndex: Int,
        outputName: String = "duplicated_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        updateOperation(PdfOperationResult.Failure(operationId,
            UnsupportedOperationException("duplicate not yet implemented")))
        return operationId
    }

    suspend fun delete(source: Uri, pageIndices: List<Int>,
        outputName: String = "deleted_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        updateOperation(PdfOperationResult.Failure(operationId,
            UnsupportedOperationException("delete not yet implemented")))
        return operationId
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

    private fun handleUriResult(
        operationId: String,
        result: AppResult<Uri>,
        originalSize: Long
    ): String {
        return when (result) {
            is AppResult.Success -> {
                val outputFile = File(result.data.path ?: "")
                updateOperation(
                    PdfOperationResult.Success(
                        operationId, outputFile, 1, originalSize, outputFile.length(), 0f
                    )
                )
                operationId
            }
            is AppResult.Error -> {
                updateOperation(PdfOperationResult.Failure(operationId,
                    Exception(result.exception.message)))
                operationId
            }
            else -> {
                updateOperation(PdfOperationResult.Failure(operationId,
                    Exception("Unknown error")))
                operationId
            }
        }
    }

    private fun handleUriListResult(
        operationId: String,
        result: AppResult<List<Uri>>,
        originalSize: Long
    ): String {
        return when (result) {
            is AppResult.Success -> {
                val files = result.data.map { File(it.path ?: "") }
                val totalSize = files.sumOf { it.length() }
                updateOperation(
                    PdfOperationResult.Success(
                        operationId, files.firstOrNull() ?: File(""),
                        files.size, originalSize, totalSize, 0f
                    )
                )
                operationId
            }
            is AppResult.Error -> {
                updateOperation(PdfOperationResult.Failure(operationId,
                    Exception(result.exception.message)))
                operationId
            }
            else -> {
                updateOperation(PdfOperationResult.Failure(operationId,
                    Exception("Unknown error")))
                operationId
            }
        }
    }

    private fun updateOperation(result: PdfOperationResult) {
        _operations.value = _operations.value + (result.operationId to result)
    }

    private fun uriForName(name: String): Uri =
        Uri.fromFile(File(context.filesDir, name))

    private fun getUriSize(uri: Uri): Long {
        return try { File(uri.path ?: return 0).length() } catch (e: Exception) { 0 }
    }
}
