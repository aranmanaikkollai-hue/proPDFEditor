package com.propdf.editor.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PdfEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<EditorUiState>(EditorUiState.Loading)
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var document: PDDocument? = null
    private var currentUri: Uri? = null

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun loadDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = EditorUiState.Loading
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    document = doc
                    currentUri = uri
                    _uiState.value = EditorUiState.Ready(
                        pageCount = doc.numberOfPages,
                        uri = uri.toString()
                    )
                } ?: throw IllegalStateException("Cannot open document")
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    fun deletePages(pages: List<Int>) {
        val doc = document ?: return
        viewModelScope.launch {
            try {
                // Remove in reverse order to maintain indices
                pages.sortedDescending().forEach { index ->
                    if (index < doc.numberOfPages) {
                        doc.removePage(index)
                    }
                }
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = currentUri.toString()
                )
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Delete failed")
            }
        }
    }

    fun duplicatePage(pageIndex: Int) {
        val doc = document ?: return
        viewModelScope.launch {
            try {
                val page = doc.getPage(pageIndex)
                val imported = doc.importPage(page)
                doc.addPage(imported)
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = currentUri.toString()
                )
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Duplicate failed")
            }
        }
    }

    fun rotatePage(pageIndex: Int) {
        val doc = document ?: return
        viewModelScope.launch {
            try {
                val page = doc.getPage(pageIndex)
                val rotation = page.rotation
                page.rotation = (rotation + 90) % 360
                _uiState.value = EditorUiState.Ready(
                    pageCount = doc.numberOfPages,
                    uri = currentUri.toString()
                )
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Rotate failed")
            }
        }
    }

    fun extractPages(pages: List<Int>) {
        // TODO: Create new PDF from selected pages
    }

    fun compress() {
        // TODO: Implement compression profiles
    }

    fun addWatermark(text: String) {
        // TODO: Implement watermark overlay
    }

    fun addPageNumbers() {
        // TODO: Implement page numbering
    }

    fun saveDocument() {
        val doc = document ?: return
        viewModelScope.launch {
            try {
                currentUri?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        doc.save(output)
                    }
                }
                _uiState.value = EditorUiState.Saved(currentUri.toString())
            } catch (e: Exception) {
                _uiState.value = EditorUiState.Error(e.message ?: "Save failed")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            document?.close()
        } catch (_: Exception) { }
    }
}

sealed interface EditorUiState {
    data object Loading : EditorUiState
    data class Ready(val pageCount: Int, val uri: String) : EditorUiState
    data class Saved(val uri: String) : EditorUiState
    data class Error(val message: String) : EditorUiState
}
