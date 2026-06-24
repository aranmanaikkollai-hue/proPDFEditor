package com.propdf.editor.presentation.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.repository.PdfOperationsRepository // Fixed import
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrganizeViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<OrganizeUiState>(OrganizeUiState.Idle)
    val uiState: StateFlow<OrganizeUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    private val _pageOrder = MutableStateFlow<List<Int>>(emptyList())
    val pageOrder: StateFlow<List<Int>> = _pageOrder

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
        _pageOrder.value = (1..document.pageCount).toList()
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        val current = _pageOrder.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val page = current.removeAt(fromIndex)
            current.add(toIndex, page)
            _pageOrder.value = current
        }
    }

    fun deletePage(pageIndex: Int) {
        val current = _pageOrder.value.toMutableList()
        if (pageIndex in current.indices) {
            current.removeAt(pageIndex)
            _pageOrder.value = current
        }
    }

    fun rotatePage(pageIndex: Int, degrees: Int) {
        // Implementation for rotating specific pages
    }

    fun applyChanges(outputName: String) {
        viewModelScope.launch {
            _uiState.value = OrganizeUiState.Loading
            val document = _selectedDocument.value ?: run {
                _uiState.value = OrganizeUiState.Error("No document selected")
                return@launch
            }
            when (val result = pdfOperationsRepository.reorganizePdf(document, _pageOrder.value, outputName)) {
                is AppResult.Success -> _uiState.value = OrganizeUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = OrganizeUiState.Error(result.exception.message)
            }
        }
    }

    fun reset() {
        _uiState.value = OrganizeUiState.Idle
        _selectedDocument.value = null
        _pageOrder.value = emptyList()
    }

    sealed class OrganizeUiState {
        object Idle : OrganizeUiState()
        object Loading : OrganizeUiState()
        data class Success(val outputPath: String) : OrganizeUiState()
        data class Error(val message: String?) : OrganizeUiState()
    }
}
