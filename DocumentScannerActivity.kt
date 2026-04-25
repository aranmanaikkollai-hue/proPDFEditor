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
import android.widget.EditText
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

    enum class ScanMode(val label: String) {
        BATCH("Batch"), ID_CARD("ID Card"), BOOK("Book"),
        BUSINESS_CARD("Biz Card"), SPLICE("Splice")
    }

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var previewView: PreviewView
    private lateinit var edgeOverlay: EdgeDetectionOverlay
    private lateinit var gridOverlay: GridOverlay
    private lateinit var focusRingView: FocusRingView

    private var torchOn      = false
    private var scanMode     = ScanMode.BATCH
    private var colorMode    = "auto"
    private val colorModes   = listOf("auto", "color", "gray", "bw")
    private var colorModeIdx = 0
    private val colorBtnLabels = listOf("AUTO", "COLOR", "GRAY", "B&W")
    private val colorBtns    = arrayOfNulls<TextView>(4)
    private var showGrid     = true

    private val capturedPages = mutableListOf<Bitmap>()
    private var idCardFront: Bitmap? = null
    private val splicePages = mutableListOf<Bitmap>()

    private lateinit var pageCountLabel  : TextView
    private lateinit var modeLabel       : TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var cameraContainer : FrameLayout
    private lateinit var previewImageView: ImageView
    private lateinit var thumbStrip      : LinearLayout
    private var previewingPageIdx = -1

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else { toast("Camera permission required"); finish() }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
            if (bmp != null) addCapturedPage(bmp)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUI()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    override fun onDestroy() {
        super.onDestroy()
        capturedPages.forEach { it.recycle() }; capturedPages.clear()
    }

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
        root.addView(buildTopBar())
        root.addView(buildModeSelectorRow())

        val mainArea = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        cameraContainer = FrameLayout(this).apply { layoutParams = FrameLayout.LayoutParams(-1, -1) }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        cameraContainer.addView(previewView)

        // Grid overlay for alignment
        gridOverlay = GridOverlay(this)
        cameraContainer.addView(gridOverlay, FrameLayout.LayoutParams(-1, -1))

        // Edge detection overlay
        edgeOverlay = EdgeDetectionOverlay(this)
        cameraContainer.addView(edgeOverlay, FrameLayout.LayoutParams(-1, -1))

        // Focus ring
        focusRingView = FocusRingView(this)
        cameraContainer.addView(focusRingView, FrameLayout.LayoutParams(-1, -1))

        // FIX: listener on cameraContainer NOT previewView
        // edgeOverlay and focusRingView sit ABOVE previewView and intercept its touches.
        // Putting the listener on cameraContainer (the parent) catches all touches first.
        cameraContainer.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) {
                handleTapToFocus(ev.x, ev.y)
                focusRingView.showAt(ev.x, ev.y)
            }
            false   // must return false so CameraX still receives the event
        }
        mainArea.addView(cameraContainer)

        // Preview layer
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
            layoutParams = LinearLayout.LayoutParams(-1, dp(86))
            setBackgroundColor(Color.parseColor("#0E0E0E")); isHorizontalScrollBarEnabled = false
        }
        thumbStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        thumbScroll.addView(thumbStrip); root.addView(thumbScroll)
        root.addView(buildBottomControls())
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(8), dp(32), dp(8), dp(10))
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            addView(buildIconBtn(android.R.drawable.ic_media_previous, "Back") { finish() })
            modeLabel = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                text = "Scan: ${scanMode.label}"; setTextColor(Color.WHITE)
                textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            addView(modeLabel)
            // Grid toggle
            addView(buildIconBtn(android.R.drawable.ic_menu_crop, "Grid") {
                showGrid = !showGrid; gridOverlay.setGridVisible(showGrid)
                toast(if (showGrid) "Grid ON" else "Grid OFF")
            })
            // Flash
            addView(buildIconBtn(android.R.drawable.ic_menu_view, "Flash") {
                torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn)
            })
            // Gallery
            addView(buildIconBtn(android.R.drawable.ic_menu_gallery, "Gallery") {
                galleryLauncher.launch("image/*")
            })
        }
    }

    private fun buildModeSelectorRow(): HorizontalScrollView {
        return HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(44))
            setBackgroundColor(Color.parseColor("#0E0E0E")); isHorizontalScrollBarEnabled = false
            val row = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            ScanMode.values().forEach { mode ->
                row.addView(TextView(this@DocumentScannerActivity).apply {
                    text = mode.label; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER; setPadding(dp(16), dp(6), dp(16), dp(6))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    setTextColor(if (mode == scanMode) Color.BLACK else Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(if (mode == scanMode) Color.parseColor("#ADC6FF") else Color.argb(80, 255, 255, 255))
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener {
                        scanMode = mode; modeLabel.text = "Scan: ${mode.label}"
                        (parent as? LinearLayout)?.children()?.filterIsInstance<TextView>()?.forEach { btn ->
                            val isNow = btn.text == mode.label
                            btn.setTextColor(if (isNow) Color.BLACK else Color.WHITE)
                            btn.background = GradientDrawable().apply {
                                setColor(if (isNow) Color.parseColor("#ADC6FF") else Color.argb(80,255,255,255))
                                cornerRadius = dp(16).toFloat()
                            }
                        }
                        onScanModeChanged(mode)
                    }
                })
            }
            addView(row)
        }
    }

    private fun LinearLayout.children(): List<View> = (0 until childCount).map { getChildAt(it) }
    private fun onScanModeChanged(mode: ScanMode) {
        when (mode) {
            ScanMode.ID_CARD      -> toast("Capture FRONT first, then BACK")
            ScanMode.BOOK         -> toast("Place spine at centre -- captures 2 pages")
            ScanMode.SPLICE       -> toast("Capture 2 images to combine side-by-side")
            ScanMode.BUSINESS_CARD-> toast("Hold card steady -- tight crop mode")
            else -> {}
        }
    }

    private fun buildPreviewEditBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
            listOf(
                "Rotate"  to { rotateCurrentPage() },
                "Crop"    to { toast("Pinch/drag to crop -- coming soon") },
                "Filter"  to { showFilterDialog(previewingPageIdx) },
                "Bright+" to { applyBrightnessToPage(previewingPageIdx, 25) },
                "Bright-" to { applyBrightnessToPage(previewingPageIdx, -25) },
                "Delete"  to { deleteCurrentPage() },
                "Camera"  to { showCamera() }
            ).forEach { (label, action) ->
                addView(TextView(this@DocumentScannerActivity).apply {
                    text = label; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                    setTextColor(if (label == "Delete") Color.parseColor("#FF6666") else Color.parseColor("#ADC6FF"))
                    gravity = Gravity.CENTER; setPadding(dp(8), dp(5), dp(8), dp(5))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }
                    background = GradientDrawable().apply {
                        setColor(Color.parseColor("#2D2D2D")); cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener { action() }
                })
            }
        }
    }

    private fun buildBottomControls(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0E0E0E"))
            setPadding(dp(16), dp(8), dp(16), dp(20))

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
                        setColor(if (i == 0) Color.parseColor("#ADC6FF") else Color.argb(80,255,255,255))
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener { switchColorMode(i) }
                }
                colorBtns[i] = btn; modeRow.addView(btn)
            }
            addView(modeRow)

            val captureRow = LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, dp(80))
            }
            pageCountLabel = TextView(this@DocumentScannerActivity).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                gravity = Gravity.CENTER; setTextColor(Color.WHITE); textSize = 13f; text = "0 pages"
            }
            captureRow.addView(pageCountLabel)
            captureRow.addView(View(this@DocumentScannerActivity).apply {
                val sz = dp(72)
                layoutParams = LinearLayout.LayoutParams(sz, sz).apply { setMargins(dp(16), 0, dp(16), 0) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(Color.WHITE)
                    setStroke(dp(4), Color.parseColor("#ADC6FF"))
                }
                setOnClickListener { performCapture() }
            })
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
            setBackgroundColor(Color.TRANSPARENT); contentDescription = desc
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
            val preview  = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                startEdgeDetectionLoop()
            } catch (e: Exception) { toast("Camera error: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    // -------------------------------------------------------
    // TAP TO FOCUS -- uses MeteringPoint factory, returns false
    // -------------------------------------------------------

    private fun handleTapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        try {
            // Must use previewView.meteringPointFactory -- not manual calculation
            val point  = previewView.meteringPointFactory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cam.cameraControl.startFocusAndMetering(action)
        } catch (_: Exception) { /* Camera not ready */ }
    }

    // -------------------------------------------------------
    // CAPTURE
    // -------------------------------------------------------

    private fun performCapture() {
        captureRawImage { rawBmp ->
            lifecycleScope.launch {
                val processed = withContext(Dispatchers.Default) { applyColorMode(rawBmp) }
                when (scanMode) {
                    ScanMode.ID_CARD      -> handleIdCardCapture(processed)
                    ScanMode.SPLICE       -> handleSpliceCapture(processed)
                    ScanMode.BOOK         -> handleBookCapture(processed)
                    ScanMode.BUSINESS_CARD-> { rawBmp.recycle(); addCapturedPage(processed) }
                    else                  -> addCapturedPage(processed)
                }
            }
        }
    }

    private fun handleIdCardCapture(bmp: Bitmap) {
        if (idCardFront == null) {
            idCardFront = bmp; toast("Front captured. Now capture the BACK.")
        } else {
            val front = idCardFront!!
            val combined = Bitmap.createBitmap(maxOf(front.width, bmp.width), front.height + bmp.height + dp(8), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(combined); canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(front, 0f, 0f, null)
            canvas.drawBitmap(bmp, 0f, (front.height + dp(8)).toFloat(), null)
            idCardFront = null; addCapturedPage(combined); toast("ID Card (front+back) done")
        }
    }

    private fun handleBookCapture(bmp: Bitmap) {
        val halfW = bmp.width / 2
        addCapturedPage(Bitmap.createBitmap(bmp, 0, 0, halfW, bmp.height))
        addCapturedPage(Bitmap.createBitmap(bmp, halfW, 0, halfW, bmp.height))
        toast("Book: 2 pages added")
    }

    private fun handleSpliceCapture(bmp: Bitmap) {
        splicePages.add(bmp)
        if (splicePages.size < 2) {
            toast("Splice: ${splicePages.size}/2 -- capture next"); addCapturedPage(bmp.copy(bmp.config, false))
        } else {
            val a = splicePages[0]; val b = splicePages[1]
            val spliced = Bitmap.createBitmap(a.width + b.width + dp(4), maxOf(a.height, b.height), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(spliced); canvas.drawColor(Color.WHITE)
            canvas.drawBitmap(a, 0f, 0f, null); canvas.drawBitmap(b, (a.width + dp(4)).toFloat(), 0f, null)
            splicePages.clear(); addCapturedPage(spliced); toast("Splice complete")
        }
    }

    private fun addCapturedPage(bmp: Bitmap) {
        capturedPages.add(bmp)
        pageCountLabel.text = "${capturedPages.size} page${if (capturedPages.size == 1) "" else "s"}"
        refreshThumbStrip(); showPreview(capturedPages.size - 1)
    }

    private fun captureRawImage(onDone: (Bitmap) -> Unit) {
        val ic = imageCapture ?: return
        ic.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(proxy: ImageProxy) {
                val bmp = imageProxyToBitmap(proxy); proxy.close()
                if (bmp != null) onDone(bmp) else toast("Capture failed")
            }
            override fun onError(exc: ImageCaptureException) { toast("Error: ${exc.message}") }
        })
    }

    // CRITICAL: use decodeByteArray -- never divide by pixelStride
    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap? {
        return try {
            val plane = proxy.planes[0]; val buf = plane.buffer
            val bytes = ByteArray(buf.remaining()); buf.get(bytes)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    // -------------------------------------------------------
    // IMAGE PROCESSING
    // -------------------------------------------------------

    private fun applyColorMode(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        fun lum(c: Int) = ((0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()).coerceIn(0, 255)
        when (colorMode) {
            "gray" -> for (i in pixels.indices) { val l = lum(pixels[i]); pixels[i] = Color.argb(0xFF, l, l, l) }
            "bw"   -> for (i in pixels.indices) { val bw = if (lum(pixels[i]) > 128) 0xFF else 0x00; pixels[i] = Color.argb(0xFF, bw, bw, bw) }
            "auto" -> {
                var minL = 255; var maxL = 0
                for (p in pixels) { val l = lum(p); if (l < minL) minL = l; if (l > maxL) maxL = l }
                val range = (maxL - minL).coerceAtLeast(1)
                for (i in pixels.indices) {
                    val c = pixels[i]
                    val r = ((((c shr 16) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                    val g = ((((c shr 8) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                    val b = (((c and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
                    pixels[i] = Color.argb(0xFF, r, g, b)
                }
            }
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun applyBrightnessToPage(idx: Int, delta: Int) {
        if (idx < 0 || idx >= capturedPages.size) return
        val bmp = capturedPages[idx]; val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((c and 0xFF) + delta).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        if (idx == previewingPageIdx) previewImageView.setImageBitmap(bmp)
        refreshThumbStrip()
    }

    private fun rotateCurrentPage() {
        val idx = previewingPageIdx; if (idx < 0 || idx >= capturedPages.size) return
        val orig = capturedPages[idx]
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(orig, 0, 0, orig.width, orig.height, matrix, true)
        capturedPages[idx] = rotated; previewImageView.setImageBitmap(rotated); refreshThumbStrip()
    }

    private fun deleteCurrentPage() {
        val idx = previewingPageIdx; if (idx < 0 || idx >= capturedPages.size) return
        capturedPages.removeAt(idx); previewingPageIdx = -1
        pageCountLabel.text = "${capturedPages.size} pages"
        refreshThumbStrip(); showCamera()
    }

    private fun showFilterDialog(idx: Int) {
        if (idx < 0 || idx >= capturedPages.size) return
        val filters = arrayOf("Auto Enhance", "Grayscale", "Black & White", "Sepia", "High Contrast", "Reset")
        AlertDialog.Builder(this).setTitle("Apply Filter").setItems(filters) { _, which ->
            lifecycleScope.launch {
                val filtered = withContext(Dispatchers.Default) {
                    val src = capturedPages[idx]
                    when (which) {
                        0 -> { val c = src.copy(Bitmap.Config.ARGB_8888, true); applyAutoEnhance(c); c }
                        1 -> applyMatrix(src, ColorMatrix().apply { setSaturation(0f) })
                        2 -> applyBwBitmap(src)
                        3 -> applyMatrix(src, ColorMatrix().apply { set(floatArrayOf(0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f)) })
                        4 -> applyMatrix(src, ColorMatrix().apply { set(floatArrayOf(2f,0f,0f,0f,-128f, 0f,2f,0f,0f,-128f, 0f,0f,2f,0f,-128f, 0f,0f,0f,1f,0f)) })
                        else -> src.copy(Bitmap.Config.ARGB_8888, true)
                    }
                }
                capturedPages[idx] = filtered
                if (idx == previewingPageIdx) previewImageView.setImageBitmap(filtered)
                refreshThumbStrip()
            }
        }.show()
    }

    private fun applyAutoEnhance(bmp: Bitmap): Bitmap {
        val pixels = IntArray(bmp.width * bmp.height); bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        fun lum(c: Int) = ((0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()).coerceIn(0, 255)
        var minL = 255; var maxL = 0
        for (p in pixels) { val l = lum(p); if (l < minL) minL = l; if (l > maxL) maxL = l }
        val range = (maxL - minL).coerceAtLeast(1)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((((c shr 16) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            val g = ((((c shr 8) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            val b = (((c and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }
        bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height); return bmp
    }

    private fun applyMatrix(src: Bitmap, cm: ColorMatrix): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }); return out
    }

    private fun applyBwBitmap(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height); out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        for (i in pixels.indices) {
            val l = ((0.299 * ((pixels[i] shr 16) and 0xFF) + 0.587 * ((pixels[i] shr 8) and 0xFF) + 0.114 * (pixels[i] and 0xFF)).toInt())
            val bw = if (l > 128) 0xFF else 0x00; pixels[i] = Color.argb(0xFF, bw, bw, bw)
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height); return out
    }

    private fun switchColorMode(idx: Int) {
        colorModeIdx = idx; colorMode = colorModes[idx]
        colorBtns.forEachIndexed { i, btn ->
            btn?.setTextColor(if (i == idx) Color.BLACK else Color.WHITE)
            btn?.background = GradientDrawable().apply {
                setColor(if (i == idx) Color.parseColor("#ADC6FF") else Color.argb(80,255,255,255))
                cornerRadius = dp(16).toFloat()
            }
        }
    }

    // -------------------------------------------------------
    // THUMBNAIL STRIP  with Arrange (drag to reorder)
    // -------------------------------------------------------

    private fun refreshThumbStrip() {
        thumbStrip.removeAllViews()
        capturedPages.forEachIndexed { i, bmp ->
            val wrap = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(72)).apply { marginEnd = dp(6) }
            }
            val thumb = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(-1, -1)
                scaleType = ImageView.ScaleType.CENTER_CROP; setImageBitmap(bmp)
                background = GradientDrawable().apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(2), if (i == previewingPageIdx) Color.parseColor("#ADC6FF") else Color.parseColor("#333333"))
                }
                setOnClickListener { showPreview(i) }
                setOnLongClickListener { showPageContextMenu(i); true }
            }
            val badge = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, dp(3), dp(3), 0) }
                text = "${i + 1}"; textSize = 8f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE); setPadding(dp(3), dp(1), dp(3), dp(1))
                background = GradientDrawable().apply { setColor(Color.parseColor("#448AFF")); cornerRadius = dp(6).toFloat() }
            }
            wrap.addView(thumb); wrap.addView(badge); thumbStrip.addView(wrap)
        }
        // Arrange button
        if (capturedPages.size > 1) {
            thumbStrip.addView(TextView(this).apply {
                text = "Arrange"; textSize = 9f; typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#ADC6FF"))
                layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                    gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8)
                }
                setOnClickListener { showArrangeDialog() }
            })
        }
    }

    private fun showPageContextMenu(idx: Int) {
        val ops = arrayOf("Preview", "Rotate", "Filter", "Delete", "Move to Front", "Move to End")
        AlertDialog.Builder(this).setTitle("Page ${idx + 1}").setItems(ops) { _, which ->
            when (which) {
                0 -> showPreview(idx)
                1 -> { if (idx == previewingPageIdx) rotateCurrentPage()
                       else { val orig = capturedPages[idx]; val m = Matrix().apply { postRotate(90f) }
                              capturedPages[idx] = Bitmap.createBitmap(orig, 0, 0, orig.width, orig.height, m, true)
                              refreshThumbStrip() } }
                2 -> showFilterDialog(idx)
                3 -> { capturedPages.removeAt(idx); if (previewingPageIdx == idx) { previewingPageIdx = -1; showCamera() }
                       pageCountLabel.text = "${capturedPages.size} pages"; refreshThumbStrip() }
                4 -> { val pg = capturedPages.removeAt(idx); capturedPages.add(0, pg); refreshThumbStrip() }
                5 -> { val pg = capturedPages.removeAt(idx); capturedPages.add(pg); refreshThumbStrip() }
            }
        }.show()
    }

    private fun showArrangeDialog() {
        val items = Array(capturedPages.size) { "Page ${it + 1}" }
        AlertDialog.Builder(this).setTitle("Arrange Pages (tap to move to front)")
            .setItems(items) { _, which ->
                val pg = capturedPages.removeAt(which); capturedPages.add(0, pg)
                refreshThumbStrip(); toast("Page ${which + 1} moved to front")
            }.show()
    }

    // -------------------------------------------------------
    // PREVIEW / CAMERA SWITCH
    // -------------------------------------------------------

    private fun showPreview(idx: Int) {
        if (idx < 0 || idx >= capturedPages.size) return
        previewingPageIdx = idx; previewImageView.setImageBitmap(capturedPages[idx])
        cameraContainer.visibility = View.GONE; previewContainer.visibility = View.VISIBLE
        refreshThumbStrip()
    }

    private fun showCamera() {
        previewingPageIdx = -1
        cameraContainer.visibility = View.VISIBLE; previewContainer.visibility = View.GONE
        refreshThumbStrip()
    }

    // -------------------------------------------------------
    // AUTO EDGE DETECTION LOOP
    // -------------------------------------------------------

    private fun startEdgeDetectionLoop() {
        lifecycleScope.launch {
            while (true) {
                delay(800)
                if (cameraContainer.visibility == View.VISIBLE) {
                    val previewBmp = withContext(Dispatchers.Main) { previewView.bitmap } ?: continue
                    val corners = withContext(Dispatchers.Default) { findDocumentCorners(previewBmp) }
                    withContext(Dispatchers.Main) { edgeOverlay.setCorners(corners, previewView.width, previewView.height) }
                }
            }
        }
    }

    private fun findDocumentCorners(src: Bitmap): Array<PointF>? {
        val maxDim = 320; val scale = min(maxDim.toFloat() / src.width, maxDim.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(1)
        val sh = (src.height * scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, sw, sh, true)
        val pixels = IntArray(sw * sh); small.getPixels(pixels, 0, sw, 0, 0, sw, sh); small.recycle()
        fun lum(c: Int) = ((0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)).toInt()).coerceIn(0, 255)
        val gray = ByteArray(sw * sh) { i -> lum(pixels[i]).toByte() }
        val edge = BooleanArray(sw * sh)
        for (y in 1 until sh - 1) for (x in 1 until sw - 1) {
            val gx = (-gray[(y-1)*sw+(x-1)] - 2*gray[y*sw+(x-1)] - gray[(y+1)*sw+(x-1)] + gray[(y-1)*sw+(x+1)] + 2*gray[y*sw+(x+1)] + gray[(y+1)*sw+(x+1)]).toInt()
            val gy = (-gray[(y-1)*sw+(x-1)] - 2*gray[(y-1)*sw+x] - gray[(y-1)*sw+(x+1)] + gray[(y+1)*sw+(x-1)] + 2*gray[(y+1)*sw+x] + gray[(y+1)*sw+(x+1)]).toInt()
            edge[y * sw + x] = sqrt((gx * gx + gy * gy).toDouble()) > 40
        }
        var minX = sw; var maxX = 0; var minY = sh; var maxY = 0; var cnt = 0
        for (y in 0 until sh) for (x in 0 until sw) {
            if (edge[y * sw + x]) { cnt++
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y }
        }
        if (cnt < sw * sh * 0.05 || (maxX - minX) < sw * 0.3 || (maxY - minY) < sh * 0.3) return null
        val inv = 1f / scale
        return arrayOf(PointF(minX * inv, minY * inv), PointF(maxX * inv, minY * inv),
                       PointF(maxX * inv, maxY * inv), PointF(minX * inv, maxY * inv))
    }

    // -------------------------------------------------------
    // SAVE TO MEDISTORE
    // -------------------------------------------------------

    private fun finishScanning() {
        if (capturedPages.isEmpty()) { toast("No pages to save"); return }
        showExportDialog()
    }

    private fun showExportDialog() {
        val formats = arrayOf("PDF Document", "JPEG Images (each page)", "PDF + JPEG")
        AlertDialog.Builder(this).setTitle("Export ${capturedPages.size} page(s) as:").setItems(formats) { _, which ->
            when (which) {
                0 -> showPdfQualityDialog()
                1 -> showJpegQualityDialog()
                2 -> { showPdfQualityDialog(); showJpegQualityDialog() }
            }
        }.show()
    }

    private fun showPdfQualityDialog() {
        val options = arrayOf("High Quality (300dpi)", "Medium Quality (150dpi)", "Compressed (72dpi)", "Custom page size...")
        AlertDialog.Builder(this).setTitle("PDF Export Quality").setItems(options) { _, which ->
            val scale = when (which) { 0 -> 1.0f; 1 -> 0.75f; 2 -> 0.5f; else -> 1.0f }
            if (which == 3) showCustomPageSizeDialog { w, h -> lifecycleScope.launch { val saved = withContext(Dispatchers.IO) { savePdfToStorage(capturedPages, w, h) }; if (saved) { toast("PDF saved to Downloads/ProPDF"); finish() } else toast("Save failed") } }
            else lifecycleScope.launch { val saved = withContext(Dispatchers.IO) { savePdfToStorage(capturedPages, scale = scale) }; if (saved) { toast("PDF saved to Downloads/ProPDF"); finish() } else toast("Save failed") }
        }.show()
    }

    private fun showJpegQualityDialog() {
        val options = arrayOf("High quality (95%)", "Medium quality (80%)", "Compressed (60%)")
        val qualities = intArrayOf(95, 80, 60)
        AlertDialog.Builder(this).setTitle("JPEG Export Quality").setItems(options) { _, which ->
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { saveJpegsToStorage(capturedPages, qualities[which]) }
                if (saved) { toast("${capturedPages.size} JPEG(s) saved to Downloads/ProPDF"); finish() }
                else toast("JPEG save failed")
            }
        }.show()
    }

    private fun showCustomPageSizeDialog(onSize: (Float, Float) -> Unit) {
        val sizes = arrayOf("A4 (595 x 842 pt)", "A3 (842 x 1191 pt)", "Letter (612 x 792 pt)", "Legal (612 x 1008 pt)", "Custom (enter manually)")
        val dims  = listOf(595f to 842f, 842f to 1191f, 612f to 792f, 612f to 1008f, null)
        AlertDialog.Builder(this).setTitle("Page Size").setItems(sizes) { _, which ->
            if (dims[which] != null) { onSize(dims[which]!!.first, dims[which]!!.second) }
            else {
                val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(10), dp(20), dp(10)) }
                val etW = EditText(this).apply { hint = "Width (pt)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
                val etH = EditText(this).apply { hint = "Height (pt)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL }
                container.addView(etW); container.addView(etH)
                AlertDialog.Builder(this).setTitle("Custom Size (points)").setView(container)
                    .setPositiveButton("Apply") { _, _ ->
                        val w = etW.text.toString().toFloatOrNull() ?: 595f
                        val h = etH.text.toString().toFloatOrNull() ?: 842f
                        onSize(w, h)
                    }.setNegativeButton("Cancel", null).show()
            }
        }.show()
    }

    private fun savePdfToStorage(pages: List<Bitmap>, customW: Float = 0f, customH: Float = 0f, scale: Float = 1.0f): Boolean {
        return try {
            val doc = android.graphics.pdf.PdfDocument()
            pages.forEachIndexed { i, bmpOrig ->
                val bmp = if (scale != 1.0f) {
                    Bitmap.createScaledBitmap(bmpOrig, (bmpOrig.width * scale).toInt().coerceAtLeast(1), (bmpOrig.height * scale).toInt().coerceAtLeast(1), true)
                } else bmpOrig
                val pageW = if (customW > 0) customW.toInt() else bmp.width
                val pageH = if (customH > 0) customH.toInt() else bmp.height
                val pi    = android.graphics.pdf.PdfDocument.PageInfo.Builder(pageW, pageH, i + 1).create()
                val page  = doc.startPage(pi)
                val matrix = android.graphics.Matrix()
                if (customW > 0 || customH > 0) {
                    matrix.setScale(pageW.toFloat() / bmp.width, pageH.toFloat() / bmp.height)
                }
                page.canvas.drawBitmap(bmp, matrix, null)
                doc.finishPage(page)
                if (scale != 1.0f) bmp.recycle()
            }
            val fileName = "Scan_${System.currentTimeMillis()}.pdf"
            val out: OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                }
                contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)?.let { contentResolver.openOutputStream(it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                FileOutputStream(File(dir, fileName))
            }
            if (out == null) { doc.close(); return false }
            out.use { doc.writeTo(it) }; doc.close(); true
        } catch (_: Exception) { false }
    }

    private fun saveJpegsToStorage(pages: List<Bitmap>, quality: Int): Boolean {
        return try {
            pages.forEachIndexed { i, bmp ->
                val fileName = "Scan_${System.currentTimeMillis()}_page${i + 1}.jpg"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                } else {
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                    FileOutputStream(File(dir, fileName)).use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                }
            }
            true
        } catch (_: Exception) { false }
    }

    // -------------------------------------------------------
    // HELPERS
    // -------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // -------------------------------------------------------
    // INNER: Grid Overlay
    // -------------------------------------------------------

    inner class GridOverlay(context: android.content.Context) : View(context) {
        private var visible = true
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 255, 255, 255); strokeWidth = dp(1).toFloat(); style = Paint.Style.STROKE
        }
        fun setGridVisible(show: Boolean) { visible = show; invalidate() }
        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            // 3x3 Rule of Thirds grid (2 vertical + 2 horizontal lines)
            val thirdW = width / 3f; val thirdH = height / 3f
            canvas.drawLine(thirdW, 0f, thirdW, height.toFloat(), paint)
            canvas.drawLine(thirdW * 2, 0f, thirdW * 2, height.toFloat(), paint)
            canvas.drawLine(0f, thirdH, width.toFloat(), thirdH, paint)
            canvas.drawLine(0f, thirdH * 2, width.toFloat(), thirdH * 2, paint)
            // Centre cross
            val cx = width / 2f; val cy = height / 2f; val arm = dp(20).toFloat()
            val cp = Paint(paint).apply { color = Color.argb(120, 173, 198, 255); strokeWidth = dp(1).toFloat() }
            canvas.drawLine(cx - arm, cy, cx + arm, cy, cp)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, cp)
        }
    }

    // -------------------------------------------------------
    // INNER: Edge Detection Overlay
    // -------------------------------------------------------

    inner class EdgeDetectionOverlay(context: android.content.Context) : View(context) {
        private var corners: Array<PointF>? = null; private var pW = 1; private var pH = 1
        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ADC6FF"); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
        private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = dp(3).toFloat(); style = Paint.Style.STROKE }
        private val fillPaint = Paint().apply { color = Color.argb(40, 173, 198, 255); style = Paint.Style.FILL }
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); if (!enabled) { corners = null; invalidate() } }
        fun setCorners(pts: Array<PointF>?, pw: Int, ph: Int) { corners = pts; pW = pw.coerceAtLeast(1); pH = ph.coerceAtLeast(1); invalidate() }
        override fun onDraw(canvas: Canvas) {
            val c = corners ?: return; if (c.size != 4) return
            val sx = width.toFloat() / pW; val sy = height.toFloat() / pH
            val path = Path().apply { moveTo(c[0].x * sx, c[0].y * sy); for (i in 1..3) lineTo(c[i].x * sx, c[i].y * sy); close() }
            canvas.drawPath(path, fillPaint); canvas.drawPath(path, linePaint)
            c.forEach { pt -> canvas.drawCircle(pt.x * sx, pt.y * sy, dp(8).toFloat(), cornerPaint) }
        }
    }

    // -------------------------------------------------------
    // INNER: Focus Ring
    // -------------------------------------------------------

    inner class FocusRingView(context: android.content.Context) : View(context) {
        private var cx = 0f; private var cy = 0f; private var visible = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#ADC6FF"); strokeWidth = dp(2).toFloat(); style = Paint.Style.STROKE }
        fun showAt(x: Float, y: Float) { cx = x; cy = y; visible = true; invalidate(); postDelayed({ visible = false; invalidate() }, 1500) }
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
