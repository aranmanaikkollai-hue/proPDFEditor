package com.propdf.viewer.pdf

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.propdf.core.domain.result.AppResult
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PdfExportManager(
    private val context: Context,
    private val engine: PdfToolEngine,
    private val tempManager: TempFileManager = TempFileManager(context)
) {

    private val _operations = MutableStateFlow<Map<String, PdfOperationResult>>(emptyMap())
    val operations: StateFlow<Map<String, PdfOperationResult>> = _operations.asStateFlow()

    private val cancelledOperations = ConcurrentHashMap.newKeySet<String>()

    suspend fun merge(sources: List<Uri>, outputName: String = "merged_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, sources.size))
        val result = engine.mergePdfs(sources, Uri.fromFile(outputFile)) { current, total ->
            if (isCancelled(operationId)) {
                updateOperation(PdfOperationResult.Cancelled(operationId, current))
                return@mergePdfs
            }
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriResult(operationId, result, outputFile, sources.sumOf { getUriSize(it) })
    }

    suspend fun split(source: Uri, outputDir: File = File(context.filesDir, "split").apply { mkdirs() }): String {
        val operationId = UUID.randomUUID().toString()
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, 1))
        val result = engine.splitPdf(source, Uri.fromFile(outputDir)) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriListResult(operationId, result, getUriSize(source))
    }

    suspend fun extract(source: Uri, pageIndices: List<Int>, outputName: String = "extracted_${System.currentTimeMillis()}.pdf"): String =
        runDocumentOperation(source, outputName, pageIndices.size) { sourceFile, outputFile, operationId ->
            PDDocument.load(sourceFile).use { sourceDoc ->
                PDDocument().use { outDoc ->
                    pageIndices.forEachIndexed { index, pageIndex ->
                        currentCoroutineContext().ensureActive()
                        if (isCancelled(operationId)) return@runDocumentOperation Result.failure(Exception("Cancelled"))
                        if (pageIndex in 0 until sourceDoc.numberOfPages) outDoc.importPage(sourceDoc.getPage(pageIndex))
                        updateOperation(PdfOperationResult.InProgress.create(operationId, index + 1, pageIndices.size))
                    }
                    outDoc.save(outputFile)
                }
            }
            Result.success(outputFile)
        }

    suspend fun reorder(source: Uri, newOrder: List<Int>, outputName: String = "reordered_${System.currentTimeMillis()}.pdf"): String =
        runDocumentOperation(source, outputName, newOrder.size) { sourceFile, outputFile, operationId ->
            PDDocument.load(sourceFile).use { sourceDoc ->
                PDDocument().use { outDoc ->
                    newOrder.forEachIndexed { index, pageIndex ->
                        currentCoroutineContext().ensureActive()
                        if (isCancelled(operationId)) return@runDocumentOperation Result.failure(Exception("Cancelled"))
                        if (pageIndex in 0 until sourceDoc.numberOfPages) outDoc.importPage(sourceDoc.getPage(pageIndex))
                        updateOperation(PdfOperationResult.InProgress.create(operationId, index + 1, newOrder.size))
                    }
                    outDoc.save(outputFile)
                }
            }
            Result.success(outputFile)
        }

    suspend fun duplicate(source: Uri, pageIndex: Int, outputName: String = "duplicated_${System.currentTimeMillis()}.pdf"): String =
        runDocumentOperation(source, outputName, 1) { sourceFile, outputFile, _ ->
            PDDocument.load(sourceFile).use { sourceDoc ->
                PDDocument().use { outDoc ->
                    if (pageIndex in 0 until sourceDoc.numberOfPages) {
                        outDoc.importPage(sourceDoc.getPage(pageIndex))
                        outDoc.importPage(sourceDoc.getPage(pageIndex))
                    }
                    outDoc.save(outputFile)
                }
            }
            Result.success(outputFile)
        }

    suspend fun rotate(source: Uri, pageIndices: List<Int>, degrees: Int, outputName: String = "rotated_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, pageIndices.size))
        val result = engine.rotatePages(source, pageIndices, degrees, Uri.fromFile(outputFile)) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriResult(operationId, result, outputFile, getUriSize(source))
    }

    suspend fun delete(source: Uri, pageIndices: List<Int>, outputName: String = "deleted_${System.currentTimeMillis()}.pdf"): String =
        runDocumentOperation(source, outputName, getPageCount(source)) { sourceFile, outputFile, operationId ->
            val pagesToDelete = pageIndices.toSet()
            PDDocument.load(sourceFile).use { sourceDoc ->
                PDDocument().use { outDoc ->
                    for (pageIndex in 0 until sourceDoc.numberOfPages) {
                        currentCoroutineContext().ensureActive()
                        if (isCancelled(operationId)) return@runDocumentOperation Result.failure(Exception("Cancelled"))
                        if (pageIndex !in pagesToDelete) outDoc.importPage(sourceDoc.getPage(pageIndex))
                        updateOperation(PdfOperationResult.InProgress.create(operationId, pageIndex + 1, sourceDoc.numberOfPages))
                    }
                    outDoc.save(outputFile)
                }
            }
            Result.success(outputFile)
        }

    suspend fun compress(source: Uri, options: CompressionOptions = CompressionOptions(), outputName: String = "compressed_${System.currentTimeMillis()}.pdf"): String {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        val originalSize = getUriSize(source)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, getPageCount(source)))
        val result = engine.compressPdf(source, Uri.fromFile(outputFile), options) { current, total ->
            updateOperation(PdfOperationResult.InProgress.create(operationId, current, total))
        }
        return handleUriResult(operationId, result, outputFile, originalSize)
    }

    fun cancelOperation(operationId: String) {
        if (operationId.isBlank()) {
            _operations.value.keys.forEach { cancelledOperations.add(it) }
        } else {
            cancelledOperations.add(operationId)
        }
    }

    suspend fun cleanup() {
        tempManager.cleanAll()
    }

    fun getOperationResult(operationId: String): PdfOperationResult? = _operations.value[operationId]

    private suspend fun runDocumentOperation(
        source: Uri,
        outputName: String,
        totalWork: Int,
        operation: suspend (sourceFile: File, outputFile: File, operationId: String) -> Result<File>
    ): String = withContext(Dispatchers.IO) {
        val operationId = UUID.randomUUID().toString()
        val outputFile = File(context.filesDir, outputName)
        updateOperation(PdfOperationResult.InProgress.create(operationId, 0, totalWork))
        val sourceFile = copyUriToTempFile(source)
        val result = operation(sourceFile, outputFile, operationId)
        if (sourceFile.parentFile == tempManager.tempDir) sourceFile.delete()
        handleResult(operationId, result, getUriSize(source))
    }

    private fun handleUriResult(operationId: String, result: AppResult<Uri>, outputFile: File, originalSize: Long): String {
        return when (result) {
            is AppResult.Success -> handleResult(operationId, Result.success(outputFile), originalSize)
            is AppResult.Error -> handleResult(operationId, Result.failure(result.exception), originalSize)
            else -> handleResult(operationId, Result.failure(Exception("Unknown PDF operation result")), originalSize)
        }
    }

    private fun handleUriListResult(operationId: String, result: AppResult<List<Uri>>, originalSize: Long): String {
        return when (result) {
            is AppResult.Success -> {
                val files = result.data.mapNotNull { uri -> uri.path?.let(::File) }
                val totalSize = files.sumOf { it.length() }
                updateOperation(PdfOperationResult.Success(operationId, files.firstOrNull() ?: File(""), files.size, originalSize, totalSize, 0f))
                operationId
            }
            is AppResult.Error -> handleResult(operationId, Result.failure(result.exception), originalSize)
            else -> handleResult(operationId, Result.failure(Exception("Unknown PDF operation result")), originalSize)
        }
    }

    private fun handleResult(operationId: String, result: Result<File>, originalSize: Long): String {
        cancelledOperations.remove(operationId)
        return when {
            result.isSuccess -> {
                val file = result.getOrThrow()
                val compressionRatio = if (originalSize > 0L) {
                    ((originalSize - file.length()).toFloat() / originalSize) * 100f
                } else 0f
                updateOperation(PdfOperationResult.Success(operationId, file, 1, originalSize, file.length(), compressionRatio))
                operationId
            }
            result.exceptionOrNull()?.message == "Cancelled" -> {
                updateOperation(PdfOperationResult.Cancelled(operationId, 0))
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

    private fun isCancelled(operationId: String): Boolean = cancelledOperations.contains(operationId)

    private fun getUriSize(uri: Uri): Long {
        if (uri.scheme == "file") return uri.path?.let { File(it).length() } ?: 0L
        return queryOpenableColumn(uri, OpenableColumns.SIZE) { cursor, index -> cursor.getLong(index) } ?: 0L
    }

    private suspend fun getPageCount(uri: Uri): Int = withContext(Dispatchers.IO) {
        try {
            val file = copyUriToTempFile(uri)
            try {
                PDDocument.load(file).use { it.numberOfPages }
            } finally {
                if (file.parentFile == tempManager.tempDir) file.delete()
            }
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun copyUriToTempFile(uri: Uri): File = withContext(Dispatchers.IO) {
        if (uri.scheme == "file") return@withContext File(requireNotNull(uri.path))
        val displayName = queryOpenableColumn(uri, OpenableColumns.DISPLAY_NAME) { cursor, index ->
            cursor.getString(index)
        } ?: "source_${System.currentTimeMillis()}.pdf"
        val outFile = File(tempManager.tempDir, displayName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("Unable to open input stream for $uri")
        outFile
    }

    private fun <T> queryOpenableColumn(uri: Uri, column: String, read: (Cursor, Int) -> T): T? {
        return context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(column)
                if (index >= 0) read(cursor, index) else null
            } else null
        }
    }
}
