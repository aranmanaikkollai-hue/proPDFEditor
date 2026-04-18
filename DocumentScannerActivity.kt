package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
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
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.sqrt

class DocumentScannerActivity : AppCompatActivity() {

    // -------------------------------------------------------
    // SCAN MODES
    // -------------------------------------------------------
    enum class ScanMode(val label: String) {
        BATCH("Batch"),
        ID_CARD("ID Card"),
        BOOK("Book"),
        BUSINESS_CARD("Biz Card"),
        SPLICE("Splice")
    }

    // -------------------------------------------------------
    // STATE
    // -------------------------------------------------------
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView
    private lateinit var edgeOverlay: EdgeDetectionOverlay
    private lateinit var focusRingView: FocusRingView

    private var torchOn   = false
    private var scanMode  = ScanMode.BATCH
    private var colorMode = "auto"   // "auto" | "color" | "gray" | "bw"
    private val colorModes = listOf("auto", "color", "gray", "bw")
    private var colorModeIdx = 0
    private val colorBtnLabels = listOf("AUTO", "COLOR", "GRAY", "B&W")
    private val colorBtns = arrayOfNulls<TextView>(4)

    // Captured pages (list of processed bitmaps)
    private val capturedPages = mutableListOf<Bitmap>()
    private var previewingPageIdx = -1   // index of page being previewed

    // ID card: front/back
    private var idCardFront: Bitmap? = null

    // Splice: two pages side-by-side
    private val splicePages = mutableListOf<Bitmap>()

    // UI refs
    private lateinit var pageCountLabel: TextView
    private lateinit var modeLabel: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var cameraContainer: FrameLayout
    private lateinit var previewImageView: ImageView
    private lateinit var thumbStrip: LinearLayout

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
                if (bmp != null) addCapturedPage(bmp)
            }
        }
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        checkCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        capturedPages.forEach { it.recycle() }
        capturedPages.clear()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    // -------------------------------------------------------
    // UI BUILD
    // -------------------------------------------------------

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)

        // Top bar
        root.addView(buildTopBar())

        // Scan mode selector
        root.addView(buildModeSelectorRow())

        // Main view area: camera preview + image preview (one at a time)
        val mainArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Camera preview layer
        cameraContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        cameraContainer.addView(previewView)
        edgeOverlay = EdgeDetectionOverlay(this)
        cameraContainer.addView(edgeOverlay, FrameLayout.LayoutParams(-1, -1))
        focusRingView = FocusRingView(this)
        cameraContainer.addView(focusRingView, FrameLayout.LayoutParams(-1, -1))

        // CRITICAL: return false so CameraX still receives events
        previewView.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                handleTapToFocus(ev.x, ev.y)
                focusRingView.showAt(ev.x, ev.y)
            }
            false
        }
        mainArea.addView(cameraContainer)

        // Image preview layer (shown when reviewing a captured page)
        previewContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }
        previewImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        previewContainer.addView(previewImageView)
        previewContainer.addView(buildPreviewEditBar(), FrameLayout.LayoutParams(
            -1, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM
        ))
        mainArea.addView(previewContainer)
        root.addView(mainArea)

        // Thumbnail strip
        val thumbScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(80))
            setBackgroundColor(Color.parseColor("#111111"))
            isHorizontalScrollBarEnabled = false
        }
        thumbStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        thumbScroll.addView(thumbStrip)
        root.addView(thumbScroll)

        // Bottom controls
        root.addView(buildBottomControls())
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(8), dp(32), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(-1, -2)

            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back") { finish() })

            modeLabel = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = "Scan: ${scanMode.label}"
                setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(modeLabel)

            // Flash toggle
            addView(buildIconBtn(android.R.drawable.ic_menu_view, "Flash") {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
            })

            // Gallery import
            addView(buildIconBtn(android.R.drawable.ic_menu_gallery, "Gallery") {
                galleryLauncher.launch("image/*")
            })
        }
    }

    private fun buildModeSelectorRow(): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            isHorizontalScrollBarEnabled = false

            val row = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            ScanMode.values().forEach { mode ->
                val btn = TextView(this@DocumentScannerActivity).apply {
                    text = mode.label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER
                    setPadding(dp(16), dp(6), dp(16), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    updateModeBtn(this, mode == scanMode)
                    setOnClickListener {
                        scanMode = mode
                        modeLabel.text = "Scan: ${mode.label}"
                        row.children().forEach { v ->
                            (v as? TextView)?.let {
                                updateModeBtn(it, it.text == mode.label)
                            }
                        }
                        onScanModeChanged(mode)
                    }
                }
                row.addView(btn)
            }
            addView(row)
        }
    }

    private fun LinearLayout.children(): List<View> =
        (0 until childCount).map { getChildAt(it) }

    private fun updateModeBtn(btn: TextView, isActive: Boolean) {
        btn.setTextColor(if (isActive) Color.BLACK else Color.WHITE)
        btn.background = GradientDrawable().apply {
            setColor(
                if (isActive) Color.parseColor("#ADC6FF")
                else Color.argb(80, 255, 255, 255)
            )
            cornerRadius = dp(16).toFloat()
        }
    }

    private fun onScanModeChanged(mode: ScanMode) {
        when (mode) {
            ScanMode.ID_CARD -> toast("ID Card: capture FRONT first, then BACK")
            ScanMode.BOOK    -> toast("Book: position spine at center, capture two pages")
            ScanMode.SPLICE  -> toast("Splice: capture 2 pages to combine side-by-side")
            else -> {}
        }
    }

    private fun buildPreviewEditBar(): LinearLayout {
        val C_BG = Color.parseColor("#1A1A1A")
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setBackgroundColor(C_BG)
            setPadding(dp(12), dp(8), dp(12), dp(8))

            // Rotate
            addView(buildPreviewBtn("Rotate") {
                previewingPageIdx.takeIf { it >= 0 }?.let { idx ->
                    val orig = capturedPages[idx]
                    val matrix = Matrix().apply { postRotate(90f) }
                    val rotated = Bitmap.createBitmap(
                        orig, 0, 0, orig.width, orig.height, matrix, true
                    )
                    capturedPages[idx] = rotated
                    previewImageView.setImageBitmap(rotated)
                    refreshThumbStrip()
                }
            })

            // Crop (shows guide)
            addView(buildPreviewBtn("Crop") {
                toast("Drag corners on preview to crop (coming soon)")
            })

            // Filters
            addView(buildPreviewBtn("Filter") {
                showFilterDialog(previewingPageIdx)
            })

            // Brightness
            addView(buildPreviewBtn("Bright+") {
                applyBrightnessToPage(previewingPageIdx, 30)
            })

            // Delete this page
            addView(buildPreviewBtn("Delete", Color.parseColor("#E53935")) {
                if (previewingPageIdx >= 0 && previewingPageIdx < capturedPages.size) {
                    capturedPages.removeAt(previewingPageIdx)
                    previewingPageIdx = -1
                    refreshThumbStrip()
                    showCamera()
                }
            })

            // Back to camera
            addView(buildPreviewBtn("Camera") {
                showCamera()
            })
        }
    }

    private fun buildPreviewBtn(
        label: String,
        textColor: Int = Color.parseColor("#ADC6FF"),
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label; textSize = 10f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor); gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(10).toFloat()
            }
            setOnClickListener { action() }
        }
    }

    private fun buildBottomControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            setPadding(dp(16), dp(8), dp(16), dp(20))

            // Color mode row
            val modeRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
            }
            colorModes.forEachIndexed { i, _ ->
                val btn = TextView(this@DocumentScannerActivity).apply {
                    text = colorBtnLabels[i]; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (i == 0) Color.BLACK else Color.WHITE)
                    gravity = Gravity.CENTER; setPadding(dp(14), dp(6), dp(14), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) }
                    background = GradientDrawable().apply {
                        setColor(if (i == 0) Color.parseColor("#ADC6FF")
                                 else Color.argb(80, 255, 255, 255))
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener { switchColorMode(i) }
                }
                colorBtns[i] = btn; modeRow.addView(btn)
            }
            addView(modeRow)

            // Capture row
            val captureRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, dp(80))
            }

            pageCountLabel = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 13f
                text = "0 pages"
            }
            captureRow.addView(pageCountLabel)

            // Shutter button
            captureRow.addView(View(this@DocumentScannerActivity).apply {
                val sz = dp(72)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply {
                    marginStart = dp(16); marginEnd = dp(16)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.WHITE)
                    setStroke(dp(4), Color.parseColor("#ADC6FF"))
                }
                setOnClickListener { performCapture() }
            })

            // Done
            captureRow.addView(TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER; text = "DONE"
                textSize = 13f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF"))
                setOnClickListener { finishScanning() }
            })
            addView(captureRow)
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
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
                startEdgeDetectionLoop()
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // TAP TO FOCUS — CRITICAL: return false from touch listener
    // -------------------------------------------------------

    private fun handleTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            val point  = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------
    // CAPTURE DISPATCH — routes by scan mode
    // -------------------------------------------------------

    private fun performCapture() {
        captureRawImage { rawBmp ->
            lifecycleScope.launch {
                val processed = withContext(Dispatchers.Default) {
                    applyColorMode(rawBmp)
                }
                when (scanMode) {
                    ScanMode.ID_CARD -> handleIdCardCapture(processed)
                    ScanMode.SPLICE  -> handleSpliceCapture(processed)
                    ScanMode.BOOK    -> handleBookCapture(processed)
                    else             -> addCapturedPage(processed)
                }
            }
        }
    }

    private fun handleIdCardCapture(bmp: Bitmap) {
        if (idCardFront == null) {
            idCardFront = bmp
            toast("Front captured. Now capture the BACK.")
        } else {
            // Combine front + back into a single tall bitmap
            val front = idCardFront!!
            val combined = Bitmap.createBitmap(
                maxOf(front.width, bmp.width),
                front.height + bmp.height + dp(8),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(combined)
            canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(front, 0f, 0f, null)
            canvas.drawBitmap(bmp, 0f, (front.height + dp(8)).toFloat(), null)
            idCardFront = null
            addCapturedPage(combined)
            toast("ID Card (front + back) captured")
        }
    }

    private fun handleBookCapture(bmp: Bitmap) {
        // Split the book image down the center into two pages
        val halfW = bmp.width / 2
        val leftPage  = Bitmap.createBitmap(bmp, 0,     0, halfW, bmp.height)
        val rightPage = Bitmap.createBitmap(bmp, halfW, 0, halfW, bmp.height)
        addCapturedPage(leftPage)
        addCapturedPage(rightPage)
        toast("Book: 2 pages added")
    }

    private fun handleSpliceCapture(bmp: Bitmap) {
        splicePages.add(bmp)
        if (splicePages.size < 2) {
            toast("Splice: captured ${splicePages.size}/2. Capture next.")
            addCapturedPage(bmp)  // also add individually
        } else {
            val a = splicePages[0]; val b = splicePages[1]
            val spliced = Bitmap.createBitmap(
                a.width + b.width + dp(4), maxOf(a.height, b.height),
                Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(spliced); canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(a, 0f, 0f, null)
            canvas.drawBitmap(b, (a.width + dp(4)).toFloat(), 0f, null)
            splicePages.clear()
            addCapturedPage(spliced)
            toast("Splice: pages combined")
        }
    }

    private fun addCapturedPage(bmp: Bitmap) {
        capturedPages.add(bmp)
        pageCountLabel.text = "${capturedPages.size} page${if (capturedPages.size == 1) "" else "s"}"
        refreshThumbStrip()
        // Auto-preview the last captured page
        showPreview(capturedPages.size - 1)
    }

    // -------------------------------------------------------
    // THUMBNAIL STRIP
    // -------------------------------------------------------

    private fun refreshThumbStrip() {
        thumbStrip.removeAllViews()
        capturedPages.forEachIndexed { i, bmp ->
            val thumb = ImageView(this).apply {
                val sz = dp(60)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { marginEnd = dp(6) }
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(2),
                        if (i == previewingPageIdx) Color.parseColor("#ADC6FF")
                        else Color.parseColor("#333333"))
                }
                setOnClickListener { showPreview(i) }
                setOnLongClickListener {
                    AlertDialog.Builder(this@DocumentScannerActivity)
                        .setTitle("Page ${i + 1}")
                        .setItems(arrayOf("Delete", "Move to Front", "Filter")) { _, w ->
                            when (w) {
                                0 -> {
                                    capturedPages.removeAt(i)
                                    if (previewingPageIdx == i) {
                                        previewingPageIdx = -1; showCamera()
                                    }
                                    refreshThumbStrip()
                                    pageCountLabel.text = "${capturedPages.size} pages"
                                }
                                1 -> {
                                    val page = capturedPages.removeAt(i)
                                    capturedPages.add(0, page)
                                    refreshThumbStrip()
                                }
                                2 -> showFilterDialog(i)
                            }
                        }.show()
                    true
                }
            }
            thumbStrip.addView(thumb)
        }
        // Page count badge
        thumbStrip.addView(TextView(this).apply {
            text = "${capturedPages.size} pg"; textSize = 10f
            setTextColor(Color.parseColor("#8B90A0"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                gravity = Gravity.CENTER_VERTICAL; marginStart = dp(4)
            }
        })
    }

    // -------------------------------------------------------
    // PREVIEW / CAMERA SWITCHING
    // -------------------------------------------------------

    private fun showPreview(idx: Int) {
        if (idx < 0 || idx >= capturedPages.size) return
        previewingPageIdx = idx
        previewImageView.setImageBitmap(capturedPages[idx])
        cameraContainer.visibility = View.GONE
        previewContainer.visibility = View.VISIBLE
        refreshThumbStrip()
    }

    private fun showCamera() {
        previewingPageIdx = -1
        cameraContainer.visibility = View.VISIBLE
        previewContainer.visibility = View.GONE
        refreshThumbStrip()
    }

    // -------------------------------------------------------
    // IMAGE PROCESSING
    // -------------------------------------------------------

    private fun captureRawImage(onDone: (Bitmap) -> Unit) {
        val ic = imageCapture ?: return
        ic.takePicture(ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val bmp = imageProxyToBitmap(proxy)
                    proxy.close()
                    if (bmp != null) onDone(bmp)
                    else toast("Capture failed")
                }
                override fun onError(exc: ImageCaptureException) {
                    toast("Error: ${exc.message}")
                }
            })
    }

    // CRITICAL: decodeByteArray on plane[0] bytes — never divide by pixelStride
    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val plane = proxy.planes[0]
            val buf   = plane.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    private fun applyColorMode(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        when (colorMode) {
            "gray" -> for (i in pixels.indices) {
                val c = pixels[i]
                val lum = lum(c); pixels[i] = Color.argb(0xFF, lum, lum, lum)
            }
            "bw" -> for (i in pixels.indices) {
                val c = pixels[i]; val bw = if (lum(c) > 128) 0xFF else 0x00
                pixels[i] = Color.argb(0xFF, bw, bw, bw)
            }
            "auto" -> {
                var minL = 255; var maxL = 0
                for (p in pixels) { val l = lum(p); if (l < minL) minL = l; if (l > maxL) maxL = l }
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

    private fun lum(c: Int): Int =
        ((0.299 * ((c shr 16) and 0xFF) +
          0.587 * ((c shr 8)  and 0xFF) +
          0.114 * (c and 0xFF)).toInt()).coerceIn(0, 255)

    private fun applyBrightnessToPage(idx: Int, delta: Int) {
        if (idx < 0 || idx >= capturedPages.size) return
        val bmp    = capturedPages[idx]
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((c shr 16) and 0xFF + delta).coerceIn(0, 255)
            val g = ((c shr 8)  and 0xFF + delta).coerceIn(0, 255)
            val b = (c and 0xFF           + delta).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (idx == previewingPageIdx) previewImageView.setImageBitmap(bmp)
        refreshThumbStrip()
    }

    private fun showFilterDialog(idx: Int) {
        if (idx < 0 || idx >= capturedPages.size) return
        val filters = arrayOf("Auto Enhance", "Grayscale", "Black & White",
                              "Sepia", "High Contrast", "Reset (Color)")
        AlertDialog.Builder(this).setTitle("Apply Filter").setItems(filters) { _, which ->
            lifecycleScope.launch {
                val orig = capturedPages[idx]
                val filtered = withContext(Dispatchers.Default) {
                    when (which) {
                        0 -> { val copy = orig.copy(Bitmap.Config.ARGB_8888, true)
                               applyAutoEnhance(copy); copy }
                        1 -> applyMatrixFilter(orig, grayscaleMatrix())
                        2 -> applyBwFilter(orig)
                        3 -> applyMatrixFilter(orig, sepiaMatrix())
                        4 -> applyMatrixFilter(orig, highContrastMatrix())
                        5 -> orig.copy(Bitmap.Config.ARGB_8888, true)
                        else -> orig
                    }
                }
                capturedPages[idx] = filtered
                if (idx == previewingPageIdx) previewImageView.setImageBitmap(filtered)
                refreshThumbStrip()
            }
        }.show()
    }

    private fun applyAutoEnhance(bmp: Bitmap): Bitmap {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        var minL = 255; var maxL = 0
        for (p in pixels) { val l = lum(p); if (l < minL) minL = l; if (l > maxL) maxL = l }
        val range = (maxL - minL).coerceAtLeast(1)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((((c shr 16) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            val g = ((((c shr 8)  and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            val b = (((c and 0xFF)           - minL) * 255 / range).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return bmp
    }

    private fun applyMatrixFilter(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint  = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun applyBwFilter(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val bw = if (lum(pixels[i]) > 128) 0xFF else 0x00
            pixels[i] = Color.argb(0xFF, bw, bw, bw)
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun grayscaleMatrix() = ColorMatrix().apply { setSaturation(0f) }

    private fun sepiaMatrix() = ColorMatrix().apply {
        set(floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f,     0f,     0f,     1f, 0f
        ))
    }

    private fun highContrastMatrix() = ColorMatrix().apply {
        set(floatArrayOf(
            2f, 0f, 0f, 0f, -128f,
            0f, 2f, 0f, 0f, -128f,
            0f, 0f, 2f, 0f, -128f,
            0f, 0f, 0f, 1f,    0f
        ))
    }

    // -------------------------------------------------------
    // COLOR MODE SWITCHING
    // -------------------------------------------------------

    private fun switchColorMode(idx: Int) {
        colorModeIdx = idx; colorMode = colorModes[idx]
        colorBtns.forEachIndexed { i, btn ->
            btn?.setTextColor(if (i == idx) Color.BLACK else Color.WHITE)
            btn?.background = GradientDrawable().apply {
                setColor(
                    if (i == idx) Color.parseColor("#ADC6FF")
                    else Color.argb(80, 255, 255, 255)
                )
                cornerRadius = dp(16).toFloat()
            }
        }
    }

    // -------------------------------------------------------
    // AUTO EDGE DETECTION
    // -------------------------------------------------------

    private fun startEdgeDetectionLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(900)
                if (edgeOverlay.isEnabled && cameraContainer.visibility == View.VISIBLE) {
                    val previewBmp = withContext(Dispatchers.Main) { previewView.bitmap }
                        ?: continue
                    val corners = withContext(Dispatchers.Default) {
                        findDocumentCorners(previewBmp)
                    }
                    withContext(Dispatchers.Main) {
                        edgeOverlay.setCorners(corners, previewView.width, previewView.height)
                    }
                }
            }
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
        val gray = ByteArray(sw * sh) { i -> lum(pixels[i]).toByte() }
        val edgeMap = BooleanArray(sw * sh)
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val gx = (-gray[(y-1)*sw+(x-1)] - 2*gray[y*sw+(x-1)] - gray[(y+1)*sw+(x-1)]
                     + gray[(y-1)*sw+(x+1)] + 2*gray[y*sw+(x+1)] + gray[(y+1)*sw+(x+1)]).toInt()
            val gy = (-gray[(y-1)*sw+(x-1)] - 2*gray[(y-1)*sw+x] - gray[(y-1)*sw+(x+1)]
                     + gray[(y+1)*sw+(x-1)] + 2*gray[(y+1)*sw+x] + gray[(y+1)*sw+(x+1)]).toInt()
            edgeMap[y * sw + x] = sqrt((gx * gx + gy * gy).toDouble()) > 40
        }
        var minX = sw; var maxX = 0; var minY = sh; var maxY = 0; var cnt = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            if (edgeMap[y * sw + x]) {
                cnt++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
        }
        if (cnt < sw * sh * 0.05) return null
        if ((maxX - minX) < sw * 0.3 || (maxY - minY) < sh * 0.3) return null
        val inv = 1f / scale
        return arrayOf(
            PointF(minX * inv, minY * inv), PointF(maxX * inv, minY * inv),
            PointF(maxX * inv, maxY * inv), PointF(minX * inv, maxY * inv)
        )
    }

    // -------------------------------------------------------
    // FINISH — SAVE PDF to MediaStore (Downloads / ProPDF folder)
    // -------------------------------------------------------

    private fun finishScanning() {
        if (capturedPages.isEmpty()) { toast("No pages to save"); return }
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { savePdfToStorage(capturedPages) }
            if (saved) {
                toast("PDF saved to Downloads/ProPDF")
                finish()
            } else {
                toast("Failed to save PDF")
            }
        }
    }

    private fun savePdfToStorage(pages: List<Bitmap>): Boolean {
        return try {
            val doc = android.graphics.pdf.PdfDocument()
            pages.forEachIndexed { i, bmp ->
                val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(
                    bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(pi)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            val fileName = "Scan_${System.currentTimeMillis()}.pdf"
            val out: OutputStream?

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ : use MediaStore
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                out = uri?.let { contentResolver.openOutputStream(it) }
            } else {
                // Legacy: direct file write
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS), "ProPDF"
                ).also { it.mkdirs() }
                out = FileOutputStream(File(dir, fileName))
            }

            if (out == null) { doc.close(); return false }
            out.use { doc.writeTo(it) }
            doc.close()
            true
        } catch (_: Exception) { false }
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER: Edge Detection Overlay
    // -------------------------------------------------------

    inner class EdgeDetectionOverlay(context: android.content.Context) : View(context) {
        var isDetectionEnabled = true; private set
        private var corners: Array<PointF>? = null
        private var pW = 1; private var pH = 1
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ADC6FF"); strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE
        }
        private val fillPaint = Paint().apply {
            color = Color.argb(40, 173, 198, 255); style = Paint.Style.FILL
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled); isDetectionEnabled = enabled
            if (!enabled) { corners = null; invalidate() }
        }

        fun setCorners(pts: Array<PointF>?, pw: Int, ph: Int) {
            corners = pts; pW = pw.coerceAtLeast(1); pH = ph.coerceAtLeast(1); invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val c = corners ?: return
            if (c.size != 4) return
            val sx = width.toFloat() / pW; val sy = height.toFloat() / pH
            val path = Path().apply {
                moveTo(c[0].x * sx, c[0].y * sy)
                for (i in 1..3) lineTo(c[i].x * sx, c[i].y * sy)
                close()
            }
            canvas.drawPath(path, fillPaint); canvas.drawPath(path, linePaint)
            c.forEach { pt -> canvas.drawCircle(pt.x * sx, pt.y * sy, dp(8).toFloat(), cornerPaint) }
        }
    }

    // -------------------------------------------------------
    // INNER: Focus Ring View
    // -------------------------------------------------------

    inner class FocusRingView(context: android.content.Context) : View(context) {
        private var cx = 0f; private var cy = 0f; private var visible = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#ADC6FF"); strokeWidth = dp(2).toFloat()
            style = Paint.Style.STROKE
        }
        fun showAt(x: Float, y: Float) {
            cx = x; cy = y; visible = true; invalidate()
            postDelayed({ visible = false; invalidate() }, 1500)
        }
        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            val r = dp(36).toFloat(); val arm = dp(10).toFloat()
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawLine(cx - r - arm, cy, cx - r + arm, cy, paint)
            canvas.drawLine(cx + r - arm, cy, cx + r + arm, cy, paint)
            canvas.drawLine(cx, cy - r - arm, cx, cy - r + arm, paint)
            canvas.drawLine(cx, cy + r - arm, cx, cy + r + arm, paint)
        }
    }
}
