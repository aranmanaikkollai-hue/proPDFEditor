package com.propdf.scanner.processing

import android.graphics.Bitmap
import android.graphics.PointF
import com.propdf.scanner.model.DocumentEdge
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class PerspectiveCorrector {
    companion object {
        private const val MAX_OUTPUT_WIDTH = 2400
        private const val MAX_OUTPUT_HEIGHT = 3200
    }

    fun correctPerspective(bitmap: Bitmap, edge: DocumentEdge): Bitmap {
        val srcMat = Mat()
        Utils.bitmapToMat(bitmap, srcMat)
        return try { correctPerspectiveInternal(srcMat, edge, bitmap.width, bitmap.height) } finally { srcMat.release() }
    }

    private fun correctPerspectiveInternal(srcMat: Mat, edge: DocumentEdge, srcWidth: Int, srcHeight: Int): Bitmap {
        val srcPoints = MatOfPoint2f(
            Point(edge.topLeft.x.toDouble(), edge.topLeft.y.toDouble()),
            Point(edge.topRight.x.toDouble(), edge.topRight.y.toDouble()),
            Point(edge.bottomRight.x.toDouble(), edge.bottomRight.y.toDouble()),
            Point(edge.bottomLeft.x.toDouble(), edge.bottomLeft.y.toDouble())
        )

        val topEdge = distance(edge.topLeft, edge.topRight)
        val rightEdge = distance(edge.topRight, edge.bottomRight)
        val bottomEdge = distance(edge.bottomRight, edge.bottomLeft)
        val leftEdge = distance(edge.bottomLeft, edge.topLeft)

        val maxWidth = max(topEdge, bottomEdge).toInt()
        val maxHeight = max(leftEdge, rightEdge).toInt()
        val scale = min(MAX_OUTPUT_WIDTH.toFloat() / maxWidth, MAX_OUTPUT_HEIGHT.toFloat() / maxHeight).coerceAtMost(1.0f)
        val outputWidth = (maxWidth * scale).toInt()
        val outputHeight = (maxHeight * scale).toInt()

        val dstPoints = MatOfPoint2f(
            Point(0.0, 0.0), Point(outputWidth.toDouble(), 0.0),
            Point(outputWidth.toDouble(), outputHeight.toDouble()), Point(0.0, outputHeight.toDouble())
        )

        val homography = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
        val dstMat = Mat(outputHeight, outputWidth, srcMat.type())
        Imgproc.warpPerspective(srcMat, dstMat, homography, Size(outputWidth.toDouble(), outputHeight.toDouble()))

        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(dstMat, result)

        srcPoints.release(); dstPoints.release(); homography.release(); dstMat.release()
        return result
    }

    fun autoCrop(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return try { autoCropInternal(mat, bitmap) } finally { mat.release() }
    }

    private fun autoCropInternal(mat: Mat, originalBitmap: Bitmap): Bitmap {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        val binary = Mat()
        Imgproc.adaptiveThreshold(gray, binary, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 11, 2.0)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(binary, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            gray.release(); binary.release(); hierarchy.release()
            return originalBitmap
        }

        var minX = mat.cols().toFloat(); var minY = mat.rows().toFloat()
        var maxX = 0f; var maxY = 0f

        for (contour in contours) {
            val rect = Imgproc.boundingRect(contour)
            minX = min(minX, rect.x.toFloat()); minY = min(minY, rect.y.toFloat())
            maxX = max(maxX, (rect.x + rect.width).toFloat()); maxY = max(maxY, (rect.y + rect.height).toFloat())
            contour.release()
        }

        val padding = 20
        minX = max(0f, minX - padding); minY = max(0f, minY - padding)
        maxX = min(mat.cols().toFloat(), maxX + padding); maxY = min(mat.rows().toFloat(), maxY + padding)

        val cropWidth = (maxX - minX).toInt(); val cropHeight = (maxY - minY).toInt()
        if (cropWidth <= 0 || cropHeight <= 0 || cropWidth > originalBitmap.width || cropHeight > originalBitmap.height) {
            gray.release(); binary.release(); hierarchy.release()
            return originalBitmap
        }

        val cropped = Bitmap.createBitmap(originalBitmap, minX.toInt(), minY.toInt(), cropWidth, cropHeight)
        gray.release(); binary.release(); hierarchy.release()
        return cropped
    }

    private fun distance(p1: PointF, p2: PointF) = sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
}
