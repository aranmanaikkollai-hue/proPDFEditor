package com.propdf.scanner.processing

import android.graphics.Bitmap
import com.propdf.scanner.model.ScanMode
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

class ScanModeDetector {
    companion object {
        private const val PASSPORT_RATIO = 0.703
        private const val ID_CARD_RATIO = 1.586
        private const val RECEIPT_RATIO = 0.4
        private const val BUSINESS_CARD_RATIO = 1.75
        private const val WHITEBOARD_RATIO_MIN = 1.3
        private const val WHITEBOARD_RATIO_MAX = 2.0
        private const val BUSINESS_CARD_MAX_AREA = 0.15f
        private const val ID_CARD_MAX_AREA = 0.25f
    }

    fun detectScanMode(bitmap: Bitmap): ScanMode {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return try { detectScanModeInternal(mat, bitmap.width, bitmap.height) } finally { mat.release() }
    }

    private fun detectScanModeInternal(mat: Mat, width: Int, height: Int): ScanMode {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

        if (contours.isEmpty()) {
            gray.release(); edges.release(); hierarchy.release()
            return ScanMode.DOCUMENT
        }

        val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) } ?: contours[0]
        val area = Imgproc.contourArea(largestContour)
        val totalArea = (width * height).toDouble()
        val areaRatio = area / totalArea

        val peri = Imgproc.arcLength(MatOfPoint2f(*largestContour.toArray()), true)
        val approx = MatOfPoint2f()
        Imgproc.approxPolyDP(MatOfPoint2f(*largestContour.toArray()), approx, 0.02 * peri, true)

        if (approx.total() == 4L) {
            val points = approx.toList()
            val aspectRatio = calculateAspectRatio(points)
            val mode = when {
                isPassport(aspectRatio, areaRatio) -> ScanMode.PASSPORT
                isIdCard(aspectRatio, areaRatio) -> ScanMode.ID_CARD
                isReceipt(aspectRatio) -> ScanMode.RECEIPT
                isBusinessCard(aspectRatio, areaRatio) -> ScanMode.BUSINESS_CARD
                isWhiteboard(aspectRatio, areaRatio) -> ScanMode.WHITEBOARD
                else -> ScanMode.DOCUMENT
            }
            approx.release(); gray.release(); edges.release(); hierarchy.release()
            contours.forEach { it.release() }
            return mode
        }

        approx.release(); gray.release(); edges.release(); hierarchy.release()
        contours.forEach { it.release() }
        return ScanMode.DOCUMENT
    }

    private fun calculateAspectRatio(points: List<Point>): Float {
        val topEdge = distance(points[0], points[1])
        val rightEdge = distance(points[1], points[2])
        return if (rightEdge > 0) (topEdge / rightEdge).toFloat() else 1.0f
    }

    private fun isPassport(ratio: Float, areaRatio: Double) = abs(ratio - PASSPORT_RATIO) < 0.15 && areaRatio < 0.4
    private fun isIdCard(ratio: Float, areaRatio: Double) = abs(ratio - ID_CARD_RATIO) < 0.2 && areaRatio < ID_CARD_MAX_AREA
    private fun isReceipt(ratio: Float) = ratio < RECEIPT_RATIO || ratio > (1 / RECEIPT_RATIO)
    private fun isBusinessCard(ratio: Float, areaRatio: Double) = abs(ratio - BUSINESS_CARD_RATIO) < 0.3 && areaRatio < BUSINESS_CARD_MAX_AREA
    private fun isWhiteboard(ratio: Float, areaRatio: Double) = ratio in WHITEBOARD_RATIO_MIN..WHITEBOARD_RATIO_MAX && areaRatio > 0.5
    private fun distance(p1: Point, p2: Point) = sqrt((p1.x - p2.x) * (p1.x - p2.x) + (p1.y - p2.y) * (p1.y - p2.y))
}
