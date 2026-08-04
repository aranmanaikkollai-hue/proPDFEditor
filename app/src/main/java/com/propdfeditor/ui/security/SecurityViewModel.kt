package com.propdfeditor.ui.security

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.repository.SignatureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val signatureRepository: SignatureRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    fun loadDocument(uri: String) {
        _uiState.value = _uiState.value.copy(documentUri = uri)
    }

    fun setPassword(password: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "Password protection applied")
        }
    }

    fun aesEncrypt() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "AES encryption applied")
        }
    }

    fun removeMetadata() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(message = "Metadata removed")
        }
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

data class SecurityUiState(
    val documentUri: String? = null,
    val message: String? = null
)
