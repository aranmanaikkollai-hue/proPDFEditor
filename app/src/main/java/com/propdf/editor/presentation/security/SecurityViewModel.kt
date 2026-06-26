package com.propdf.editor.presentation.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<SecurityUiState>(SecurityUiState.Idle)
    val uiState: StateFlow<SecurityUiState> = _uiState

    private val _selectedDocument = MutableStateFlow<PdfDocument?>(null)
    val selectedDocument: StateFlow<PdfDocument?> = _selectedDocument

    // Password stored separately since SecurityConfig.userPassword is the field
    private var pendingPassword: String = ""
    private var allowPrinting: Boolean = true
    private var allowCopying: Boolean = false

    fun selectDocument(document: PdfDocument) {
        _selectedDocument.value = document
    }

    fun setPassword(password: String) {
        pendingPassword = password
    }

    fun setPermissions(allowPrinting: Boolean, allowCopying: Boolean, allowEditing: Boolean) {
        this.allowPrinting = allowPrinting
        this.allowCopying = allowCopying
        // allowEditing not in SecurityConfig — ignored
    }

    fun encryptDocument(inputFile: File, outputFile: File) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Loading
            val config = SecurityConfig(
                userPassword = pendingPassword,
                ownerPassword = pendingPassword,
                allowPrinting = allowPrinting,
                allowCopying = allowCopying
            )
            when (val result = pdfOperationsRepository.encrypt(inputFile, outputFile, config)) {
                is AppResult.Success -> _uiState.value = SecurityUiState.Success(result.data.absolutePath)
                is AppResult.Error -> _uiState.value = SecurityUiState.Error(result.message)
                else -> Unit
            }
        }
    }

    fun decryptDocument(inputFile: File, outputFile: File, password: String) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Loading
            when (val result = pdfOperationsRepository.decrypt(inputFile, outputFile, password)) {
                is AppResult.Success -> _uiState.value = SecurityUiState.Success(result.data.absolutePath)
                is AppResult.Error -> _uiState.value = SecurityUiState.Error(result.message)
                else -> Unit
            }
        }
    }

    fun reset() {
        _uiState.value = SecurityUiState.Idle
        _selectedDocument.value = null
        pendingPassword = ""
    }

    sealed class SecurityUiState {
        object Idle : SecurityUiState()
        object Loading : SecurityUiState()
        data class Success(val outputPath: String) : SecurityUiState()
        data class Error(val message: String?) : SecurityUiState()
    }
}
