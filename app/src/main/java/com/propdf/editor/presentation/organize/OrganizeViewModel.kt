package com.propdf.editor.presentation.organize

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
class OrganizeViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<OrganizeUiState>(OrganizeUiState.Idle)
    val uiState: StateFlow<OrganizeUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    private val _pageOrder = MutableStateFlow<List<Int>>(emptyList())
    val pageOrder: StateFlow<List<Int>> = _pageOrder

    private val _deletedPages = MutableStateFlow<List<Int>>(emptyList())
    val deletedPages: StateFlow<List<Int>> = _deletedPages

    private val _pageRotations = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val pageRotations: StateFlow<Map<Int, Int>> = _pageRotations

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
        val total = document.pageCount
        _pageOrder.value = (1..total).toList()
        _deletedPages.value = emptyList()
        _pageRotations.value = emptyMap()
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        val current = _pageOrder.value.toMutableList()
        if (fromIndex in current.indices && toIndex in current.indices) {
            val page = current.removeAt(fromIndex)
            current.add(toIndex, page)
            _pageOrder.value = current
        }
    }

    fun markPageForDeletion(pageIndex: Int) {
        val current = _deletedPages.value.toMutableList()
        val pageNum = _pageOrder.value.getOrNull(pageIndex) ?: return
        if (!current.contains(pageNum)) current.add(pageNum)
        _deletedPages.value = current
    }

    fun rotatePage(pageIndex: Int, degrees: Int) {
        val pageNum = _pageOrder.value.getOrNull(pageIndex) ?: return
        val current = _pageRotations.value.toMutableMap()
        current[pageNum] = ((current[pageNum] ?: 0) + degrees) % 360
        _pageRotations.value = current
    }

    fun applyChanges(inputFile: File, outputFile: File) {
        viewModelScope.launch {
            _uiState.value = OrganizeUiState.Loading

            // Apply deletions first
            val toDelete = _deletedPages.value
            val rotations = _pageRotations.value

            val intermediateFile = if (toDelete.isNotEmpty()) {
                val temp = File(inputFile.parent, "temp_${System.currentTimeMillis()}.pdf")
                val delResult = pdfOperationsRepository.deletePages(inputFile, temp, toDelete)
                if (delResult is AppResult.Error) {
                    _uiState.value = OrganizeUiState.Error(delResult.message)
                    return@launch
                }
                temp
            } else {
                inputFile
            }

            // Apply rotations
            if (rotations.isNotEmpty()) {
                when (val result = pdfOperationsRepository.rotatePages(intermediateFile, outputFile, rotations)) {
                    is AppResult.Success -> _uiState.value = OrganizeUiState.Success(result.data.absolutePath)
                    is AppResult.Error -> _uiState.value = OrganizeUiState.Error(result.message)
                    else -> Unit
                }
                if (intermediateFile != inputFile) intermediateFile.delete()
            } else {
                intermediateFile.copyTo(outputFile, overwrite = true)
                if (intermediateFile != inputFile) intermediateFile.delete()
                _uiState.value = OrganizeUiState.Success(outputFile.absolutePath)
            }
        }
    }

    fun reset() {
        _uiState.value = OrganizeUiState.Idle
        _selectedDocument.value = null
        _pageOrder.value = emptyList()
        _deletedPages.value = emptyList()
        _pageRotations.value = emptyMap()
    }

    sealed class OrganizeUiState {
        object Idle : OrganizeUiState()
        object Loading : OrganizeUiState()
        data class Success(val outputPath: String) : OrganizeUiState()
        data class Error(val message: String?) : OrganizeUiState()
    }
}
