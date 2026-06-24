package com.propdf.editor.presentation.merge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.MergeRequest
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.repository.PdfOperationsRepository // Fixed import
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MergeViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<MergeUiState>(MergeUiState.Idle)
    val uiState: StateFlow<MergeUiState> = _uiState

    private val _selectedDocuments = MutableStateFlow<List<PdfDocument>>(emptyList())
    val selectedDocuments: StateFlow<List<PdfDocument>> = _selectedDocuments

    fun addDocument(document: PdfDocument) {
        val current = _selectedDocuments.value.toMutableList()
        if (!current.any { it.uri == document.uri }) {
            current.add(document)
            _selectedDocuments.value = current
        }
    }

    fun removeDocument(document: PdfDocument) {
        val current = _selectedDocuments.value.toMutableList()
        current.removeAll { it.uri == document.uri }
        _selectedDocuments.value = current
    }

    fun reorderDocuments(fromIndex: Int, toIndex: Int) {
        val current = _selectedDocuments.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val item = current.removeAt(fromIndex)
            current.add(toIndex, item)
            _selectedDocuments.value = current
        }
    }

    fun mergeDocuments(outputName: String) {
        viewModelScope.launch {
            _uiState.value = MergeUiState.Loading
            val request = MergeRequest(
                documents = _selectedDocuments.value,
                outputName = outputName
            )
            when (val result = pdfOperationsRepository.mergePdfs(request)) {
                is AppResult.Success -> _uiState.value = MergeUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = MergeUiState.Error(result.exception.message)
            }
        }
    }

    fun reset() {
        _uiState.value = MergeUiState.Idle
        _selectedDocuments.value = emptyList()
    }

    sealed class MergeUiState {
        object Idle : MergeUiState()
        object Loading : MergeUiState()
        data class Success(val outputPath: String) : MergeUiState()
        data class Error(val message: String?) : MergeUiState()
    }
}
