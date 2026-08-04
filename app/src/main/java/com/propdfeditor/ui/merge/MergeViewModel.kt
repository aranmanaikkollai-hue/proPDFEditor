package com.propdfeditor.ui.merge

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
class MergeViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow<MergeUiState>(MergeUiState.Idle)
    val uiState: StateFlow<MergeUiState> = _uiState.asStateFlow()

    private val _files = MutableStateFlow<List<MergeFile>>(emptyList())
    val files: StateFlow<List<MergeFile>> = _files.asStateFlow()

    init {
        PDFBoxResourceLoader.init(context)
    }

    fun addFile(uri: Uri) {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        val name = docFile?.name ?: "Unknown"
        val pageCount = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { it.numberOfPages }
            } ?: 0
        } catch (_: Exception) { 0 }

        val current = _files.value.toMutableList()
        current.add(
            MergeFile(
                uri = uri.toString(),
                name = name,
                pageCount = pageCount,
                order = current.size
            )
        )
        _files.value = current
    }

    fun removeFile(uri: String) {
        _files.value = _files.value.filter { it.uri != uri }
            .mapIndexed { index, file -> file.copy(order = index) }
    }

    fun moveUp(uri: String) {
        val current = _files.value.toMutableList()
        val index = current.indexOfFirst { it.uri == uri }
        if (index > 0) {
            val temp = current[index]
            current[index] = current[index - 1].copy(order = index)
            current[index - 1] = temp.copy(order = index - 1)
            _files.value = current
        }
    }

    fun moveDown(uri: String) {
        val current = _files.value.toMutableList()
        val index = current.indexOfFirst { it.uri == uri }
        if (index < current.size - 1) {
            val temp = current[index]
            current[index] = current[index + 1].copy(order = index)
            current[index + 1] = temp.copy(order = index + 1)
            _files.value = current
        }
    }

    fun merge(destination: Uri) {
        viewModelScope.launch {
            _uiState.value = MergeUiState.Merging
            try {
                withContext(Dispatchers.IO) {
                    val mergedDoc = PDDocument()
                    _files.value.forEach { file ->
                        context.contentResolver.openInputStream(Uri.parse(file.uri))?.use { stream ->
                            PDDocument.load(stream).use { doc ->
                                doc.pages.forEach { page ->
                                    mergedDoc.addPage(mergedDoc.importPage(page))
                                }
                            }
                        }
                    }
                    context.contentResolver.openOutputStream(destination)?.use { output ->
                        mergedDoc.save(output)
                    }
                    mergedDoc.close()
                }
                _uiState.value = MergeUiState.Done
            } catch (e: Exception) {
                _uiState.value = MergeUiState.Error(e.message ?: "Merge failed")
            }
        }
    }
}

data class MergeFile(
    val uri: String,
    val name: String,
    val pageCount: Int,
    val order: Int
)

sealed interface MergeUiState {
    data object Idle : MergeUiState
    data object Merging : MergeUiState
    data object Done : MergeUiState
    data class Error(val message: String) : MergeUiState
}
