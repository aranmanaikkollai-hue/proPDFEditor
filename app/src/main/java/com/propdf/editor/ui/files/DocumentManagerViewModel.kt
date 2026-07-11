package com.propdf.editor.ui.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.domain.model.PdfDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentManagerUiState(
    val documents: List<PdfDocument> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class DocumentManagerViewModel @Inject constructor(
    private val documentRepository: com.propdf.editor.domain.repository.DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentManagerUiState())
    val uiState: StateFlow<DocumentManagerUiState> = _uiState.asStateFlow()

    init {
        loadDocuments()
    }

    private fun loadDocuments() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val docs = documentRepository.getAllDocuments()
                _uiState.value = DocumentManagerUiState(documents = docs, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = DocumentManagerUiState(error = e.message, isLoading = false)
            }
        }
    }

    fun toggleFavorite(documentId: Long) {
        viewModelScope.launch {
            documentRepository.setFavorite(documentId, true)
            loadDocuments()
        }
    }

    fun deleteDocument(documentId: Long) {
        viewModelScope.launch {
            documentRepository.deleteDocument(documentId)
            loadDocuments()
        }
    }

    fun getHiddenDocuments(): List<PdfDocument> {
        return _uiState.value.documents.filter { it.isDeleted }
    }
}
