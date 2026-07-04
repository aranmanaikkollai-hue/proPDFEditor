package com.propdf.editor.ui.tools.merge

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.ImageInsertionConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CombineImagesViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CombineImagesUiState())
    val uiState: StateFlow<CombineImagesUiState> = _uiState.asStateFlow()

    fun addImages(uris: List<Uri>) {
        _uiState.update { state ->
            state.copy(selectedImages = state.selectedImages + uris.filter { it !in state.selectedImages })
        }
    }

    fun removeImage(uri: Uri) {
        _uiState.update { state ->
            state.copy(selectedImages = state.selectedImages - uri)
        }
    }

    fun reorderImages(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            val list = state.selectedImages.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
            }
            state.copy(selectedImages = list)
        }
    }

    fun combineToPdf(outputName: String, pageWidth: Float = 595f, pageHeight: Float = 842f) {
        val images = _uiState.value.selectedImages
        if (images.isEmpty()) {
            _uiState.update { it.copy(error = "Select at least one image") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            
            val config = ImageInsertionConfig(
                imageUri = images.first(),
                pageWidth = pageWidth,
                pageHeight = pageHeight
            )
            
            when (val result = pdfOperationsRepository.combineImagesToPdf(images, outputName, config)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(
                        isLoading = false,
                        outputUri = result.data,
                        selectedImages = emptyList()
                    ) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                }
                else -> {}
            }
        }
    }

    fun clearOutput() {
        _uiState.update { it.copy(outputUri = null) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class CombineImagesUiState(
    val selectedImages: List<Uri> = emptyList(),
    val isLoading: Boolean = false,
    val outputUri: Uri? = null,
    val error: String? = null
)
