package com.propdf.editor.ui.split

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(SplitUiState())
    val uiState: StateFlow<SplitUiState> = _uiState.asStateFlow()

    fun setDocumentUri(uri: Uri) {
        _uiState.value = _uiState.value.copy(documentUri = uri.toString())
    }

    fun splitDocument() {
        viewModelScope.launch {
            // Implementation
        }
    }
}

data class SplitUiState(
    val documentUri: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)
