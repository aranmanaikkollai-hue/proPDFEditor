package com.propdfeditor.scanner.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdfeditor.scanner.domain.model.ScannerUiState
import com.propdfeditor.scanner.domain.usecase.BatchScanUseCase
import com.propdfeditor.scanner.domain.usecase.DeleteScanPageUseCase
import com.propdfeditor.scanner.domain.usecase.DetectEdgesUseCase
import com.propdfeditor.scanner.domain.usecase.ProcessScanUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val detectEdgesUseCase: DetectEdgesUseCase,
    private val processScanUseCase: ProcessScanUseCase,
    private val batchScanUseCase: BatchScanUseCase,
    private val deleteScanPageUseCase: DeleteScanPageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState

    fun detectEdges(imagePath: String) {
        viewModelScope.launch {
            _uiState.value = ScannerUiState.Processing
            try {
                val result = detectEdgesUseCase(imagePath)
                _uiState.value = ScannerUiState.EdgesDetected(result)
            } catch (e: Exception) {
                _uiState.value = ScannerUiState.Error(e.message ?: "Edge detection failed")
            }
        }
    }

    fun processScan(imagePath: String, corners: List<com.propdfeditor.scanner.domain.model.PointF>) {
        viewModelScope.launch {
            _uiState.value = ScannerUiState.Processing
            try {
                val result = processScanUseCase(imagePath, corners)
                _uiState.value = ScannerUiState.ScanComplete(result)
            } catch (e: Exception) {
                _uiState.value = ScannerUiState.Error(e.message ?: "Processing failed")
            }
        }
    }

    fun deletePage(pageId: String) {
        viewModelScope.launch {
            deleteScanPageUseCase(pageId)
        }
    }
}
