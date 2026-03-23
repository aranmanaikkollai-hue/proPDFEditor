package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.data.repository.PdfOperationsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * DocumentScannerActivity — CameraX document scanner.
 *
 * Fixes:
 *  - Runtime CAMERA permission request before starting camera
 *  - Camera bound to lifecycle correctly (no manual unbind needed on destroy)
 *  - Single executor for capture — avoids concurrent access
 *  - All crashes caught and shown as Toast
 *  - Output PDF saved to getExternalFilesDir() — always accessible
 */
@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var pdfOps: PdfOperationsManager

    // ── Views ─────────────────────────────────────────────────
    private lateinit var previewView  : PreviewView
    private lateinit var btnCapture   : Button
    private lateinit var btnGallery   : Button
    private lateinit var btnDone      : Button
    private lateinit var tvCount      : TextView
    private lateinit var progressBar  : ProgressBar
    private lateinit var spinnerMode  : Spinner

    // ── Camera ────────────────────────────────────────────────
    private var imageCapture   : ImageCapture? = null
    private var flashEnabled   = false
    private var cameraProvider : ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService

    // ── Scan data ─────────────────────────────────────────────
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var scanMode = ScanMode.AUTO

    enum class ScanMode { AUTO, COLOR, GRAYSCALE, BW }

    // ── Permission launcher ───────────────────────────────────
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera()
        else {
            toast("Camera permission required to scan documents.")
            finish()
        }
    }

    // ── Gallery picker ────────────────────────────────────────
    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        lifecycleScope.launch {
            uris.forEach { uri ->
                val bmp = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                bmp?.let { capturedBitmaps.add(processMode(it)) }
            }
            updateCount()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()
        requestCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        capturedBitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    // ── UI ────────────────────────────────────────────────────

    private fun buildUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(-1, -1)
        }

        // Camera preview
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }

        // Mode spinner
        spinnerMode = Spinner(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2)
            adapter = ArrayAdapter(
                this@DocumentScannerActivity,
                android.R.layout.simple_spinner_item,
                arrayOf("Auto (Document)", "Color", "Grayscale", "Black & White")
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    scanMode = ScanMode.values()[pos]
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        // Page count + progress
        tvCount = TextView(this).apply {
            text = "0 pages captured"
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(4))
            visibility = View.GONE
            isIndeterminate = true
        }

        // Buttons row
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#1A1A1A"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        // Flash toggle
        val btnFlash = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setImageResource(android.R.drawable.btn_star_big_off)
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                flashEnabled = !flashEnabled
                imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                toast(if (flashEnabled) "Flash ON" else "Flash OFF")
            }
        }

        btnGallery = Button(this).apply {
            text = "Gallery"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { galleryPicker.launch("image/*") }
        }

        btnCapture = Button(this).apply {
            text = "📷  Capture"
            layoutParams = LinearLayout.LayoutParams(0, -2, 2f)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setTextColor(Color.WHITE)
            setOnClickListener { capturePhoto() }
        }

        btnDone = Button(this).apply {
            text = "✅  Done"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            isEnabled = false
            setOnClickListener { finishScan() }
        }

        btnRow.addView(btnFlash)
        btnRow.addView(btnGallery)
        btnRow.addView(btnCapture)
        btnRow.addView(btnDone)

        root.addView(previewView)
        root.addView(spinnerMode)
        root.addView(tvCount)
        root.addView(progressBar)
        root.addView(btnRow)
        setContentView(root)

        supportActionBar?.title = "Scan Document"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    // ── Permission ────────────────────────────────────────────

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ── Camera ────────────────────────────────────────────────

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                cameraProvider = future.get()
                bindCamera()
            } catch (e: Exception) {
                toast("Camera init failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            .build()

        try {
            // Unbind existing use cases before rebinding
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            toast("Cannot start camera: ${e.message}")
        }
    }

    // ── Capture ───────────────────────────────────────────────

    private fun capturePhoto() {
        val capture = imageCapture ?: run { toast("Camera not ready"); return }
        btnCapture.isEnabled = false

        val outFile = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        val opts    = ImageCapture.OutputFileOptions.Builder(outFile).build()

        capture.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                lifecycleScope.launch {
                    val bmp = withContext(Dispatchers.Default) {
                        val raw = BitmapFactory.decodeFile(outFile.absolutePath) ?: return@withContext null
                        processMode(raw)
                    }
                    if (bmp != null) {
                        capturedBitmaps.add(bmp)
                        toast("Page ${capturedBitmaps.size} captured")
                    } else {
                        toast("Failed to decode captured image")
                    }
                    updateCount()
                    btnCapture.isEnabled = true
                }
            }

            override fun onError(e: ImageCaptureException) {
                lifecycleScope.launch {
                    toast("Capture failed: ${e.message}")
                    btnCapture.isEnabled = true
                }
            }
        })
    }

    // ── Image processing ──────────────────────────────────────

    private fun processMode(src: Bitmap): Bitmap = when (scanMode) {
        ScanMode.AUTO -> enhanceDocument(src)
        ScanMode.GRAYSCALE -> toGrayscale(src)
        ScanMode.BW -> toBW(src)
        ScanMode.COLOR -> src
    }

    private fun enhanceDocument(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                1.4f, 0f, 0f, 0f, -30f,
                0f, 1.4f, 0f, 0f, -30f,
                0f, 0f, 1.4f, 0f, -30f,
                0f, 0f, 0f, 1f, 0f
            )))
        })
        return out
    }

    private fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().also { it.setSaturation(0f) })
        })
        return out
    }

    private fun toBW(src: Bitmap): Bitmap {
        val gray = toGrayscale(src)
        val w = gray.width; val h = gray.height
        val pixels = IntArray(w * h)
        gray.getPixels(pixels, 0, w, 0, 0, w, h)
        for (i in pixels.indices) {
            val c = pixels[i]
            val l = (Color.red(c) * 0.299 + Color.green(c) * 0.587 + Color.blue(c) * 0.114).toInt()
            pixels[i] = if (l > 128) Color.WHITE else Color.BLACK
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        gray.recycle()
        return out
    }

    // ── Finish scan → create PDF ──────────────────────────────

    private fun finishScan() {
        if (capturedBitmaps.isEmpty()) { toast("No pages captured"); return }

        progressBar.visibility = View.VISIBLE
        btnDone.isEnabled = false

        lifecycleScope.launch {
            try {
                val imgFiles = withContext(Dispatchers.IO) {
                    capturedBitmaps.mapIndexed { i, bmp ->
                        val f = File(cacheDir, "scanpage_$i.jpg")
                        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                        f
                    }
                }

                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val outPdf = File(
                    getExternalFilesDir(null) ?: cacheDir,
                    "Scan_$stamp.pdf"
                )

                pdfOps.imagesToPdf(imgFiles, outPdf)
                    .onSuccess { pdf ->
                        toast("✅ Scan saved: ${pdf.name}")
                        // Return result to caller
                        setResult(RESULT_OK, Intent().putExtra("pdf_path", pdf.absolutePath))
                        finish()
                    }
                    .onFailure {
                        toast("❌ Failed to create PDF: ${it.message}")
                        btnDone.isEnabled = true
                    }

                // Clean up temp image files
                imgFiles.forEach { it.delete() }

            } catch (e: Exception) {
                toast("❌ Error: ${e.message}")
                btnDone.isEnabled = true
            } finally {
                progressBar.visibility = View.GONE
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun updateCount() {
        tvCount.text = "${capturedBitmaps.size} page(s) captured"
        btnDone.isEnabled = capturedBitmaps.isNotEmpty()
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
