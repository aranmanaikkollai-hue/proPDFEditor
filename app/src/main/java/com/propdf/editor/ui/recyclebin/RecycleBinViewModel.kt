package com.propdf.editor.ui.recyclebin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.usecase.GetDeletedFilesUseCase
import com.propdf.editor.domain.usecase.RestoreDocumentUseCase
import com.propdf.editor.domain.usecase.PermanentDeleteUseCase
import com.propdf.editor.domain.usecase.EmptyRecycleBinUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RecycleBinViewModel @Inject constructor(
    private val getDeletedFiles: GetDeletedFilesUseCase,
    private val restoreDocument: RestoreDocumentUseCase,
    private val permanentDelete: PermanentDeleteUseCase,
    private val emptyRecycleBin: EmptyRecycleBinUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(RecycleBinUiState())
    val uiState: StateFlow<RecycleBinUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            getDeletedFiles().collect { files ->
                _uiState.update { it.copy(
                    files = files,
                    isLoading = false,
                    isEmpty = files.isEmpty()
                ) }
            }
        }
    }

    fun restore(id: Long) {
        viewModelScope.launch {
            restoreDocument(id)
        }
    }

    fun permanentDelete(id: Long) {
        viewModelScope.launch {
            permanentDelete(id)
        }
    }

    fun emptyBin() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            emptyRecycleBin()
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}

data class RecycleBinUiState(
    val files: List<PdfDocument> = emptyList(),
    val isLoading: Boolean = true,
    val isEmpty: Boolean = false,
    val error: String? = null
)
