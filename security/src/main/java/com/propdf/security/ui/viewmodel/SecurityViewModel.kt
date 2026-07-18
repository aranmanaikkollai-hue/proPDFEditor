// security/src/main/java/com/propdf/security/ui/viewmodel/SecurityViewModel.kt
package com.propdf.security.ui.viewmodel

import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.security.data.entity.EncryptionType
import com.propdf.security.data.entity.RedactionEntity
import com.propdf.security.data.entity.SecureDocumentEntity
import com.propdf.security.data.entity.SecurityOperationEntity
import com.propdf.security.data.repository.SecurityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val repository: SecurityRepository
) : ViewModel() {

    private val _operations = MutableStateFlow<List<SecurityOperationEntity>>(emptyList())
    val operations: StateFlow<List<SecurityOperationEntity>> = _operations.asStateFlow()

    private val _redactions = MutableStateFlow<List<RedactionEntity>>(emptyList())
    val redactions: StateFlow<List<RedactionEntity>> = _redactions.asStateFlow()

    private val _secureDocuments = MutableStateFlow<List<SecureDocumentEntity>>(emptyList())
    val secureDocuments: StateFlow<List<SecureDocumentEntity>> = _secureDocuments.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllOperations().collect { _operations.value = it }
        }
        viewModelScope.launch {
            repository.getAllSecureDocuments().collect { _secureDocuments.value = it }
        }
    }

    fun loadRedactions(documentUri: String) {
        viewModelScope.launch {
            repository.getPendingRedactions(documentUri).collect { _redactions.value = it }
        }
    }

    fun encryptWithAes(sourceUri: Uri, password: String, outputUri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.encryptWithAes(sourceUri, password, outputUri)
                .onSuccess { _isProcessing.value = false }
                .onFailure { 
                    _error.value = it.message
                    _isProcessing.value = false
                }
        }
    }

    fun decryptWithAes(sourceUri: Uri, password: String, outputUri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.decryptWithAes(sourceUri, password, outputUri)
                .onSuccess { _isProcessing.value = false }
                .onFailure {
                    _error.value = it.message
                    _isProcessing.value = false
                }
        }
    }

    fun applyPasswordProtection(
        sourceUri: Uri,
        userPassword: String?,
        ownerPassword: String?,
        permissions: Int,
        encryptionType: EncryptionType,
        outputUri: Uri
    ) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.applyPasswordProtection(
                sourceUri, userPassword, ownerPassword, permissions, encryptionType, outputUri
            ).onSuccess { _isProcessing.value = false }
             .onFailure {
                 _error.value = it.message
                 _isProcessing.value = false
             }
        }
    }

    fun setPermissions(sourceUri: Uri, ownerPassword: String, permissions: Int, outputUri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.setPermissions(sourceUri, ownerPassword, permissions, outputUri)
                .onSuccess { _isProcessing.value = false }
                .onFailure {
                    _error.value = it.message
                    _isProcessing.value = false
                }
        }
    }

    fun removeMetadata(sourceUri: Uri, outputUri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.removeMetadata(sourceUri, outputUri)
                .onSuccess { _isProcessing.value = false }
                .onFailure {
                    _error.value = it.message
                    _isProcessing.value = false
                }
        }
    }

    fun sanitizeDocument(sourceUri: Uri, outputUri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.sanitizeDocument(sourceUri, outputUri)
                .onSuccess { _isProcessing.value = false }
                .onFailure {
                    _error.value = it.message
                    _isProcessing.value = false
                }
        }
    }

    fun addRedaction(documentUri: String, pageNumber: Int, rect: RectF, overlayText: String? = null) {
        viewModelScope.launch {
            repository.addRedaction(documentUri, pageNumber, rect, overlayText)
        }
    }

    fun removeRedaction(redaction: RedactionEntity) {
        viewModelScope.launch {
            repository.removeRedaction(redaction)
        }
    }

    fun applyRedactions(sourceUri: Uri, outputUri: Uri, permanent: Boolean = false) {
        viewModelScope.launch {
            _isProcessing.value = true
            if (permanent) {
                repository.applyPermanentRedactions(sourceUri, outputUri)
            } else {
                repository.applyRedactions(sourceUri, outputUri)
            }.onSuccess { _isProcessing.value = false }
             .onFailure {
                 _error.value = it.message
                 _isProcessing.value = false
             }
        }
    }

    fun secureDelete(fileUri: Uri) {
        viewModelScope.launch {
            _isProcessing.value = true
            repository.secureDelete(fileUri)
                .onSuccess { _isProcessing.value = false }
                .onFailure {
                    _error.value = it.message
                    _isProcessing.value = false
                }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
