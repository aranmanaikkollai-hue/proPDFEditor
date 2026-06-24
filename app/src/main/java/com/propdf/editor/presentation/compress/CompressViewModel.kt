package com.propdf.editor.presentation.compress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.repository.PdfOperationsRepository // Fixed import
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CompressViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<CompressUiState>(CompressUiState.Idle)
    val uiState: StateFlow<CompressUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    private val _compressConfig = MutableStateFlow<CompressConfig>(CompressConfig.MEDIUM)
    val compressConfig: StateFlow<CompressConfig> = _compressConfig

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
    }

    fun setCompressConfig(config: CompressConfig) {
        _compressConfig.value = config
    }

    fun compressDocument(outputName: String) {
        viewModelScope.launch {
            _uiState.value = CompressUiState.Loading
            val document = _selectedDocument.value ?: run {
                _uiState.value = CompressUiState.Error("No document selected")
                return@launch
            }
            when (val result = pdfOperationsRepository.compressPdf(document, _compressConfig.value, outputName)) {
                is AppResult.Success -> {
                    val originalSize = document.size
                    val compressedSize = result.data.size
                    val reduction = ((originalSize - compressedSize) * 100 / originalSize.toFloat()).coerceAtLeast(0f)
                    _uiState.value = CompressUiState.Success(result.data, reduction)
                }
                is AppResult.Error -> _uiState.value = CompressUiState.Error(result.exception.message)
            }
        }
    }

    fun reset() {
        _uiState.value = CompressUiState.Idle
        _selectedDocument.value = null
        _compressConfig.value = CompressConfig.MEDIUM
    }

    sealed class CompressUiState {
        object Idle : CompressUiState()
        object Loading : CompressUiState()
        data class Success(val outputPath: String, val sizeReductionPercent: Float) : CompressUiState()
        data class Error(val message: String?) : CompressUiState()
    }
}
