package com.propdf.share.data.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.propdf.share.domain.model.LanServerInfo
import com.google.gson.Gson
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates QR codes for sharing LAN server info.
 */
@Singleton
class QrCodeGenerator @Inject constructor() {
    private val gson = Gson()

    fun generateLanQr(serverInfo: LanServerInfo, size: Int = 512): Bitmap? {
        return try {
            val json = gson.toJson(serverInfo)
            generateQrCode(json, size)
        } catch (e: Exception) {
            null
        }
    }

    fun generateQrCode(content: String, size: Int = 512): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.MARGIN to 2
            )
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bitmap
        } catch (e: Exception) {
            null
        }
    }
}
