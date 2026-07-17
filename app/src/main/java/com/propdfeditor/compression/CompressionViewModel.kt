package com.propdfeditor.compression

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionPreview
import com.propdf.core.domain.model.CompressionResult
import com.propdf.core.domain.model.CompressionStrategy
import com.propdf.core.domain.model.QualityPreset
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.CompressPdfUseCase
import com.propdf.core.domain.usecase.PreviewCompressionUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CompressionViewModel @Inject constructor(
    private val compressUseCase: CompressPdfUseCase,
    private val previewUseCase: PreviewCompressionUseCase,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(CompressionUiState())
    val uiState: StateFlow<CompressionUiState> = _uiState.asStateFlow()

    private var compressionJob: Job? = null

    fun selectFile(uri: String, sizeBytes: Long, pageCount: Int) {
        _uiState.update {
            it.copy(
                sourceUri = uri,
                originalSizeBytes = sizeBytes,
                pageCount = pageCount,
                preview = null,
                error = null
            )
        }
        generatePreview()
    }

    fun selectPreset(preset: QualityPreset) {
        _uiState.update {
            it.copy(
                selectedPreset = preset,
                config = preset.config,
                preview = null
            )
        }
        generatePreview()
    }

    fun updateConfig(config: CompressionConfig) {
        _uiState.update {
            it.copy(
                selectedPreset = null, // Custom overrides preset
                config = config
            )
        }
        generatePreview()
    }

    fun startCompression(outputUri: String) {
        val currentState = _uiState.value
        val sourceUri = currentState.sourceUri ?: return
        
        compressionJob?.cancel()
        compressionJob = viewModelScope.launch {
            _uiState.update { it.copy(isCompressing = true, progress = 0f, error = null) }
            
            compressUseCase(
                CompressPdfUseCase.Params(
                    sourceUri = sourceUri,
                    outputUri = outputUri,
                    config = currentState.config
                )
            ).collect { result ->
                when (result) {
                    is AppResult.Loading -> {
                        _uiState.update { 
                            it.copy(progress = result.progress) 
                        }
                    }
                    is AppResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                result = result.data,
                                progress = 1f
                            )
                        }
                    }
                    is AppResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isCompressing = false,
                                error = result.exception.message
                            )
                        }
                    }
                }
            }
        }
    }

    fun cancelCompression() {
        compressionJob?.cancel()
        compressionJob = null
        _uiState.update { 
            it.copy(isCompressing = false, progress = 0f) 
        }
    }

    private fun generatePreview() {
        val currentState = _uiState.value
        val sourceUri = currentState.sourceUri ?: return
        
        viewModelScope.launch {
            _uiState.update { it.copy(isPreviewLoading = true) }
            
            val result = previewUseCase(
                PreviewCompressionUseCase.Params(
                    sourceUri = sourceUri,
                    config = currentState.config
                )
            )
            
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            preview = result.data,
                            isPreviewLoading = false
                        )
                    }
                }
                else -> {
                    _uiState.update { it.copy(isPreviewLoading = false) }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        compressionJob?.cancel()
    }
}

data class CompressionUiState(
    val sourceUri: String? = null,
    val originalSizeBytes: Long = 0,
    val pageCount: Int = 0,
    val config: CompressionConfig = QualityPreset.EBOOK.config,
    val selectedPreset: QualityPreset? = QualityPreset.EBOOK,
    val preview: CompressionPreview? = null,
    val isPreviewLoading: Boolean = false,
    val isCompressing: Boolean = false,
    val progress: Float = 0f,
    val result: CompressionResult? = null,
    val error: String? = null
)
