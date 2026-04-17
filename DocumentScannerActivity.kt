package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class DocumentScannerActivity : AppCompatActivity() {

    // -------------------------------------------------------
    // STATE
    // -------------------------------------------------------
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView
    private lateinit var edgeOverlay: EdgeDetectionOverlay
    private lateinit var focusRingView: FocusRingView

    private var torchOn = false
    private var colorMode = "auto"   // "auto" | "color" | "gray" | "bw"
    private val colorModes = listOf("auto", "color", "gray", "bw")
    private var colorModeIdx = 0
    private val colorBtnLabels = listOf("AUTO", "COLOR", "GRAY", "B&W")
    private val colorBtns = arrayOfNulls<TextView>(4)

    private val capturedPages = mutableListOf<Bitmap>()

    // -------------------------------------------------------
    // PERMISSION
    // -------------------------------------------------------
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else { toast("Camera permission required"); finish() }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                if (bmp != null) processAndAddPage(bmp)
            }
        }
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // -------------------------------------------------------
    // UI CONSTRUCTION
    // -------------------------------------------------------

    private fun buildUI() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        // Camera preview
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(previewView)

        // Edge detection overlay (draw corners + quad outline)
        edgeOverlay = EdgeDetectionOverlay(this)
        root.addView(edgeOverlay, FrameLayout.LayoutParams(-1, -1))

        // Focus ring overlay
        focusRingView = FocusRingView(this)
        root.addView(focusRingView, FrameLayout.LayoutParams(-1, -1))

        // Tap-to-focus on preview -- CRITICAL: return false so camera still gets events
        previewView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                handleTapToFocus(ev.x, ev.y)
                focusRingView.showAt(ev.x, ev.y)
            }
            false   // do NOT consume -- must return false
        }

        // Bottom controls panel
        root.addView(buildBottomControls(), FrameLayout.LayoutParams(
            -1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))

        // Top controls (flash + gallery)
        root.addView(buildTopControls(), FrameLayout.LayoutParams(
            -1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))
    }

    private fun buildTopControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.argb(160, 0, 0, 0))

            // Back
            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back") {
                finish()
            })

            // Title
            addView(TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = "Scan Document"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            })

            // Flash toggle
            addView(buildIconBtn(android.R.drawable.ic_menu_view, "Flash") {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            })

            // Gallery pick
            addView(buildIconBtn(android.R.drawable.ic_menu_gallery, "Gallery") {
                galleryLauncher.launch("image/*")
            })
        }
    }

    private fun buildBottomControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setPadding(dp(16), dp(8), dp(16), dp(24))

            // Color mode selector row
            val modeRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            }
            colorModes.forEachIndexed { i, _ ->
                val btn = TextView(this@DocumentScannerActivity).apply {
                    text = colorBtnLabels[i]
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (i == colorModeIdx) Color.BLACK else Color.WHITE)
                    gravity = Gravity.CENTER
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                    background = if (i == colorModeIdx) {
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.parseColor("#ADC6FF"))
                            cornerRadius = dp(16).toFloat()
                        }
                    } else {
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(Color.argb(100, 255, 255, 255))
                            cornerRadius = dp(16).toFloat()
                        }
                    }
                    setOnClickListener { switchColorMode(i) }
                }
                colorBtns[i] = btn
                modeRow.addView(btn)
            }
            addView(modeRow)

            // Capture row: page count | shutter | auto-edge toggle
            val captureRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            // Page count badge
            val pageCount = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 12f
                text = "0 pages"
            }
            captureRow.addView(pageCount)

            // Shutter button - large circle
            val shutter = View(this@DocumentScannerActivity).apply {
                val sz = dp(70)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = dp(24); marginEnd = dp(24)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(4), Color.parseColor("#ADC6FF"))
                }
                setOnClickListener {
                    captureImage { bmp ->
                        lifecycleScope.launch {
                            processAndAddPage(bmp)
                            pageCount.text = "${capturedPages.size} pages"
                        }
                    }
                }
            }
            captureRow.addView(shutter)

            // Done button
            val done = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER
                text = "DONE"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF"))
                setOnClickListener { finishScanning() }
            }
            captureRow.addView(done)
            addView(captureRow)

            // Auto edge detection toggle
            val edgeToggle = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
                gravity = Gravity.CENTER
                text = "AUTO EDGE DETECTION: ON"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF"))
                var edgeOn = true
                setOnClickListener {
                    edgeOn = !edgeOn
                    text = "AUTO EDGE DETECTION: ${if (edgeOn) "ON" else "OFF"}"
                    edgeOverlay.setEnabled(edgeOn)
                }
            }
            addView(edgeToggle)
        }
    }

    private fun buildIconBtn(iconRes: Int, desc: String, action: () -> Unit): ImageButton {
        return ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setImageResource(iconRes)
            colorFilter = PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = desc
            setOnClickListener { action() }
        }
    }

    // -------------------------------------------------------
    // CAMERA SETUP
    // -------------------------------------------------------

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val provider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
                // Start periodic auto edge detection on preview frames
                startEdgeDetectionLoop()
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // TAP TO FOCUS  -- the key fix: use MeteringPoint properly
    // -------------------------------------------------------

    private fun handleTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val factory = previewView.meteringPointFactory
            val point   = factory.createPoint(x, y)
            val action  = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (e: Exception) {
            // Camera not ready yet -- safe to ignore
        }
    }

    // -------------------------------------------------------
    // IMAGE CAPTURE
    // -------------------------------------------------------

    private fun captureImage(onDone: (Bitmap) -> Unit) {
        val ic = imageCapture ?: return
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val bmp = imageProxyToBitmap(proxy)
                    proxy.close()
                    if (bmp != null) onDone(bmp)
                    else toast("Capture failed")
                }
                override fun onError(exc: ImageCaptureException) {
                    toast("Capture error: ${exc.message}")
                }
            }
        )
    }

    // CRITICAL: use decodeByteArray on JPEG bytes -- never divide by pixelStride
    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val plane = proxy.planes[0]
            val buf   = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------
    // AUTO EDGE DETECTION
    // -------------------------------------------------------

    // Runs a lightweight edge-detect loop on scaled-down preview frames
    private fun startEdgeDetectionLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(800)  // check every 800 ms to avoid draining battery
                if (edgeOverlay.isDetectionEnabled) {
                    detectAndDrawEdges()
                }
            }
        }
    }

    private suspend fun detectAndDrawEdges() {
        // Capture a low-res preview bitmap for analysis
        val previewBmp = withContext(Dispatchers.Main) {
            previewView.bitmap
        } ?: return

        val corners = withContext(Dispatchers.Default) {
            findDocumentCorners(previewBmp)
        }

        withContext(Dispatchers.Main) {
            edgeOverlay.setCorners(corners, previewView.width, previewView.height)
        }
    }

    /**
     * Lightweight document corner finder.
     * Scales down, converts to grayscale, applies simple edge gradient,
     * then searches for the bounding quadrilateral of the brightest region.
     * This approximates Google ML Kit's document detection behaviour
     * without requiring Play Services.
     */
    private fun findDocumentCorners(src: Bitmap): Array<PointF>? {
        val maxDim = 320
        val scale  = min(maxDim.toFloat() / src.width, maxDim.toFloat() / src.height)
        val sw = (src.width  * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        // Convert to grayscale byte array
        val gray = ByteArray(sw * sh) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8)  and 0xFF
            val b = c and 0xFF
            ((0.299 * r + 0.587 * g + 0.114 * b).toInt()).coerceIn(0, 255).toByte()
        }

        // Simple Sobel gradient to find edges
        val edgeMap = BooleanArray(sw * sh)
        val threshold = 40
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val gx = (-gray[(y-1)*sw+(x-1)] - 2*gray[y*sw+(x-1)] - gray[(y+1)*sw+(x-1)]
                         + gray[(y-1)*sw+(x+1)] + 2*gray[y*sw+(x+1)] + gray[(y+1)*sw+(x+1)]).toInt()
                val gy = (-gray[(y-1)*sw+(x-1)] - 2*gray[(y-1)*sw+x] - gray[(y-1)*sw+(x+1)]
                         + gray[(y+1)*sw+(x-1)] + 2*gray[(y+1)*sw+x] + gray[(y+1)*sw+(x+1)]).toInt()
                val mag = sqrt((gx * gx + gy * gy).toDouble())
                edgeMap[y * sw + x] = mag > threshold
            }
        }

        // Find bounding box of the largest edge-connected region (document boundary)
        var minX = sw; var maxX = 0; var minY = sh; var maxY = 0
        var edgeCount = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (edgeMap[y * sw + x]) {
                    edgeCount++
                    if (x < minX) minX = x; if (x > maxX) maxX = x
                    if (y < minY) minY = y; if (y > maxY) maxY = y
                }
            }
        }

        // Require a meaningful edge region (> 5% of area) to avoid false positives
        if (edgeCount < sw * sh * 0.05) return null
        if ((maxX - minX) < sw * 0.3 || (maxY - minY) < sh * 0.3) return null

        // Scale corners back to preview dimensions
        val invScale = 1f / scale
        return arrayOf(
            PointF(minX * invScale, minY * invScale),   // top-left
            PointF(maxX * invScale, minY * invScale),   // top-right
            PointF(maxX * invScale, maxY * invScale),   // bottom-right
            PointF(minX * invScale, maxY * invScale)    // bottom-left
        )
    }

    // -------------------------------------------------------
    // IMAGE PROCESSING
    // -------------------------------------------------------

    private suspend fun processAndAddPage(raw: Bitmap) {
        val processed = withContext(Dispatchers.Default) {
            applyColorMode(raw)
        }
        capturedPages.add(processed)
        toast("Page ${capturedPages.size} added")
    }

    private fun applyColorMode(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        when (colorMode) {
            "gray" -> {
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8)  and 0xFF
                    val b = c and 0xFF
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    pixels[i] = Color.argb(0xFF, lum, lum, lum)
                }
            }
            "bw" -> {
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8)  and 0xFF
                    val b = c and 0xFF
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    val bw = if (lum > 128) 0xFF else 0x00
                    pixels[i] = Color.argb(0xFF, bw, bw, bw)
                }
            }
            "auto" -> {
                // Auto-enhance: boost contrast by stretching histogram
                var minL = 255; var maxL = 0
                for (p in pixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8)  and 0xFF
                    val b = p and 0xFF
                    val l = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
                    if (l < minL) minL = l; if (l > maxL) maxL = l
                }
                val range = (maxL - minL).coerceAtLeast(1)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = (((((c shr 16) and 0xFF) - minL) * 255.0 / range).toInt()).coerceIn(0, 255)
                    val g = (((((c shr 8)  and 0xFF) - minL) * 255.0 / range).toInt()).coerceIn(0, 255)
                    val b = ((((c and 0xFF)          - minL) * 255.0 / range).toInt()).coerceIn(0, 255)
                    pixels[i] = Color.argb(0xFF, r, g, b)
                }
            }
            // "color" = no processing
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    // -------------------------------------------------------
    // COLOR MODE SWITCHING
    // -------------------------------------------------------

    private fun switchColorMode(idx: Int) {
        colorModeIdx = idx
        colorMode = colorModes[idx]
        colorBtns.forEachIndexed { i, btn ->
            btn?.apply {
                setTextColor(if (i == idx) Color.BLACK else Color.WHITE)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (i == idx) Color.parseColor("#ADC6FF")
                             else Color.argb(100, 255, 255, 255))
                    cornerRadius = dp(16).toFloat()
                }
            }
        }
    }

    // -------------------------------------------------------
    // FINISH SCANNING -> save as PDF
    // -------------------------------------------------------

    private fun finishScanning() {
        if (capturedPages.isEmpty()) { toast("No pages scanned"); return }
        lifecycleScope.launch {
            val pdfFile = withContext(Dispatchers.IO) { savePagesAsPdf(capturedPages) }
            if (pdfFile != null) {
                toast("Saved: ${pdfFile.name}")
                // Optionally open in viewer:
                // ViewerActivity.start(this@DocumentScannerActivity,
                //     android.net.Uri.fromFile(pdfFile))
                finish()
            } else {
                toast("Failed to save PDF")
            }
        }
    }

    private fun savePagesAsPdf(pages: List<Bitmap>): File? {
        return try {
            val doc = android.graphics.pdf.PdfDocument()
            pages.forEachIndexed { i, bmp ->
                val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(pi)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            val dir  = File(cacheDir, "scans").also { it.mkdirs() }
            val file = File(dir, "scan_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file
        } catch (e: Exception) { null }
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER: Edge Detection Overlay View
    // -------------------------------------------------------

    inner class EdgeDetectionOverlay(context: android.content.Context) : View(context) {

        var isDetectionEnabled = true
        private var corners: Array<PointF>? = null
        private var previewW = 0; private var previewH = 0

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ADC6FF")
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(3).toFloat()
            style = Paint.Style.STROKE
        }
        private val fillPaint = Paint().apply {
            color = Color.argb(40, 173, 198, 255)
            style = Paint.Style.FILL
        }

        fun setDetectionEnabled(on: Boolean) { isDetectionEnabled = on; if (!on) { corners = null; invalidate() } }

        fun setCorners(pts: Array<PointF>?, pw: Int, ph: Int) {
            corners = pts; previewW = pw; previewH = ph; invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val c = corners ?: return
            if (c.size != 4) return

            // Scale from preview coords to overlay coords
            val sx = width.toFloat()  / previewW.toFloat().coerceAtLeast(1f)
            val sy = height.toFloat() / previewH.toFloat().coerceAtLeast(1f)

            val path = Path()
            path.moveTo(c[0].x * sx, c[0].y * sy)
            c.forEachIndexed { i, pt ->
                if (i > 0) path.lineTo(pt.x * sx, pt.y * sy)
            }
            path.close()

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, linePaint)

            // Corner handles
            val r = dp(8).toFloat()
            c.forEach { pt ->
                canvas.drawCircle(pt.x * sx, pt.y * sy, r, cornerPaint)
            }
        }
    }

    // -------------------------------------------------------
    // INNER: Focus Ring View (shown on tap)
    // -------------------------------------------------------

    inner class FocusRingView(context: android.content.Context) : View(context) {

        private var cx = 0f; private var cy = 0f; private var visible = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ADC6FF")
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }

        fun showAt(x: Float, y: Float) {
            cx = x; cy = y; visible = true
            invalidate()
            postDelayed({ visible = false; invalidate() }, 1500)
        }

        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            val r = dp(36).toFloat()
            canvas.drawCircle(cx, cy, r, paint)
            // Cross-hair lines
            val arm = dp(10).toFloat()
            canvas.drawLine(cx - r - arm, cy, cx - r + arm, cy, paint)
            canvas.drawLine(cx + r - arm, cy, cx + r + arm, cy, paint)
            canvas.drawLine(cx, cy - r - arm, cx, cy - r + arm, paint)
            canvas.drawLine(cx, cy + r - arm, cx, cy + r + arm, paint)
        }
    }
}
