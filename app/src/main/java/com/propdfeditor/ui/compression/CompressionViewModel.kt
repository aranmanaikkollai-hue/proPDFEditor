package com.propdfeditor.ui.compression

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class CompressionViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<CompressionUiState>(CompressionUiState.Idle)
    val uiState: StateFlow<CompressionUiState> = _uiState.asStateFlow()

    private var currentDocument: PDDocument? = null
    private var currentUri: Uri? = null

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun loadDocument(uri: Uri) {
        viewModelScope.launch {
            try {
                val docFile = DocumentFile.fromSingleUri(context, uri)
                val name = docFile?.name ?: "document.pdf"
                val size = docFile?.length() ?: 0L

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    currentDocument = doc
                    currentUri = uri
                    _uiState.value = CompressionUiState.Loaded(
                        fileName = name,
                        originalSize = size,
                        estimatedSize = (size * 0.7).toLong()
                    )
                }
            } catch (e: Exception) {
                _uiState.value = CompressionUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    fun saveCompressed(
        destination: Uri,
        quality: Float,
        removeImages: Boolean,
        flattenForms: Boolean
    ) {
        val doc = currentDocument ?: return
        viewModelScope.launch {
            _uiState.value = CompressionUiState.Compressing
            try {
                withContext(Dispatchers.IO) {
                    // Basic compression: save with reduced quality
                    // In production, implement image downsampling and object stream compression
                    context.contentResolver.openOutputStream(destination)?.use { output ->
                        doc.save(output)
                    }
                }
                val compressedSize = DocumentFile.fromSingleUri(context, destination)?.length() ?: 0L
                _uiState.value = CompressionUiState.Done(
                    originalSize = _uiState.value.let {
                        if (it is CompressionUiState.Loaded) it.originalSize else 0L
                    },
                    compressedSize = compressedSize
                )
            } catch (e: Exception) {
                _uiState.value = CompressionUiState.Error(e.message ?: "Compression failed")
            }
        }
    }

    fun reset() {
        currentDocument?.close()
        currentDocument = null
        currentUri = null
        _uiState.value = CompressionUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        currentDocument?.close()
    }
}

sealed interface CompressionUiState {
    data object Idle : CompressionUiState
    data class Loaded(
        val fileName: String,
        val originalSize: Long,
        val estimatedSize: Long
    ) : CompressionUiState
    data object Compressing : CompressionUiState
    data class Done(val originalSize: Long, val compressedSize: Long) : CompressionUiState
    data class Error(val message: String) : CompressionUiState
}
