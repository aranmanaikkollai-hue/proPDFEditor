package com.propdf.viewer.pdf

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import com.propdf.viewer.pdf.worker.PdfWorkScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel for PDF tools UI.
 * Bridges between UI and background operations via WorkManager or direct coroutines.
 */
@HiltViewModel
class PdfToolsViewModel @Inject constructor(
    private val exportManager: PdfExportManager,
    private val workScheduler: PdfWorkScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(PdfToolsUiState())
    val uiState: StateFlow<PdfToolsUiState> = _uiState.asStateFlow()

    private val _activeOperations = MutableStateFlow<Map<String, ActiveOperation>>(emptyMap())
    val activeOperations: StateFlow<Map<String, ActiveOperation>> = _activeOperations.asStateFlow()

    // ==================== DIRECT OPERATIONS (foreground) ====================

    fun mergePdfs(sources: List<Uri>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.merge(sources)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun splitPdf(source: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.split(source)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun extractPages(source: Uri, pageIndices: List<Int>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.extract(source, pageIndices)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun reorderPages(source: Uri, newOrder: List<Int>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.reorder(source, newOrder)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun duplicatePage(source: Uri, pageIndex: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.duplicate(source, pageIndex)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun rotatePages(source: Uri, pageIndices: List<Int>, degrees: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.rotate(source, pageIndices, degrees)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun deletePages(source: Uri, pageIndices: List<Int>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.delete(source, pageIndices)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    fun compressPdf(source: Uri, options: CompressionOptions) {
        viewModelScope.launch {
            _uiState.update { it.copy(isProcessing = true, error = null) }
            try {
                val operationId = exportManager.compress(source, options)
                observeOperation(operationId)
            } catch (e: Exception) {
                _uiState.update { it.copy(isProcessing = false, error = e.message) }
            }
        }
    }

    // ==================== BACKGROUND OPERATIONS (WorkManager) ====================

    fun scheduleBackgroundOperation(
        type: PdfOperationType,
        sourceUris: List<String>,
        pageIndices: IntArray? = null,
        degrees: Int = 90,
        compressionOptions: CompressionOptions? = null
    ) {
        workScheduler.schedule(
            operationType = type,
            sourceUris = sourceUris,
            pageIndices = pageIndices,
            degrees = degrees,
            compressionOptions = compressionOptions
        )
    }

    // ==================== CANCELLATION ====================

    fun cancelOperation(operationId: String) {
        exportManager.cancelOperation(operationId)
        _activeOperations.update { current ->
            current - operationId
        }
    }

    fun cancelAll() {
        exportManager.cancelOperation("")
        workScheduler.cancelAll()
    }

    // ==================== CLEANUP ====================

    fun cleanup() {
        viewModelScope.launch {
            exportManager.cleanup()
        }
    }

    // ==================== PRIVATE ====================

    private fun observeOperation(operationId: String) {
        viewModelScope.launch {
            _activeOperations.update { current ->
                current + (operationId to ActiveOperation(operationId, 0f, true))
            }

            var completed = false
            var attempts = 0
            while (!completed && attempts < 300) {
                val result = exportManager.getOperationResult(operationId)
                when (result) {
                    is PdfOperationResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isProcessing = false,
                                lastOutputFile = result.outputFile,
                                compressionRatio = result.compressionRatio
                            )
                        }
                        _activeOperations.update { current ->
                            current + (operationId to ActiveOperation(operationId, 100f, false))
                        }
                        completed = true
                    }
                    is PdfOperationResult.Failure -> {
                        _uiState.update {
                            it.copy(isProcessing = false, error = result.error.message)
                        }
                        _activeOperations.update { current ->
                            current + (operationId to ActiveOperation(operationId, 0f, false, result.error.message))
                        }
                        completed = true
                    }
                    is PdfOperationResult.Cancelled -> {
                        _uiState.update { it.copy(isProcessing = false) }
                        _activeOperations.update { current ->
                            current + (operationId to ActiveOperation(operationId, 0f, false, "Cancelled"))
                        }
                        completed = true
                    }
                    is PdfOperationResult.InProgress -> {
                        _activeOperations.update { current ->
                            current + (operationId to ActiveOperation(
                                operationId,
                                result.percentComplete,
                                true
                            ))
                        }
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
