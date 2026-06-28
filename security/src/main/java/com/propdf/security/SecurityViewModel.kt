package com.propdf.security

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.EncryptedBackupConfig
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.core.domain.model.SessionState
import com.propdf.core.domain.model.VaultConfig
import com.propdf.core.domain.model.VaultEntry
import com.propdf.core.domain.result.AppResult
import com.propdf.security.biometric.BiometricAuthManager
import com.propdf.security.domain.usecase.CreateEncryptedBackupUseCase
import com.propdf.security.domain.usecase.DecryptFromVaultUseCase
import com.propdf.security.domain.usecase.DeleteVaultEntryUseCase
import com.propdf.security.domain.usecase.EncryptPdfUseCase
import com.propdf.security.domain.usecase.EncryptToVaultUseCase
import com.propdf.security.domain.usecase.ExportFromVaultUseCase
import com.propdf.security.domain.usecase.LockSessionUseCase
import com.propdf.security.domain.usecase.RestoreEncryptedBackupUseCase
import com.propdf.security.domain.usecase.SetSessionTimeoutUseCase
import com.propdf.security.domain.usecase.UnlockSessionUseCase
import com.propdf.security.session.SessionManager
import com.propdf.security.signature.SignatureEntry
import com.propdf.security.signature.SignatureManager
import com.propdf.security.watermark.WatermarkEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Enhanced SecurityViewModel for Phase 8  Security Hardening.
 *
 * Extends existing functionality with:
 * - Biometric authentication state
 * - Vault operations (encrypt/decrypt/export/delete)
 * - Encrypted backup/restore
 * - Session timeout management
 *
 * Preserves all existing encryption, signature, and watermark APIs.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    // Existing dependencies
    private val encryptPdfUseCase: EncryptPdfUseCase,
    private val decryptPdfUseCase: com.propdf.security.domain.usecase.DecryptPdfUseCase,
    private val signatureManager: SignatureManager,
    private val watermarkEngine: WatermarkEngine,

    // Phase 8: Biometric
    private val biometricAuthManager: BiometricAuthManager,

    // Phase 8: Vault
    private val encryptToVaultUseCase: EncryptToVaultUseCase,
    private val decryptFromVaultUseCase: DecryptFromVaultUseCase,
    private val deleteVaultEntryUseCase: DeleteVaultEntryUseCase,
    private val exportFromVaultUseCase: ExportFromVaultUseCase,

    // Phase 8: Backups
    private val createEncryptedBackupUseCase: CreateEncryptedBackupUseCase,
    private val restoreEncryptedBackupUseCase: RestoreEncryptedBackupUseCase,

    // Phase 8: Session
    private val sessionManager: SessionManager,
    private val unlockSessionUseCase: UnlockSessionUseCase,
    private val lockSessionUseCase: LockSessionUseCase,
    private val setSessionTimeoutUseCase: SetSessionTimeoutUseCase
) : ViewModel() {

    //  UI State 

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    data class UiState(
        val error: String? = null,
        val lastOutputFile: File? = null,
        val biometricStatus: BiometricAuthManager.BiometricStatus? = null,
        val sessionState: SessionState = SessionState.Unlocked,
        val vaultEntries: List<VaultEntry> = emptyList(),
        val isVaultLoading: Boolean = false
    )

    //  Session State Observation 

    val sessionState: StateFlow<SessionState> = sessionManager.sessionState

    init {
        viewModelScope.launch {
            sessionManager.sessionState.collect { state: SessionState ->
                _uiState.value = _uiState.value.copy(sessionState = state)
            }
        }
        checkBiometricStatus()
    }

    //  Biometric Authentication 

    fun checkBiometricStatus() {
        val status = biometricAuthManager.checkStatus()
        _uiState.value = _uiState.value.copy(biometricStatus = status)
    }

    fun hasBiometricHardware(): Boolean = biometricAuthManager.hasBiometricHardware()

    fun isBiometricReady(): Boolean = biometricAuthManager.isBiometricReady()

    //  Session Management 

    fun unlockSession() {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                unlockSessionUseCase()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message)
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun lockSession() {
        viewModelScope.launch {
            lockSessionUseCase()
        }
    }

    fun setSessionTimeout(timeoutMs: Long) {
        viewModelScope.launch {
            setSessionTimeoutUseCase(timeoutMs)
        }
    }

    fun onUserActivity() {
        sessionManager.onUserActivity()
    }

    //  Vault Operations 

    fun encryptToVault(sourceFile: File, useBiometric: Boolean = false) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = _uiState.value.copy(error = null)
            try {
                val result = encryptToVaultUseCase(
                    EncryptToVaultUseCase.Params(sourceFile, useBiometric)
                )
                loadVaultEntries()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Vault encryption failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun decryptFromVault(entry: VaultEntry, useBiometric: Boolean = false) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = _uiState.value.copy(error = null)
            try {
                val result = decryptFromVaultUseCase(
                    DecryptFromVaultUseCase.Params(entry, useBiometric)
                )
                val tempFile = when (result) {
                    is AppResult.Success -> result.data
                    is AppResult.Error -> throw result.exception
                    else -> throw Exception("Vault decryption failed")
                }
                _uiState.value = _uiState.value.copy(lastOutputFile = tempFile)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Vault decryption failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun exportFromVault(entry: VaultEntry, destination: File, useBiometric: Boolean = false) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = _uiState.value.copy(error = null)
            try {
                exportFromVaultUseCase(
                    ExportFromVaultUseCase.Params(entry, destination, useBiometric)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Vault export failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun deleteVaultEntry(entry: VaultEntry) {
        viewModelScope.launch {
            _isProcessing.value = true
            try {
                deleteVaultEntryUseCase(entry)
                loadVaultEntries()
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Delete failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private fun loadVaultEntries() {
        // Vault entries loaded from repository in production
        // Placeholder for integration with storage module
    }

    //  Encrypted Backups 

    fun createEncryptedBackup(sources: List<File>, outputUri: Uri, password: CharArray) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = _uiState.value.copy(error = null)
            try {
                val config = EncryptedBackupConfig(password = password)
                createEncryptedBackupUseCase(
                    CreateEncryptedBackupUseCase.Params(sources, outputUri, config)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Backup failed")
            } finally {
                _isProcessing.value = false
                password.fill('\u0000') // Secure clear
            }
        }
    }

    fun restoreEncryptedBackup(backupUri: Uri, destination: File, password: CharArray) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = _uiState.value.copy(error = null)
            try {
                restoreEncryptedBackupUseCase(
                    RestoreEncryptedBackupUseCase.Params(backupUri, destination, password)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(error = e.message ?: "Restore failed")
            } finally {
                _isProcessing.value = false
                password.fill('\u0000') // Secure clear
            }
        }
    }

    //  Existing: Signature Operations 

    private val _signatures = MutableStateFlow<List<SignatureEntry>>(emptyList())
    val signatures: StateFlow<List<SignatureEntry>> = _signatures.asStateFlow()

    init {
        loadSignatures()
    }

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

    //  Existing: PDF Encryption 

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

    //  Existing: Watermark 

    fun addTextWatermark(inputFile: File, outputFile: File, text: String) {
        viewModelScope.launch {
            _isProcessing.value = true
            _uiState.value = UiState()
            try {
                watermarkEngine.addTextWatermark(Uri.fromFile(inputFile), outputFile, text)
                _uiState.value = UiState(lastOutputFile = outputFile)
            } catch (e: Exception) {
                _uiState.value = UiState(error = e.message ?: "Watermark failed")
            } finally {
                _isProcessing.value = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        sessionManager.shutdown()
    }
}
