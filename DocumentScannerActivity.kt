package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.R
import com.propdf.editor.data.repository.ScannerProcessor
import com.propdf.editor.data.repository.PdfOperationsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * DocumentScannerActivity - Camera-based document scanner
 *
 * Features:
 * - Real-time camera preview with CameraX
 * - Auto edge detection (simulated - requires OpenCV native integration)
 * - Perspective correction and crop
 * - Image enhancement (B&W, grayscale, color modes)
 * - Multi-page scanning in one session
 * - Export all scanned pages as PDF
 * - Compatible with API 21+ (CameraX requirement)
 */
@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var scannerProcessor: ScannerProcessor
    @Inject lateinit var pdfOps: PdfOperationsManager

    // ── Views ────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: ImageButton
    private lateinit var btnFlash: ImageButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnDone: Button
    private lateinit var rvScannedPages: androidx.recyclerview.widget.RecyclerView
    private lateinit var tvPageCount: TextView
    private lateinit var spinnerMode: Spinner

    // ── State ────────────────────────────────────────────────────
    private var imageCapture: ImageCapture? = null
    private var flashEnabled = false
    private var scanMode = ScanMode.AUTO
    private val scannedBitmaps = mutableListOf<Bitmap>()
    private lateinit var cameraExecutor: ExecutorService

    // Gallery picker
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris -> processGalleryImages(uris) }

    // Camera permission
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scanner)

        initViews()
        setupScanModes()
        requestCameraPermission()
        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun initViews() {
        previewView = findViewById(R.id.preview_view)
        btnCapture = findViewById(R.id.btn_capture)
        btnFlash = findViewById(R.id.btn_flash)
        btnGallery = findViewById(R.id.btn_gallery)
        btnDone = findViewById(R.id.btn_done)
        rvScannedPages = findViewById(R.id.rv_scanned_pages)
        tvPageCount = findViewById(R.id.tv_page_count)
        spinnerMode = findViewById(R.id.spinner_scan_mode)

        btnCapture.setOnClickListener { capturePhoto() }
        btnFlash.setOnClickListener { toggleFlash() }
        btnGallery.setOnClickListener { galleryLauncher.launch("image/*") }
        btnDone.setOnClickListener { finishScanning() }

        updatePageCount()
    }

    private fun setupScanModes() {
        val modes = arrayOf("Auto (Document)", "Color", "Grayscale", "Black & White")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, modes)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMode.adapter = adapter
        spinnerMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                scanMode = ScanMode.values()[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun requestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> startCamera()
            else -> cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_STILL)
                .setTargetResolution(Size(1920, 1080))
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture
                )
                // Control flash
                camera.cameraControl.enableTorch(flashEnabled)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val imageCapture = imageCapture ?: return

        val outputFile = File(
            cacheDir,
            "scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        btnCapture.isEnabled = false

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    btnCapture.isEnabled = true
                    processCapture(outputFile)
                }

                override fun onError(exc: ImageCaptureException) {
                    btnCapture.isEnabled = true
                    Toast.makeText(
                        this@DocumentScannerActivity,
                        "Capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        )
    }

    private fun processCapture(file: File) {
        lifecycleScope.launch {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@launch

            // Apply scan mode processing
            val processed = when (scanMode) {
                ScanMode.AUTO -> scannerProcessor.enhanceDocument(bitmap)
                ScanMode.COLOR -> bitmap
                ScanMode.GRAYSCALE -> scannerProcessor.toGrayscale(bitmap)
                ScanMode.BLACK_WHITE -> scannerProcessor.toBinaryBlackWhite(bitmap)
            }

            scannedBitmaps.add(processed)
            updatePageCount()

            Toast.makeText(this@DocumentScannerActivity,
                "Page ${scannedBitmaps.size} captured", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processGalleryImages(uris: List<Uri>) {
        lifecycleScope.launch {
            for (uri in uris) {
                val stream = contentResolver.openInputStream(uri) ?: continue
                val bitmap = BitmapFactory.decodeStream(stream)
                stream.close()
                val processed = when (scanMode) {
                    ScanMode.AUTO -> scannerProcessor.enhanceDocument(bitmap)
                    ScanMode.GRAYSCALE -> scannerProcessor.toGrayscale(bitmap)
                    ScanMode.BLACK_WHITE -> scannerProcessor.toBinaryBlackWhite(bitmap)
                    else -> bitmap
                }
                scannedBitmaps.add(processed)
            }
            updatePageCount()
        }
    }

    private fun finishScanning() {
        if (scannedBitmaps.isEmpty()) {
            Toast.makeText(this, "No pages scanned", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            // Save bitmaps as image files
            val imageFiles = scannedBitmaps.mapIndexed { i, bmp ->
                val file = File(cacheDir, "page_$i.jpg")
                file.outputStream().use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                file
            }

            // Convert images to PDF
            val outputFile = File(
                getExternalFilesDir(null),
                "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf"
            )

            val result = pdfOps.imagesToPdf(imageFiles, outputFile)
            result.onSuccess { pdf ->
                Toast.makeText(this@DocumentScannerActivity,
                    "PDF created: ${pdf.name}", Toast.LENGTH_LONG).show()

                // Return to caller
                val resultIntent = Intent().apply {
                    putExtra("pdf_path", pdf.absolutePath)
                }
                setResult(RESULT_OK, resultIntent)
                finish()
            }.onFailure { e ->
                Toast.makeText(this@DocumentScannerActivity,
                    "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun toggleFlash() {
        flashEnabled = !flashEnabled
        btnFlash.setImageResource(
            if (flashEnabled) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        )
    }

    private fun updatePageCount() {
        tvPageCount.text = "${scannedBitmaps.size} page(s)"
        btnDone.isEnabled = scannedBitmaps.isNotEmpty()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    enum class ScanMode { AUTO, COLOR, GRAYSCALE, BLACK_WHITE }
}
