package com.propdf.scanner.ui.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.scanner.data.repository.ScannerRepository
import com.propdf.scanner.model.*
import com.propdf.scanner.processing.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    private val scannerRepository: ScannerRepository,
    private val edgeDetector: EdgeDetector,
    private val perspectiveCorrector: PerspectiveCorrector,
    private val imageEnhancer: ImageEnhancer,
    private val scanModeDetector: ScanModeDetector,
    private val pdfCreator: PdfCreator,
    private val batchProcessor: BatchProcessor
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScannerUiState>(ScannerUiState.Idle)
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _currentDocument = MutableStateFlow<ScannedDocument?>(null)
    val currentDocument: StateFlow<ScannedDocument?> = _currentDocument.asStateFlow()

    private val _batchState = MutableStateFlow<BatchScanState>(BatchScanState.Idle)
    val batchState: StateFlow<BatchScanState> = _batchState.asStateFlow()

    private val _documents = MutableStateFlow<List<ScannedDocument>>(emptyList())
    val documents: StateFlow<List<ScannedDocument>> = _documents.asStateFlow()

    private val _currentPage = MutableStateFlow<ScannedPage?>(null)
    val currentPage: StateFlow<ScannedPage?> = _currentPage.asStateFlow()

    init {
        viewModelScope.launch {
            scannerRepository.getAllDocuments().collect { _documents.value = it }
        }
    }

    fun detectEdges(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = ScannerUiState.DetectingEdges
            val edge = edgeDetector.detectEdges(bitmap)
            _uiState.value = if (edge != null) ScannerUiState.EdgeDetected(edge) else ScannerUiState.Preview
        }
    }

    fun capturePage(imagePath: String, edge: DocumentEdge?, scanMode: ScanMode, colorFilter: ColorFilter, autoEnhance: Boolean) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.value = ScannerUiState.Capturing
            try {
                val bitmap = BitmapFactory.decodeFile(imagePath) ?: throw IllegalStateException("Failed to decode image")
                val processedBitmap = processImage(bitmap, edge, scanMode, colorFilter, autoEnhance)
                val scannerDir = File(System.getProperty("java.io.tmpdir"), "scanner").apply { mkdirs() }
                val processedFile = File(scannerDir, "${UUID.randomUUID()}.jpg")
                FileOutputStream(processedFile).use { out -> processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }

                val page = ScannedPage(
                    originalImagePath = imagePath, processedImagePath = processedFile.absolutePath,
                    documentEdge = edge, colorFilter = if (autoEnhance) ColorFilter.MAGIC_COLOR else colorFilter
                )
                _currentPage.value = page
                _uiState.value = ScannerUiState.Idle
            } catch (e: Exception) {
                _uiState.value = ScannerUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun processImage(bitmap: Bitmap, edge: DocumentEdge?, scanMode: ScanMode, colorFilter: ColorFilter, autoEnhance: Boolean): Bitmap {
        val corrected = if (edge != null) perspectiveCorrector.correctPerspective(bitmap, edge) else bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val cropped = perspectiveCorrector.autoCrop(corrected)
        if (corrected != bitmap && corrected != cropped) corrected.recycle()

        val filtered = if (colorFilter != ColorFilter.ORIGINAL) imageEnhancer.applyFilter(cropped, colorFilter)
            else if (autoEnhance) imageEnhancer.applyFilter(cropped, ColorFilter.MAGIC_COLOR) else cropped

        val final = if (autoEnhance) imageEnhancer.autoRotate(filtered) else filtered
        if (final != filtered && colorFilter != ColorFilter.ORIGINAL) filtered.recycle()
        if (filtered != cropped && colorFilter != ColorFilter.ORIGINAL) filtered.recycle()
        if (cropped != corrected) cropped.recycle()
        if (corrected != bitmap) corrected.recycle()

        return final
    }

    fun applyFilterToCurrentPage(filter: ColorFilter) {
        val page = _currentPage.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeFile(page.processedImagePath ?: page.originalImagePath) ?: return@launch
            val filtered = imageEnhancer.applyFilter(bitmap, filter)
            val file = File(page.processedImagePath ?: page.originalImagePath)
            FileOutputStream(file).use { out -> filtered.compress(Bitmap.CompressFormat.JPEG, 95, out) }
            bitmap.recycle(); filtered.recycle()
            _currentPage.value = page.copy(colorFilter = filter)
        }
    }

    fun saveDocument(name: String, scanMode: ScanMode) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            val document = ScannedDocument(name = name, pages = listOf(page), scanMode = scanMode)
            scannerRepository.saveDocument(document)
            _currentDocument.value = document
            _currentPage.value = null
        }
    }

    fun addPageToCurrentDocument(imagePath: String, edge: DocumentEdge?, scanMode: ScanMode, colorFilter: ColorFilter, autoEnhance: Boolean) {
        val doc = _currentDocument.value ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val bitmap = BitmapFactory.decodeFile(imagePath) ?: return@launch
            val processed = processImage(bitmap, edge, scanMode, colorFilter, autoEnhance)
            val scannerDir = File(System.getProperty("java.io.tmpdir"), "scanner").apply { mkdirs() }
            val processedFile = File(scannerDir, "${UUID.randomUUID()}.jpg")
            FileOutputStream(processedFile).use { out -> processed.compress(Bitmap.CompressFormat.JPEG, 95, out) }

            val page = ScannedPage(
                originalImagePath = imagePath, processedImagePath = processedFile.absolutePath,
                documentEdge = edge, colorFilter = if (autoEnhance) ColorFilter.MAGIC_COLOR else colorFilter,
                pageNumber = doc.pages.size + 1
            )

            val updated = doc.copy(pages = doc.pages + page, updatedAt = System.currentTimeMillis())
            scannerRepository.updateDocument(updated)
            _currentDocument.value = updated
            bitmap.recycle(); processed.recycle()
        }
    }

    fun processBatch(imagePaths: List<String>, scanMode: ScanMode, colorFilter: ColorFilter, autoEnhance: Boolean) {
        viewModelScope.launch {
            batchProcessor.processBatch(imagePaths, scanMode, colorFilter, autoEnhance).let { doc ->
                scannerRepository.saveDocument(doc)
                _currentDocument.value = doc
            }
        }
    }

    fun exportToPdf(document: ScannedDocument, outputFile: File, config: ExportConfig) {
        viewModelScope.launch {
            pdfCreator.createPdf(document.pages, outputFile, config)
        }
    }

    fun exportImages(document: ScannedDocument, outputDir: File, config: ExportConfig) {
        viewModelScope.launch {
            pdfCreator.exportImages(document.pages, outputDir, config)
        }
    }

    fun deleteDocument(document: ScannedDocument) {
        viewModelScope.launch { scannerRepository.deleteDocument(document) }
    }

    fun deleteDocumentById(id: String) {
        viewModelScope.launch { scannerRepository.deleteDocumentById(id) }
    }

    fun searchDocuments(query: String) {
        viewModelScope.launch {
            scannerRepository.searchDocuments(query).collect { _documents.value = it }
        }
    }

    fun setCurrentDocument(document: ScannedDocument?) {
        _currentDocument.value = document
    }

    fun clearCurrentPage() {
        _currentPage.value = null
        _uiState.value = ScannerUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        batchProcessor.cancel()
    }
}
