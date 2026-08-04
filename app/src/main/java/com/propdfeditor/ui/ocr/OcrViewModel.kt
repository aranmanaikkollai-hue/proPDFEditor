package com.propdfeditor.ui.ocr

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.repository.OcrRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OcrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ocrRepository: OcrRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<OcrUiState>(OcrUiState.Idle)
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    fun performOcr(uri: Uri, language: String) {
        viewModelScope.launch {
            _uiState.value = OcrUiState.Processing
            ocrRepository.performOcr(uri, language)
                .onSuccess { record ->
                    _uiState.value = OcrUiState.Success(record.extractedText)
                }
                .onFailure { e ->
                    _uiState.value = OcrUiState.Error(e.message ?: "OCR failed")
                }
        }
    }

    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("OCR Text", text))
    }

    fun exportText(text: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share OCR Text"))
    }
}

sealed interface OcrUiState {
    data object Idle : OcrUiState
    data object Processing : OcrUiState
    data class Success(val text: String) : OcrUiState
    data class Error(val message: String) : OcrUiState
}
