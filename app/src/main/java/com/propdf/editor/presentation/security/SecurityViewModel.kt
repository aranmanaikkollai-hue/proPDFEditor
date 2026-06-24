package com.propdf.editor.presentation.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.core.domain.repository.PdfOperationsRepository // Fixed import
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SecurityUiState>(SecurityUiState.Idle)
    val uiState: StateFlow<SecurityUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    private val _securityConfig = MutableStateFlow<SecurityConfig>(SecurityConfig())
    val securityConfig: StateFlow<SecurityConfig> = _securityConfig

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
    }

    fun setPassword(password: String) {
        _securityConfig.value = _securityConfig.value.copy(password = password)
    }

    fun setPermissions(allowPrinting: Boolean, allowCopying: Boolean, allowEditing: Boolean) {
        _securityConfig.value = _securityConfig.value.copy(
            allowPrinting = allowPrinting,
            allowCopying = allowCopying,
            allowEditing = allowEditing
        )
    }

    fun encryptDocument(outputName: String) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Loading
            val document = _selectedDocument.value ?: run {
                _uiState.value = SecurityUiState.Error("No document selected")
                return@launch
            }
            when (val result = pdfOperationsRepository.encryptPdf(document, _securityConfig.value, outputName)) {
                is AppResult.Success -> _uiState.value = SecurityUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = SecurityUiState.Error(result.exception.message)
            }
        }
    }

    fun decryptDocument(password: String, outputName: String) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Loading
            val document = _selectedDocument.value ?: run {
                _uiState.value = SecurityUiState.Error("No document selected")
                return@launch
            }
            when (val result = pdfOperationsRepository.decryptPdf(document, password, outputName)) {
                is AppResult.Success -> _uiState.value = SecurityUiState.Success(result.data)
                is AppResult.Error -> _uiState.value = SecurityUiState.Error(result.exception.message)
            }
        }
    }

    fun reset() {
        _uiState.value = SecurityUiState.Idle
        _selectedDocument.value = null
        _securityConfig.value = SecurityConfig()
    }

    sealed class SecurityUiState {
        object Idle : SecurityUiState()
        object Loading : SecurityUiState()
        data class Success(val outputPath: String) : SecurityUiState()
        data class Error(val message: String?) : SecurityUiState()
    }
}
