package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

    // UI references
    private lateinit var previewView   : PreviewView
    private lateinit var tvColorMode   : TextView
    private lateinit var tvPageCount   : TextView
    private lateinit var btnCapture    : Button
    private lateinit var btnDone       : Button
    private lateinit var btnGallery    : Button
    private lateinit var btnFlash      : ImageButton
    private lateinit var thumbStrip    : LinearLayout
    private lateinit var thumbScroll   : HorizontalScrollView
    private lateinit var colorBtns     : Array<Button>   // direct references

    // Camera state
    private var camera        : Camera?        = null
    private var imageCapture  : ImageCapture?  = null
    private var torchOn       = false
    private lateinit var cameraExecutor : ExecutorService

    // Scan state
    private val pages       = mutableListOf<Bitmap>()
    private var colorMode   = 0   // 0=Auto 1=Color 2=Gray 3=BW

    private val colorLabels = arrayOf("AUTO-ENHANCE", "COLOR", "GRAYSCALE", "B&W")
    private val colorColors = arrayOf("#1A73E8", "#FFFFFF", "#FFFFFF", "#FFFFFF")

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)
                        ?.use { BitmapFactory.decodeStream(it) }
                }
                if (raw == null) { toast("Cannot read image"); return@launch }
                val processed = withContext(Dispatchers.Default) { applyColorMode(raw) }
                addPage(processed)
            } catch (e: Exception) { toast("Error: ${e.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()
        checkAndStartCamera()
    }

    private fun checkAndStartCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), 101)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perms: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(req, perms, results)
        if (req == 101 && results.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            startCamera()
        else toast("Camera permission required to scan")
    }

    private fun buildUI() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        // Full-screen preview
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }

        // Header bar
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(Color.parseColor("#CC000000"))
            layoutParams = FrameLayout.LayoutParams(-1, dp(54)).apply {
                gravity = Gravity.TOP
            }
        }
        val btnClose = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener { finish() }
        }
        tvColorMode = TextView(this).apply {
            text = colorLabels[0]
            setTextColor(Color.WHITE)
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setPadding(dp(10), 0, 0, 0)
        }
        tvPageCount = TextView(this).apply {
            text = "0 pages"
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(0, 0, dp(8), 0)
        }
        // Flash button - lightning bolt style
        btnFlash = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_view)
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            setOnClickListener {
                torchOn = !torchOn
                camera?.cameraControl?.enableTorch(torchOn)
                setBackgroundColor(if (torchOn) Color.parseColor("#FFCC00") else Color.parseColor("#33FFFFFF"))
                setColorFilter(if (torchOn) Color.BLACK else Color.WHITE)
            }
        }
        header.addView(btnClose)
        header.addView(tvColorMode)
        header.addView(tvPageCount)
        header.addView(btnFlash)

        // Tap-to-focus overlay (transparent, only handles touch, passes events through)
        val focusOverlay = object : View(this) {
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (ev.action == MotionEvent.ACTION_UP) {
                    try {
                        val factory = previewView.meteringPointFactory
                        val point   = factory.createPoint(ev.x, ev.y)
                        val action  = FocusMeteringAction.Builder(point).build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        showFocusDot(ev.x, ev.y)
                    } catch (_: Exception) {}
                }
                return false  // never consume - scroll must still work
            }
        }.apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(140) }
            setBackgroundColor(Color.TRANSPARENT)
        }

        // Bottom panel
        val bottomPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply { gravity = Gravity.BOTTOM }
            setBackgroundColor(Color.parseColor("#CC000000"))
        }

        // Thumbnail strip
        thumbScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(80))
            visibility = View.GONE
        }
        thumbStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        thumbScroll.addView(thumbStrip)

        // Hint text
        val tvHint = TextView(this).apply {
            text = "Tap to focus  |  Tap capture to scan"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

        // Color mode row - store direct references to buttons
        val colorRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(2))
        }
        colorBtns = Array(4) { i ->
            Button(this).apply {
                text = colorLabels[i]
                textSize = 9f
                setTextColor(if (i == 0) Color.parseColor("#1A73E8") else Color.WHITE)
                setBackgroundColor(Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                setOnClickListener { onColorModeSelected(i) }
            }.also { colorRow.addView(it) }
        }

        // Action buttons
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(14))
        }
        btnGallery = Button(this).apply {
            text = "GALLERY"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#555555"))
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(3),0,dp(3),0) }
            setOnClickListener { galleryLauncher.launch("image/*") }
        }
        btnCapture = Button(this).apply {
            text = "CAPTURE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 2f).apply { setMargins(dp(3),0,dp(3),0) }
            setOnClickListener { doCapture() }
        }
        btnDone = Button(this).apply {
            text = "DONE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, dp(54), 1f).apply { setMargins(dp(3),0,dp(3),0) }
            setOnClickListener { savePdf() }
        }
        actionRow.addView(btnGallery); actionRow.addView(btnCapture); actionRow.addView(btnDone)

        bottomPanel.addView(thumbScroll)
        bottomPanel.addView(tvHint)
        bottomPanel.addView(colorRow)
        bottomPanel.addView(actionRow)

        root.addView(previewView)
        root.addView(focusOverlay)
        root.addView(header)
        root.addView(bottomPanel)
        setContentView(root)
    }

    private fun showFocusDot(x: Float, y: Float) {
        val dot = View(this).apply {
            setBackgroundColor(Color.parseColor("#88FFFF00"))
            val s = dp(50)
            layoutParams = FrameLayout.LayoutParams(s, s).apply {
                leftMargin = (x - s/2).toInt()
                topMargin  = (y - s/2).toInt()
            }
        }
        (previewView.parent as? FrameLayout)?.apply {
            addView(dot)
            dot.postDelayed({ removeView(dot) }, 700)
        }
    }

    private fun onColorModeSelected(mode: Int) {
        colorMode = mode
        tvColorMode.text = colorLabels[mode]
        colorBtns.forEachIndexed { i, btn ->
            btn.setTextColor(
                if (i == mode) Color.parseColor("#1A73E8") else Color.WHITE
            )
        }
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).also { future ->
            future.addListener({
                try {
                    val provider = future.get()
                    imageCapture = ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(
                        this, CameraSelector.DEFAULT_BACK_CAMERA,
                        preview, imageCapture
                    )
                } catch (e: Exception) {
                    toast("Camera error: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun doCapture() {
        val ic = imageCapture ?: run { toast("Camera not ready yet"); return }
        btnCapture.isEnabled = false
        btnCapture.text = "..."
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    lifecycleScope.launch {
                        try {
                            val raw = withContext(Dispatchers.Default) {
                                imageProxyToBitmap(proxy).also { proxy.close() }
                            }
                            val processed = withContext(Dispatchers.Default) {
                                applyColorMode(raw)
                            }
                            if (raw !== processed) raw.recycle()
                            addPage(processed)
                        } catch (e: Exception) {
                            toast("Processing error: ${e.message}")
                        } finally {
                            btnCapture.isEnabled = true
                            btnCapture.text = "CAPTURE"
                        }
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
        val plane       = proxy.planes[0]
        val buffer      = plane.buffer.rewind()
        val rowStride   = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = rowStride / pixelStride
        val h = proxy.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return if (w == proxy.width) bmp
        else Bitmap.createBitmap(bmp, 0, 0, proxy.width, h).also { bmp.recycle() }
    }

    private suspend fun applyColorMode(src: Bitmap): Bitmap = when (colorMode) {
        1    -> src
        2    -> scannerProcessor.toGrayscale(src)
        3    -> scannerProcessor.toBinaryBlackWhite(src)
        else -> scannerProcessor.enhanceDocument(src)
    }

    private fun addPage(bmp: Bitmap) {
        val index = pages.size
        pages.add(bmp)
        tvPageCount.text = "${pages.size} page${if (pages.size == 1) "" else "s"}"
        thumbScroll.visibility = View.VISIBLE

        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(72)).apply {
                setMargins(dp(3), dp(4), dp(3), dp(4))
            }
            setBackgroundColor(Color.parseColor("#333333"))
        }
        val iv = ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val del = Button(this).apply {
            text = "x"
            textSize = 9f
            setPadding(0,0,0,0)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC333333"))
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = Gravity.TOP or Gravity.END
            }
            setOnClickListener {
                val idx = thumbStrip.indexOfChild(frame)
                if (idx >= 0 && idx < pages.size) {
                    val removed = pages.removeAt(idx)
                    if (!removed.isRecycled) removed.recycle()
                    thumbStrip.removeView(frame)
                    tvPageCount.text = "${pages.size} page${if (pages.size==1) "" else "s"}"
                    if (pages.isEmpty()) thumbScroll.visibility = View.GONE
                }
            }
        }
        frame.addView(iv); frame.addView(del)
        thumbStrip.addView(frame)
        thumbScroll.post { thumbScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }

    private fun savePdf() {
        if (pages.isEmpty()) { toast("Capture at least one page first"); return }
        btnDone.isEnabled = false; btnDone.text = "Saving..."
        lifecycleScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val imgFiles = withContext(Dispatchers.IO) {
                    pages.mapIndexed { i, bmp ->
                        File(cacheDir, "scan_${stamp}_p${i+1}.jpg").also { f ->
                            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                        }
                    }
                }
                val tmp = File(cacheDir, "Scan_$stamp.pdf")
                pdfOps.imagesToPdf(imgFiles, tmp).fold(
                    onSuccess = {
                        withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@DocumentScannerActivity, it)
                        }
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
        pages.forEach { if (!it.isRecycled) it.recycle() }
    }
}
