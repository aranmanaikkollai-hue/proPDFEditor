package com.propdfeditor.scanner.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.common.util.concurrent.ListenableFuture
import com.propdfeditor.scanner.R
import com.propdfeditor.scanner.domain.model.*
import com.propdfeditor.scanner.presentation.components.EdgeOverlayView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Premium scanner activity with CameraX integration.
 * Features:
 * - Real-time edge detection overlay
 * - Tap to focus
 * - Pinch to zoom
 * - Auto-capture on stable detection
 * - Flash toggle
 * - Mode selector (Document, Receipt, Whiteboard, Batch)
 * - Smooth transitions between capture and review
 */
@AndroidEntryPoint
class ScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ScannerActivity"
        private const val AUTO_CAPTURE_DELAY_MS = 800L
        private const val EDGE_PREVIEW_INTERVAL_MS = 300L
        const val EXTRA_BATCH_SESSION_ID = "batch_session_id"
        const val EXTRA_PAGE_COUNT = "page_count"
    }

    @Inject
    lateinit var viewModel: ScannerViewModel

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var edgeOverlayView: EdgeOverlayView
    private lateinit var captureButton: ImageButton
    private lateinit var flashButton: ImageButton
    private lateinit var modeSelector: LinearLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var processingOverlay: LinearLayout
    private lateinit var reviewContainer: FrameLayout
    private lateinit var reviewImageView: ImageView
    private lateinit var addPageButton: ImageButton
    private lateinit var doneButton: ImageButton
    private lateinit var retakeButton: ImageButton
    private lateinit var batchCounterText: TextView

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var cameraProvider: ProcessCameraProvider? = null

    private var isAutoCapturing = false
    private var edgeDetectionJob: Job? = null
    private var consecutiveStableFrames = 0
    private val STABLE_FRAME_THRESHOLD = 5

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createRootLayout())
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupClickListeners()
        observeViewModel()
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        viewModel.initializeScanner()
    }

    private fun createRootLayout(): FrameLayout {
        return FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )

            // Camera preview
            previewView = PreviewView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            addView(previewView)

            // Edge detection overlay
            edgeOverlayView = EdgeOverlayView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            addView(edgeOverlayView)

            // Top controls bar
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 48
                    leftMargin = 24
                    rightMargin = 24
                }

                flashButton = ImageButton(context).apply {
                    setImageResource(R.drawable.ic_flash_auto)
                    background = null
                    setPadding(16, 16, 16, 16)
                }
                addView(flashButton)

                batchCounterText = TextView(context).apply {
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    visibility = View.GONE
                }
                addView(batchCounterText)
            })

            // Mode selector
            modeSelector = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
                    topMargin = 120
                }
            }
            setupModeSelector()
            addView(modeSelector)

            // Bottom capture button
            addView(FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = android.view.Gravity.BOTTOM
                    bottomMargin = 48
                }

                captureButton = ImageButton(context).apply {
                    layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                        gravity = android.view.Gravity.CENTER
                    }
                    setImageResource(R.drawable.ic_capture)
                    background = null
                }
                addView(captureButton)
            })

            // Processing overlay
            processingOverlay = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xCC000000.toInt())
                visibility = View.GONE
                gravity = android.view.Gravity.CENTER

                progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(400, 20).apply {
                        bottomMargin = 16
                    }
                }
                addView(progressBar)

                progressText = TextView(context).apply {
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    text = "Processing..."
                }
                addView(progressText)
            }
            addView(processingOverlay)

            // Review container
            reviewContainer = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(0xFF000000.toInt())
                visibility = View.GONE

                reviewImageView = ImageView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                addView(reviewImageView)

                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL
                        bottomMargin = 48
                    }

                    retakeButton = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_retake)
                        background = null
                        setPadding(24, 24, 24, 24)
                    }
                    addView(retakeButton)

                    addPageButton = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_add_page)
                        background = null
                        setPadding(24, 24, 24, 24)
                    }
                    addView(addPageButton)

                    doneButton = ImageButton(context).apply {
                        setImageResource(R.drawable.ic_done)
                        background = null
                        setPadding(24, 24, 24, 24)
                    }
                    addView(doneButton)
                })
            }
            addView(reviewContainer)
        }
    }

    private fun setupModeSelector() {
        val modes = listOf(
            ScanMode.AUTO to "Auto",
            ScanMode.DOCUMENT to "Doc",
            ScanMode.RECEIPT to "Receipt",
            ScanMode.WHITEBOARD to "Board",
            ScanMode.BATCH to "Batch"
        )
        modes.forEach { (mode, label) ->
            val btn = TextView(this).apply {
                text = label
                textSize = 14f
                setPadding(24, 12, 24, 12)
                setTextColor(0xFFFFFFFF.toInt())
                setBackgroundColor(0x44000000.toInt())
                setOnClickListener {
                    viewModel.setScanMode(mode)
                    highlightModeButton(this, modes.map { it.second })
                }
            }
            modeSelector.addView(btn)
        }
    }

    private fun highlightModeButton(selected: TextView, allLabels: List<String>) {
        for (i in 0 until modeSelector.childCount) {
            val child = modeSelector.getChildAt(i) as TextView
            child.setBackgroundColor(
                if (child == selected) 0xFF2196F3.toInt() else 0x44000000.toInt()
            )
        }
    }

    private fun setupClickListeners() {
        captureButton.setOnClickListener { captureImage() }
        flashButton.setOnClickListener { viewModel.toggleFlash() }
        retakeButton.setOnClickListener {
            reviewContainer.visibility = View.GONE
            viewModel.resetScanner()
        }
        addPageButton.setOnClickListener { viewModel.addToBatchAndContinue() }
        doneButton.setOnClickListener { viewModel.completeBatch() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is ScannerUiState.Preview -> {
                                reviewContainer.visibility = View.GONE
                                processingOverlay.visibility = View.GONE
                                captureButton.isEnabled = true
                            }
                            is ScannerUiState.Processing -> {
                                processingOverlay.visibility = View.VISIBLE
                                progressText.text = state.stage
                                captureButton.isEnabled = false
                            }
                            is ScannerUiState.Review -> {
                                processingOverlay.visibility = View.GONE
                                reviewContainer.visibility = View.VISIBLE
                                loadReviewImage(state.page)
                            }
                            is ScannerUiState.BatchReview -> {
                                finishWithResult(state.session)
                            }
                            is ScannerUiState.Error -> {
                                Toast.makeText(this@ScannerActivity, state.message, Toast.LENGTH_LONG).show()
                                if (state.recoverable) {
                                    viewModel.resetScanner()
                                } else {
                                    finish()
                                }
                            }
                            else -> {}
                        }
                    }
                }
                launch {
                    viewModel.processingProgress.collect { progress ->
                        progressBar.progress = progress
                    }
                }
                launch {
                    viewModel.batchSession.collect { session ->
                        val count = session?.pages?.size ?: 0
                        batchCounterText.visibility = if (count > 0) View.VISIBLE else View.GONE
                        batchCounterText.text = count.toString()
                    }
                }
                launch {
                    viewModel.isFlashOn.collect { isOn ->
                        flashButton.setImageResource(
                            if (isOn) R.drawable.ic_flash_on else R.drawable.ic_flash_auto
                        )
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Camera binding failed", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder()
            .setTargetResolution(Size(1920, 1080))
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetResolution(Size(1920, 1080))
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(Size(640, 480))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, EdgeDetectionAnalyzer()) }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                this, cameraSelector, preview, imageCapture, imageAnalysis
            )
            setupCameraGestures()
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed", e)
        }
    }

    private fun setupCameraGestures() {
        previewView.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    true
                }
                else -> false
            }
        }
    }

    private fun captureImage() {
        val imageCapture = imageCapture ?: return
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    image.close()

                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        ?: return

                    val rotation = image.imageInfo.rotationDegrees
                    val rotated = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap

                    runOnUiThread {
                        viewModel.onImageCaptured(this@ScannerActivity, rotated)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    runOnUiThread {
                        Toast.makeText(this@ScannerActivity, "Capture failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /**
     * Real-time edge detection analyzer for preview overlay.
     * Runs on camera executor thread, lightweight processing only.
     */
    private inner class EdgeDetectionAnalyzer : ImageAnalysis.Analyzer {
        private var lastProcessTime = 0L
        private var lastCorners: List<PointF>? = null

        override fun analyze(image: ImageProxy) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastProcessTime < EDGE_PREVIEW_INTERVAL_MS) {
                image.close()
                return
            }
            lastProcessTime = currentTime

            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            image.close()

            bitmap?.let {
                val scaled = Bitmap.createScaledBitmap(it, 320, 240, false)
                viewModel.previewEdgeDetection(scaled) { result ->
                    runOnUiThread {
                        edgeOverlayView.updateEdgeResult(result)

                        // Auto-capture logic
                        if (viewModel.cameraConfig.value.enableAutoCapture && result.confidence > 85) {
                            if (cornersAreStable(result.corners)) {
                                consecutiveStableFrames++
                                if (consecutiveStableFrames >= STABLE_FRAME_THRESHOLD && !isAutoCapturing) {
                                    isAutoCapturing = true
                                    captureImage()
                                }
                            } else {
                                consecutiveStableFrames = 0
                            }
                        } else {
                            consecutiveStableFrames = 0
                        }
                        lastCorners = result.corners
                    }
                }
            }
        }

        private fun cornersAreStable(current: List<PointF>): Boolean {
            val last = lastCorners ?: return false
            if (last.size != 4 || current.size != 4) return false
            val threshold = 15f // pixels at 320x240 scale
            return current.zip(last).all { (c, l) ->
                kotlin.math.abs(c.x - l.x) < threshold && kotlin.math.abs(c.y - l.y) < threshold
            }
        }
    }

    private fun loadReviewImage(page: ScannedPage) {
        page.processedUri?.let { uri ->
            reviewImageView.setImageURI(uri)
        }
    }

    private fun finishWithResult(session: BatchScanSession) {
        setResult(RESULT_OK, android.content.Intent().apply {
            putExtra(EXTRA_BATCH_SESSION_ID, session.id)
            putExtra(EXTRA_PAGE_COUNT, session.pages.size)
        })
        finish()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        edgeDetectionJob?.cancel()
    }
}
