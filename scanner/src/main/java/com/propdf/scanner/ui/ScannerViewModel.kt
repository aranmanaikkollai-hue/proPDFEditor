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
import com.propdf.scanner.domain.usecase.DetectEdgesUseCase
import com.propdf.scanner.domain.model.EdgeDetectionResult
import com.propdf.scanner.domain.model.PointF as EdgePointF
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
    private val ocrEngine: MlKitOcrEngine,
    private val detectEdgesUseCase: DetectEdgesUseCase
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

    // ==================== Live edge detection ====================
    //
    // DetectEdgesUseCase (backed by the real OpenCV pipeline via
    // ScannerProcessingRepositoryImpl -> OpenCvDocumentProcessor) already existed and
    // worked, but nothing in the UI ever called it -- there was no ImageAnalysis use
    // case bound to the camera, so the scanner never showed a live document outline,
    // never knew when the frame was "stable", and had no basis for auto-capture.

    sealed class LiveEdgeState {
        data object Searching : LiveEdgeState()
        data class DocumentDetected(
            val corners: List<EdgePointF>,
            val sourceWidth: Int,
            val sourceHeight: Int,
            val stable: Boolean
        ) : LiveEdgeState()
    }

    private val _liveEdgeState = MutableStateFlow<LiveEdgeState>(LiveEdgeState.Searching)
    val liveEdgeState: StateFlow<LiveEdgeState> = _liveEdgeState.asStateFlow()

    private val _autoCaptureEnabled = MutableStateFlow(false)
    val autoCaptureEnabled: StateFlow<Boolean> = _autoCaptureEnabled.asStateFlow()

    fun setAutoCaptureEnabled(enabled: Boolean) {
        _autoCaptureEnabled.value = enabled
        stableFrameCount = 0
    }

    private var analyzingFrame = false
    private var lastAnalysisAt = 0L
    private var lastStableCorners: List<EdgePointF>? = null
    private var stableFrameCount = 0
    private var autoCaptureFired = false

    private val ANALYSIS_INTERVAL_MS = 350L
    private val STABILITY_FRAMES_REQUIRED = 4
    private val STABILITY_THRESHOLD_FRACTION = 0.02f // 2% of image diagonal

    /**
     * Called once per camera frame from ImageAnalysis. Cheap to call every frame: it
     * throttles internally (both by time and by "still processing the last one") so
     * expensive OpenCV detection never runs back-to-back and never blocks the analyzer
     * thread from returning quickly, keeping the preview responsive. [downscaledBitmap]
     * should already be a small preview-resolution bitmap, not a full-res capture frame
     * -- running edge detection at full camera resolution every fraction of a second
     * would be the "extremely expensive full-resolution processing every frame" this
     * was explicitly supposed to avoid.
     */
    fun onAnalyzedFrame(downscaledBitmap: Bitmap, sourceWidth: Int, sourceHeight: Int) {
        val now = System.currentTimeMillis()
        if (analyzingFrame || now - lastAnalysisAt < ANALYSIS_INTERVAL_MS) return
        if (_isProcessing.value) return // don't fight an in-progress capture/import
        analyzingFrame = true
        lastAnalysisAt = now

        viewModelScope.launch(Dispatchers.Default) {
            val result: EdgeDetectionResult = try {
                detectEdgesUseCase(downscaledBitmap)
            } catch (e: Exception) {
                analyzingFrame = false
                return@launch
            }

            if (!result.hasDetectedCorners || result.confidence < 0.4f) {
                stableFrameCount = 0
                lastStableCorners = null
                autoCaptureFired = false
                _liveEdgeState.value = LiveEdgeState.Searching
                analyzingFrame = false
                return@launch
            }

            val diagonal = kotlin.math.hypot(sourceWidth.toFloat(), sourceHeight.toFloat())
            val prev = lastStableCorners
            val isSimilar = prev != null && prev.size == result.corners.size &&
                prev.zip(result.corners).all { (a, b) ->
                    kotlin.math.hypot(a.x - b.x, a.y - b.y) < diagonal * STABILITY_THRESHOLD_FRACTION
                }

            stableFrameCount = if (isSimilar) stableFrameCount + 1 else 1
            lastStableCorners = result.corners
            val isStable = stableFrameCount >= STABILITY_FRAMES_REQUIRED

            _liveEdgeState.value = LiveEdgeState.DocumentDetected(
                corners = result.corners,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                stable = isStable
            )

            if (isStable && _autoCaptureEnabled.value && !autoCaptureFired) {
                autoCaptureFired = true
                _autoCaptureTrigger.value = _autoCaptureTrigger.value + 1
            }
            if (!isStable) autoCaptureFired = false

            analyzingFrame = false
        }
    }

    // Incrementing counter (not a plain event flow) so the screen can observe it via
    // LaunchedEffect(keyed on the value) and fire exactly once per stable detection,
    // including the very first one, without needing a separate SharedFlow/channel.
    private val _autoCaptureTrigger = MutableStateFlow(0)
    val autoCaptureTrigger: StateFlow<Int> = _autoCaptureTrigger.asStateFlow()

    fun resetLiveDetection() {
        stableFrameCount = 0
        lastStableCorners = null
        autoCaptureFired = false
        _liveEdgeState.value = LiveEdgeState.Searching
    }

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

    /**
     * Runs perspective correction on the current page's original (uncropped) capture
     * using manually-adjusted corners, replacing just the processed `bitmap` -- the
     * `originalBitmap` is untouched, so re-adjusting corners again (or resetting) is
     * always possible without any quality loss from repeated re-compression.
     *
     * DocumentScannerEngine.perspectiveCorrect()/detectDocumentCorners() already
     * existed and are what the automatic scanDocument() pipeline uses internally, but
     * there was no way to call them with a user-supplied quad -- automatic detection is
     * good but not perfect, and the task explicitly calls out manual corner correction
     * as "essential when automatic detection is imperfect".
     */
    fun adjustCornersCurrent(corners: List<android.graphics.PointF>) {
        val page = _currentPage.value ?: return
        if (corners.size != 4) return
        viewModelScope.launch {
            _isProcessing.value = true
            val corrected = scannerEngine.perspectiveCorrect(page.originalBitmap, corners)
            val updated = page.copy(bitmap = corrected)
            updatePage(updated)
            _currentPage.value = updated
            _isProcessing.value = false
        }
    }

    /** Best-guess starting corners for the corner-adjustment UI: detected quad, or the full image bounds if detection fails. */
    suspend fun detectCornersForCurrent(): List<android.graphics.PointF> {
        val page = _currentPage.value ?: return emptyList()
        return withContext(Dispatchers.Default) {
            scannerEngine.detectDocumentCorners(page.originalBitmap) ?: listOf(
                android.graphics.PointF(0f, 0f),
                android.graphics.PointF(page.originalBitmap.width.toFloat(), 0f),
                android.graphics.PointF(page.originalBitmap.width.toFloat(), page.originalBitmap.height.toFloat()),
                android.graphics.PointF(0f, page.originalBitmap.height.toFloat())
            )
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
