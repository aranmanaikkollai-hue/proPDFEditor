package com.propdfeditor.security

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdfeditor.core.database.entity.CertificateEntity
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.core.database.entity.SignatureHistoryEntity
import com.propdfeditor.core.pdf.signature.PdfSignatureEngine
import com.propdfeditor.core.repository.CertificateRepository
import com.propdfeditor.core.repository.SignatureHistoryRepository
import com.propdfeditor.core.repository.SignatureRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SignatureViewModel @Inject constructor(
    private val signatureRepository: SignatureRepository,
    private val historyRepository: SignatureHistoryRepository,
    private val certificateRepository: CertificateRepository,
    private val pdfSignatureEngine: PdfSignatureEngine
) : ViewModel() {

    private val _signatures = MutableStateFlow<List<SignatureEntity>>(emptyList())
    val signatures: StateFlow<List<SignatureEntity>> = _signatures.asStateFlow()

    private val _favoriteSignatures = MutableStateFlow<List<SignatureEntity>>(emptyList())
    val favoriteSignatures: StateFlow<List<SignatureEntity>> = _favoriteSignatures.asStateFlow()

    private val _history = MutableStateFlow<List<SignatureHistoryEntity>>(emptyList())
    val history: StateFlow<List<SignatureHistoryEntity>> = _history.asStateFlow()

    private val _certificates = MutableStateFlow<List<CertificateEntity>>(emptyList())
    val certificates: StateFlow<List<CertificateEntity>> = _certificates.asStateFlow()

    private val _uiEvent = MutableSharedFlow<UiEvent>()
    val uiEvent: SharedFlow<UiEvent> = _uiEvent.asSharedFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadSignatures()
        loadHistory()
        loadCertificates()
    }

    private fun loadSignatures() {
        viewModelScope.launch {
            signatureRepository.getAllSignatures()
                .catch { emit(emptyList()) }
                .collectLatest { _signatures.value = it }
        }
        viewModelScope.launch {
            signatureRepository.getFavoriteSignatures()
                .catch { emit(emptyList()) }
                .collectLatest { _favoriteSignatures.value = it }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            historyRepository.getAllHistory()
                .catch { emit(emptyList()) }
                .collectLatest { _history.value = it }
        }
    }

    private fun loadCertificates() {
        viewModelScope.launch {
            certificateRepository.getAllCertificates()
                .catch { emit(emptyList()) }
                .collectLatest { _certificates.value = it }
        }
    }

    fun createDrawnSignature(name: String, bitmap: Bitmap, strokeWidth: Float, strokeColor: Int) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                signatureRepository.createDrawnSignature(
                    name = name,
                    bitmap = bitmap,
                    strokeWidth = strokeWidth,
                    strokeColor = strokeColor
                )
                _uiEvent.emit(UiEvent.SignatureCreated)
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to save signature"))
            }
            _isLoading.value = false
        }
    }

    fun createImageSignature(name: String, uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                signatureRepository.createImageSignature(name = name, imageUri = uri)
                _uiEvent.emit(UiEvent.SignatureCreated)
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to import image"))
            }
            _isLoading.value = false
        }
    }

    fun createTypedSignature(
        name: String,
        text: String,
        fontFamily: String,
        fontSize: Float,
        textColor: Int
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                signatureRepository.createTypedSignature(
                    name = name,
                    text = text,
                    fontFamily = fontFamily,
                    fontSize = fontSize,
                    textColor = textColor
                )
                _uiEvent.emit(UiEvent.SignatureCreated)
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to create typed signature"))
            }
            _isLoading.value = false
        }
    }

    fun deleteSignature(signature: SignatureEntity) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                signatureRepository.deleteSignature(signature)
                _uiEvent.emit(UiEvent.SignatureDeleted)
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to delete signature"))
            }
            _isLoading.value = false
        }
    }

    fun toggleFavorite(signature: SignatureEntity) {
        viewModelScope.launch {
            signatureRepository.setFavorite(signature.id, !signature.isFavorite)
        }
    }

    fun applyVisualSignature(
        documentUri: Uri,
        outputFile: File,
        signatureId: Long,
        pageNumber: Int,
        rect: RectF,
        documentName: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                val signature = signatureRepository.getSignatureById(signatureId)
                    ?: throw IllegalArgumentException("Signature not found")
                
                val bitmap = signatureRepository.getSignatureBitmap(signature)
                    ?: throw IllegalArgumentException("Cannot load signature bitmap")
                
                pdfSignatureEngine.applyVisualSignature(
                    inputUri = documentUri,
                    outputFile = outputFile,
                    signatureBitmap = bitmap,
                    pageNumber = pageNumber,
                    rect = rect
                ).getOrThrow()
                
                signatureRepository.incrementUseCount(signatureId)
                
                val historyEntry = SignatureHistoryEntity(
                    signatureId = signatureId,
                    documentPath = documentUri.toString(),
                    documentName = documentName,
                    pageNumber = pageNumber,
                    signatureType = signature.type,
                    signatureName = signature.name,
                    signatureRectLeft = rect.left,
                    signatureRectTop = rect.top,
                    signatureRectRight = rect.right,
                    signatureRectBottom = rect.bottom,
                    outputPath = outputFile.absolutePath,
                    fileSizeAfter = outputFile.length()
                )
                historyRepository.addHistoryEntry(historyEntry)
                
                _uiEvent.emit(UiEvent.SignatureApplied(outputFile.absolutePath))
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to apply signature"))
            }
            _isLoading.value = false
        }
    }

    fun applyDigitalSignature(
        documentUri: Uri,
        outputFile: File,
        signatureId: Long,
        certificateId: Long,
        pageNumber: Int,
        rect: RectF,
        documentName: String,
        keystorePassword: String,
        keyPassword: String
    ) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                val signature = signatureRepository.getSignatureById(signatureId)
                    ?: throw IllegalArgumentException("Signature not found")
                val certificate = certificateRepository.getCertificateById(certificateId)
                    ?: throw IllegalArgumentException("Certificate not found")
                
                val bitmap = signatureRepository.getSignatureBitmap(signature)
                
                val result = pdfSignatureEngine.applyDigitalSignature(
                    inputUri = documentUri,
                    outputFile = outputFile,
                    signatureBitmap = bitmap,
                    pageNumber = pageNumber,
                    rect = rect,
                    keystorePath = certificate.keystorePath,
                    keystorePassword = keystorePassword,
                    alias = certificate.alias,
                    keyPassword = keyPassword
                ).getOrThrow()
                
                signatureRepository.incrementUseCount(signatureId)
                certificateRepository.incrementUseCount(certificateId)
                
                val historyEntry = SignatureHistoryEntity(
                    signatureId = signatureId,
                    documentPath = documentUri.toString(),
                    documentName = documentName,
                    pageNumber = pageNumber,
                    signatureType = signature.type,
                    signatureName = signature.name,
                    certificateAlias = certificate.alias,
                    isVerified = true,
                    verificationStatus = SignatureHistoryEntity.VerificationStatus.VALID,
                    signatureRectLeft = rect.left,
                    signatureRectTop = rect.top,
                    signatureRectRight = rect.right,
                    signatureRectBottom = rect.bottom,
                    outputPath = outputFile.absolutePath,
                    hashAlgorithm = result.hashAlgorithm,
                    fileSizeAfter = outputFile.length()
                )
                historyRepository.addHistoryEntry(historyEntry)
                
                _uiEvent.emit(UiEvent.SignatureApplied(outputFile.absolutePath))
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to apply digital signature"))
            }
            _isLoading.value = false
        }
    }

    fun verifyDocumentSignatures(documentUri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                val results = pdfSignatureEngine.verifySignatures(documentUri)
                _uiEvent.emit(UiEvent.VerificationComplete(results))
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to verify signatures"))
            }
            _isLoading.value = false
        }
    }

    fun importCertificate(alias: String, displayName: String, data: ByteArray, password: String) {
        viewModelScope.launch {
            _isLoading.value = true
            runCatching {
                certificateRepository.importP12Certificate(alias, displayName, data, password)
                _uiEvent.emit(UiEvent.CertificateImported)
            }.onFailure {
                _uiEvent.emit(UiEvent.Error(it.message ?: "Failed to import certificate"))
            }
            _isLoading.value = false
        }
    }

    fun deleteCertificate(certificate: CertificateEntity) {
        viewModelScope.launch {
            certificateRepository.deleteCertificate(certificate)
        }
    }

    fun setDefaultCertificate(id: Long) {
        viewModelScope.launch {
            certificateRepository.setAsDefault(id)
        }
    }

    sealed class UiEvent {
        object SignatureCreated : UiEvent()
        object SignatureDeleted : UiEvent()
        data class SignatureApplied(val outputPath: String) : UiEvent()
        data class VerificationComplete(val results: List<PdfSignatureEngine.SignatureVerificationResult>) : UiEvent()
        object CertificateImported : UiEvent()
        data class Error(val message: String) : UiEvent()
    }
}
