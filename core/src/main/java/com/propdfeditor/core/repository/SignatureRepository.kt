package com.propdfeditor.core.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.propdfeditor.core.database.dao.SignatureDao
import com.propdfeditor.core.database.entity.SignatureEntity
import com.propdfeditor.core.util.FileUtils
import com.propdfeditor.core.util.ImageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SignatureRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val signatureDao: SignatureDao
) {
    private val signatureDir: File by lazy {
        File(context.filesDir, "signatures").apply { mkdirs() }
    }

    fun getAllSignatures(): Flow<List<SignatureEntity>> = signatureDao.getAllSignatures()

    fun getSignaturesByType(type: SignatureEntity.SignatureType): Flow<List<SignatureEntity>> =
        signatureDao.getSignaturesByType(type)

    fun getFavoriteSignatures(): Flow<List<SignatureEntity>> = signatureDao.getFavoriteSignatures()

    suspend fun getSignatureById(id: Long): SignatureEntity? = signatureDao.getSignatureById(id)

    suspend fun createDrawnSignature(
        name: String,
        bitmap: Bitmap,
        strokeWidth: Float = 4f,
        strokeColor: Int = Color.BLACK,
        backgroundColor: Int = Color.TRANSPARENT,
        width: Int = 400,
        height: Int = 200
    ): SignatureEntity = withContext(Dispatchers.IO) {
        val fileName = "drawn_${System.currentTimeMillis()}.png"
        val file = File(signatureDir, fileName)
        
        ImageUtils.saveBitmapWithBackground(bitmap, file, backgroundColor)
        
        val entity = SignatureEntity(
            name = name,
            type = SignatureEntity.SignatureType.DRAWN,
            bitmapPath = file.absolutePath,
            strokeWidth = strokeWidth,
            strokeColor = strokeColor,
            backgroundColor = backgroundColor,
            width = width,
            height = height,
            createdAt = Date(),
            updatedAt = Date()
        )
        
        val id = signatureDao.insertSignature(entity)
        entity.copy(id = id)
    }

    suspend fun createImageSignature(
        name: String,
        imageUri: Uri,
        backgroundColor: Int = Color.TRANSPARENT,
        width: Int = 400,
        height: Int = 200
    ): SignatureEntity = withContext(Dispatchers.IO) {
        val fileName = "image_${System.currentTimeMillis()}.png"
        val file = File(signatureDir, fileName)
        
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Cannot read image URI")
        
        // Process image to have transparent background if needed
        ImageUtils.processSignatureImage(file, backgroundColor)
        
        val entity = SignatureEntity(
            name = name,
            type = SignatureEntity.SignatureType.IMAGE,
            bitmapPath = file.absolutePath,
            backgroundColor = backgroundColor,
            width = width,
            height = height,
            createdAt = Date(),
            updatedAt = Date()
        )
        
        val id = signatureDao.insertSignature(entity)
        entity.copy(id = id)
    }

    suspend fun createTypedSignature(
        name: String,
        text: String,
        fontFamily: String = "cursive",
        fontSize: Float = 48f,
        textColor: Int = Color.BLACK,
        backgroundColor: Int = Color.TRANSPARENT,
        width: Int = 400,
        height: Int = 200
    ): SignatureEntity = withContext(Dispatchers.IO) {
        val bitmap = renderTypedSignature(text, fontFamily, fontSize, textColor, backgroundColor, width, height)
        val fileName = "typed_${System.currentTimeMillis()}.png"
        val file = File(signatureDir, fileName)
        
        ImageUtils.saveBitmapWithBackground(bitmap, file, backgroundColor)
        
        val entity = SignatureEntity(
            name = name,
            type = SignatureEntity.SignatureType.TYPED,
            textContent = text,
            fontFamily = fontFamily,
            fontSize = fontSize,
            textColor = textColor,
            backgroundColor = backgroundColor,
            bitmapPath = file.absolutePath,
            width = width,
            height = height,
            createdAt = Date(),
            updatedAt = Date()
        )
        
        val id = signatureDao.insertSignature(entity)
        entity.copy(id = id)
    }

    suspend fun updateSignature(signature: SignatureEntity) {
        signatureDao.updateSignature(signature.copy(updatedAt = Date()))
    }

    suspend fun deleteSignature(signature: SignatureEntity) = withContext(Dispatchers.IO) {
        signature.bitmapPath?.let { path ->
            File(path).delete()
        }
        signatureDao.deleteSignature(signature)
    }

    suspend fun incrementUseCount(id: Long) {
        signatureDao.incrementUseCount(id)
    }

    suspend fun setFavorite(id: Long, isFavorite: Boolean) {
        signatureDao.setFavorite(id, isFavorite)
    }

    fun getSignatureBitmap(signature: SignatureEntity): Bitmap? {
        return signature.bitmapPath?.let { path ->
            ImageUtils.loadBitmap(File(path))
        }
    }

    private fun renderTypedSignature(
        text: String,
        fontFamily: String,
        fontSize: Float,
        textColor: Int,
        backgroundColor: Int,
        width: Int,
        height: Int
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Background
        if (backgroundColor != Color.TRANSPARENT) {
            canvas.drawColor(backgroundColor)
        } else {
            bitmap.eraseColor(Color.TRANSPARENT)
        }
        
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            this.textSize = fontSize
            textAlign = Paint.Align.CENTER
            
            typeface = when (fontFamily.lowercase()) {
                "cursive", "script" -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
                "serif" -> Typeface.SERIF
                "sans-serif" -> Typeface.SANS_SERIF
                "monospace" -> Typeface.MONOSPACE
                "bold" -> Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                else -> Typeface.create(Typeface.SERIF, Typeface.ITALIC)
            }
        }
        
        // Center text vertically and horizontally
        val x = width / 2f
        val y = height / 2f - (paint.descent() + paint.ascent()) / 2f
        
        // Add underline for signature feel
        val textWidth = paint.measureText(text)
        val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            strokeWidth = 2f
            alpha = 128
        }
        
        canvas.drawText(text, x, y, paint)
        canvas.drawLine(
            x - textWidth / 2 - 20,
            y + paint.descent() + 5,
            x + textWidth / 2 + 20,
            y + paint.descent() + 5,
            underlinePaint
        )
        
        return bitmap
    }
}
