package com.propdfeditor.ui.split

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SplitViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<SplitUiState>(SplitUiState.Idle)
    val uiState: StateFlow<SplitUiState> = _uiState.asStateFlow()

    private val _pageRanges = MutableStateFlow<List<PageRange>>(emptyList())
    val pageRanges: StateFlow<List<PageRange>> = _pageRanges.asStateFlow()

    private var currentDocument: PDDocument? = null
    private var currentUri: Uri? = null
    private var nextRangeId = 0

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun loadDocument(uri: Uri) {
        viewModelScope.launch {
            try {
                val docFile = DocumentFile.fromSingleUri(context, uri)
                val name = docFile?.name ?: "document.pdf"

                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val doc = PDDocument.load(stream)
                    currentDocument = doc
                    currentUri = uri
                    _pageRanges.value = listOf(
                        PageRange(nextRangeId++, 1, doc.numberOfPages)
                    )
                    _uiState.value = SplitUiState.Loaded(
                        fileName = name,
                        pageCount = doc.numberOfPages
                    )
                }
            } catch (e: Exception) {
                _uiState.value = SplitUiState.Error(e.message ?: "Failed to load PDF")
            }
        }
    }

    fun addRange() {
        val current = _pageRanges.value.toMutableList()
        val lastEnd = current.lastOrNull()?.end ?: 0
        val docPages = (currentDocument?.numberOfPages ?: 0)
        if (lastEnd < docPages) {
            current.add(PageRange(nextRangeId++, lastEnd + 1, docPages))
            _pageRanges.value = current
        }
    }

    fun removeRange(id: Int) {
        _pageRanges.value = _pageRanges.value.filter { it.id != id }
    }

    fun updateRange(id: Int, start: Int, end: Int) {
        _pageRanges.value = _pageRanges.value.map {
            if (it.id == id) it.copy(start = start, end = end) else it
        }
    }

    fun splitEveryN(n: Int) {
        val doc = currentDocument ?: return
        val ranges = mutableListOf<PageRange>()
        var currentPage = 1
        while (currentPage <= doc.numberOfPages) {
            val end = kotlin.math.min(currentPage + n - 1, doc.numberOfPages)
            ranges.add(PageRange(nextRangeId++, currentPage, end))
            currentPage = end + 1
        }
        _pageRanges.value = ranges
    }

    fun splitToFolder(folderUri: Uri) {
        val doc = currentDocument ?: return
        viewModelScope.launch {
            _uiState.value = SplitUiState.Splitting
            try {
                withContext(Dispatchers.IO) {
                    _pageRanges.value.forEachIndexed { index, range ->
                        val newDoc = PDDocument()
                        for (i in range.start - 1 until range.end) {
                            if (i < doc.numberOfPages) {
                                newDoc.addPage(newDoc.importPage(doc.getPage(i)))
                            }
                        }
                        val fileName = "split_${index + 1}.pdf"
                        val newUri = DocumentFile.fromTreeUri(context, folderUri)?.createFile(
                            "application/pdf",
                            fileName
                        )?.uri
                        newUri?.let { uri ->
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                newDoc.save(output)
                            }
                        }
                        newDoc.close()
                    }
                }
                _uiState.value = SplitUiState.Done(_pageRanges.value.size)
            } catch (e: Exception) {
                _uiState.value = SplitUiState.Error(e.message ?: "Split failed")
            }
        }
    }

    fun reset() {
        currentDocument?.close()
        currentDocument = null
        currentUri = null
        _pageRanges.value = emptyList()
        _uiState.value = SplitUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        currentDocument?.close()
    }
}

sealed interface SplitUiState {
    data object Idle : SplitUiState
    data class Loaded(val fileName: String, val pageCount: Int) : SplitUiState
    data object Splitting : SplitUiState
    data class Done(val fileCount: Int) : SplitUiState
    data class Error(val message: String) : SplitUiState
}
