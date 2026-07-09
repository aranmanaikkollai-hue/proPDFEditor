package com.propdf.scanner.processing

import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Canvas
import com.propdf.scanner.model.ColorFilter
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class ImageEnhancer {
    companion object {
        private const val SHADOW_KERNEL_SIZE = 25
        private const val GLARE_THRESHOLD = 240
    }

    fun applyFilter(bitmap: Bitmap, filter: ColorFilter): Bitmap = when (filter) {
        ColorFilter.ORIGINAL -> bitmap.copy(Bitmap.Config.ARGB_8888, true)
        ColorFilter.MAGIC_COLOR -> applyMagicColor(bitmap)
        ColorFilter.GRAYSCALE -> applyGrayscale(bitmap)
        ColorFilter.BLACK_WHITE -> applyBlackWhite(bitmap)
        ColorFilter.SHADOW_REMOVAL -> removeShadows(bitmap)
        ColorFilter.NOISE_REMOVAL -> removeNoise(bitmap)
        ColorFilter.GLARE_REMOVAL -> removeGlare(bitmap)
    }

    private fun applyMagicColor(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        try {
            val lab = Mat()
            Imgproc.cvtColor(mat, lab, Imgproc.COLOR_BGR2Lab)
            val channels = ArrayList<Mat>()
            Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])
            clahe.release()
            Core.merge(channels, lab)
            val result = Mat()
            Imgproc.cvtColor(lab, result, Imgproc.COLOR_Lab2BGR)
            whiteBalance(result)
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(result, output)
            channels.forEach { it.release() }; lab.release(); result.release()
            return output
        } finally { mat.release() }
    }

    private fun applyGrayscale(bitmap: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint()
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun applyBlackWhite(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        try {
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            val binary = Mat()
            Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 11, 10.0)
            val result = Mat()
            Imgproc.cvtColor(binary, result, Imgproc.COLOR_GRAY2BGR)
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(result, output)
            gray.release(); binary.release(); result.release()
            return output
        } finally { mat.release() }
    }

    private fun removeShadows(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        try {
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            val dilated = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(SHADOW_KERNEL_SIZE.toDouble(), SHADOW_KERNEL_SIZE.toDouble()))
            Imgproc.dilate(gray, dilated, kernel)
            val bg = Mat()
            Imgproc.medianBlur(dilated, bg, 21)
            val diff = Mat()
            Core.divide(gray, bg, diff, 255.0)
            val result = Mat()
            Imgproc.cvtColor(diff, result, Imgproc.COLOR_GRAY2BGR)
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(result, output)
            gray.release(); dilated.release(); bg.release(); diff.release(); result.release(); kernel.release()
            return output
        } finally { mat.release() }
    }

    private fun removeNoise(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        try {
            val denoised = Mat()
            Photo.fastNlMeansDenoisingColored(mat, denoised, 10f, 10f, 7, 21)
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(denoised, output)
            denoised.release()
            return output
        } finally { mat.release() }
    }

    private fun removeGlare(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        try {
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
            val mask = Mat()
            Imgproc.threshold(gray, mask, GLARE_THRESHOLD.toDouble(), 255.0, Imgproc.THRESH_BINARY)
            val dilatedMask = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(15.0, 15.0))
            Imgproc.dilate(mask, dilatedMask, kernel)
            val inpainted = Mat()
            Photo.inpaint(mat, dilatedMask, inpainted, 3.0, Photo.INPAINT_NS)
            val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(inpainted, output)
            gray.release(); mask.release(); dilatedMask.release(); inpainted.release(); kernel.release()
            return output
        } finally { mat.release() }
    }

    fun autoRotate(bitmap: Bitmap): Bitmap {
        val orientations = listOf(0, 90, 180, 270)
        var bestOrientation = 0
        var bestScore = -1.0
        for (angle in orientations) {
            val rotated = rotateBitmap(bitmap, angle)
            val score = calculateHorizontalLineScore(rotated)
            if (score > bestScore) { bestScore = score; bestOrientation = angle }
            if (angle != 0) rotated.recycle()
        }
        return if (bestOrientation == 0) bitmap else rotateBitmap(bitmap, bestOrientation)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun calculateHorizontalLineScore(bitmap: Bitmap): Double {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val lines = Mat()
        Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180, 100, 100.0, 10.0)
        var horizontalScore = 0.0
        for (i in 0 until lines.rows()) {
            val line = lines.get(i, 0)
            val angle = atan2(line[3] - line[1], line[2] - line[0]) * 180 / Math.PI
            if (abs(angle) < 10 || abs(angle) > 170) horizontalScore += abs(line[2] - line[0])
        }
        mat.release(); gray.release(); edges.release(); lines.release()
        return horizontalScore
    }

    private fun whiteBalance(mat: Mat) {
        val channels = ArrayList<Mat>()
        Core.split(mat, channels)
        for (channel in channels) {
            val mean = Core.mean(channel).`val`[0]
            Core.subtract(channel, Scalar(mean), channel)
            Core.add(channel, Scalar(128.0), channel)
        }
        Core.merge(channels, mat)
        channels.forEach { it.release() }
    }
}

object Photo {
    const val INPAINT_NS = 0
    const val INPAINT_TELEA = 1
    @JvmStatic external fun fastNlMeansDenoisingColored(src: Mat, dst: Mat, h: Float, hColor: Float, templateWindowSize: Int, searchWindowSize: Int)
    @JvmStatic external fun inpaint(src: Mat, mask: Mat, dst: Mat, inpaintRadius: Double, flags: Int)
}
