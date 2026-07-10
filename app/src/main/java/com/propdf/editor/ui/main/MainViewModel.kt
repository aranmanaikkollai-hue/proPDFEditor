package com.propdf.editor.ui.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.repository.DocumentRepository
import com.propdf.editor.data.worker.WorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastOpenedDocument: PdfDocument? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val workScheduler: WorkScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // Schedule background work
        workScheduler.scheduleDocumentScan()
        workScheduler.scheduleStorageAnalyzer()
        
        // Clean old recycle bin items
        viewModelScope.launch {
            documentRepository.cleanOldRecycleBinItems(30)
        }
    }

    fun handlePickedDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // Persist permission
                // Add to database
                val document = PdfDocument(
                    uriString = uri.toString(),
                    fileName = uri.lastPathSegment ?: "document.pdf",
                    filePath = uri.path ?: "",
                    sizeBytes = 0,
                    lastModified = System.currentTimeMillis()
                )
                documentRepository.insertDocument(document)
                _uiState.update { it.copy(isLoading = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun openPdfString(uriString: String) {
        viewModelScope.launch {
            // Update last opened
            val doc = documentRepository.getDocumentById(uriString.hashCode().toLong())
            doc?.let {
                documentRepository.updateLastOpened(it.id)
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
