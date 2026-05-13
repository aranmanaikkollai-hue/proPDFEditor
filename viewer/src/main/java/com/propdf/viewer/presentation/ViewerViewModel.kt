package com.propdf.viewer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.model.PdfPage
import com.propdf.core.domain.repository.PdfViewerRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.usecase.GetPdfPagesUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ViewerViewModel(
    private val getPages: GetPdfPagesUseCase,
    private val repository: PdfViewerRepository,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow<ViewerUiState>(ViewerUiState.Loading)
    val uiState: StateFlow<ViewerUiState> = _uiState

    private val _pages = MutableStateFlow<List<PdfPage>>(emptyList())
    val pages: StateFlow<List<PdfPage>> = _pages

    fun loadPdf(uri: android.net.Uri) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.value = ViewerUiState.Loading
            when (val result = getPages(uri)) {
                is AppResult.Success -> {
                    _pages.value = result.data
                    _uiState.value = ViewerUiState.Success(result.data)
                }
                is AppResult.Error -> _uiState.value = ViewerUiState.Error(result.exception.message ?: "Unknown error")
                is AppResult.Loading -> { /* already loading */ }
            }
        }
    }

    fun refreshPage(pageIndex: Int) {
        viewModelScope.launch(dispatchers.io) {
            // Page refresh logic
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.clearCache()
    }
}

sealed class ViewerUiState {
    object Loading : ViewerUiState()
    data class Success(val pages: List<PdfPage>) : ViewerUiState()
    data class Error(val message: String) : ViewerUiState()
}
