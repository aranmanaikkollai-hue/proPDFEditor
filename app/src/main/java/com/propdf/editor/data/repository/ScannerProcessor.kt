package com.propdf.editor.data.repository

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Optimized Document Scanner Processor with:
 * - Proper OpenCV Mat lifecycle management (release after use)
 * - Sequential processing to prevent memory spikes
 * - Error isolation per image
 * - Configurable quality levels
 */
class ScannerProcessor {

    companion object {
        private const val TAG = "ScannerProc"
        private const val MAX_DIMENSION = 3000 // Prevent OOM on huge images
    }

    /**
     * Process scanned image: auto-crop, perspective correction, enhance.
     * Returns null on failure (never crashes).
     */
    suspend fun processDocument(bitmap: Bitmap, quality: ProcessQuality = ProcessQuality.MEDIUM): Bitmap? = withContext(Dispatchers.Default) {
        var mat: Mat? = null
        var gray: Mat? = null
        var edges: Mat? = null
        var warped: Mat? = null

        try {
            // Resize if too large to prevent OOM
            val scale = if (max(bitmap.width, bitmap.height) > MAX_DIMENSION) {
                MAX_DIMENSION.toFloat() / max(bitmap.width, bitmap.height)
            } else 1f

            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            } else bitmap

            mat = Mat()
            Utils.bitmapToMat(scaled, mat)
            if (scaled !== bitmap) scaled.recycle()

            gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

            // Auto-detect document edges
            edges = Mat()
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(gray, edges, 75.0, 200.0)

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()

            // Find largest quadrilateral (document)
            val docContour = findLargestQuadrilateral(contours)

            warped = if (docContour != null) {
                perspectiveTransform(mat, docContour)
            } else {
                // No document found — just enhance
                mat.clone()
            }

            // Apply quality enhancement
            enhance(warped, quality)

            // Convert back to bitmap
            val result = Bitmap.createBitmap(warped.cols(), warped.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warped, result)
            result

        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OOM processing document", oom)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Processing error", e)
            null
        } finally {
            mat?.release()
            gray?.release()
            edges?.release()
            warped?.release()
        }
    }

    private fun findLargestQuadrilateral(contours: List<MatOfPoint>): MatOfPoint2f? {
        var maxArea = 0.0
        var best: MatOfPoint2f? = null

        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area < 10000) continue // Too small

            val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)

            if (approx.toArray().size == 4 && area > maxArea) {
                maxArea = area
                best = approx
            } else {
                approx.release()
            }
        }
        return best
    }

    private fun perspectiveTransform(src: Mat, contour: MatOfPoint2f): Mat {
        val pts = contour.toArray().sortedBy { it.x + it.y }
        val tl = pts[0]
        val br = pts[3]
        val tr = if (pts[1].x > pts[2].x) pts[1] else pts[2]
        val bl = if (pts[1].x > pts[2].x) pts[2] else pts[1]

        val widthA = abs(br.x - bl.x)
        val widthB = abs(tr.x - tl.x)
        val maxWidth = max(widthA, widthB).toInt()

        val heightA = abs(tr.y - br.y)
        val heightB = abs(tl.y - bl.y)
        val maxHeight = max(heightA, heightB).toInt()

        val srcMat = MatOfPoint2f(tl, tr, br, bl)
        val dstMat = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth.toDouble(), 0.0),
            Point(maxWidth.toDouble(), maxHeight.toDouble()),
            Point(0.0, maxHeight.toDouble())
        )

        val transform = Imgproc.getPerspectiveTransform(srcMat, dstMat)
        val warped = Mat()
        Imgproc.warpPerspective(src, warped, transform, Size(maxWidth.toDouble(), maxHeight.toDouble()))

        srcMat.release()
        dstMat.release()
        transform.release()
        contour.release()

        return warped
    }

    private fun enhance(mat: Mat, quality: ProcessQuality) {
        when (quality) {
            ProcessQuality.LOW -> {
                // Fast: just grayscale
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2GRAY)
            }
            ProcessQuality.MEDIUM -> {
                // Auto contrast
                val lab = Mat()
                Imgproc.cvtColor(mat, lab, Imgproc.COLOR_BGR2Lab)
                val channels = ArrayList<Mat>()
                Core.split(lab, channels)
                Imgproc.equalizeHist(channels[0], channels[0])
                Core.merge(channels, lab)
                Imgproc.cvtColor(lab, mat, Imgproc.COLOR_Lab2BGR)
                channels.forEach { it.release() }
                lab.release()
            }
            ProcessQuality.HIGH -> {
                // Full enhancement
                val lab = Mat()
                Imgproc.cvtColor(mat, lab, Imgproc.COLOR_BGR2Lab)
                val channels = ArrayList<Mat>()
                Core.split(lab, channels)
                Imgproc.equalizeHist(channels[0], channels[0])
                Core.merge(channels, lab)
                Imgproc.cvtColor(lab, mat, Imgproc.COLOR_Lab2BGR)
                channels.forEach { it.release() }
                lab.release()

                // Sharpen
                val kernel = Mat(3, 3, CvType.CV_32F).apply {
                    put(0, 0, 0.0, -1.0, 0.0)
                    put(1, 0, -1.0, 5.0, -1.0)
                    put(2, 0, 0.0, -1.0, 0.0)
                }
                Imgproc.filter2D(mat, mat, -1, kernel)
                kernel.release()
            }
        }
    }

    enum class ProcessQuality { LOW, MEDIUM, HIGH }
}
