package com.propdf.editor.ui.scanner

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
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
import androidx.lifecycle.lifecycleScope
import com.propdf.editor.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DocumentScannerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DocScanner"
        private const val PERM_REQ = 2001
        private val PERMS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }

    private var isDark = true
    private val prefs by lazy { getSharedPreferences("propdf_prefs", Context.MODE_PRIVATE) }

    private fun bg() = if (isDark) Color.parseColor("#0E0E0E") else Color.parseColor("#F2F2F7")
    private fun cardBg() = if (isDark) Color.parseColor("#1A1A1A") else Color.WHITE
    private fun txt1() = if (isDark) "#FFFFFF" else "#1A1A1A"
    private fun txt2() = if (isDark) "#A0A0A0" else "#6B7280"
    private val c_pri = Color.parseColor("#448AFF")

    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private val capturedBitmaps = mutableListOf<Bitmap>()
    private var currentFilter = "auto"
    private var currentBrightness = 0

    // Store previewView reference so startCamera can use it
    private var previewView: PreviewView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isDark = prefs.getBoolean("dark_mode", true)
        cameraExecutor = Executors.newSingleThreadExecutor()
        if (allPermissionsGranted()) { buildUI(); startCamera() }
        else { ActivityCompat.requestPermissions(this, PERMS, PERM_REQ) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERM_REQ && allPermissionsGranted()) { buildUI(); startCamera() }
        else { toast("Camera permission required"); finish() }
    }

    private fun allPermissionsGranted() = PERMS.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun buildUI() {
        val root = FrameLayout(this).apply { setBackgroundColor(bg()) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        column.addView(buildTopBar())
        val pv = PreviewView(this).apply {
            layoutParams = LinearLayout.LayoutParams(-1, 0, 1f)
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        previewView = pv
        column.addView(pv)
        column.addView(buildBottomBar(pv))
        root.addView(column)
        setContentView(root)
    }

    private fun buildTopBar(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(12), dp(40), dp(12), dp(10))
            addView(TextView(this@DocumentScannerActivity).apply {
                text = "Back"
                textSize = 14f
                setTextColor(c_pri)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { finish() }
            })
            addView(View(this@DocumentScannerActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            addView(TextView(this@DocumentScannerActivity).apply {
                text = "Scanner"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor(txt1()))
            })
            addView(View(this@DocumentScannerActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 0, 1f) })
            addView(TextView(this@DocumentScannerActivity).apply {
                text = "Gallery"
                textSize = 14f
                setTextColor(c_pri)
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { pickImageFromGallery() }
            })
        }
    }

    private fun buildBottomBar(pv: PreviewView): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg())
            setPadding(dp(12), dp(10), dp(12), dp(24))
            addView(LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                val modes = listOf("Auto" to "auto", "Color" to "color", "Gray" to "gray", "B&W" to "bw")
                modes.forEach { (label, mode) ->
                    addView(TextView(this@DocumentScannerActivity).apply {
                        text = label
                        textSize = 12f
                        setTextColor(if (currentFilter == mode) c_pri else Color.parseColor(txt2()))
                        setPadding(dp(12), dp(6), dp(12), dp(6))
                        setOnClickListener { currentFilter = mode; updateFilterUI() }
                    })
                }
            })
            addView(LinearLayout(this@DocumentScannerActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(ImageButton(this@DocumentScannerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(16) }
                    setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { finish() }
                })
                addView(ImageButton(this@DocumentScannerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(c_pri) }
                    setOnClickListener { takePhoto(pv) }
                })
                addView(ImageButton(this@DocumentScannerActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginStart = dp(16) }
                    setImageResource(android.R.drawable.ic_menu_gallery)
                    setBackgroundColor(Color.TRANSPARENT)
                    setOnClickListener { showCapturedImages() }
                })
            })
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
            imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            } catch (_: Exception) { toast("Camera init failed") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto(pv: PreviewView) {
        val capture = imageCapture ?: return
        val file = File(externalMediaDirs.first(), "${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onError(exc: ImageCaptureException) { toast("Capture failed: ${exc.message}") }
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                val bmp = BitmapFactory.decodeFile(file.absolutePath)
                if (bmp != null) {
                    capturedBitmaps.add(bmp)
                    toast("Photo captured (${capturedBitmaps.size})")
                }
            }
        })
    }

    private fun pickImageFromGallery() {
        val pick = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(pick, 3001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 3001 && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                contentResolver.openInputStream(uri)?.use {
                    val bmp = BitmapFactory.decodeStream(it)
                    if (bmp != null) { capturedBitmaps.add(bmp); toast("Image added (${capturedBitmaps.size})") }
                }
            }
        }
    }

    private fun showCapturedImages() {
        if (capturedBitmaps.isEmpty()) { toast("No images captured"); return }
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        capturedBitmaps.forEachIndexed { i, bmp ->
            col.addView(ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(-1, dp(200)).apply { bottomMargin = dp(8) }
                setImageBitmap(bmp)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setOnClickListener { showImageOptions(i) }
            })
        }
        scroll.addView(col)
        AlertDialog.Builder(this).setTitle("Captured Images").setView(scroll)
            .setPositiveButton("Save as PDF") { _, _ -> saveAsPdf() }
            .setNegativeButton("Save as JPEGs") { _, _ -> saveAsJpegs() }
            .setNeutralButton("Close", null).show()
    }

    private fun showImageOptions(index: Int) {
        val items = arrayOf("Remove", "Rotate 90", "Rotate -90", "Flip Horizontal", "Flip Vertical", "Brightness +20", "Brightness -20", "Grayscale", "Black & White", "Sepia", "High Contrast", "Auto Enhance")
        AlertDialog.Builder(this).setTitle("Image Options").setItems(items) { _, which ->
            val bmp = capturedBitmaps[index]
            val modified = when (which) {
                0 -> { capturedBitmaps.removeAt(index); toast("Removed"); return@setItems }
                1 -> rotateBitmap(bmp, 90f)
                2 -> rotateBitmap(bmp, -90f)
                3 -> flipBitmap(bmp, horizontal = true)
                4 -> flipBitmap(bmp, horizontal = false)
                5 -> adjustBrightness(bmp, 20)
                6 -> adjustBrightness(bmp, -20)
                7 -> applyGrayscale(bmp)
                8 -> applyBlackAndWhite(bmp)
                9 -> applySepia(bmp)
                10 -> applyHighContrast(bmp)
                11 -> autoEnhance(bmp)
                else -> bmp
            }
            capturedBitmaps[index] = modified
            showCapturedImages()
        }.show()
    }

    private fun saveAsPdf() {
        if (capturedBitmaps.isEmpty()) { toast("No images to save"); return }
        val doc = android.graphics.pdf.PdfDocument()
        try {
            capturedBitmaps.forEachIndexed { i, bmp ->
                val pi = android.graphics.pdf.PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(pi)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            val fileName = "Scan_${System.currentTimeMillis()}.pdf"
            var outUri: Uri? = null
            val out: java.io.OutputStream? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                }
                outUri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                outUri?.let { contentResolver.openOutputStream(it) }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                val outFile = File(dir, fileName)
                outUri = Uri.fromFile(outFile)
                FileOutputStream(outFile)
            }
            if (out == null) { toast("Cannot create output"); return }
            out.use { doc.writeTo(it) }
            toast("Saved to Downloads/ProPDF")
        } catch (e: Exception) { toast("Save failed: ${e.message}") } finally { doc.close() }
    }

    private fun saveAsJpegs() {
        if (capturedBitmaps.isEmpty()) { toast("No images to save"); return }
        capturedBitmaps.forEachIndexed { i, bmp ->
            val fileName = "Scan_${System.currentTimeMillis()}_page${i + 1}.jpg"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ProPDF")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { contentResolver.openOutputStream(it)?.use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ProPDF").also { it.mkdirs() }
                File(dir, fileName).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        toast("Saved ${capturedBitmaps.size} JPEGs to Downloads/ProPDF")
    }

    private fun rotateBitmap(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun flipBitmap(src: Bitmap, horizontal: Boolean): Bitmap {
        val matrix = Matrix().apply {
            if (horizontal) postScale(-1f, 1f, src.width / 2f, src.height / 2f)
            else postScale(1f, -1f, src.width / 2f, src.height / 2f)
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun adjustBrightness(src: Bitmap, delta: Int): Bitmap {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (((c shr 16) and 0xFF) + delta).coerceIn(0, 255)
            val g = (((c shr 8) and 0xFF) + delta).coerceIn(0, 255)
            val b = ((c and 0xFF) + delta).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }
        src.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return src
    }

    private fun applyGrayscale(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setSaturation(0f) }) }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun applyBlackAndWhite(src: Bitmap): Bitmap {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        for (i in pixels.indices) {
            val l = ((0.299 * ((pixels[i] shr 16) and 0xFF) + 0.587 * ((pixels[i] shr 8) and 0xFF) + 0.114 * (pixels[i] and 0xFF)).toInt())
            val bw = if (l > 128) 0xFF else 0x00
            pixels[i] = Color.argb(0xFF, bw, bw, bw)
        }
        src.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return src
    }

    private fun applySepia(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                set(floatArrayOf(0.393f,0.769f,0.189f,0f,0f, 0.349f,0.686f,0.168f,0f,0f, 0.272f,0.534f,0.131f,0f,0f, 0f,0f,0f,1f,0f))
            })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun applyHighContrast(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply {
                set(floatArrayOf(2f,0f,0f,0f,-128f, 0f,2f,0f,0f,-128f, 0f,0f,2f,0f,-128f, 0f,0f,0f,1f,0f))
            })
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun autoEnhance(src: Bitmap): Bitmap {
        val pixels = IntArray(src.width * src.height)
        src.getPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        var minL = 255; var maxL = 0
        for (p in pixels) {
            val l = ((0.299 * ((p shr 16) and 0xFF) + 0.587 * ((p shr 8) and 0xFF) + 0.114 * (p and 0xFF)).toInt())
            if (l < minL) minL = l; if (l > maxL) maxL = l
        }
        val range = (maxL - minL).coerceAtLeast(1)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = ((((c shr 16) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            val g = ((((c shr 8) and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            val b = (((c and 0xFF) - minL) * 255 / range).coerceIn(0, 255)
            pixels[i] = Color.argb(0xFF, r, g, b)
        }
        src.setPixels(pixels, 0, src.width, 0, 0, src.width, src.height)
        return src
    }

    private fun updateFilterUI() { /* Refresh UI */ }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
