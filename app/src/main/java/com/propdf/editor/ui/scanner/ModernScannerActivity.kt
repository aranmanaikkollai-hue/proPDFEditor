package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.propdf.editor.databinding.ActivityModernScannerBinding
import com.propdf.scanner.engine.ColorMode
import com.propdf.scanner.engine.ScanOptions
import com.propdf.scanner.ui.ScannerViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AndroidEntryPoint
class ModernScannerActivity : AppCompatActivity() {

    companion object {
        private const val PERM_REQ = 2001
        private val PERMS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var binding: ActivityModernScannerBinding
    private val viewModel: ScannerViewModel by viewModels()

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var previewView: androidx.camera.view.PreviewView? = null

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImageFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModernScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        previewView = binding.cameraPreview

        checkPermissions()
        setupUI()
        observeViewModel()
    }

    private fun checkPermissions() {
        if (PERMS.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, PERMS, PERM_REQ)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCamera()
        } else {
            toast("Camera permission required")
            finish()
        }
    }

    private fun setupUI() {
        binding.btnCapture.setOnClickListener { takePhoto() }
        binding.btnGallery.setOnClickListener { galleryLauncher.launch("image/*") }

        binding.filterAuto.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.AUTO) }
        binding.filterColor.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.COLOR) }
        binding.filterGrayscale.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.GRAYSCALE) }
        binding.filterBw.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.BLACK_WHITE) }
        binding.filterSepia.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.SEPIA) }

        binding.btnRotateLeft.setOnClickListener { viewModel.rotateCurrent(-90f) }
        binding.btnRotateRight.setOnClickListener { viewModel.rotateCurrent(90f) }
        binding.btnBrightnessUp.setOnClickListener { viewModel.adjustBrightnessCurrent(20) }
        binding.btnBrightnessDown.setOnClickListener { viewModel.adjustBrightnessCurrent(-20) }
        binding.btnOcr.setOnClickListener { viewModel.runOcrOnCurrent() }
        binding.btnSavePdf.setOnClickListener { showSaveOptions() }
        binding.btnClear.setOnClickListener { viewModel.clearAll() }

        binding.pageThumbnails.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isProcessing.collectLatest { processing ->
                        binding.progressBar.visibility = if (processing) View.VISIBLE else View.GONE
                        binding.btnCapture.isEnabled = !processing
                    }
                }
                launch {
                    viewModel.progress.collectLatest { progress ->
                        binding.progressBar.progress = (progress * 100).toInt()
                    }
                }
                launch {
                    viewModel.currentPage.collectLatest { page ->
                        page?.let {
                            binding.previewImage.setImageBitmap(it.bitmap)
                            binding.cropOverlay.setCorners(it.corners)
                            binding.previewImage.visibility = View.VISIBLE
                            binding.cameraPreview.visibility = View.GONE
                            binding.cropOverlay.visibility = View.VISIBLE
                        } ?: run {
                            binding.previewImage.visibility = View.GONE
                            binding.cameraPreview.visibility = View.VISIBLE
                            binding.cropOverlay.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.capturedPages.collectLatest { pages ->
                        binding.pageCount.text = "${pages.size} pages"
                    }
                }
                launch {
                    viewModel.ocrText.collectLatest { text ->
                        if (text.isNotEmpty()) {
                            binding.ocrResult.text = text
                            binding.ocrResult.visibility = View.VISIBLE
                        } else {
                            binding.ocrResult.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.uiState.collectLatest { state ->
                        state.error?.let { toast(it) }
                        state.lastOutputUri?.let { uri ->
                            toast("Saved: $uri")
                        }
                    }
                }
            }
        }
    }

    private fun startCamera() {
        val pv = previewView ?: return
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(pv.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (_: Exception) {
                toast("Camera init failed")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    toast("Capture failed: ${exc.message}")
                }
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val bmp = BitmapFactory.decodeFile(file.absolutePath)
                    bmp?.let {
                        viewModel.captureDocument(it, ScanOptions(
                            autoCorrect = true,
                            removeShadows = true,
                            autoEnhance = true,
                            colorMode = ColorMode.AUTO
                        ))
                    }
                }
            })
    }

    private fun loadImageFromUri(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { stream ->
            val bmp = BitmapFactory.decodeStream(stream)
            bmp?.let {
                viewModel.captureDocument(it, ScanOptions())
            }
        }
    }

    private fun showSaveOptions() {
        val options = arrayOf("Searchable PDF (with OCR)", "Image-only PDF", "Save as JPEGs")
        AlertDialog.Builder(this)
            .setTitle("Export")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> viewModel.generateSearchablePdf()
                    1 -> viewModel.generateImagePdf()
                    2 -> viewModel.saveAsJpegs()
                }
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
