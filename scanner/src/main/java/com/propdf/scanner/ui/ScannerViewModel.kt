package com.propdf.scanner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.ScannedPage
import com.propdf.scanner.engine.ColorMode
import com.propdf.scanner.engine.DocumentScannerEngine
import com.propdf.scanner.engine.ScanOptions
import com.propdf.scanner.engine.ScannedDocument
import com.propdf.scanner.engine.ocr.MlKitOcrEngine
import com.propdf.scanner.engine.pdf.SearchablePdfGenerator
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val scannerEngine: DocumentScannerEngine,
    private val pdfGenerator: SearchablePdfGenerator,
    private val ocrEngine: MlKitOcrEngine
) : ViewModel() {

    // Wrapper to hold both metadata (ScannedPage) and bitmaps
    data class CapturedPage(
        val meta: ScannedPage,
        val bitmap: Bitmap,
        val originalBitmap: Bitmap
    )

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val _capturedPages = MutableStateFlow<List<CapturedPage>>(emptyList())
    val capturedPages: StateFlow<List<CapturedPage>> = _capturedPages.asStateFlow()

    private val _currentPage = MutableStateFlow<CapturedPage?>(null)
    val currentPage: StateFlow<CapturedPage?> = _currentPage.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    private val _ocrText = MutableStateFlow("")
    val ocrText: StateFlow<String> = _ocrText.asStateFlow()

    private val _importState = MutableStateFlow(GalleryImportState())
    val importState: StateFlow<GalleryImportState> = _importState.asStateFlow()

    private var importJob: Job? = null

    /**
     * Imports multiple gallery images as scanner pages, one at a time.
     *
     * The screen previously used `ActivityResultContracts.GetContent()` (single URI
     * only) and decoded that one image at *full resolution* synchronously on the
     * calling thread before handing it to captureDocument(). Neither of those survives
     * a multi-select: decoding several full-resolution photos back-to-back on the main
     * thread would freeze the UI, and firing captureDocument() once per image from a
     * loop would run each one as its own concurrent coroutine racing on the same
     * `_capturedPages` StateFlow (classic lost-update bug -- whichever coroutine reads
     * the list last before writing wins, silently dropping pages).
     *
     * This processes the whole batch as a single sequential coroutine: each URI is
     * decoded off the main thread with a bounded `inSampleSize` (so a 12MP photo
     * doesn't get fully decoded into memory just to be downscaled afterward), run
     * through the same scanner engine every other capture path uses, and appended one
     * at a time -- so ordering is preserved, progress is real, and one bad/corrupt
     * image doesn't abort the rest of the batch.
     */
    fun importFromGallery(uris: List<Uri>, options: ScanOptions = ScanOptions()) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _isProcessing.value = true
            _importState.value = GalleryImportState(total = uris.size, current = 0, failed = 0, isImporting = true)

            var imported = 0
            var failed = 0
            for ((index, uri) in uris.withIndex()) {
                if (!isActive) break // cancelled
                _importState.value = _importState.value.copy(current = index + 1)
                _progress.value = (index).toFloat() / uris.size

                val bitmap = decodeBoundedBitmap(uri)
                if (bitmap == null) {
                    failed++
                    continue
                }

                val result = scannerEngine.scanDocument(bitmap, options)
                result.onSuccess { doc ->
                    val meta = ScannedPage(
                        index = _capturedPages.value.size,
                        width = doc.bitmap.width,
                        height = doc.bitmap.height,
                        rotation = 0
                    )
                    val page = CapturedPage(meta = meta, bitmap = doc.bitmap, originalBitmap = doc.originalBitmap)
                    _capturedPages.value = _capturedPages.value + page
                    _currentPage.value = page
                    imported++
                }.onFailure {
                    failed++
                }
            }

            _importState.value = _importState.value.copy(isImporting = false, failed = failed)
            _uiState.value = _uiState.value.copy(
                error = if (failed > 0) "$imported imported, $failed failed" else null
            )
            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun cancelGalleryImport() {
        importJob?.cancel()
        _importState.value = _importState.value.copy(isImporting = false)
        _isProcessing.value = false
    }

    /**
     * Decodes [uri] to a bitmap bounded to [maxDimension] on its longest edge. Reads the
     * image bounds first (no pixel data loaded) to compute a downsample factor, then
     * decodes once at that size -- so a full-resolution gallery photo is never fully
     * materialized in memory just to be scaled down afterward. 2000px keeps plenty of
     * detail for a readable scanned document/OCR while bounding memory use for a batch
     * of many images.
     */
    private suspend fun decodeBoundedBitmap(uri: Uri, maxDimension: Int = 2000): Bitmap? = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(appContext.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > maxDimension) {
                        val scale = maxDimension.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * scale).toInt().coerceAtLeast(1),
                            (info.size.height * scale).toInt().coerceAtLeast(1)
                        )
                    }
                    decoder.isMutableRequired = true
                }
            } else {
                val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                appContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, boundsOpts)
                }
                val longest = maxOf(boundsOpts.outWidth, boundsOpts.outHeight)
                var sampleSize = 1
                while (longest / sampleSize > maxDimension) sampleSize *= 2
                val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
                appContext.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, decodeOpts)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    data class GalleryImportState(
        val total: Int = 0,
        val current: Int = 0,
        val failed: Int = 0,
        val isImporting: Boolean = false
    )

    fun captureDocument(bitmap: Bitmap, options: ScanOptions = ScanOptions()) {
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0f

            val result = scannerEngine.scanDocument(bitmap, options)

            result.onSuccess { doc ->
                val meta = ScannedPage(
                    index = _capturedPages.value.size,
                    width = doc.bitmap.width,
                    height = doc.bitmap.height,
                    rotation = 0
                )
                val page = CapturedPage(
                    meta = meta,
                    bitmap = doc.bitmap,
                    originalBitmap = doc.originalBitmap
                )
                _capturedPages.value = _capturedPages.value + page
                _currentPage.value = page
                _uiState.value = _uiState.value.copy(error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }

            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun applyFilterToCurrent(filter: ColorMode) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val filtered = scannerEngine.applyFilter(page.originalBitmap, filter)
            val updated = page.copy(bitmap = filtered)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    fun rotateCurrent(degrees: Float) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val rotated = scannerEngine.rotate(page.bitmap, degrees)
            val updated = page.copy(bitmap = rotated)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    fun adjustBrightnessCurrent(delta: Int) {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val adjusted = scannerEngine.adjustBrightness(page.originalBitmap, delta)
            val updated = page.copy(bitmap = adjusted)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    fun removePage(pageIndex: Int) {
        _capturedPages.value = _capturedPages.value.filter { it.meta.index != pageIndex }
        if (_currentPage.value?.meta?.index == pageIndex) {
            _currentPage.value = _capturedPages.value.lastOrNull()
        }
    }

    fun reorderPages(newOrder: List<Int>) {
        val pages = _capturedPages.value
        _capturedPages.value = newOrder.mapNotNull { idx -> pages.find { it.meta.index == idx } }
    }

    fun selectPage(pageIndex: Int) {
        _currentPage.value = _capturedPages.value.find { it.meta.index == pageIndex }
    }

    fun runOcrOnCurrent() {
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            _isProcessing.value = true
            val result = ocrEngine.extractText(page.bitmap)
            result.onSuccess { text ->
                _ocrText.value = text
                _uiState.value = _uiState.value.copy(error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }
            _isProcessing.value = false
        }
    }

    fun generateSearchablePdf(fileName: String? = null) {
        val pages = _capturedPages.value
        if (pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No pages to export")
            return
        }
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0f

            val result = pdfGenerator.generateSearchablePdf(
                images = pages.map { it.bitmap },
                outputFileName = fileName ?: "Scan_${System.currentTimeMillis()}.pdf",
                onProgress = { current, total ->
                    _progress.value = current.toFloat() / total
                }
            )

            result.onSuccess { uri ->
                _uiState.value = _uiState.value.copy(lastOutputUri = uri, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }

            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun generateImagePdf(fileName: String? = null) {
        val pages = _capturedPages.value
        if (pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(error = "No pages to export")
            return
        }
        viewModelScope.launch {
            _isProcessing.value = true
            _progress.value = 0f

            val result = pdfGenerator.generateImagePdf(
                images = pages.map { it.bitmap },
                outputFileName = fileName ?: "Scan_${System.currentTimeMillis()}.pdf",
                onProgress = { current, total ->
                    _progress.value = current.toFloat() / total
                }
            )

            result.onSuccess { uri ->
                _uiState.value = _uiState.value.copy(lastOutputUri = uri, error = null)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(error = e.message)
            }

            _isProcessing.value = false
            _progress.value = 1f
        }
    }

    fun saveAsJpegs() {
        _uiState.value = _uiState.value.copy(error = "JPEG export not yet implemented")
    }

    /**
     * Clears the just-consumed export Uri after the screen has handed it off
     * to the viewer, so a previously generated PDF's Uri can never be
     * re-delivered (e.g. if this ViewModel outlives a single scan session).
     */
    fun consumeLastOutputUri() {
        _uiState.value = _uiState.value.copy(lastOutputUri = null)
    }

    fun clearAll() {
        _capturedPages.value = emptyList()
        _currentPage.value = null
        _ocrText.value = ""
        _uiState.value = ScannerUiState()
    }

    private fun updatePage(updated: CapturedPage) {
        _capturedPages.value = _capturedPages.value.map {
            if (it.meta.index == updated.meta.index) updated else it
        }
    }

    override fun onCleared() {
        super.onCleared()
        ocrEngine.close()
    }

    data class ScannerUiState(
        val lastOutputUri: Uri? = null,
        val error: String? = null
    )
}
