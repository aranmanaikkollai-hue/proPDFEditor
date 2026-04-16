package com.propdf.editor.ui.scanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.ScannerProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var scannerProcessor: ScannerProcessor

    private lateinit var previewView: PreviewView
    private lateinit var edgeOverlay: EdgeDetectionOverlay
    private lateinit var colorBtns: Array<TextView>

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private var selectedColorMode = "auto"

    private val cameraExecutor = Executors.newSingleThreadExecutor()

    private val galleryLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch { processGalleryImage(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScannerUI()
        requestCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // -------------------------------------------------------
    // UI
    // -------------------------------------------------------

    private fun buildScannerUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }

        // Camera preview
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView)

        // Edge detection overlay (transparent, drawn on top of preview)
        edgeOverlay = EdgeDetectionOverlay(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        root.addView(edgeOverlay)

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = FrameLayout.LayoutParams(-1, dp(56)).apply {
                gravity = Gravity.TOP
            }
        }
        topBar.addView(buildIconBtn(android.R.drawable.ic_media_previous) { finish() })
        topBar.addView(TextView(this).apply {
            text = "Document Scanner"
            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) }
        })
        topBar.addView(buildIconBtn(android.R.drawable.ic_menu_camera) { toggleTorch() })
        topBar.addView(buildIconBtn(android.R.drawable.ic_menu_gallery) {
            galleryLauncher.launch("image/*")
        })
        root.addView(topBar)

        // Color mode selector
        val colorBar = buildColorModeBar()
        colorBar.layoutParams = FrameLayout.LayoutParams(-2, -2).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(120)
        }
        root.addView(colorBar)

        // Capture button
        val captureBtn = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(72), dp(72)).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(32)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.WHITE)
                setStroke(dp(4), Color.parseColor("#448AFF"))
            }
            setOnClickListener { captureImage() }
        }
        root.addView(captureBtn)

        // Touch-to-focus on preview
        // FIX: return false so CameraX also receives the event
        previewView.setOnTouchListener { _, event ->
            handleFocusTouch(event)
            false   // must return false so CameraX handles the rest
        }

        setContentView(root)
    }

    private fun buildIconBtn(iconRes: Int, onClick: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            setImageResource(iconRes)
            setBackgroundColor(Color.TRANSPARENT)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }
    }

    private fun buildColorModeBar(): LinearLayout {
        val modes = listOf(
            "auto" to "AUTO", "color" to "COLOR",
            "gray" to "GRAY", "bw" to "B+W"
        )
        colorBtns = Array(modes.size) { TextView(this) }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC1A1A1A"))
                cornerRadius = dp(24).toFloat()
            }

            modes.forEachIndexed { idx, (mode, label) ->
                val btn = TextView(this@DocumentScannerActivity).apply {
                    text = label
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                    gravity = Gravity.CENTER
                    setTextColor(if (mode == selectedColorMode) Color.parseColor("#002E69") else Color.WHITE)
                    background = if (mode == selectedColorMode) {
                        GradientDrawable().apply {
                            colors = intArrayOf(Color.parseColor("#ADC6FF"), Color.parseColor("#4B8EFF"))
                            cornerRadius = dp(16).toFloat()
                        }
                    } else null
                    setOnClickListener {
                        selectedColorMode = mode
                        refreshColorButtons()
                    }
                }
                colorBtns[idx] = btn
                addView(btn)
            }
        }
    }

    private fun refreshColorButtons() {
        val modes = listOf("auto", "color", "gray", "bw")
        colorBtns.forEachIndexed { idx, btn ->
            val isSelected = modes[idx] == selectedColorMode
            btn.setTextColor(if (isSelected) Color.parseColor("#002E69") else Color.WHITE)
            btn.background = if (isSelected) {
                GradientDrawable().apply {
                    colors = intArrayOf(Color.parseColor("#ADC6FF"), Color.parseColor("#4B8EFF"))
                    cornerRadius = dp(16).toFloat()
                }
            } else null
        }
    }

    // -------------------------------------------------------
    // CAMERA
    // -------------------------------------------------------

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this,
                android.Manifest.permission.CAMERA) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            androidx.core.app.ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), 100
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 &&
            grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Image analysis for real-time edge detection
            val analysis = androidx.camera.core.ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .build()
            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                processFrameForEdges(imageProxy)
                imageProxy.close()
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview, imageCapture, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // TAP TO FOCUS - FIXED
    // Previously returned true which consumed the touch event.
    // Now returns false so CameraX preview can also receive it.
    // -------------------------------------------------------

    private fun handleFocusTouch(event: MotionEvent) {
        if (event.action != MotionEvent.ACTION_UP) return
        val cam = camera ?: return
        val factory = previewView.meteringPointFactory
        val point = factory.createPoint(event.x, event.y)
        val action = FocusMeteringAction.Builder(point).build()
        try {
            cam.cameraControl.startFocusAndMetering(action)
            edgeOverlay.showFocusRing(event.x, event.y)
        } catch (e: Exception) {
            // Camera not ready - ignore
        }
    }

    // -------------------------------------------------------
    // AUTO EDGE DETECTION
    // Analyzes camera frames at reduced resolution to find
    // the document corners. Draws an animated quad overlay.
    // -------------------------------------------------------

    private fun processFrameForEdges(imageProxy: ImageProxy) {
        val bmp = imageProxyToBitmap(imageProxy) ?: return

        // Scale down for fast processing
        val scale = 0.25f
        val small = Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * scale).toInt().coerceAtLeast(1),
            (bmp.height * scale).toInt().coerceAtLeast(1),
            false
        )
        bmp.recycle()

        val corners = detectDocumentCorners(small, imageProxy.width, imageProxy.height)
        small.recycle()

        runOnUiThread {
            edgeOverlay.updateCorners(corners)
        }
    }

    /**
     * Detects document corners by finding the bounding region of
     * high-contrast pixels, scaled back to full preview dimensions.
     */
    private fun detectDocumentCorners(
        smallBmp: Bitmap,
        fullW: Int, fullH: Int
    ): Array<PointF>? {
        val w = smallBmp.width
        val h = smallBmp.height
        if (w <= 0 || h <= 0) return null

        val pixels = IntArray(w * h)
        smallBmp.getPixels(pixels, 0, w, 0, 0, w, h)

        // Convert to grayscale and build edge map
        var minX = w; var minY = h; var maxX = 0; var maxY = 0
        var edgeCount = 0

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c   = pixels[y * w + x]
                val cR  = pixels[y * w + (x + 1)]
                val cD  = pixels[(y + 1) * w + x]
                val grayC = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
                val grayR = (Color.red(cR) * 299 + Color.green(cR) * 587 + Color.blue(cR) * 114) / 1000
                val grayD = (Color.red(cD) * 299 + Color.green(cD) * 587 + Color.blue(cD) * 114) / 1000
                val edge = kotlin.math.abs(grayC - grayR) + kotlin.math.abs(grayC - grayD)
                if (edge > 30) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                    edgeCount++
                }
            }
        }

        // Not enough edges = no document found
        val minEdgeFraction = (w * h * 0.01).toInt()
        if (edgeCount < minEdgeFraction) return null
        if (maxX <= minX || maxY <= minY) return null

        // Add margin
        val margin = 2
        minX = (minX - margin).coerceAtLeast(0)
        minY = (minY - margin).coerceAtLeast(0)
        maxX = (maxX + margin).coerceAtMost(w - 1)
        maxY = (maxY + margin).coerceAtMost(h - 1)

        // Scale back to preview dimensions
        val scaleX = fullW.toFloat() / w.toFloat()
        val scaleY = fullH.toFloat() / h.toFloat()

        // Map to actual view size using PreviewView dimensions
        val viewW = previewView.width.toFloat().coerceAtLeast(1f)
        val viewH = previewView.height.toFloat().coerceAtLeast(1f)
        val ratioX = viewW / fullW.toFloat()
        val ratioY = viewH / fullH.toFloat()

        return arrayOf(
            PointF(minX * scaleX * ratioX, minY * scaleY * ratioY),   // top-left
            PointF(maxX * scaleX * ratioX, minY * scaleY * ratioY),   // top-right
            PointF(maxX * scaleX * ratioX, maxY * scaleY * ratioY),   // bottom-right
            PointF(minX * scaleX * ratioX, maxY * scaleY * ratioY)    // bottom-left
        )
    }

    /**
     * Safe bitmap extraction from ImageProxy.
     * Uses BitmapFactory.decodeByteArray on plane[0] bytes.
     * NEVER divides by pixelStride (can be 0 for YUV_420_888).
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer: ByteBuffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------
    // CAPTURE
    // -------------------------------------------------------

    private fun captureImage() {
        val capture = imageCapture ?: return
        capture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bmp = imageProxyToBitmap(image)
                image.close()
                if (bmp != null) {
                    lifecycleScope.launch {
                        processAndScan(bmp)
                    }
                }
            }
            override fun onError(exc: ImageCaptureException) {
                runOnUiThread {
                    Toast.makeText(
                        this@DocumentScannerActivity,
                        "Capture failed: ${exc.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        })
    }

    private suspend fun processAndScan(bmp: Bitmap) {
        withContext(Dispatchers.Main) {
            Toast.makeText(this@DocumentScannerActivity, "Processing...", Toast.LENGTH_SHORT).show()
        }
        try {
            val processed = withContext(Dispatchers.IO) {
                scannerProcessor.processImage(bmp, selectedColorMode)
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(this@DocumentScannerActivity, "Scan complete", Toast.LENGTH_SHORT).show()
                // TODO: pass processed bitmap to PDF creation flow
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@DocumentScannerActivity,
                    "Processing error: ${e.message}", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private suspend fun processGalleryImage(uri: android.net.Uri) {
        try {
            val bmp = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            if (bmp != null) processAndScan(bmp)
        } catch (e: Exception) {
            Toast.makeText(this, "Gallery error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // -------------------------------------------------------
    // TORCH
    // -------------------------------------------------------

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        cam.cameraControl.enableTorch(torchOn)
    }

    // -------------------------------------------------------
    // HELPER
    // -------------------------------------------------------

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

// -------------------------------------------------------
// EDGE DETECTION OVERLAY VIEW
// Draws the detected document quad and focus ring.
// -------------------------------------------------------

class EdgeDetectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var corners: Array<PointF>? = null
    private var focusX = 0f
    private var focusY = 0f
    private var showFocus = false
    private var focusAlpha = 255

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ADC6FF")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4B8EFF")
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1AADC6FF")
        style = Paint.Style.FILL
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    fun updateCorners(newCorners: Array<PointF>?) {
        corners = newCorners
        invalidate()
    }

    fun showFocusRing(x: Float, y: Float) {
        focusX = x; focusY = y
        showFocus = true; focusAlpha = 255
        invalidate()
        postDelayed({
            showFocus = false; invalidate()
        }, 1200)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pts = corners
        if (pts != null && pts.size == 4) {
            // Filled quad
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                lineTo(pts[1].x, pts[1].y)
                lineTo(pts[2].x, pts[2].y)
                lineTo(pts[3].x, pts[3].y)
                close()
            }
            canvas.drawPath(path, fillPaint)

            // Outline
            edgePaint.alpha = 200
            canvas.drawPath(path, edgePaint)

            // Corner L-brackets
            val arm = 28f
            drawCornerBracket(canvas, pts[0], arm, 1f, 1f)   // TL
            drawCornerBracket(canvas, pts[1], arm, -1f, 1f)  // TR
            drawCornerBracket(canvas, pts[2], arm, -1f, -1f) // BR
            drawCornerBracket(canvas, pts[3], arm, 1f, -1f)  // BL
        }

        // Focus ring
        if (showFocus) {
            focusPaint.alpha = focusAlpha
            canvas.drawCircle(focusX, focusY, 60f, focusPaint)
            canvas.drawLine(focusX - 20f, focusY, focusX + 20f, focusY, focusPaint)
            canvas.drawLine(focusX, focusY - 20f, focusX, focusY + 20f, focusPaint)
        }
    }

    private fun drawCornerBracket(
        canvas: Canvas, pt: PointF, arm: Float, dx: Float, dy: Float
    ) {
        canvas.drawLine(pt.x, pt.y, pt.x + arm * dx, pt.y, cornerPaint)
        canvas.drawLine(pt.x, pt.y, pt.x, pt.y + arm * dy, cornerPaint)
    }
}
