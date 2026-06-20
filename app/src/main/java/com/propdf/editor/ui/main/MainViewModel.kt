package com.propdf.editor.ui.main

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.saf.SafEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainUiState(
    val files: List<RecentFile> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val recentFilesRepo: RecentFilesRepository,
    private val safEngine: SafEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch {
            recentFilesRepo.observeAll()
                .catch { e -> _uiState.update { it.copy(error = e.message, isLoading = false) } }
                .collect { files -> _uiState.update { it.copy(files = files, isLoading = false) } }
        }
    }

    fun openPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = safEngine.openDocument(uri)) {
                is AppResult.Success -> {
                    recentFilesRepo.add(RecentFile(uri = uri.toString(), displayName = uri.lastPathSegment ?: uri.toString()))
                    _events.send(Event.OpenPdf(uri))
                    _uiState.update { it.copy(isLoading = false) }
                }
                is AppResult.Error -> {
                    _events.send(Event.Error(result.exception))
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                }
            }
        }
    }

    fun openPdfString(uriString: String) { openPdf(Uri.parse(uriString)) }

    fun toggleFavourite(uri: String) {
        viewModelScope.launch {
            val result = recentFilesRepo.getByUri(uri)
            if (result is AppResult.Success) {
                recentFilesRepo.setFavourite(uri, !result.data.isFavourite)
            }
        }
    }

    fun setSearchQuery(query: String) { _uiState.update { it.copy(searchQuery = query) } }

    sealed class Event {
        data class OpenPdf(val uri: Uri) : Event()
        data class OpenScanner(val dummy: Unit = Unit) : Event()
        data class OpenTools(val dummy: Unit = Unit) : Event()
        data class Error(val exception: Throwable) : Event()
    }
}
