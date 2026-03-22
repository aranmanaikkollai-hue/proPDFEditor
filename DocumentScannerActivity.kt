package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.R
import com.propdf.editor.data.repository.PdfOperationsManager
import com.propdf.editor.data.repository.ScannerProcessor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject

@AndroidEntryPoint
class DocumentScannerActivity : AppCompatActivity() {

    @Inject lateinit var scannerProcessor: ScannerProcessor
    @Inject lateinit var pdfOps: PdfOperationsManager

    private lateinit var previewView: PreviewView
    private lateinit var btnCapture: Button
    private lateinit var btnDone: Button
    private lateinit var tvPageCount: TextView

    private var imageCapture: ImageCapture? = null
    private val scannedBitmaps = mutableListOf<Bitmap>()
    private lateinit var cameraExecutor: ExecutorService

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        lifecycleScope.launch {
            for (uri in uris) {
                contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.let { scannedBitmaps.add(it) }
                }
            }
            updatePageCount()
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { Toast.makeText(this, "Camera required", Toast.LENGTH_SHORT).show(); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple layout built programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        previewView = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
        }
        tvPageCount = TextView(this).apply {
            text = "0 pages scanned"
            setPadding(16, 8, 16, 8)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }
        btnCapture = Button(this).apply {
            text = "Capture"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        btnDone = Button(this).apply {
            text = "Done"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            isEnabled = false
        }
        btnRow.addView(btnCapture)
        btnRow.addView(Button(this).apply {
            text = "Gallery"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            setOnClickListener { galleryLauncher.launch("image/*") }
        })
        btnRow.addView(btnDone)
        layout.addView(previewView)
        layout.addView(tvPageCount)
        layout.addView(btnRow)
        setContentView(layout)

        btnCapture.setOnClickListener { capturePhoto() }
        btnDone.setOnClickListener { finishScanning() }
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            startCamera() else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        ProcessCameraProvider.getInstance(this).addListener({
            val provider = ProcessCameraProvider.getInstance(this).get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder().build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun capturePhoto() {
        val file = File(cacheDir, "scan_${System.currentTimeMillis()}.jpg")
        imageCapture?.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    BitmapFactory.decodeFile(file.absolutePath)?.let {
                        scannedBitmaps.add(it)
                        updatePageCount()
                        Toast.makeText(this@DocumentScannerActivity, "Page ${scannedBitmaps.size} captured", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onError(e: ImageCaptureException) {
                    Toast.makeText(this@DocumentScannerActivity, "Capture failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun finishScanning() {
        if (scannedBitmaps.isEmpty()) { Toast.makeText(this, "No pages", Toast.LENGTH_SHORT).show(); return }
        lifecycleScope.launch {
            val imageFiles = scannedBitmaps.mapIndexed { i, bmp ->
                File(cacheDir, "page_$i.jpg").also { f ->
                    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                }
            }
            val out = File(getExternalFilesDir(null), "Scan_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf")
            pdfOps.imagesToPdf(imageFiles, out).onSuccess { pdf ->
                setResult(RESULT_OK, Intent().putExtra("pdf_path", pdf.absolutePath))
                finish()
            }.onFailure { Toast.makeText(this@DocumentScannerActivity, "Error: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    private fun updatePageCount() {
        tvPageCount.text = "${scannedBitmaps.size} page(s) scanned"
        btnDone.isEnabled = scannedBitmaps.isNotEmpty()
    }

    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }
}
