package com.propdf.editor.ui.ocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.propdf.editor.databinding.ActivityOcrCropBinding
import kotlin.math.max
import kotlin.math.min

class OcrCropActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOcrCropBinding
    private var originalBitmap: Bitmap? = null
    private var cropRect = Rect()
    private var isDragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOcrCropBinding.inflate(layoutInflater)
        setContentView(binding.root)
        intent.getParcelableExtra<Uri>("image_uri")?.let { loadImage(it) }
        setupTouchHandling()
        setupButtons()
    }

    private fun loadImage(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { stream ->
            originalBitmap = BitmapFactory.decodeStream(stream)
            binding.cropImageView.setImageBitmap(originalBitmap)
        }
    }

    private fun setupTouchHandling() {
        binding.cropOverlay.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    cropRect.left = event.x.toInt(); cropRect.top = event.y.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDragging) { cropRect.right = event.x.toInt(); cropRect.bottom = event.y.toInt(); binding.cropOverlay.setCropRect(cropRect); binding.cropOverlay.invalidate() }
                    true
                }
                MotionEvent.ACTION_UP -> { isDragging = false; normalizeCropRect(); true }
                else -> false
            }
        }
    }

    private fun normalizeCropRect() {
        cropRect.set(min(cropRect.left, cropRect.right), min(cropRect.top, cropRect.bottom),
            max(cropRect.left, cropRect.right), max(cropRect.top, cropRect.bottom))
    }

    private fun setupButtons() {
        binding.btnCrop.setOnClickListener { applyCrop() }
        binding.btnRotateLeft.setOnClickListener { rotateImage(-90f) }
        binding.btnRotateRight.setOnClickListener { rotateImage(90f) }
        binding.btnCancel.setOnClickListener { setResult(RESULT_CANCELED); finish() }
    }

    private fun applyCrop() {
        val bitmap = originalBitmap ?: return
        val normalizedRect = Rect(cropRect.left.coerceIn(0, bitmap.width), cropRect.top.coerceIn(0, bitmap.height),
            cropRect.right.coerceIn(0, bitmap.width), cropRect.bottom.coerceIn(0, bitmap.height))
        if (normalizedRect.width() <= 0 || normalizedRect.height() <= 0) { setResult(RESULT_CANCELED); finish(); return }
        val cropped = Bitmap.createBitmap(bitmap, normalizedRect.left, normalizedRect.top, normalizedRect.width(), normalizedRect.height())
        val file = java.io.File(cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
        java.io.FileOutputStream(file).use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 95, out) }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        setResult(RESULT_OK, android.content.Intent().putExtra("cropped_uri", uri))
        finish()
    }

    private fun rotateImage(degrees: Float) {
        val bitmap = originalBitmap ?: return
        val matrix = Matrix().apply { postRotate(degrees) }
        originalBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        binding.cropImageView.setImageBitmap(originalBitmap)
    }

    override fun onDestroy() { super.onDestroy(); originalBitmap?.recycle() }
}
