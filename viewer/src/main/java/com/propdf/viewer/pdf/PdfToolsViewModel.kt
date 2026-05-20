package com.propdf.viewer.pdf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PdfToolsViewModel @Inject constructor(
    private val exportManager: PdfExportManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfToolsUiState())
    val uiState: StateFlow<PdfToolsUiState> = _uiState.asStateFlow()

    private val _activeOperations = MutableStateFlow<Map<String, ActiveOperation>>(emptyMap())
    val activeOperations: StateFlow<Map<String, ActiveOperation>> = _activeOperations.asStateFlow()

    fun mergePdfs(sources: List<Uri>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.merge(sources)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun splitPdf(source: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.split(source)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun extractPages(source: Uri, pageIndices: List<Int>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.extract(source, pageIndices)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun reorderPages(source: Uri, newOrder: List<Int>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.reorder(source, newOrder)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun duplicatePage(source: Uri, pageIndex: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.duplicate(source, pageIndex)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun rotatePages(source: Uri, pageIndices: List<Int>, degrees: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.rotate(source, pageIndices, degrees)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun deletePages(source: Uri, pageIndices: List<Int>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.delete(source, pageIndices)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun compressPdf(source: Uri, options: CompressionOptions) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessing = true, error = null)
            try {
                val operationId = exportManager.compress(source, options)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isProcessing = false, error = e.message)
            }
        }
    }

    fun cancelOperation(operationId: String) {
        exportManager.cancelOperation(operationId)
        _activeOperations.value = _activeOperations.value - operationId
    }

    fun cancelAll() {
        exportManager.cancelOperation("")
    }

    fun cleanup() {
        viewModelScope.launch {
            exportManager.cleanup()
        }
    }

    private fun observeOperation(operationId: String) {
        viewModelScope.launch {
            _activeOperations.value = _activeOperations.value + (
                operationId to ActiveOperation(operationId, 0f, true)
            )

            var completed = false
            var attempts = 0
            while (!completed && attempts < 300) {
                val result = exportManager.getOperationResult(operationId)
                when (result) {
                    is PdfOperationResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false,
                            lastOutputFile = result.outputFile,
                            compressionRatio = result.compressionRatio
                        )
                        _activeOperations.value = _activeOperations.value + (
                            operationId to ActiveOperation(operationId, 100f, false)
                        )
                        completed = true
                    }
                    is PdfOperationResult.Failure -> {
                        _uiState.value = _uiState.value.copy(
                            isProcessing = false, error = result.error.message
                        )
                        _activeOperations.value = _activeOperations.value + (
                            operationId to ActiveOperation(operationId, 0f, false, result.error.message)
                        )
                        completed = true
                    }
                    is PdfOperationResult.Cancelled -> {
                        _uiState.value = _uiState.value.copy(isProcessing = false)
                        _activeOperations.value = _activeOperations.value + (
                            operationId to ActiveOperation(operationId, 0f, false, "Cancelled")
                        )
                        completed = true
                    }
                    is PdfOperationResult.InProgress -> {
                        _activeOperations.value = _activeOperations.value + (
                            operationId to ActiveOperation(operationId, result.percentComplete, true)
                        )
                    }
                    null -> {}
                }
                kotlinx.coroutines.delay(500)
                attempts++
            }
        }
    }

    data class PdfToolsUiState(
        val isProcessing: Boolean = false,
        val lastOutputFile: File? = null,
        val compressionRatio: Float = 0f,
        val error: String? = null
    )

    data class ActiveOperation(
        val operationId: String,
        val progressPercent: Float,
        val isRunning: Boolean,
        val errorMessage: String? = null
    )
}
