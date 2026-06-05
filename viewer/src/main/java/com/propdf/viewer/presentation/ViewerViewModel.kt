package com.propdf.viewer.presentation

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.onError
import com.propdf.core.domain.result.onSuccess
import com.propdf.viewer.data.repository.PdfViewerRepositoryImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val viewerRepo: PdfViewerRepositoryImpl,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : ViewModel() {

    companion object {
        private const val TAG = "ViewerVM"
    }

    private val _uiState = MutableStateFlow(ViewerUiState())
    val uiState: StateFlow<ViewerUiState> = _uiState.asStateFlow()

    private var currentFile: File? = null
    private var totalPages = 0
    private var currentPage = 0

    fun loadPdf(file: File) {
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            currentFile = file
            val countResult = viewerRepo.getPageCount(file)
            countResult.onSuccess { count ->
                totalPages = count
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        totalPages = count,
                        currentPage = 0,
                        fileName = file.name
                    )
                }
                renderPage(0)
            }.onError { error ->
                _uiState.update { it.copy(isLoading = false, error = error.message) }
            }
        }
    }

    fun renderPage(pageIndex: Int) {
        val file = currentFile ?: return
        if (pageIndex < 0 || pageIndex >= totalPages) return

        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isRendering = true) }
            val screenWidth = _uiState.value.screenWidth
            val result = viewerRepo.renderPage(file, pageIndex, screenWidth)
            result.onSuccess { bitmap ->
                _uiState.update { state ->
                    val pages = state.pages.toMutableMap()
                    pages[pageIndex] = bitmap
                    state.copy(
                        pages = pages,
                        isRendering = false,
                        currentPage = pageIndex
                    )
                }
                currentPage = pageIndex
                preloadNearby(pageIndex)
            }.onError { error ->
                _uiState.update { it.copy(isRendering = false, error = error.message) }
            }
        }
    }

    fun goToPage(pageIndex: Int) {
        if (pageIndex in 0 until totalPages) {
            renderPage(pageIndex)
        }
    }

    fun nextPage() = goToPage(currentPage + 1)
    fun prevPage() = goToPage(currentPage - 1)

    fun setZoom(zoom: Float) {
        _uiState.update { it.copy(zoom = zoom.coerceIn(0.5f, 5.0f)) }
    }

    fun setScreenWidth(width: Int) {
        _uiState.update { it.copy(screenWidth = width) }
    }

    fun toggleTheme() {
        _uiState.update { it.copy(isDark = !it.isDark) }
    }

    fun search(query: String) {
        val file = currentFile ?: return
        viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isSearching = true, searchResults = emptyList()) }
            val results = mutableListOf<Int>()
            for (i in 0 until totalPages) {
                val textResult = viewerRepo.getPageText(file, i)
                if (textResult is AppResult.Success<String> &&
                    textResult.data.contains(query, ignoreCase = true)) {
                    results.add(i)
                }
            }
            _uiState.update {
                it.copy(isSearching = false, searchResults = results, searchQuery = query)
            }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchResults = emptyList(), searchQuery = "") }
    }

    private fun preloadNearby(anchorPage: Int) {
        val file = currentFile ?: return
        viewModelScope.launch(dispatchers.io) {
            viewerRepo.preloadPages(file, anchorPage, _uiState.value.screenWidth)
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewerRepo.clearCache()
    }
}

data class ViewerUiState(
    val isLoading: Boolean = false,
    val isRendering: Boolean = false,
    val isSearching: Boolean = false,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val pages: Map<Int, Bitmap> = emptyMap(),
    val zoom: Float = 1.0f,
    val isDark: Boolean = true,
    val screenWidth: Int = 1080,
    val searchResults: List<Int> = emptyList(),
    val searchQuery: String = "",
    val fileName: String = "",
    val error: String? = null
)
