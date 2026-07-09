package com.propdf.scanner.processing

import android.graphics.Bitmap
import android.graphics.PointF
import com.propdf.scanner.model.DocumentEdge
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

class EdgeDetector {
    companion object {
        private const val CANNY_THRESHOLD1 = 50.0
        private const val CANNY_THRESHOLD2 = 150.0
        private const val MIN_CONTOUR_AREA_RATIO = 0.15
        private const val MAX_CONTOUR_AREA_RATIO = 0.95
        private const val CORNER_REFINE_ITERATIONS = 30
        private const val CORNER_REFINE_EPSILON = 0.001
    }

    fun detectEdges(bitmap: Bitmap): DocumentEdge? {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return try { detectEdgesInMat(mat, bitmap.width.toFloat(), bitmap.height.toFloat()) } finally { mat.release() }
    }

    private fun detectEdgesInMat(mat: Mat, width: Float, height: Float): DocumentEdge? {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)
        val blurred = Mat()
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(blurred, edges, CANNY_THRESHOLD1, CANNY_THRESHOLD2)
        val dilated = Mat()
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        Imgproc.dilate(edges, dilated, kernel)

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.sortByDescending { Imgproc.contourArea(it) }

        val totalArea = width * height
        val minArea = totalArea * MIN_CONTOUR_AREA_RATIO
        val maxArea = totalArea * MAX_CONTOUR_AREA_RATIO

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) continue

            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)

            if (approx.total() == 4L) {
                val points = approx.toList()
                val corners = points.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                if (isValidAspectRatio(corners)) {
                    val ordered = orderCorners(corners)
                    val refined = refineCorners(gray, ordered)
                    releaseAll(gray, blurred, edges, dilated, hierarchy, approx, contours)
                    return DocumentEdge(refined[0], refined[1], refined[2], refined[3])
                }
            }
            approx.release()
        }
        releaseAll(gray, blurred, edges, dilated, hierarchy, null, contours)
        return null
    }

    fun detectEdgesFast(bitmap: Bitmap): DocumentEdge? {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        return try { detectEdgesFastInMat(mat, bitmap.width.toFloat(), bitmap.height.toFloat()) } finally { mat.release() }
    }

    private fun detectEdgesFastInMat(mat: Mat, width: Float, height: Float): DocumentEdge? {
        val scale = 0.5
        val resized = Mat()
        Imgproc.resize(mat, resized, Size(width * scale, height * scale))
        val gray = Mat()
        Imgproc.cvtColor(resized, gray, Imgproc.COLOR_BGR2GRAY)
        val edges = Mat()
        Imgproc.Canny(gray, edges, CANNY_THRESHOLD1, CANNY_THRESHOLD2)
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.sortByDescending { Imgproc.contourArea(it) }

        val totalArea = width * height * scale * scale
        val minArea = totalArea * MIN_CONTOUR_AREA_RATIO
        val maxArea = totalArea * MAX_CONTOUR_AREA_RATIO

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) continue
            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                val points = approx.toList()
                val corners = points.map { PointF((it.x / scale).toFloat(), (it.y / scale).toFloat()) }
                if (isValidAspectRatio(corners)) {
                    val ordered = orderCorners(corners)
                    releaseAll(null, null, edges, null, hierarchy, approx, contours)
                    resized.release(); gray.release()
                    return DocumentEdge(ordered[0], ordered[1], ordered[2], ordered[3])
                }
            }
            approx.release()
        }
        resized.release(); gray.release(); edges.release(); hierarchy.release()
        contours.forEach { it.release() }
        return null
    }

    private fun isValidAspectRatio(corners: List<PointF>): Boolean {
        if (corners.size != 4) return false
        val topEdge = distance(corners[0], corners[1])
        val rightEdge = distance(corners[1], corners[2])
        val bottomEdge = distance(corners[2], corners[3])
        val leftEdge = distance(corners[3], corners[0])
        val avgWidth = (topEdge + bottomEdge) / 2
        val avgHeight = (rightEdge + leftEdge) / 2
        if (avgWidth == 0f || avgHeight == 0f) return false
        return (avgWidth / avgHeight) in 0.3f..3.0f
    }

    private fun orderCorners(corners: List<PointF>): List<PointF> {
        val centerX = corners.map { it.x }.average().toFloat()
        val centerY = corners.map { it.y }.average().toFloat()
        return corners.sortedBy { atan2(it.y - centerY, it.x - centerX) }.let {
            listOf(it[3], it[0], it[1], it[2])
        }
    }

    private fun refineCorners(grayMat: Mat, corners: List<PointF>): List<PointF> {
        val cornersMat = MatOfPoint2f()
        cornersMat.fromList(corners.map { Point(it.x.toDouble(), it.y.toDouble()) })
        val criteria = TermCriteria(TermCriteria.EPS + TermCriteria.MAX_ITER, CORNER_REFINE_ITERATIONS, CORNER_REFINE_EPSILON)
        Imgproc.cornerSubPix(grayMat, cornersMat, Size(5.0, 5.0), Size(-1.0, -1.0), criteria)
        val refined = cornersMat.toList().map { PointF(it.x.toFloat(), it.y.toFloat()) }
        cornersMat.release()
        return refined
    }

    private fun distance(p1: PointF, p2: PointF) = sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2))

    private fun releaseAll(gray: Mat?, blurred: Mat?, edges: Mat?, dilated: Mat?, hierarchy: Mat?, approx: MatOfPoint2f?, contours: List<MatOfPoint>) {
        gray?.release(); blurred?.release(); edges?.release(); dilated?.release(); hierarchy?.release(); approx?.release(); contours.forEach { it.release() }
    }
}
