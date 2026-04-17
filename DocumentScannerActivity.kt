package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
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
    private var colorMode = "auto"
    private val colorModes = listOf("auto", "color", "gray", "bw")
    private var colorModeIdx = 0
    private val colorBtnLabels = listOf("AUTO", "COLOR", "GRAY", "B&W")
    private val colorBtns = arrayOfNulls<TextView>(4)

    private val capturedPages = mutableListOf<Bitmap>()
    private lateinit var pageCountLabel: TextView

    // -------------------------------------------------------
    // PERMISSION LAUNCHERS
    // -------------------------------------------------------

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { toast("Camera permission required"); finish() }
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
                else toast("Cannot read image")
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

    override fun onDestroy() {
        super.onDestroy()
        capturedPages.forEach { it.recycle() }
        capturedPages.clear()
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

        // Edge detection quad overlay
        edgeOverlay = EdgeDetectionOverlay(this)
        root.addView(edgeOverlay, FrameLayout.LayoutParams(-1, -1))

        // Focus ring overlay
        focusRingView = FocusRingView(this)
        root.addView(focusRingView, FrameLayout.LayoutParams(-1, -1))

        // Tap-to-focus: CRITICAL return false so CameraX still receives the event
        previewView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                handleTapToFocus(ev.x, ev.y)
                focusRingView.showAt(ev.x, ev.y)
            }
            false   // must NOT consume the event
        }

        // Top controls
        root.addView(
            buildTopControls(),
            FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.TOP
            }
        )

        // Bottom controls
        root.addView(
            buildBottomControls(),
            FrameLayout.LayoutParams(-1, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = android.view.Gravity.BOTTOM
            }
        )
    }

    private fun buildTopControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(16))
            setBackgroundColor(Color.argb(160, 0, 0, 0))

            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back") { finish() })

            addView(TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = "Scan Document"
                setTextColor(Color.WHITE)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                gravity = android.view.Gravity.CENTER
            })

            // Flash toggle
            val flashBtn = buildIconBtn(android.R.drawable.ic_menu_view, "Flash") {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            }
            addView(flashBtn)

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
            setPadding(dp(16), dp(12), dp(16), dp(28))

            // Color mode selector row
            val modeRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }
            }
            colorModes.forEachIndexed { i, _ ->
                val btn = TextView(this@DocumentScannerActivity).apply {
                    text = colorBtnLabels[i]
                    textSize = 11f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (i == colorModeIdx) Color.BLACK else Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                    background = GradientDrawable().apply {
                        setColor(
                            if (i == colorModeIdx) Color.parseColor("#ADC6FF")
                            else Color.argb(100, 255, 255, 255)
                        )
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener { switchColorMode(i) }
                }
                colorBtns[i] = btn
                modeRow.addView(btn)
            }
            addView(modeRow)

            // Capture row
            val captureRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)
            }

            // Page count
            pageCountLabel = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = android.view.Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 13f
                text = "0 pages"
            }
            captureRow.addView(pageCountLabel)

            // Shutter button
            captureRow.addView(View(this@DocumentScannerActivity).apply {
                val sz = dp(72)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = dp(20); marginEnd = dp(20)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                    setStroke(dp(4), Color.parseColor("#ADC6FF"))
                }
                setOnClickListener {
                    captureImage { bmp ->
                        lifecycleScope.launch {
                            processAndAddPage(bmp)
                        }
                    }
                }
            })

            // Done button
            captureRow.addView(TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = android.view.Gravity.CENTER
                text = "DONE"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF"))
                setOnClickListener { finishScanning() }
            })
            addView(captureRow)

            // Auto edge detection toggle
            addView(TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) }
                gravity = android.view.Gravity.CENTER
                text = "AUTO EDGE DETECTION: ON"
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF"))
                var edgeOn = true
                setOnClickListener {
                    edgeOn = !edgeOn
                    text = "AUTO EDGE DETECTION: ${if (edgeOn) "ON" else "OFF"}"
                    // Use isEnabled property (calls the override below)
                    edgeOverlay.isEnabled = edgeOn
                }
            })
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
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
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
                startEdgeDetectionLoop()
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // TAP-TO-FOCUS
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
        } catch (_: Exception) {
            // Camera not yet ready
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

    // CRITICAL: use decodeByteArray on plane[0] bytes - NEVER divide by pixelStride
    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val plane = proxy.planes[0]
            val buf   = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------
    // AUTO EDGE DETECTION LOOP
    // -------------------------------------------------------

    private fun startEdgeDetectionLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(800)
                if (edgeOverlay.isEnabled && edgeOverlay.isDetectionEnabled) {
                    detectAndDrawEdges()
                }
            }
        }
    }

    private suspend fun detectAndDrawEdges() {
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

    private fun findDocumentCorners(src: Bitmap): Array<PointF>? {
        val maxDim = 320
        val scale  = min(maxDim.toFloat() / src.width, maxDim.toFloat() / src.height)
        val sw = (src.width  * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)

        val pixels = IntArray(sw * sh)
        small.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        small.recycle()

        val gray = ByteArray(sw * sh) { i ->
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8)  and 0xFF
            val b = c and 0xFF
            ((0.299 * r + 0.587 * g + 0.114 * b).toInt()).coerceIn(0, 255).toByte()
        }

        val edgeMap = BooleanArray(sw * sh)
        val threshold = 40
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val gx = (
                    -gray[(y-1)*sw+(x-1)] - 2*gray[y*sw+(x-1)] - gray[(y+1)*sw+(x-1)]
                    + gray[(y-1)*sw+(x+1)] + 2*gray[y*sw+(x+1)] + gray[(y+1)*sw+(x+1)]
                ).toInt()
                val gy = (
                    -gray[(y-1)*sw+(x-1)] - 2*gray[(y-1)*sw+x] - gray[(y-1)*sw+(x+1)]
                    + gray[(y+1)*sw+(x-1)] + 2*gray[(y+1)*sw+x] + gray[(y+1)*sw+(x+1)]
                ).toInt()
                edgeMap[y * sw + x] = sqrt((gx * gx + gy * gy).toDouble()) > threshold
            }
        }

        var minX = sw; var maxX = 0; var minY = sh; var maxY = 0
        var edgeCount = 0
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                if (edgeMap[y * sw + x]) {
                    edgeCount++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }

        if (edgeCount < sw * sh * 0.05) return null
        if ((maxX - minX) < sw * 0.3 || (maxY - minY) < sh * 0.3) return null

        val inv = 1f / scale
        return arrayOf(
            PointF(minX * inv, minY * inv),
            PointF(maxX * inv, minY * inv),
            PointF(maxX * inv, maxY * inv),
            PointF(minX * inv, maxY * inv)
        )
    }

    // -------------------------------------------------------
    // IMAGE PROCESSING
    // -------------------------------------------------------

    private suspend fun processAndAddPage(raw: Bitmap) {
        val processed = withContext(Dispatchers.Default) { applyColorMode(raw) }
        capturedPages.add(processed)
        pageCountLabel.text = "${capturedPages.size} page${if (capturedPages.size == 1) "" else "s"}"
        toast("Page ${capturedPages.size} added")
    }

    private fun applyColorMode(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        when (colorMode) {
            "gray" -> for (i in pixels.indices) {
                val c = pixels[i]
                val lum = ((0.299 * ((c shr 16) and 0xFF) +
                            0.587 * ((c shr 8) and 0xFF) +
                            0.114 * (c and 0xFF)).toInt())
                pixels[i] = Color.argb(0xFF, lum, lum, lum)
            }
            "bw" -> for (i in pixels.indices) {
                val c = pixels[i]
                val lum = ((0.299 * ((c shr 16) and 0xFF) +
                            0.587 * ((c shr 8) and 0xFF) +
                            0.114 * (c and 0xFF)).toInt())
                val bw = if (lum > 128) 0xFF else 0x00
                pixels[i] = Color.argb(0xFF, bw, bw, bw)
            }
            "auto" -> {
                var minL = 255; var maxL = 0
                for (p in pixels) {
                    val l = ((0.299 * ((p shr 16) and 0xFF) +
                              0.587 * ((p shr 8) and 0xFF) +
                              0.114 * (p and 0xFF)).toInt())
                    if (l < minL) minL = l; if (l > maxL) maxL = l
                }
                val range = (maxL - minL).coerceAtLeast(1)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = ((((c shr 16) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                    val g = ((((c shr 8)  and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                    val b = (((c and 0xFF)           - minL) * 255 / range).coerceIn(0, 255)
                    pixels[i] = Color.argb(0xFF, r, g, b)
                }
            }
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun switchColorMode(idx: Int) {
        colorModeIdx = idx
        colorMode = colorModes[idx]
        colorBtns.forEachIndexed { i, btn ->
            btn?.apply {
                setTextColor(if (i == idx) Color.BLACK else Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(
                        if (i == idx) Color.parseColor("#ADC6FF")
                        else Color.argb(100, 255, 255, 255)
                    )
                    cornerRadius = dp(16).toFloat()
                }
            }
        }
    }

    // -------------------------------------------------------
    // FINISH SCANNING
    // -------------------------------------------------------

    private fun finishScanning() {
        if (capturedPages.isEmpty()) { toast("No pages scanned"); return }
        lifecycleScope.launch {
            val pdfFile = withContext(Dispatchers.IO) { savePagesAsPdf(capturedPages) }
            if (pdfFile != null) {
                toast("Saved: ${pdfFile.name}")
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
                    bmp.width, bmp.height, i + 1
                ).create()
                val page = doc.startPage(pi)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            val dir  = File(cacheDir, "scans").also { it.mkdirs() }
            val file = File(dir, "scan_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            doc.close()
            file
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER CLASS: Edge Detection Overlay
    // setEnabled is properly overriding View.setEnabled
    // -------------------------------------------------------

    inner class EdgeDetectionOverlay(context: android.content.Context) : View(context) {

        // Separate flag for detection logic — does not shadow View.isEnabled
        var isDetectionEnabled = true
            private set

        private var corners: Array<PointF>? = null
        private var previewW = 1
        private var previewH = 1

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

        // FIX: properly override View.setEnabled — no longer hides supertype member
        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            isDetectionEnabled = enabled
            if (!enabled) {
                corners = null
                invalidate()
            }
        }

        fun setCorners(pts: Array<PointF>?, pw: Int, ph: Int) {
            corners = pts
            previewW = pw.coerceAtLeast(1)
            previewH = ph.coerceAtLeast(1)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val c = corners ?: return
            if (c.size != 4) return

            val sx = width.toFloat()  / previewW.toFloat()
            val sy = height.toFloat() / previewH.toFloat()

            val path = Path().apply {
                moveTo(c[0].x * sx, c[0].y * sy)
                for (i in 1..3) lineTo(c[i].x * sx, c[i].y * sy)
                close()
            }

            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, linePaint)

            val r = dp(8).toFloat()
            c.forEach { pt -> canvas.drawCircle(pt.x * sx, pt.y * sy, r, cornerPaint) }
        }
    }

    // -------------------------------------------------------
    // INNER CLASS: Focus Ring View
    // -------------------------------------------------------

    inner class FocusRingView(context: android.content.Context) : View(context) {

        private var cx = 0f
        private var cy = 0f
        private var visible = false

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ADC6FF")
            strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }

        fun showAt(x: Float, y: Float) {
            cx = x; cy = y; visible = true
            invalidate()
            postDelayed({
                visible = false
                invalidate()
            }, 1500)
        }

        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            val r   = dp(36).toFloat()
            val arm = dp(10).toFloat()
            canvas.drawCircle(cx, cy, r, paint)
            // Cross-hair arms
            canvas.drawLine(cx - r - arm, cy, cx - r + arm, cy, paint)
            canvas.drawLine(cx + r - arm, cy, cx + r + arm, cy, paint)
            canvas.drawLine(cx, cy - r - arm, cx, cy - r + arm, paint)
            canvas.drawLine(cx, cy + r - arm, cx, cy + r + arm, paint)
        }
    }
}
