package com.propdfeditor.ui.security

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.security.data.entity.EncryptionType
import com.propdf.security.domain.usecase.EncryptDocumentUseCase
import com.propdf.security.domain.usecase.SanitizeDocumentUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Security Hub screen (Password Protect / AES Encrypt / Remove Metadata).
 *
 * This previously just set a fake success message ("Password protection applied" /
 * "AES encryption applied" / "Metadata removed") without touching the document at
 * all -- none of the three buttons did any actual work. It now calls the real
 * iText-backed engines in the :security module (EncryptDocumentUseCase,
 * SanitizeDocumentUseCase), which already existed and were fully implemented but
 * only reachable from the old Fragment/Activity UI (EncryptionFragment,
 * SanitizationFragment). No new PDF engine was written here -- this only wires the
 * Compose screen to the engines that were already there.
 */
@HiltViewModel
class SecurityViewModel @Inject constructor(
    private val encryptDocumentUseCase: EncryptDocumentUseCase,
    private val sanitizeDocumentUseCase: SanitizeDocumentUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    fun loadDocument(uri: String) {
        _uiState.value = _uiState.value.copy(documentUri = uri)
    }

    /** Opens the password-protect dialog (source of truth for which action a picked output URI is for). */
    fun requestPasswordProtect() {
        _uiState.value = _uiState.value.copy(pendingAction = PendingAction.PASSWORD_PROTECT)
    }

    fun requestAesEncrypt() {
        _uiState.value = _uiState.value.copy(pendingAction = PendingAction.AES_ENCRYPT)
    }

    fun requestRemoveMetadata() {
        _uiState.value = _uiState.value.copy(pendingAction = PendingAction.REMOVE_METADATA)
    }

    fun cancelPendingAction() {
        _uiState.value = _uiState.value.copy(pendingAction = null)
    }

    /**
     * Called once the SAF "save as" picker has returned an output location for whichever
     * action is currently pending. [password] is only used for PASSWORD_PROTECT/AES_ENCRYPT.
     */
    fun onOutputLocationChosen(outputUri: Uri, password: String = "") {
        val source = _uiState.value.documentUri?.let { Uri.parse(it) } ?: run {
            _uiState.value = _uiState.value.copy(message = "No document loaded", pendingAction = null)
            return
        }
        val action = _uiState.value.pendingAction
        _uiState.value = _uiState.value.copy(pendingAction = null, isProcessing = true)

        viewModelScope.launch {
            val result = when (action) {
                PendingAction.PASSWORD_PROTECT -> encryptDocumentUseCase(
                    sourceUri = source,
                    userPassword = password.ifBlank { null },
                    ownerPassword = password.ifBlank { null },
                    permissions = 0,
                    encryptionType = EncryptionType.STANDARD_128,
                    outputUri = outputUri
                )
                PendingAction.AES_ENCRYPT -> encryptDocumentUseCase(
                    sourceUri = source,
                    userPassword = password.ifBlank { null },
                    ownerPassword = password.ifBlank { null },
                    permissions = 0,
                    encryptionType = EncryptionType.AES_256,
                    outputUri = outputUri
                )
                PendingAction.REMOVE_METADATA -> sanitizeDocumentUseCase.removeMetadata(source, outputUri)
                null -> Result.failure(IllegalStateException("No action pending"))
            }

            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        message = "Saved to ${it}",
                        lastOutputUri = it.toString()
                    )
                },
                onFailure = { e ->
                    // Previously fell back to the raw Java exception class
                    // name (e.g. "Failed: IOException") whenever the
                    // exception carried no message -- a technical detail
                    // that means nothing to the person using the app. The
                    // real exception is still logged for diagnosis.
                    Log.e("SecurityViewModel", "Security operation failed", e)
                    _uiState.value = _uiState.value.copy(
                        isProcessing = false,
                        message = e.message ?: "Something went wrong. Please try again."
                    )
                }
            )
        }
    }

    /** Shown for tools that don't have a functional implementation wired up yet. */
    fun showComingSoon(feature: String) {
        _uiState.value = _uiState.value.copy(message = "$feature is coming soon")
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(message = null)
    }
}

enum class PendingAction { PASSWORD_PROTECT, AES_ENCRYPT, REMOVE_METADATA }

data class SecurityUiState(
    val documentUri: String? = null,
    val message: String? = null,
    val isProcessing: Boolean = false,
    val pendingAction: PendingAction? = null,
    val lastOutputUri: String? = null
)
