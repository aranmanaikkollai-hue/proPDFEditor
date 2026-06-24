package com.propdf.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.security.domain.usecase.DecryptPdfUseCase
import com.propdf.security.domain.usecase.EncryptPdfUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val encryptPdfUseCase: EncryptPdfUseCase,
    private val decryptPdfUseCase: DecryptPdfUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<SecurityUiState>(SecurityUiState.Idle)
    val uiState: StateFlow<SecurityUiState> = _uiState

    fun encryptPdf(inputPath: String, outputPath: String, password: String) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Loading
            try {
                encryptPdfUseCase(inputPath, outputPath, password)
                _uiState.value = SecurityUiState.Success("Encrypted successfully")
            } catch (e: Exception) {
                _uiState.value = SecurityUiState.Error(e.message ?: "Encryption failed")
            }
        }
    }

    fun decryptPdf(inputPath: String, outputPath: String, password: String) {
        viewModelScope.launch {
            _uiState.value = SecurityUiState.Loading
            try {
                decryptPdfUseCase(inputPath, outputPath, password)
                _uiState.value = SecurityUiState.Success("Decrypted successfully")
            } catch (e: Exception) {
                _uiState.value = SecurityUiState.Error(e.message ?: "Decryption failed")
            }
        }
    }
}

sealed class SecurityUiState {
    object Idle : SecurityUiState()
    object Loading : SecurityUiState()
    data class Success(val message: String) : SecurityUiState()
    data class Error(val message: String) : SecurityUiState()
}
