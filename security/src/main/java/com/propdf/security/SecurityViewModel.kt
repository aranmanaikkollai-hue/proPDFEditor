package com.propdf.security

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.security.encryption.EncryptionManager
import com.propdf.security.redaction.RedactionEngine
import com.propdf.security.redaction.RedactionRegion
import com.propdf.security.signature.SignatureEntry
import com.propdf.security.signature.SignatureManager
import com.propdf.security.watermark.WatermarkEngine
import com.propdf.security.watermark.WatermarkOptions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val signatureManager: SignatureManager,
    private val encryptionManager: EncryptionManager,
    private val watermarkEngine: WatermarkEngine,
    private val redactionEngine: RedactionEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    private val _signatures = MutableStateFlow<List<SignatureEntry>>(emptyList())
    val signatures: StateFlow<List<SignatureEntry>> = _signatures.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        loadSignatures()
    }

    // ==================== SIGNATURE ====================

    fun saveDrawnSignature(bitmap: Bitmap, name: String? = null) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = signatureManager.saveSignature(bitmap, name)
            result.onSuccess {
                loadSignatures()
                _uiState.value = _uiState.value.copy(error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun saveImageSignature(uri: Uri, name: String? = null) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = signatureManager.imageToSignature(uri)
            result.onSuccess { bitmap ->
                val saveResult = signatureManager.saveSignature(bitmap, name)
                saveResult.onSuccess {
                    loadSignatures()
                    _uiState.value = _uiState.value.copy(error = null)
                }.onFailure { e ->
                    _uiState.value = _uiState.value.copy(error = e.message)
                }
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun deleteSignature(id: String) {
        signatureManager.deleteSignature(id)
        loadSignatures()
    }

    fun loadSignatures() {
        _signatures.value = signatureManager.loadSignatures()
    }

    // ==================== ENCRYPTION ====================

    fun passwordProtectPdf(sourceUri: Uri, outputFile: File, password: String, ownerPassword: String? = null) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = encryptionManager.passwordProtect(sourceUri, outputFile, password, ownerPassword)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(lastOutputFile = it, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun removePassword(sourceUri: Uri, outputFile: File, password: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = encryptionManager.removePassword(sourceUri, outputFile, password)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(lastOutputFile = it, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun applyRestrictions(sourceUri: Uri, outputFile: File, ownerPassword: String, config: PermissionConfig) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = encryptionManager.applyRestrictions(
                sourceUri, outputFile, ownerPassword,
                allowPrinting = config.allowPrinting,
                allowModifications = config.allowModifications,
                allowCopy = config.allowCopy,
                allowAnnotations = config.allowAnnotations
            )
            result.onSuccess {
                _uiState.value = _uiState.value.copy(lastOutputFile = it, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    // ==================== WATERMARK ====================

    fun addTextWatermark(sourceUri: Uri, outputFile: File, text: String, options: WatermarkOptions = WatermarkOptions()) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = watermarkEngine.addTextWatermark(sourceUri, outputFile, text, options)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(lastOutputFile = it, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun addImageWatermark(sourceUri: Uri, outputFile: File, bitmap: Bitmap, options: WatermarkOptions = WatermarkOptions()) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = watermarkEngine.addImageWatermark(sourceUri, outputFile, bitmap, options)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(lastOutputFile = it, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    // ==================== REDACTION ====================

    fun redactRegions(sourceUri: Uri, outputFile: File, regions: List<RedactionRegion>) {
        viewModelScope.launch {
            _isProcessing.value = true
            val result = redactionEngine.redactRegions(sourceUri, outputFile, regions)
            result.onSuccess {
                _uiState.value = _uiState.value.copy(lastOutputFile = it, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    data class SecurityUiState(
        val lastOutputFile: File? = null,
        val error: String? = null
    )

    data class PermissionConfig(
        val allowPrinting: Boolean = true,
        val allowModifications: Boolean = false,
        val allowCopy: Boolean = false,
        val allowAnnotations: Boolean = false
    )
}
