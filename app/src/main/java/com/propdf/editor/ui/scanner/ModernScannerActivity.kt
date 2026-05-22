package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.propdf.editor.R
import com.propdf.scanner.di.ScannerModule
import com.propdf.scanner.engine.ColorMode
import com.propdf.scanner.engine.ScanOptions
import com.propdf.scanner.ui.DocumentCropOverlay
import com.propdf.scanner.ui.ScannerViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ModernScannerActivity : AppCompatActivity() {

    companion object {
        private const val PERM_REQ = 2001
        private val PERMS = arrayOf(Manifest.permission.CAMERA)
    }

    private lateinit var viewModel: ScannerViewModel

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null

    // Views
    private lateinit var cameraPreview: PreviewView
    private lateinit var cropOverlay: DocumentCropOverlay
    private lateinit var previewImage: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var pageCount: TextView
    private lateinit var ocrResult: TextView
    private lateinit var pageThumbnails: RecyclerView
    private lateinit var btnCapture: FloatingActionButton
    private lateinit var btnGallery: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRotateLeft: ImageButton
    private lateinit var btnRotateRight: ImageButton
    private lateinit var btnBrightnessUp: ImageButton
    private lateinit var btnBrightnessDown: ImageButton
    private lateinit var btnOcr: MaterialButton
    private lateinit var btnSavePdf: MaterialButton
    private lateinit var btnClear: MaterialButton
    private lateinit var filterAuto: MaterialButton
    private lateinit var filterColor: MaterialButton
    private lateinit var filterGrayscale: MaterialButton
    private lateinit var filterBw: MaterialButton
    private lateinit var filterSepia: MaterialButton

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { loadImageFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_modern_scanner)

        // Manual DI
        viewModel = ScannerViewModel(
            ScannerModule.provideDocumentScannerEngine(this),
            ScannerModule.provideSearchablePdfGenerator(this),
            ScannerModule.provideMlKitOcrEngine(this)
        )

        cameraExecutor = Executors.newSingleThreadExecutor()
        initViews()
        checkPermissions()
        setupUI()
        observeViewModel()
    }

    private fun initViews() {
        cameraPreview = findViewById(R.id.cameraPreview)
        cropOverlay = findViewById(R.id.cropOverlay)
        previewImage = findViewById(R.id.previewImage)
        progressBar = findViewById(R.id.progressBar)
        pageCount = findViewById(R.id.pageCount)
        ocrResult = findViewById(R.id.ocrResult)
        pageThumbnails = findViewById(R.id.pageThumbnails)
        btnCapture = findViewById(R.id.btnCapture)
        btnGallery = findViewById(R.id.btnGallery)
        btnBack = findViewById(R.id.btnBack)
        btnRotateLeft = findViewById(R.id.btnRotateLeft)
        btnRotateRight = findViewById(R.id.btnRotateRight)
        btnBrightnessUp = findViewById(R.id.btnBrightnessUp)
        btnBrightnessDown = findViewById(R.id.btnBrightnessDown)
        btnOcr = findViewById(R.id.btnOcr)
        btnSavePdf = findViewById(R.id.btnSavePdf)
        btnClear = findViewById(R.id.btnClear)
        filterAuto = findViewById(R.id.filterAuto)
        filterColor = findViewById(R.id.filterColor)
        filterGrayscale = findViewById(R.id.filterGrayscale)
        filterBw = findViewById(R.id.filterBw)
        filterSepia = findViewById(R.id.filterSepia)
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
        btnCapture.setOnClickListener { takePhoto() }
        btnGallery.setOnClickListener { galleryLauncher.launch("image/*") }
        btnBack.setOnClickListener { finish() }

        filterAuto.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.AUTO) }
        filterColor.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.COLOR) }
        filterGrayscale.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.GRAYSCALE) }
        filterBw.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.BLACK_WHITE) }
        filterSepia.setOnClickListener { viewModel.applyFilterToCurrent(ColorMode.SEPIA) }

        btnRotateLeft.setOnClickListener { viewModel.rotateCurrent(-90f) }
        btnRotateRight.setOnClickListener { viewModel.rotateCurrent(90f) }
        btnBrightnessUp.setOnClickListener { viewModel.adjustBrightnessCurrent(20) }
        btnBrightnessDown.setOnClickListener { viewModel.adjustBrightnessCurrent(-20) }
        btnOcr.setOnClickListener { viewModel.runOcrOnCurrent() }
        btnSavePdf.setOnClickListener { showSaveOptions() }
        btnClear.setOnClickListener { viewModel.clearAll() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.isProcessing.collectLatest { processing ->
                        progressBar.visibility = if (processing) View.VISIBLE else View.GONE
                        btnCapture.isEnabled = !processing
                    }
                }
                launch {
                    viewModel.progress.collectLatest { progress ->
                        progressBar.progress = (progress * 100).toInt()
                    }
                }
                launch {
                    viewModel.currentPage.collectLatest { page ->
                        page?.let {
                            previewImage.setImageBitmap(it.bitmap)
                            cropOverlay.setCorners(it.corners)
                            previewImage.visibility = View.VISIBLE
                            cameraPreview.visibility = View.GONE
                            cropOverlay.visibility = View.VISIBLE
                        } ?: run {
                            previewImage.visibility = View.GONE
                            cameraPreview.visibility = View.VISIBLE
                            cropOverlay.visibility = View.GONE
                        }
                    }
                }
                launch {
                    viewModel.capturedPages.collectLatest { pages ->
                        pageCount.text = "${pages.size} pages"
                    }
                }
                launch {
                    viewModel.ocrText.collectLatest { text ->
                        if (text.isNotEmpty()) {
                            ocrResult.text = text
                            ocrResult.visibility = View.VISIBLE
                        } else {
                            ocrResult.visibility = View.GONE
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
        val pv = cameraPreview
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
        ScannerModule.release()
    }
}
