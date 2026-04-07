package com.propdf.editor.ui.scanner

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorMatrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Bundle
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
    private lateinit var captureBtn: Button
    private lateinit var doneBtn: Button
    private lateinit var thumbnailStrip: LinearLayout
    private lateinit var colorModeBtn: Button
    private lateinit var focusTapOverlay: View
    private lateinit var tvPageCount: TextView

    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var colorMode = COLOR_AUTO

    companion object {
        const val COLOR_AUTO = 0
        const val COLOR_COLOR = 1
        const val COLOR_GRAY = 2
        const val COLOR_BW = 3
    }

    private val colorLabels = arrayOf("Auto", "Color", "Gray", "B&W")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUI()
        startCamera()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUI() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }

        // Transparent overlay for tap-to-focus (separate from UI buttons)
        focusTapOverlay = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            setBackgroundColor(Color.TRANSPARENT)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    camera?.cameraControl?.startFocusAndMetering(action)
                    showFocusRing(event.x, event.y)
                }
                true
            }
        }

        // Bottom controls
        val bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = android.view.Gravity.BOTTOM }
            setBackgroundColor(Color.parseColor("#CC000000"))
        }

        // Thumbnail strip
        val thumbScroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, dp(80))
        }
        thumbnailStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        thumbScroll.addView(thumbnailStrip)

        // Controls row
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(16))
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        tvPageCount = TextView(this).apply {
            text = "0 pages"
            setTextColor(Color.WHITE)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }

        colorModeBtn = Button(this).apply {
            text = "Auto"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#33FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(0, 0, dp(12), 0)
            }
            setOnClickListener { cycleColorMode() }
        }

        captureBtn = Button(this).apply {
            text = "SCAN"
            textSize = 16f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                setMargins(0, 0, dp(12), 0)
            }
            setOnClickListener { captureImage() }
        }

        doneBtn = Button(this).apply {
            text = "SAVE PDF"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A73E8"))
            setOnClickListener { savePdf() }
        }

        controlRow.addView(tvPageCount)
        controlRow.addView(colorModeBtn)
        controlRow.addView(captureBtn)
        controlRow.addView(doneBtn)
        bottomBar.addView(thumbScroll)
        bottomBar.addView(controlRow)

        root.addView(previewView)
        root.addView(focusTapOverlay)
        root.addView(bottomBar)
        setContentView(root)
    }

    private fun showFocusRing(x: Float, y: Float) {
        // Brief visual feedback for focus point
        val ring = View(this).apply {
            val size = dp(60)
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (x - size / 2).toInt()
                topMargin  = (y - size / 2).toInt()
            }
            setBackgroundColor(Color.TRANSPARENT)
            background = null
            alpha = 1f
        }
        (previewView.parent as? FrameLayout)?.addView(ring)
        ring.postDelayed({ (previewView.parent as? FrameLayout)?.removeView(ring) }, 800)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                toast("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureImage() {
        val ic = imageCapture ?: return
        captureBtn.isEnabled = false
        ic.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(proxy: ImageProxy) {
                    val bitmap = imageProxyToBitmap(proxy)
                    proxy.close()
                    lifecycleScope.launch {
                        val processed = withContext(Dispatchers.Default) {
                            when (colorMode) {
                                COLOR_GRAY  -> scannerProcessor.toGrayscale(bitmap)
                                COLOR_BW    -> scannerProcessor.toBinaryBlackWhite(bitmap)
                                COLOR_AUTO  -> scannerProcessor.enhanceDocument(bitmap)
                                else        -> bitmap
                            }
                        }
                        capturedBitmaps.add(processed)
                        addThumbnail(processed, capturedBitmaps.size - 1)
                        tvPageCount.text = "${capturedBitmaps.size} page${if (capturedBitmaps.size == 1) "" else "s"}"
                        captureBtn.isEnabled = true
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    captureBtn.isEnabled = true
                    toast("Capture failed: ${exc.message}")
                }
            }
        )
    }

    private fun imageProxyToBitmap(proxy: ImageProxy): Bitmap {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val w = rowStride / pixelStride
        val h = proxy.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buffer)
        return if (w == proxy.width) bmp
        else Bitmap.createBitmap(bmp, 0, 0, proxy.width, h)
    }

    private fun addThumbnail(bmp: Bitmap, index: Int) {
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(60), dp(72)).apply {
                setMargins(dp(4), 0, dp(4), 0)
            }
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(-1, -1)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bmp)
        }
        val delBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dp(20), dp(20)).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.END
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(Color.parseColor("#BB000000"))
            setOnClickListener {
                capturedBitmaps.removeAt(index)
                thumbnailStrip.removeView(frame)
                tvPageCount.text = "${capturedBitmaps.size} page${if (capturedBitmaps.size == 1) "" else "s"}"
            }
        }
        frame.addView(iv); frame.addView(delBtn)
        thumbnailStrip.addView(frame)
    }

    private fun cycleColorMode() {
        colorMode = (colorMode + 1) % 4
        colorModeBtn.text = colorLabels[colorMode]
    }

    private fun savePdf() {
        if (capturedBitmaps.isEmpty()) { toast("Scan at least one page first"); return }
        doneBtn.isEnabled = false
        lifecycleScope.launch {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val outName = "Scan_$stamp.pdf"
                val imageFiles = withContext(Dispatchers.IO) {
                    capturedBitmaps.mapIndexed { i, bmp ->
                        val tmp = File(cacheDir, "scan_page_$i.jpg")
                        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                        tmp
                    }
                }
                val tmpPdf = File(cacheDir, outName)
                pdfOps.imagesToPdf(imageFiles, tmpPdf).fold(
                    onSuccess = { pdf ->
                        val saved = withContext(Dispatchers.IO) {
                            FileHelper.saveToDownloads(this@DocumentScannerActivity, pdf)
                        }
                        toast("Saved: ${saved.displayPath}")
                        finish()
                    },
                    onFailure = { e ->
                        toast("Save failed: ${e.message}")
                        doneBtn.isEnabled = true
                    }
                )
            } catch (e: Exception) {
                toast("Error: ${e.message}")
                doneBtn.isEnabled = true
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        capturedBitmaps.forEach { it.recycle() }
    }
}
