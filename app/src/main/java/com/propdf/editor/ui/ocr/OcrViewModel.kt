package com.propdf.editor.ui.ocr

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.*
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.ocr.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OcrViewModel @Inject constructor(
    private val recognizeImageUseCase: RecognizeImageUseCase,
    private val batchOcrUseCase: BatchOcrUseCase,
    private val preprocessImageUseCase: PreprocessImageUseCase,
    private val exportOcrUseCase: ExportOcrUseCase,
    private val searchOcrTextUseCase: SearchOcrTextUseCase,
    private val correctOcrTextUseCase: CorrectOcrTextUseCase,
    private val detectTablesUseCase: DetectTablesUseCase,
    private val detectHandwritingUseCase: DetectHandwritingUseCase,
    private val downloadOcrModelUseCase: DownloadOcrModelUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    private val _ocrResults = MutableStateFlow<List<OcrPageResult>>(emptyList())
    val ocrResults: StateFlow<List<OcrPageResult>> = _ocrResults.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _searchResults = MutableStateFlow<List<IntRange>>(emptyList())
    val searchResults: StateFlow<List<IntRange>> = _searchResults.asStateFlow()

    private val _availableLanguages = MutableStateFlow(OcrLanguage.offlineLanguages())
    val availableLanguages: StateFlow<List<OcrLanguage>> = _availableLanguages.asStateFlow()

    private val _selectedLanguages = MutableStateFlow(listOf(OcrLanguage.AUTO))
    val selectedLanguages: StateFlow<List<OcrLanguage>> = _selectedLanguages.asStateFlow()

    private val _preprocessConfig = MutableStateFlow(OcrPreprocessConfig())
    val preprocessConfig: StateFlow<OcrPreprocessConfig> = _preprocessConfig.asStateFlow()

    private val _progress = MutableStateFlow(0)
    val progress: StateFlow<Int> = _progress.asStateFlow()

    private val _isHandwritingDetected = MutableStateFlow(false)
    val isHandwritingDetected: StateFlow<Boolean> = _isHandwritingDetected.asStateFlow()

    private val _detectedTables = MutableStateFlow<List<OcrTable>>(emptyList())
    val detectedTables: StateFlow<List<OcrTable>> = _detectedTables.asStateFlow()

    fun setLanguages(languages: List<OcrLanguage>) {
        _selectedLanguages.value = languages
    }

    fun setPreprocessConfig(config: OcrPreprocessConfig) {
        _preprocessConfig.value = config
    }

    fun recognizeImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = OcrUiState.Processing("Recognizing text...")
            _progress.value = 0
            val config = OcrConfig(languages = _selectedLanguages.value, preprocessConfig = _preprocessConfig.value)
            when (val result = recognizeImageUseCase(bitmap, config)) {
                is AppResult.Success -> {
                    _ocrResults.value = listOf(result.data)
                    _currentPageIndex.value = 0
                    _uiState.value = OcrUiState.Success(result.data)
                    checkHandwriting(bitmap)
                    detectTables(bitmap)
                }
                is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "OCR failed")
                is AppResult.Loading -> _uiState.value = OcrUiState.Processing("Processing...")
            }
        }
    }

    fun recognizeImageUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = OcrUiState.Processing("Loading image...")
            val config = OcrConfig(languages = _selectedLanguages.value, preprocessConfig = _preprocessConfig.value)
            when (val result = recognizeImageUseCase(uri, config)) {
                is AppResult.Success -> {
                    _ocrResults.value = listOf(result.data)
                    _currentPageIndex.value = 0
                    _uiState.value = OcrUiState.Success(result.data)
                }
                is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "OCR failed")
                is AppResult.Loading -> _uiState.value = OcrUiState.Processing("Processing...")
            }
        }
    }

    fun recognizeBatch(uris: List<Uri>) {
        viewModelScope.launch {
            _uiState.value = OcrUiState.Processing("Starting batch OCR...")
            _progress.value = 0
            val config = OcrConfig(languages = _selectedLanguages.value, preprocessConfig = _preprocessConfig.value)
            val results = mutableListOf<OcrPageResult>()
            val total = uris.size
            batchOcrUseCase(uris, config).collect { result ->
                when (result) {
                    is AppResult.Success -> {
                        results.add(result.data)
                        _ocrResults.value = results.toList()
                        _progress.value = (results.size * 100) / total
                        _uiState.value = OcrUiState.Processing("Processed ${results.size}/$total pages...")
                    }
                    is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "Batch OCR failed")
                    is AppResult.Loading -> {}
                }
            }
            if (results.isNotEmpty()) _uiState.value = OcrUiState.BatchComplete(results.size)
        }
    }

    fun preprocessImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.value = OcrUiState.Processing("Preprocessing image...")
            when (val result = preprocessImageUseCase(bitmap, _preprocessConfig.value)) {
                is AppResult.Success -> _uiState.value = OcrUiState.Preprocessed(result.data)
                is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "Preprocessing failed")
                is AppResult.Loading -> {}
            }
        }
    }

    fun searchText(query: String, caseSensitive: Boolean = false) {
        viewModelScope.launch {
            val currentText = _ocrResults.value.getOrNull(_currentPageIndex.value)?.fullText ?: return@launch
            if (query.isBlank()) { _searchResults.value = emptyList(); return@launch }
            when (val result = searchOcrTextUseCase(currentText, query, caseSensitive)) {
                is AppResult.Success -> _searchResults.value = result.data
                else -> _searchResults.value = emptyList()
            }
        }
    }

    fun clearSearch() { _searchResults.value = emptyList() }

    fun correctText() {
        viewModelScope.launch {
            val currentResult = _ocrResults.value.getOrNull(_currentPageIndex.value) ?: return@launch
            _uiState.value = OcrUiState.Processing("Correcting text...")
            when (val result = correctOcrTextUseCase(currentResult.fullText, _selectedLanguages.value.firstOrNull() ?: OcrLanguage.ENGLISH)) {
                is AppResult.Success -> {
                    val corrected = currentResult.copy(fullText = result.data)
                    val updated = _ocrResults.value.toMutableList()
                    updated[_currentPageIndex.value] = corrected
                    _ocrResults.value = updated
                    _uiState.value = OcrUiState.Success(corrected)
                }
                is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "Correction failed")
                is AppResult.Loading -> {}
            }
        }
    }

    private suspend fun checkHandwriting(bitmap: Bitmap) {
        when (val result = detectHandwritingUseCase(bitmap)) {
            is AppResult.Success -> _isHandwritingDetected.value = result.data.hasHandwriting
            else -> {}
        }
    }

    private suspend fun detectTables(bitmap: Bitmap) {
        val config = OcrConfig(languages = _selectedLanguages.value)
        when (val result = detectTablesUseCase(bitmap, config)) {
            is AppResult.Success -> _detectedTables.value = result.data
            else -> _detectedTables.value = emptyList()
        }
    }

    fun exportResults(outputUri: Uri, format: OcrOutputFormat) {
        viewModelScope.launch {
            if (_ocrResults.value.isEmpty()) {
                _uiState.value = OcrUiState.Error("No OCR results to export")
                return@launch
            }
            _uiState.value = OcrUiState.Processing("Exporting...")
            when (val result = exportOcrUseCase(_ocrResults.value, outputUri, format)) {
                is AppResult.Success -> _uiState.value = OcrUiState.Exported(result.data)
                is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "Export failed")
                is AppResult.Loading -> {}
            }
        }
    }

    fun nextPage() {
        if (_currentPageIndex.value < _ocrResults.value.size - 1) {
            _currentPageIndex.value++
            clearSearch()
        }
    }

    fun previousPage() {
        if (_currentPageIndex.value > 0) {
            _currentPageIndex.value--
            clearSearch()
        }
    }

    fun goToPage(index: Int) {
        if (index in _ocrResults.value.indices) {
            _currentPageIndex.value = index
            clearSearch()
        }
    }

    fun getCurrentText(): String = _ocrResults.value.getOrNull(_currentPageIndex.value)?.fullText ?: ""
    fun getAllText(): String = _ocrResults.value.joinToString("\n\n---\n\n") { it.fullText }

    fun downloadModel(language: OcrLanguage) {
        viewModelScope.launch {
            downloadOcrModelUseCase(language).collect { result ->
                when (result) {
                    is AppResult.Success -> {
                        if (result.data >= 100) {
                            _uiState.value = OcrUiState.ModelDownloaded(language)
                        } else {
                            _uiState.value = OcrUiState.Processing("Downloading model... ${result.data}%")
                        }
                    }
                    is AppResult.Error -> _uiState.value = OcrUiState.Error(result.message ?: "Download failed")
                    is AppResult.Loading -> _uiState.value = OcrUiState.Processing("Downloading model...")
                }
            }
        }
    }

    fun reset() {
        _ocrResults.value = emptyList()
        _currentPageIndex.value = 0
        _searchResults.value = emptyList()
        _progress.value = 0
        _isHandwritingDetected.value = false
        _detectedTables.value = emptyList()
        _uiState.value = OcrUiState.Idle
    }
}

sealed class OcrUiState {
    object Idle : OcrUiState()
    data class Processing(val message: String) : OcrUiState()
    data class Success(val result: OcrPageResult) : OcrUiState()
    data class BatchComplete(val pageCount: Int) : OcrUiState()
    data class Preprocessed(val bitmap: Bitmap) : OcrUiState()
    data class Exported(val uri: Uri) : OcrUiState()
    data class ModelDownloaded(val language: OcrLanguage) : OcrUiState()
    data class Error(val message: String) : OcrUiState()
}
