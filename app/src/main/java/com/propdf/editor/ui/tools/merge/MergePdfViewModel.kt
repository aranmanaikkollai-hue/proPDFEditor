package com.propdf.editor.ui.tools.merge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.MergeConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MergePdfViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MergeUiState())
    val uiState: StateFlow<MergeUiState> = _uiState.asStateFlow()

    fun addPdf(uri: Uri) {
        _uiState.update { state ->
            if (state.selectedUris.contains(uri)) state
            else state.copy(selectedUris = state.selectedUris + uri)
        }
    }

    fun removePdf(uri: Uri) {
        _uiState.update { state ->
            state.copy(selectedUris = state.selectedUris - uri)
        }
    }

    fun reorderPdfs(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            val list = state.selectedUris.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
            }
            state.copy(selectedUris = list)
        }
    }

    fun mergePdfs(outputName: String) {
        val uris = _uiState.value.selectedUris
        if (uris.size < 2) {
            _uiState.update { it.copy(error = "Select at least 2 PDFs to merge") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            val config = MergeConfig(
                sourceUris = uris,
                outputFileName = outputName
            )
            
            when (val result = pdfOperationsRepository.mergePdfs(config)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(
                        isLoading = false,
                        mergedUri = result.data,
                        selectedUris = emptyList()
                    ) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                }
                else -> {}
            }
        }
    }

    fun clearMergedUri() {
        _uiState.update { it.copy(mergedUri = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class MergeUiState(
    val selectedUris: List<Uri> = emptyList(),
    val isLoading: Boolean = false,
    val mergedUri: Uri? = null,
    val error: String? = null
)
