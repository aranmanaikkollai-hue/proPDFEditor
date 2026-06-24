package com.propdf.editor.presentation.split

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.PageRange
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.model.SplitRequest
import com.propdf.core.domain.repository.PdfOperationsRepository // Fixed import
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SplitUiState>(SplitUiState.Idle)
    val uiState: StateFlow<SplitUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    private val _pageRanges = MutableStateFlow<List<PageRange>>(emptyList())
    val pageRanges: StateFlow<List<PageRange>> = _pageRanges

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
        _pageRanges.value = emptyList()
    }

    fun addPageRange(startPage: Int, endPage: Int) {
        val current = _pageRanges.value.toMutableList()
        current.add(PageRange(startPage, endPage))
        _pageRanges.value = current
    }

    fun removePageRange(index: Int) {
        val current = _pageRanges.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            _pageRanges.value = current
        }
    }

    fun splitDocument(outputPrefix: String) {
        viewModelScope.launch {
            _uiState.value = SplitUiState.Loading
            val document = _selectedDocument.value ?: run {
                _uiState.value = SplitUiState.Error("No document selected")
                return@launch
            }
            val request = SplitRequest(
                document = document,
                pageRanges = _pageRanges.value,
                outputPrefix = outputPrefix
            )
            when (val result = pdfOperationsRepository.splitPdf(request)) {
                is AppResult.Success -> _uiState.value = SplitUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = SplitUiState.Error(result.exception.message)
            }
        }
    }

    fun reset() {
        _uiState.value = SplitUiState.Idle
        _selectedDocument.value = null
        _pageRanges.value = emptyList()
    }

    sealed class SplitUiState {
        object Idle : SplitUiState()
        object Loading : SplitUiState()
        data class Success(val outputPaths: List<String>) : SplitUiState()
        data class Error(val message: String?) : SplitUiState()
    }
}
