package com.propdfeditor.ui.compression

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.PdfProcessingError
import com.propdfeditor.batch.util.PdfOperationException
import com.propdfeditor.batch.util.PdfProcessor
import com.propdfeditor.batch.util.PdfPasswordException
import com.propdfeditor.batch.util.SignedPdfException
import com.propdfeditor.core.util.toSafeUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject

/**
 * Drives the Compress screen on top of the shared [PdfProcessor] / PdfBoxCompressionRepository.
 *
 * The input is only ever read; the output is always a separate document chosen by the user. No PDFBox document
 * is held between steps, so nothing needs closing here.
 */
@HiltViewModel
class CompressionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pdfProcessor: PdfProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow<CompressionUiState>(CompressionUiState.Idle)
    val uiState: StateFlow<CompressionUiState> = _uiState.asStateFlow()

    private var inputUri: Uri? = null
    private var job: Job? = null
    private var loaded: CompressionUiState.Loaded? = null

    /** Reads the chosen PDF without modifying it and refuses inputs that must not be compressed. */
    fun loadDocument(uri: Uri) {
        job?.cancel()
        inputUri = null
        loaded = null
        _uiState.value = CompressionUiState.Inspecting
        job = viewModelScope.launch {
            try {
                val info = pdfProcessor.inspectForCompression(context, uri)
                val (name, size) = describe(uri)
                inputUri = uri
                val ready = CompressionUiState.Loaded(name, size, info.pageCount)
                loaded = ready
                _uiState.value = ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Compression input rejected")
                _uiState.value = CompressionUiState.Error(inputMessage(e))
            }
        }
    }

    /** Compresses into [destination] (a document created for this purpose). The input is never written. */
    fun compress(destination: Uri, settings: CompressionSettings) {
        val source = inputUri ?: return
        val ready = loaded ?: return
        if (destination == source) {
            _uiState.value = CompressionUiState.Error("Choose a new file; the original PDF is never overwritten.")
            return
        }
        job?.cancel()
        _uiState.value = CompressionUiState.Compressing(0f)
        job = viewModelScope.launch {
            try {
                val result = pdfProcessor.compressPdf(context, source, destination, settings.toConfig()) { progress ->
                    _uiState.value = CompressionUiState.Compressing(progress.coerceIn(0f, 1f))
                }
                _uiState.value = CompressionUiState.Done(
                    fileName = ready.fileName,
                    comparison = SizeComparison(result.originalSizeBytes, result.compressedSizeBytes),
                    pageCount = result.pageCount,
                    imagesRecompressed = result.imagesProcessed,
                    metadataRemoved = result.metadataRemoved
                )
            } catch (e: CancellationException) {
                withContext(NonCancellable) { discardOutput(destination, source) }
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Compression failed")
                withContext(NonCancellable) { discardOutput(destination, source) }
                _uiState.value = CompressionUiState.Error(compressMessage(e))
            }
        }
    }

    /** Stops a running compression and returns to the options; the input is unchanged. */
    fun cancel() {
        job?.cancel()
        _uiState.value = loaded ?: CompressionUiState.Idle
    }

    /** Leaves an error: back to the options when a PDF is still loaded, otherwise to the start. */
    fun dismissError() {
        _uiState.value = loaded ?: CompressionUiState.Idle
    }

    fun reset() {
        job?.cancel()
        inputUri = null
        loaded = null
        _uiState.value = CompressionUiState.Idle
    }

    /** Name and size of the input, taken from the provider; falls back to the URI and a descriptor size. */
    private fun describe(uri: Uri): Pair<String, Long> {
        var name: String? = null
        var size = -1L
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val n = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !cursor.isNull(n)) name = cursor.getString(n)
                        if (s >= 0 && !cursor.isNull(s)) size = cursor.getLong(s)
                    }
                }
        } catch (e: Exception) {
            Timber.w(e, "Could not query document metadata")
        }
        if (size < 0) {
            size = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            } catch (e: Exception) {
                0L
            }
        }
        return (name ?: uri.lastPathSegment ?: "document.pdf") to size
    }

    /**
     * Removes the destination document that this run created, so a failed or cancelled run does not leave an empty
     * or partial PDF behind. Never touches the input.
     */
    private fun discardOutput(destination: Uri, source: Uri) {
        if (destination == source) return
        try {
            if (destination.scheme == "content") {
                DocumentsContract.deleteDocument(context.contentResolver, destination)
            } else if (destination.scheme == "file") {
                destination.path?.let { java.io.File(it).delete() }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not remove the unfinished output document")
        }
    }

    private fun inputMessage(e: Throwable): String = when (e) {
        is PdfPasswordException -> e.message ?: "This PDF is password protected."
        is SignedPdfException -> "This PDF is digitally signed. Compressing it would invalidate the signature, so it cannot be compressed."
        is PdfOperationException -> e.message ?: "This PDF cannot be compressed."
        is IOException -> "This file is not a readable PDF."
        else -> e.toSafeUserMessage("This PDF could not be opened.")
    }

    private fun compressMessage(e: Throwable): String {
        val base = when (e) {
            is SignedPdfException -> "This PDF is digitally signed. Compressing it would invalidate the signature."
            is PdfOperationException -> e.message ?: "This PDF could not be compressed."
            is PdfProcessingError.ProcessingFailed -> e.message ?: "The compressed file failed its safety check."
            is AppException.IOError ->
                if (e.message.orEmpty().lowercase().let { "space" in it }) "Not enough storage space to save the compressed PDF."
                else "The compressed PDF could not be saved. Try a different location."
            else -> e.toSafeUserMessage("This document could not be compressed.")
        }
        return "$base Your original PDF was not changed."
    }

    override fun onCleared() {
        job?.cancel()
        super.onCleared()
    }
}

sealed interface CompressionUiState {
    data object Idle : CompressionUiState
    data object Inspecting : CompressionUiState
    data class Loaded(val fileName: String, val originalSize: Long, val pageCount: Int) : CompressionUiState
    data class Compressing(val progress: Float) : CompressionUiState
    data class Done(
        val fileName: String,
        val comparison: SizeComparison,
        val pageCount: Int,
        val imagesRecompressed: Int,
        val metadataRemoved: Boolean
    ) : CompressionUiState
    data class Error(val message: String) : CompressionUiState
}
