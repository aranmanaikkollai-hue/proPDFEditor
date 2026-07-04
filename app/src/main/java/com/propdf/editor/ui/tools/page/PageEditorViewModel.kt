package com.propdf.editor.ui.tools.page

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.worker.PdfOperationWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import javax.inject.Inject

@HiltViewModel
class PageEditorViewModel @Inject constructor(
    private val pdfOperationsRepository: PdfOperationsRepository,
    private val workManager: WorkManager,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _uiState = MutableStateFlow(PageEditorUiState())
    val uiState: StateFlow<PageEditorUiState> = _uiState.asStateFlow()

    private val _operationResult = MutableSharedFlow<OperationResult>()
    val operationResult: SharedFlow<OperationResult> = _operationResult.asSharedFlow()

    private var currentPdfUri: Uri? = null
    private var pageCount: Int = 0

    fun loadPdf(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            currentPdfUri = uri
            
            when (val result = pdfOperationsRepository.getPageCount(uri)) {
                is AppResult.Success -> {
                    pageCount = result.data
                    loadPageThumbnails(uri, (1..pageCount).toList())
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                }
                else -> {}
            }
        }
    }

    private fun loadPageThumbnails(uri: Uri, pages: List<Int>) {
        viewModelScope.launch {
            when (val result = pdfOperationsRepository.getPageThumbnails(uri, pages)) {
                is AppResult.Success -> {
                    val pageItems = result.data.mapIndexed { index, bitmap ->
                        PageItem(
                            pageNumber = index + 1,
                            thumbnail = bitmap,
                            isSelected = false
                        )
                    }
                    _uiState.update { it.copy(
                        isLoading = false,
                        pages = pageItems,
                        pageCount = pageCount
                    ) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                }
                else -> {}
            }
        }
    }

    fun togglePageSelection(pageNumber: Int) {
        _uiState.update { state ->
            state.copy(pages = state.pages.map { page ->
                if (page.pageNumber == pageNumber) page.copy(isSelected = !page.isSelected) else page
            })
        }
    }

    fun selectAllPages() {
        _uiState.update { state ->
            state.copy(pages = state.pages.map { it.copy(isSelected = true) })
        }
    }

    fun clearSelection() {
        _uiState.update { state ->
            state.copy(pages = state.pages.map { it.copy(isSelected = false) })
        }
    }

    fun selectRange(start: Int, end: Int) {
        _uiState.update { state ->
            state.copy(pages = state.pages.map { page ->
                page.copy(isSelected = page.pageNumber in start..end)
            })
        }
    }

    // ==================== PAGE OPERATIONS ====================

    fun deleteSelectedPages() {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_DELETE_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
        }
    }

    fun duplicateSelectedPages() {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_DUPLICATE_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
        }
    }

    fun moveSelectedPages(targetPosition: Int) {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_MOVE_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
            putInt("target_position", targetPosition)
        }
    }

    fun extractSelectedPages(outputName: String) {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_EXTRACT_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
            putString("output_name", outputName)
        }
    }

    fun rotateSelectedPages(degrees: Int) {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_ROTATE_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
            putInt("degrees", degrees)
        }
    }

    fun cropPages(config: CropConfig) {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_CROP_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    fun resizePages(config: ResizeConfig) {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_RESIZE_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    fun mirrorPages(horizontal: Boolean = true) {
        val selected = getSelectedPages()
        if (selected.isEmpty()) return
        executeOperation(PdfOperationWorker.OP_MIRROR_PAGES) {
            putIntArray(PdfOperationWorker.KEY_PAGE_NUMBERS, selected.toIntArray())
            putBoolean("horizontal", horizontal)
        }
    }

    // ==================== INSERT OPERATIONS ====================

    fun insertBlankPage(position: Int, width: Float = 595f, height: Float = 842f) {
        val uri = currentPdfUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = pdfOperationsRepository.insertBlankPage(uri, position, width, height)) {
                is AppResult.Success -> {
                    _operationResult.emit(OperationResult.Success(result.data))
                    loadPdf(result.data)
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                    _operationResult.emit(OperationResult.Error(result.exception.message))
                }
                else -> {}
            }
        }
    }

    fun insertImagePage(position: Int, config: ImageInsertionConfig) {
        val uri = currentPdfUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = pdfOperationsRepository.insertImagePage(uri, position, config)) {
                is AppResult.Success -> {
                    _operationResult.emit(OperationResult.Success(result.data))
                    loadPdf(result.data)
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                    _operationResult.emit(OperationResult.Error(result.exception.message))
                }
                else -> {}
            }
        }
    }

    fun insertPdfPages(position: Int, insertUri: Uri, sourcePages: List<Int> = emptyList()) {
        val uri = currentPdfUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = pdfOperationsRepository.insertPdfPages(uri, insertUri, position, sourcePages)) {
                is AppResult.Success -> {
                    _operationResult.emit(OperationResult.Success(result.data))
                    loadPdf(result.data)
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                    _operationResult.emit(OperationResult.Error(result.exception.message))
                }
                else -> {}
            }
        }
    }

    // ==================== SPLIT & MERGE ====================

    fun splitBySize(maxSizeMB: Int, outputPrefix: String) {
        executeOperation(PdfOperationWorker.OP_SPLIT_SIZE) {
            putInt("max_size_mb", maxSizeMB)
            putString("output_prefix", outputPrefix)
        }
    }

    fun splitByBookmark(outputPrefix: String) {
        executeOperation(PdfOperationWorker.OP_SPLIT_BOOKMARK) {
            putString("output_prefix", outputPrefix)
        }
    }

    fun splitEveryNPages(n: Int, outputPrefix: String) {
        executeOperation(PdfOperationWorker.OP_SPLIT_N) {
            putInt("n_pages", n)
            putString("output_prefix", outputPrefix)
        }
    }

    // ==================== DOCUMENT ENHANCEMENT ====================

    fun addPageNumbers(config: PageNumberConfig) {
        executeOperation(PdfOperationWorker.OP_ADD_PAGE_NUMBERS) {
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    fun addHeaderFooter(config: HeaderFooterConfig) {
        executeOperation(PdfOperationWorker.OP_ADD_HEADER_FOOTER) {
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    fun addWatermark(config: WatermarkConfig) {
        executeOperation(PdfOperationWorker.OP_ADD_WATERMARK) {
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    fun addBackground(config: BackgroundConfig) {
        executeOperation(PdfOperationWorker.OP_ADD_BACKGROUND) {
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    // ==================== COMPRESSION ====================

    fun compressPdf(config: CompressConfig) {
        executeOperation(PdfOperationWorker.OP_COMPRESS) {
            putString(PdfOperationWorker.KEY_CONFIG_JSON, Json.encodeToString(config))
        }
    }

    fun optimizePdf(aggressive: Boolean = false) {
        executeOperation(PdfOperationWorker.OP_OPTIMIZE) {
            putBoolean("aggressive", aggressive)
        }
    }

    // ==================== DRAG REORDER ====================

    fun movePage(fromIndex: Int, toIndex: Int) {
        val currentPages = _uiState.value.pages.toMutableList()
        if (fromIndex in currentPages.indices && toIndex in currentPages.indices) {
            val item = currentPages.removeAt(fromIndex)
            currentPages.add(toIndex, item)
            _uiState.update { it.copy(pages = currentPages) }
        }
    }

    fun applyReorder() {
        val newOrder = _uiState.value.pages.map { it.pageNumber }
        val uri = currentPdfUri ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = pdfOperationsRepository.extractPages(uri, newOrder, "reordered")) {
                is AppResult.Success -> {
                    _operationResult.emit(OperationResult.Success(result.data))
                    loadPdf(result.data)
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(isLoading = false, error = result.exception.message) }
                }
                else -> {}
            }
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private fun getSelectedPages(): List<Int> {
        return _uiState.value.pages.filter { it.isSelected }.map { it.pageNumber }
    }

    private fun executeOperation(operationType: String, dataBuilder: Data.Builder.() -> Unit) {
        val uri = currentPdfUri ?: return
        val inputData = Data.Builder()
            .putString(PdfOperationWorker.KEY_OPERATION_TYPE, operationType)
            .putString(PdfOperationWorker.KEY_SOURCE_URI, uri.toString())
            .apply(dataBuilder)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<PdfOperationWorker>()
            .setInputData(inputData)
            .build()

        workManager.enqueueUniqueWork(
            "pdf_op_${System.currentTimeMillis()}",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        _uiState.update { it.copy(operationInProgress = true) }

        workManager.getWorkInfoByIdLiveData(workRequest.id).observeForever { workInfo ->
            when (workInfo?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val resultUri = workInfo.outputData.getString(PdfOperationWorker.KEY_RESULT_URI)
                    resultUri?.let {
                        _operationResult.tryEmit(OperationResult.Success(Uri.parse(it)))
                        loadPdf(Uri.parse(it))
                    }
                    _uiState.update { it.copy(operationInProgress = false) }
                }
                WorkInfo.State.FAILED -> {
                    val error = workInfo.outputData.getString(PdfOperationWorker.KEY_ERROR_MESSAGE)
                    _operationResult.tryEmit(OperationResult.Error(error))
                    _uiState.update { it.copy(operationInProgress = false, error = error) }
                }
                else -> {}
            }
        }
    }
}
