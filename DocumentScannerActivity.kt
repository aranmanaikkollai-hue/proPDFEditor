package com.propdf.editor.ui.scanner

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.data.repository.ScannerProcessor
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager
    @Inject lateinit var scannerProcessor: ScannerProcessor

    private lateinit var previewView: PreviewView
    private lateinit var tvHeader: TextView
    private lateinit var tvPageCount: TextView
    private lateinit var tvHint: TextView
    private lateinit var btnCapture: Button
    private lateinit var btnDone: Button
    private lateinit var btnGallery: Button
    private lateinit var thumbnailStrip: LinearLayout
    private lateinit var thumbScroll: HorizontalScrollView

    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchOn = false
    private lateinit var cameraExecutor: ExecutorService

    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var colorMode = 0  // 0=Auto-Enhance 1=Color 2=Gray 3=B&W
    private val colorLabels = arrayOf("Auto-Enhance", "Color", "Grayscale", "Black & White")

    companion object {
        private const val CAMERA_PERMISSION = Manifest.permission.CAMERA
        private const val REQ_CAMERA = 101
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) {
                    val stream = contentResolver.openInputStream(uri)!!
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
                val processed = applyColorMode(bmp)
                capturedBitmaps.add(processed)
                addThumbnail(processed, capturedBitmaps.size - 1)
                updatePageCount()
            } catch (e: Exception) {
                toast("Could not load image: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()
        if (hasCameraPermission()) startCamera()
        else ActivityCompat.requestPermissions(this, arrayOf(CAMERA_PERMISSION), REQ_CAMERA)
    }

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == REQ_CAMERA && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else toast("Camera permission required")
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUI() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Camera preview -- fills the whole screen
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        // Header bar
        val headerBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, dp(52)).apply {
                gravity = Gravity.TOP
            }
            setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(12), 0, dp(12), 0)
        }
        val btnBack = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            setOnClickListener { finish() }
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        tvHeader = TextView(this).apply {
            text = colorLabels[colorMode]
            setTextColor(Color.WHITE)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(dp(12), 0, 0, 0)
        }
        tvPageCount = TextView(this).apply {
            text = "0 pages"
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(0, 0, dp(10), 0)
        }
        // Flash toggle button
        val btnFlash = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
            setOnClickListener {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                setColorFilter(if (torchOn) Color.YELLOW else Color.WHITE)
            }
        }
        headerBar.addView(btnBack)
        headerBar.addView(tvHeader)
        headerBar.addView(tvPageCount)
        headerBar.addView(btnFlash)

        // Hint text above buttons
        tvHint = TextView(this).apply {
            text = "Tap to focus  |  Tap capture to scan"
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = dp(130)
            }
        }

        // Bottom controls
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
            }
            setBackgroundColor(Color.parseColor("#CC000000"))
        }

        // Thumbnail strip
        thumbScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(76))
            isVisible(capturedBitmaps.isNotEmpty())
        }
        thumbnailStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        thumbScroll.addView(thumbnailStrip)

        // Color mode cycle row
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(4), dp(8), 0)
        }
        colorLabels.forEachIndexed { i, label ->
            colorRow.addView(Button(this).apply {
                text = label.replace(" ", "\n")
                textSize = 9f
                setTextColor(if (i == colorMode) Color.parseColor("#1A73E8") else Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                tag = "color_$i"
                setOnClickListener { setColorMode(i) }
            })
        }

        // Main action buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            setPadding(dp(8), dp(6), dp(8), dp(16))
        }
        btnGallery = Button(this).apply {
            text = "GALLERY"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { galleryLauncher.launch("image/*") }
        }
        btnCapture = Button(this).apply {
            text = "CAPTURE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 2f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { captureImage() }
        }
        btnDone = Button(this).apply {
            text = "DONE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, dp(56), 1f).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { savePdf() }
        }
        btnRow.addView(btnGallery); btnRow.addView(btnCapture); btnRow.addView(btnDone)

        bottomBar.addView(thumbScroll)
        bottomBar.addView(colorRow)
        bottomBar.addView(btnRow)

        // Tap-to-focus overlay -- uses PARTIAL touch; does NOT consume events needed by PreviewView
        val focusOverlay = object : View(this) {
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.action == MotionEvent.ACTION_UP) {
                    val factory = previewView.meteringPointFactory
                    val point  = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                }
                return false  // pass through so PreviewView still works
            }
        }.apply {
            // Only occupy the preview area, not the bottom bar
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply {
                bottomMargin = dp(130)
            }
            setBackgroundColor(Color.TRANSPARENT)
        }

        root.addView(previewView)
        root.addView(focusOverlay)
        root.addView(headerBar)
        root.addView(tvHint)
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun View.isVisible(v: Boolean) { visibility = if (v) View.VISIBLE else View.GONE }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview  = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                toast("Camera failed to start: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage() {
        val ic = imageCapture ?: run { toast("Camera not ready"); return }
        btnCapture.isEnabled = false
        btnCapture.text = "..."
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val raw = imageProxyToBitmap(proxy)
                    proxy.close()
                    lifecycleScope.launch {
                        val processed = withContext(Dispatchers.Default) { applyColorMode(raw) }
                        capturedBitmaps.add(processed)
                        addThumbnail(processed, capturedBitmaps.size - 1)
                        updatePageCount()
                        btnCapture.isEnabled = true
                        btnCapture.text = "CAPTURE"
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    btnCapture.text = "CAPTURE"
                    toast("Capture failed: ${e.message}")
                }
            }
        )
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
        val plane      = proxy.planes[0]
        val buffer     = plane.buffer
        val rowStride  = plane.rowStride
        val pixelStride= plane.pixelStride
        val w = rowStride / pixelStride
        val h = proxy.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return if (w == proxy.width) bmp
        else Bitmap.createBitmap(bmp, 0, 0, proxy.width, h)
    }

    private suspend fun applyColorMode(src: Bitmap): Bitmap = when (colorMode) {
        1    -> src  // Color -- no processing
        2    -> scannerProcessor.toGrayscale(src)
        3    -> scannerProcessor.toBinaryBlackWhite(src)
        else -> scannerProcessor.enhanceDocument(src)  // Auto-Enhance
    }

    private fun addThumbnail(bmp: Bitmap, index: Int) {
        thumbScroll.visibility = View.VISIBLE
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(68)).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bmp)
        }
        val del = Button(this).apply {
            text = "x"
            textSize = 8f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC222222"))
            layoutParams = FrameLayout.LayoutParams(dp(18), dp(18)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setOnClickListener {
                if (index < capturedBitmaps.size) {
                    capturedBitmaps[index].recycle()
                    capturedBitmaps.removeAt(index)
                }
                thumbnailStrip.removeView(frame)
                updatePageCount()
                if (capturedBitmaps.isEmpty()) thumbScroll.visibility = View.GONE
            }
        }
        frame.addView(iv); frame.addView(del)
        thumbnailStrip.addView(frame)
        thumbScroll.post { thumbScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }

    private fun setColorMode(mode: Int) {
        colorMode = mode
        tvHeader.text = colorLabels[mode]
        // Update color row button highlights
        for (i in 0 until 4) {
            val btn = (tvHeader.parent?.parent as? LinearLayout)
                ?.findViewWithTag<Button>("color_$i")
            btn?.setTextColor(if (i == mode) Color.parseColor("#1A73E8") else Color.WHITE)
        }
    }

    private fun updatePageCount() {
        val n = capturedBitmaps.size
        tvPageCount.text = "$n page${if (n == 1) "" else "s"}"
    }

    private fun savePdf() {
        if (capturedBitmaps.isEmpty()) { toast("Capture at least one page first"); return }
        btnDone.isEnabled = false; btnDone.text = "Saving..."
        lifecycleScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imageFiles = withContext(Dispatchers.IO) {
                    capturedBitmaps.mapIndexed { i, bmp ->
                        val f = File(cacheDir, "scan_${stamp}_$i.jpg")
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        f
                    }
                }
                val tmpPdf = File(cacheDir, "Scan_$stamp.pdf")
                pdfOps.imagesToPdf(imageFiles, tmpPdf).fold(
                    onSuccess = { pdf ->
                        withContext(Dispatchers.IO) { FileHelper.saveToDownloads(this@DocumentScannerActivity, pdf) }
                        toast("Saved: Scan_$stamp.pdf")
                        finish()
                    },
                    onFailure = { e ->
                        toast("Save failed: ${e.message}")
                        btnDone.isEnabled = true; btnDone.text = "DONE"
                    }
                )
            } catch (e: Exception) {
                toast("Error: ${e.message}")
                btnDone.isEnabled = true; btnDone.text = "DONE"
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        capturedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }
}
