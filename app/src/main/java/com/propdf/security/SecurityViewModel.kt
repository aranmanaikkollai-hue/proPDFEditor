package com.propdf.security

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.security.domain.usecase.DecryptPdfUseCase
import com.propdf.security.domain.usecase.EncryptPdfUseCase
import com.propdf.security.signature.SignatureEntry
import com.propdf.security.signature.SignatureManager
import com.propdf.security.watermark.WatermarkEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val encryptPdfUseCase: EncryptPdfUseCase,
    private val decryptPdfUseCase: DecryptPdfUseCase,
    private val signatureManager: SignatureManager,
    private val watermarkEngine: WatermarkEngine
) : ViewModel() {

    // ── UI state ─────────────────────────────────────────────────────────────

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing

    private val _signatures = MutableStateFlow<List<SignatureEntry>>(emptyList())
    val signatures: StateFlow<List<SignatureEntry>> = _signatures

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState

    data class UiState(
        val error: String? = null,
        val lastOutputFile: File? = null
    )

    init {
        loadSignatures()
    }

    // ── Signature operations ─────────────────────────────────────────────────

    fun saveDrawnSignature(bitmap: Bitmap, name: String?) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                signatureManager.saveSignature(bitmap, name)
                loadSignatures()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun saveImageSignature(uri: Uri, name: String?) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                val bitmapResult = signatureManager.imageToSignature(uri)
                bitmapResult.getOrNull()?.let { bitmap ->
                    signatureManager.saveSignature(bitmap, name)
                    loadSignatures()
                } ?: run {
                    _uiState.value = _uiState.value.copy(error = "Failed to process image")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun loadSignatures() {
        _signatures.value = signatureManager.loadSignatures()
    }

    // ── Encryption operations ────────────────────────────────────────────────

    fun passwordProtectPdf(inputFile: File, outputFile: File, password: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = UiState()
            try {
                encryptPdfUseCase(
                    EncryptPdfUseCase.Params(
                        inputFile = inputFile,
                        outputFile = outputFile,
                        config = SecurityConfig(
                            userPassword = password,
                            ownerPassword = password
                        )
                    )
                )
                _uiState.value = UiState(lastOutputFile = outputFile)
            } catch (e: Exception) {
                _uiState.value = UiState(error = e.message ?: "Encryption failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // ── Watermark operations ─────────────────────────────────────────────────

    fun addTextWatermark(inputFile: File, outputFile: File, text: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = UiState()
            try {
                watermarkEngine.addTextWatermark(android.net.Uri.fromFile(inputFile), outputFile, text)
                _uiState.value = UiState(lastOutputFile = outputFile)
            } catch (e: Exception) {
                _uiState.value = UiState(error = e.message ?: "Watermark failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }
}
