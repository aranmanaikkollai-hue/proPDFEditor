package com.propdf.editor.presentation.compress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CompressViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<CompressUiState>(CompressUiState.Idle)
    val uiState: StateFlow<CompressUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    // Default compression level 6 (medium)
    private val _compressLevel = MutableStateFlow(6)
    val compressLevel: StateFlow<Int> = _compressLevel

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
    }

    fun setCompressLevel(level: Int) {
        _compressLevel.value = level.coerceIn(1, 9)
    }

    fun compressDocument(inputFile: File, outputFile: File) {
        viewModelScope.launch {
            _uiState.value = CompressUiState.Loading
            val config = CompressConfig(level = _compressLevel.value)
            when (val result = pdfOperationsRepository.compress(inputFile, outputFile, config)) {
                is AppResult.Success -> {
                    val originalSize = inputFile.length()
                    val compressedSize = result.data.length()
                    val reduction = if (originalSize > 0)
                        ((originalSize - compressedSize) * 100f / originalSize).coerceAtLeast(0f)
                    else 0f
                    _uiState.value = CompressUiState.Success(result.data.absolutePath, reduction)
                }
                is AppResult.Error -> _uiState.value = CompressUiState.Error(result.message)
                else -> Unit
            }
        }
    }

    fun reset() {
        _uiState.value = CompressUiState.Idle
        _selectedDocument.value = null
        _compressLevel.value = 6
    }

    sealed class CompressUiState {
        object Idle : CompressUiState()
        object Loading : CompressUiState()
        data class Success(val outputPath: String, val sizeReductionPercent: Float) : CompressUiState()
        data class Error(val message: String?) : CompressUiState()
    }
}
