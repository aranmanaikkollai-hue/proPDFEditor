package com.propdf.editor.ui.scanner

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.ui.viewer.ViewerActivity
import com.propdf.editor.utils.FileHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    enum class ScanMode { AUTO, COLOR, GRAYSCALE, BW }

    private lateinit var previewView     : PreviewView
    private lateinit var btnCapture      : Button
    private lateinit var btnGallery      : Button
    private lateinit var btnDone         : Button
    private lateinit var tvCount         : TextView
    private lateinit var progressBar     : ProgressBar
    private lateinit var spinnerMode     : Spinner
    private lateinit var thumbnailRow    : LinearLayout
    private lateinit var thumbnailScroll : HorizontalScrollView
    // FIX: focusRingOverlay is a separate FrameLayout ON TOP of preview, not inside it
    private lateinit var focusRingLayout : FrameLayout

    private var imageCapture   : ImageCapture? = null
    private var camera         : Camera? = null
    private var cameraProvider : ProcessCameraProvider? = null
    private var flashEnabled   = false
    private lateinit var cameraExecutor: ExecutorService

    private val scannedPages = mutableListOf<Pair<Bitmap, File>>()
    private var scanMode     = ScanMode.AUTO

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else { toast("Camera permission required"); finish() }
    }

    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        lifecycleScope.launch {
            uris.forEach { uri ->
                val bmp = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                bmp?.let { addScannedPage(applyMode(it)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()
        requestCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        scannedPages.forEach { (bmp, _) -> if (!bmp.isRecycled) bmp.recycle() }
    }

    private fun buildUI() {
        // Root: FrameLayout so camera preview can be full-size with overlays on top
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        root.layoutParams = ViewGroup.LayoutParams(-1, -1)

        // Camera preview fills entire screen
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        root.addView(previewView)

        // FIX: Focus ring overlay -- separate layer, NOT inside previewView
        // This prevents the touch event from being consumed by children
        focusRingLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            // Make overlay non-clickable so touches pass through to previewView's parent
            isClickable = false; isFocusable = false
        }
        root.addView(focusRingLayout)

        // Mode spinner -- top area
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.TOP
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            gravity = Gravity.CENTER_VERTICAL
        }
        spinnerMode = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setBackgroundColor(Color.TRANSPARENT)
            adapter = ArrayAdapter(
                this@DocumentScannerActivity,
                android.R.layout.simple_spinner_item,
                arrayOf("Auto-Enhance", "Color", "Grayscale", "Black & White")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    scanMode = ScanMode.values()[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        tvCount = TextView(this).apply {
            text = "0 pages"; textSize = 12f
            setTextColor(Color.WHITE); setPadding(dp(12), 0, dp(8), 0)
        }
        val btnFlash = ImageButton(this).apply {
            setImageResource(android.R.drawable.btn_star_big_off)
            setBackgroundColor(Color.TRANSPARENT); setColorFilter(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener {
                flashEnabled = !flashEnabled
                imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                setColorFilter(if (flashEnabled) Color.YELLOW else Color.WHITE)
            }
        }
        topBar.addView(spinnerMode); topBar.addView(tvCount); topBar.addView(btnFlash)
        root.addView(topBar)

        // Bottom controls area
        val bottomArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC000000"))
            layoutParams = FrameLayout.LayoutParams(-1, -2).apply {
                gravity = Gravity.BOTTOM
            }
        }

        // Thumbnail strip
        thumbnailScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(78))
            visibility = View.GONE
        }
        thumbnailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        thumbnailScroll.addView(thumbnailRow)
        bottomArea.addView(thumbnailScroll)

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(3))
            visibility = View.GONE; isIndeterminate = true
        }
        bottomArea.addView(progressBar)

        // Tap-to-focus hint
        val tvHint = TextView(this).apply {
            text = "Tap to focus  |  Tap capture to scan"
            textSize = 11f; setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        }
        bottomArea.addView(tvHint)

        // Button row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(10), dp(8), dp(16)); gravity = Gravity.CENTER_VERTICAL
        }
        btnGallery = Button(this).apply {
            text = "Gallery"; layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#333333"))
            setOnClickListener { galleryPicker.launch("image/*") }
        }
        btnCapture = Button(this).apply {
            text = "CAPTURE"; layoutParams = LinearLayout.LayoutParams(0, dp(56), 2f).apply {
                setMargins(dp(8), 0, dp(8), 0)
            }
            textSize = 16f; setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setOnClickListener { capturePhoto() }
        }
        btnDone = Button(this).apply {
            text = "Done"; layoutParams = LinearLayout.LayoutParams(0, dp(48), 1f)
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#2E7D32"))
            isEnabled = false; setOnClickListener { finishScan() }
        }
        btnRow.addView(btnGallery); btnRow.addView(btnCapture); btnRow.addView(btnDone)
        bottomArea.addView(btnRow)
        root.addView(bottomArea)
        setContentView(root)

        // Tap-to-focus: use a transparent overlay view placed over camera preview only
        // This does NOT intercept clicks on the bottom controls
        val focusTapOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, 0, 1f)  // will be sized after layout
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = false
        }
        root.addView(focusTapOverlay, 2)  // above previewView, below bottomArea
        focusTapOverlay.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) tapToFocus(ev.x, ev.y)
            false
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Scan Document"
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else cameraPermLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get(); bindCamera()
            } catch (e: Exception) { toast("Camera error: ${e.message}") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val pv = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()
        try {
            pv.unbindAll()
            camera = pv.bindToLifecycle(
                this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
            )
        } catch (e: Exception) { toast("Cannot bind camera: ${e.message}") }
    }

    // FIX: Tap to focus -- uses camera's FocusMeteringAction, NOT a touch listener on a child view
    private fun tapToFocus(x: Float, y: Float) {
        val cam = camera ?: return
        val factory = previewView.meteringPointFactory
        val point   = factory.createPoint(x, y)
        val action  = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        cam.cameraControl.startFocusAndMetering(action)
        showFocusRing(x, y)
    }

    private fun showFocusRing(x: Float, y: Float) {
        // Show in the dedicated overlay, not inside previewView
        val size   = dp(70)
        val ring   = View(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setStroke(dp(2), Color.YELLOW)
            }
        }
        val params = FrameLayout.LayoutParams(size, size).apply {
            leftMargin = (x - size / 2).toInt().coerceAtLeast(0)
            topMargin  = (y - size / 2).toInt().coerceAtLeast(0)
        }
        focusRingLayout.removeAllViews()
        focusRingLayout.addView(ring, params)
        focusRingLayout.postDelayed({ focusRingLayout.removeAllViews() }, 1000)
    }

    private fun capturePhoto() {
        val capture = imageCapture ?: run { toast("Camera not ready"); return }
        btnCapture.isEnabled = false
        val outFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(outFile).build(),
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch {
                        val processed = withContext(Dispatchers.Default) {
                            val raw = BitmapFactory.decodeFile(outFile.absolutePath) ?: return@withContext null
                            applyMode(raw)
                        }
                        if (processed != null) {
                            addScannedPage(processed)
                            toast("Page ${scannedPages.size} captured")
                        } else toast("Failed to decode image")
                        btnCapture.isEnabled = true
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    lifecycleScope.launch { toast("Capture error: ${e.message}"); btnCapture.isEnabled = true }
                }
            }
        )
    }

    private fun addScannedPage(bmp: Bitmap) {
        val f = File(cacheDir, "page_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        scannedPages.add(Pair(bmp, f))
        addThumbnail(bmp, scannedPages.size - 1)
        updateCount()
    }

    private fun addThumbnail(bmp: Bitmap, idx: Int) {
        thumbnailScroll.visibility = View.VISIBLE
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(70)).apply {
                setMargins(dp(3), 0, dp(3), 0)
            }
        }
        val iv = ImageView(this).apply {
            val thumb = Bitmap.createScaledBitmap(bmp, dp(58), dp(66), true)
            setImageBitmap(thumb); layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val delBtn = TextView(this).apply {
            text = "x"; textSize = 10f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#CC000000"))
            setPadding(dp(4), 0, dp(4), 0)
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP or Gravity.END }
            setOnClickListener { removePage(idx) }
        }
        val numTv = TextView(this).apply {
            text = "${idx+1}"; textSize = 9f
            setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#881A73E8"))
            setPadding(dp(3), 0, dp(3), 0)
            layoutParams = FrameLayout.LayoutParams(-2, -2).apply { gravity = Gravity.BOTTOM or Gravity.START }
        }
        frame.addView(iv); frame.addView(delBtn); frame.addView(numTv)
        thumbnailRow.addView(frame)
        thumbnailScroll.post { thumbnailScroll.fullScroll(HorizontalScrollView.FOCUS_RIGHT) }
    }

    private fun removePage(idx: Int) {
        if (idx >= scannedPages.size) return
        scannedPages[idx].first.recycle(); scannedPages.removeAt(idx)
        thumbnailRow.removeAllViews()
        scannedPages.forEachIndexed { i, (bmp, _) -> addThumbnail(bmp, i) }
        updateCount()
        if (scannedPages.isEmpty()) thumbnailScroll.visibility = View.GONE
    }

    private fun updateCount() {
        tvCount.text = "${scannedPages.size} page${if (scannedPages.size == 1) "" else "s"}"
        btnDone.isEnabled = scannedPages.isNotEmpty()
    }

    private fun applyMode(src: Bitmap): Bitmap = when (scanMode) {
        ScanMode.AUTO      -> enhance(src)
        ScanMode.GRAYSCALE -> grayscale(src)
        ScanMode.BW        -> toBW(src)
        ScanMode.COLOR     -> src
    }

    private fun enhance(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.4f, 0f, 0f, 0f, -25f, 0f, 1.4f, 0f, 0f, -25f,
                0f, 0f, 1.4f, 0f, -25f, 0f, 0f, 0f, 1f, 0f
            )))
        }); return out
    }

    private fun grayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        }); return out
    }

    private fun toBW(src: Bitmap): Bitmap {
        val gray = grayscale(src); val w = gray.width; val h = gray.height
        val pixels = IntArray(w * h); gray.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val l = (Color.red(c)*0.299 + Color.green(c)*0.587 + Color.blue(c)*0.114).toInt()
            pixels[i] = if (l > 128) Color.WHITE else Color.BLACK
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h); gray.recycle(); return out
    }

    private fun finishScan() {
        if (scannedPages.isEmpty()) { toast("No pages captured"); return }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val et    = EditText(this).apply {
            setText("Scan_$stamp"); selectAll(); setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Save Scanned PDF")
            .setMessage("Enter filename:").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifBlank { "Scan_$stamp" }.removeSuffix(".pdf")
                doSave(name)
            }.setNegativeButton("Cancel", null).show()
    }

    private fun doSave(outName: String) {
        progressBar.visibility = View.VISIBLE; btnDone.isEnabled = false
        lifecycleScope.launch {
            try {
                val imgFiles = scannedPages.map { (_, f) -> f }
                val tmpPdf   = File(cacheDir, "$outName.pdf")
                pdfOps.imagesToPdf(imgFiles, tmpPdf).fold(
                    onSuccess = { pdf ->
                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@DocumentScannerActivity, pdf)
                        }
                        imgFiles.forEach { it.delete() }
                        progressBar.visibility = View.GONE
                        AlertDialog.Builder(this@DocumentScannerActivity)
                            .setTitle("Saved!")
                            .setMessage("$outName.pdf\n\n${saved.displayPath}")
                            .setPositiveButton("View") { _, _ ->
                                val f   = saved.file ?: pdf
                                val uri = try {
                                    androidx.core.content.FileProvider.getUriForFile(
                                        this@DocumentScannerActivity, "$packageName.provider", f
                                    )
                                } catch (_: Exception) { android.net.Uri.fromFile(f) }
                                ViewerActivity.start(this@DocumentScannerActivity, uri)
                                finish()
                            }
                            .setNegativeButton("OK") { _, _ -> finish() }.show()
                    },
                    onFailure = {
                        progressBar.visibility = View.GONE; btnDone.isEnabled = true
                        toast("Failed: ${it.message}")
                    }
                )
            } catch (e: Exception) {
                progressBar.visibility = View.GONE; btnDone.isEnabled = true
                toast("Error: ${e.message}")
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
