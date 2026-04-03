// FILE: DocumentScannerActivity.kt  (FULL REPLACEMENT -- v4.0)
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/ui/scanner/DocumentScannerActivity.kt
//
// FEATURES ADDED IN THIS VERSION:
//   1. Auto edge detection (pure Kotlin Canny -- no OpenCV)
//   2. EdgeOverlayView renders detected quad in real time
//   3. Blur detection -- shows warning, blocks capture
//   4. Perspective warp on capture (via EdgeDetectionProcessor.perspectiveWarp)
//   [unchanged] CameraX, tap-to-focus, flash, filter buttons
//
// RULES OBEYED:
//   - No JitPack
//   - Explicit import androidx.camera.core.Camera (rule #13)
//   - No .use{} on PdfDocument (not used here)
//   - All floats use f suffix
//   - Pure ASCII
//   - scrollbars set via isVerticalScrollBarEnabled (rule #14)
//   - No FrameLayout.LayoutParams(w,h,weight) (rule #10)

package com.propdf.editor.ui.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera   // explicit import -- rule #13
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.data.repository.EdgeDetectionProcessor
import com.propdf.editor.data.repository.ScannerProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var scannerProcessor: ScannerProcessor
    @Inject lateinit var edgeDetector: EdgeDetectionProcessor

    // CameraX objects
    private lateinit var previewView: PreviewView
    private lateinit var edgeOverlay: EdgeOverlayView   // quad overlay
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null                  // rule #13: explicit type

    // Filter label shown below preview
    private lateinit var tvFilter: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    // Scanned pages accumulation
    private val scannedFiles = mutableListOf<File>()

    // Current filter: "original" | "grayscale" | "bw" | "enhance"
    private var activeFilter = "original"

    // Edge detection state (updated by analysis thread)
    private var lastQuad: EdgeDetectionProcessor.Quad? = null
    private var lastBlurry: Boolean = false

    // Auto-capture gate: prevent rapid-fire captures
    private var autoCapturePending = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    // -----------------------------------------------------------------------
    // LIFECYCLE
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // -----------------------------------------------------------------------
    // UI BUILD  (programmatic, matching existing app style)
    // -----------------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUI() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Preview fills the screen
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)  // rule #10: no weight
        }
        root.addView(previewView)

        // Edge detection overlay (transparent, same size)
        edgeOverlay = EdgeOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        root.addView(edgeOverlay)

        // Tap-to-focus: separate transparent layer (rule from bug #22)
        val focusTapOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.TRANSPARENT)
        }
        root.addView(focusTapOverlay)

        // Bottom control bar
        val controlBar = buildControlBar()
        controlBar.layoutParams = FrameLayout.LayoutParams(-1, dp(160)).apply {
            gravity = Gravity.BOTTOM
        }
        root.addView(controlBar)

        // Status bar (top)
        tvStatus = TextView(this).apply {
            text = "Point at a document"
            textSize = 13f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(16), dp(8), dp(16), dp(8))
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
                setMargins(0, dp(56), 0, 0)
            }
        }
        root.addView(tvStatus)

        // Progress bar for processing
        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.CENTER
            }
        }
        root.addView(progressBar)

        setContentView(root)

        // Tap-to-focus wiring (separate overlay avoids blocking capture button)
        focusTapOverlay.setOnTouchListener { _, event ->
            val factory = previewView.meteringPointFactory
            val point  = factory.createPoint(event.x, event.y)
            val action = FocusMeteringAction.Builder(point).build()
            camera?.cameraControl?.startFocusAndMetering(action)
            false
        }
    }

    private fun buildControlBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }

        // Filter selector row
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        listOf("Original", "Grayscale", "B&W", "Enhance").forEach { lbl ->
            filterRow.addView(makeFilterChip(lbl))
        }
        bar.addView(filterRow)

        // Capture row: flash | capture FAB | auto-capture toggle | done
        val captureRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
        }

        // Flash button
        val btnFlash = makeTextBtn("Flash OFF") { btn ->
            camera?.cameraControl?.enableTorch(!(camera?.cameraInfo?.torchState?.value == 1))
            btn.text = if (camera?.cameraInfo?.torchState?.value == 1) "Flash ON" else "Flash OFF"
        }
        btnFlash.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

        // Capture FAB
        val fabCapture = FloatingActionButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                Color.parseColor("#1A73E8")
            )
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72)).apply {
                setMargins(dp(16), 0, dp(16), 0)
            }
            setOnClickListener { captureImage() }
        }

        // Auto-capture toggle
        val btnAuto = makeTextBtn("Auto: OFF") { btn ->
            autoCapturePending = !autoCapturePending
            btn.text = if (autoCapturePending) "Auto: ON" else "Auto: OFF"
        }
        btnAuto.layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

        captureRow.addView(btnFlash)
        captureRow.addView(fabCapture)
        captureRow.addView(btnAuto)
        bar.addView(captureRow)

        // Page count + Done row
        val doneRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, dp(8), 0, 0)
            }
        }
        tvFilter = TextView(this).apply {
            text = "0 pages captured"
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val btnDone = makeTextBtn("Done") {
            if (scannedFiles.isEmpty()) {
                Toast.makeText(this, "Capture at least one page", Toast.LENGTH_SHORT).show()
            } else {
                goToSaveScreen()
            }
        }
        doneRow.addView(tvFilter)
        doneRow.addView(btnDone)
        bar.addView(doneRow)

        return bar
    }

    private fun makeFilterChip(label: String): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(5), dp(10), dp(5))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
            setOnClickListener {
                activeFilter = label.lowercase().replace("&", "").replace(" ", "")
                Toast.makeText(this@DocumentScannerActivity, "$label filter", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun makeTextBtn(label: String, onClick: (TextView) -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onClick(this) }
        }
    }

    // -----------------------------------------------------------------------
    // CAMERAX
    // -----------------------------------------------------------------------

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            // ImageAnalysis: run edge detection on every frame (throttled)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(cameraExecutor) { proxy -> analyzeFrame(proxy) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(        // rule #13: store result
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, analysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -----------------------------------------------------------------------
    // FRAME ANALYSIS (edge detection + blur, runs on cameraExecutor)
    // -----------------------------------------------------------------------

    private var lastAnalysisMs = 0L

    private fun analyzeFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastAnalysisMs < 400L) {   // 400ms throttle (~2.5 fps)
            proxy.close(); return
        }
        lastAnalysisMs = now

        val bmp = proxy.toBitmap()
        proxy.close()

        lifecycleScope.launch(Dispatchers.Default) {
            val result = edgeDetector.detectDocumentCorners(bmp)
            bmp.recycle()

            lastQuad   = result.quad
            lastBlurry = result.isBlurry

            // Update overlay (post to UI thread is inside EdgeOverlayView.updateDetection)
            edgeOverlay.updateDetection(
                result.quad, bmp.width, bmp.height, result.isBlurry
            )

            withContext(Dispatchers.Main) {
                tvStatus.text = when {
                    result.isBlurry -> "Hold still -- image is blurry"
                    result.quad != null -> "Document detected -- tap capture"
                    else -> "Point at a document"
                }

                // Auto-capture: fire when quad found, not blurry, gate open
                if (autoCapturePending && result.quad != null && !result.isBlurry) {
                    autoCapturePending = false   // one shot per toggle
                    captureImage()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // CAPTURE
    // -----------------------------------------------------------------------

    private fun captureImage() {
        val cap = imageCapture ?: return

        if (lastBlurry) {
            Toast.makeText(this, "Image is blurry -- hold still", Toast.LENGTH_SHORT).show()
            return
        }

        progressBar.visibility = View.VISIBLE
        val outFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val opts    = ImageCapture.OutputFileOptions.Builder(outFile).build()

        cap.takePicture(opts, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch {
                        processCapture(outFile)
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@DocumentScannerActivity,
                        "Capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private suspend fun processCapture(rawFile: File) = withContext(Dispatchers.Default) {
        try {
            var bmp = BitmapFactory.decodeFile(rawFile.absolutePath)

            // 1. Perspective warp if quad detected
            val q = lastQuad
            if (q != null) {
                val warped = edgeDetector.perspectiveWarp(bmp, q)
                bmp.recycle()
                bmp = warped
            }

            // 2. Apply selected filter
            bmp = when (activeFilter) {
                "grayscale" -> scannerProcessor.toGrayscale(bmp)
                "bw", "b&w" -> scannerProcessor.toBinaryBlackWhite(bmp)
                "enhance"   -> scannerProcessor.enhanceDocument(bmp)
                else        -> bmp
            }

            // 3. Save processed image
            val processedFile = File(cacheDir, "processed_${System.currentTimeMillis()}.jpg")
            processedFile.outputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            bmp.recycle()
            rawFile.delete()

            scannedFiles.add(processedFile)

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                tvFilter.text = "${scannedFiles.size} page(s) captured"
                Toast.makeText(
                    this@DocumentScannerActivity,
                    "Page ${scannedFiles.size} added",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                Toast.makeText(this@DocumentScannerActivity, "Processing error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // -----------------------------------------------------------------------
    // NAVIGATION
    // -----------------------------------------------------------------------

    private fun goToSaveScreen() {
        val paths = scannedFiles.map { it.absolutePath }.toTypedArray()
        val intent = Intent(this, ScanSaveActivity::class.java).apply {
            putExtra(ScanSaveActivity.EXTRA_PATHS, paths)
        }
        startActivity(intent)
        finish()
    }

    // -----------------------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------------------

    private fun dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "DocumentScannerActivity"
    }
}
