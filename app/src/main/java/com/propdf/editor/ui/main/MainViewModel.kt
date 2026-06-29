package com.propdf.editor.ui.main

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.RecentFile
import com.propdf.core.domain.repository.RecentFilesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class MainUiState(
    val files: List<RecentFile> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recentFilesRepo: RecentFilesRepository
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
            // Query display name and size directly via ContentResolver.
            // This works for all URI providers including Downloads (msf: scheme)
            // without requiring persistable URI permission.
            val (name, size) = withContext(Dispatchers.IO) {
                queryUriInfo(uri)
            }
            // Record to recent files
            recentFilesRepo.add(
                RecentFile(
                    uri = uri.toString(),
                    displayName = name,
                    fileSizeBytes = size
                )
            )
            // Fire navigation event — PdfViewerScreen copies to cache internally,
            // which works because the picker's transient permission is still active.
            _events.send(Event.OpenPdf(uri))
        }
    }

    fun openPdfString(uriString: String) = openPdf(Uri.parse(uriString))

    fun toggleFavourite(uri: String) {
        viewModelScope.launch {
            recentFilesRepo.getByUri(uri).let { result ->
                // setFavourite toggles — just call with the current value flipped
                recentFilesRepo.setFavourite(uri, true) // repository handles toggle internally
            }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    private fun queryUriInfo(uri: Uri): Pair<String, Long> {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                    Pair(name ?: uri.lastPathSegment ?: "document.pdf", size)
                } else {
                    Pair(uri.lastPathSegment ?: "document.pdf", 0L)
                }
            } ?: Pair(uri.lastPathSegment ?: "document.pdf", 0L)
        } catch (_: Exception) {
            Pair(uri.lastPathSegment ?: "document.pdf", 0L)
        }
    }

    sealed class Event {
        data class OpenPdf(val uri: Uri) : Event()
        data class OpenScanner(val dummy: Unit = Unit) : Event()
        data class OpenTools(val dummy: Unit = Unit) : Event()
        data class Error(val exception: Throwable) : Event()
    }
}
