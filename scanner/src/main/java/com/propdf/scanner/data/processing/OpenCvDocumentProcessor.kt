package com.propdfeditor.scanner.data.processing

import android.graphics.Bitmap
import android.util.Log
import com.propdfeditor.scanner.domain.model.EdgeDetectionResult
import com.propdfeditor.scanner.domain.model.EnhancementParams
import com.propdfeditor.scanner.domain.model.PointF
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/**
 * Production-grade OpenCV document processing engine.
 * All operations are coroutine-safe and run on Default dispatcher.
 *
 * Memory strategy:
 * - Process on downscaled matrices when possible
 * - Explicitly release Mat objects after use
 * - Use try-finally for guaranteed cleanup
 */
class OpenCvDocumentProcessor {

    companion object {
        private const val TAG = "OpenCvDocumentProcessor"
        private const val EDGE_DETECT_SCALE = 500.0 // Process edge detection at this width
    }

    /**
     * Detect document edges using adaptive thresholding and contour detection.
     * Returns the 4 corners of the largest quadrilateral contour.
     *
     * Algorithm:
     * 1. Convert to grayscale
     * 2. Downscale for performance
     * 3. Gaussian blur + adaptive threshold
     * 4. Dilate to connect edges
     * 5. Find contours
     * 6. Approximate polygons, find largest quadrilateral
     * 7. Scale corners back to original size
     */
    suspend fun detectDocumentEdges(bitmap: Bitmap): EdgeDetectionResult = withContext(Dispatchers.Default) {
        if (!isActive) return@withContext EdgeDetectionResult(emptyList(), 0f, android.graphics.RectF())

        var srcMat: Mat? = null
        var grayMat: Mat? = null
        var blurredMat: Mat? = null
        var threshMat: Mat? = null
        var dilatedMat: Mat? = null
        var edgesMat: Mat? = null
        var scaledMat: Mat? = null
        var hierarchy: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            val originalWidth = srcMat.cols().toDouble()
            val originalHeight = srcMat.rows().toDouble()

            // Downscale for faster processing
            val scale = EDGE_DETECT_SCALE / originalWidth
            val scaledWidth = (originalWidth * scale).toInt()
            val scaledHeight = (originalHeight * scale).toInt()

            scaledMat = Mat()
            Imgproc.resize(srcMat, scaledMat, Size(scaledWidth.toDouble(), scaledHeight.toDouble()))

            // Convert to grayscale
            grayMat = Mat()
            Imgproc.cvtColor(scaledMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // Gaussian blur to reduce noise
            blurredMat = Mat()
            Imgproc.GaussianBlur(grayMat, blurredMat, Size(5.0, 5.0), 0.0)

            // Adaptive threshold for edge detection
            threshMat = Mat()
            Imgproc.adaptiveThreshold(
                blurredMat, threshMat, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 11, 2.0
            )

            // Dilate to connect broken edges
            dilatedMat = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.dilate(threshMat, dilatedMat, kernel)

            // Canny edge detection
            edgesMat = Mat()
            Imgproc.Canny(dilatedMat, edgesMat, 50.0, 150.0)

            // Find contours
            val contours = ArrayList<MatOfPoint>()
            hierarchy = Mat()
            Imgproc.findContours(edgesMat, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)

            // Find the largest quadrilateral contour
            var maxArea = 0.0
            var bestApprox: MatOfPoint2f? = null

            for (contour in contours) {
                if (!isActive) break

                val area = Imgproc.contourArea(contour)
                if (area < scaledWidth * scaledHeight * 0.1) continue // Too small

                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.02 * peri, true)

                if (approx.total() == 4L && area > maxArea) {
                    maxArea = area
                    bestApprox = approx
                }
            }

            // If no quadrilateral found, try largest contour with 4-point approximation
            if (bestApprox == null && contours.isNotEmpty()) {
                val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largestContour != null) {
                    val peri = Imgproc.arcLength(MatOfPoint2f(*largestContour.toArray()), true)
                    val approx = MatOfPoint2f()
                    Imgproc.approxPolyDP(MatOfPoint2f(*largestContour.toArray()), approx, 0.02 * peri, true)
                    if (approx.total() >= 4L) {
                        bestApprox = approx
                        maxArea = Imgproc.contourArea(largestContour)
                    }
                }
            }

            // Scale corners back to original size
            val corners = if (bestApprox != null) {
                val points = bestApprox.toArray()
                val scaleBack = 1.0 / scale
                points.take(4).map { pt ->
                    PointF(
                        (pt.x * scaleBack).toFloat().coerceIn(0f, originalWidth.toFloat()),
                        (pt.y * scaleBack).toFloat().coerceIn(0f, originalHeight.toFloat())
                    )
                }.let { orderPoints(it) }
            } else {
                // Fallback: use image corners
                listOf(
                    PointF(0f, 0f),
                    PointF(originalWidth.toFloat(), 0f),
                    PointF(originalWidth.toFloat(), originalHeight.toFloat()),
                    PointF(0f, originalHeight.toFloat())
                )
            }

            val confidence = if (bestApprox != null) {
                val documentArea = maxArea / (scale * scale)
                val imageArea = originalWidth * originalHeight
                ((documentArea / imageArea) * 100f).toFloat().coerceIn(0f, 100f)
            } else 0f

            val boundingRect = android.graphics.RectF(
                corners.minOf { it.x },
                corners.minOf { it.y },
                corners.maxOf { it.x },
                corners.maxOf { it.y }
            )

            EdgeDetectionResult(corners, confidence, boundingRect)

        } catch (e: Exception) {
            Log.e(TAG, "Edge detection failed", e)
            EdgeDetectionResult(
                listOf(
                    PointF(0f, 0f),
                    PointF(bitmap.width.toFloat(), 0f),
                    PointF(bitmap.width.toFloat(), bitmap.height.toFloat()),
                    PointF(0f, bitmap.height.toFloat())
                ),
                0f,
                android.graphics.RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            )
        } finally {
            srcMat?.release()
            grayMat?.release()
            blurredMat?.release()
            threshMat?.release()
            dilatedMat?.release()
            edgesMat?.release()
            scaledMat?.release()
            hierarchy?.release()
        }
    }

    /**
     * Apply perspective correction using 4-point homography.
     * Maps detected corners to a rectangular output.
     */
    suspend fun applyPerspectiveCorrection(
        bitmap: Bitmap,
        corners: List<PointF>
    ): Bitmap = withContext(Dispatchers.Default) {
        if (corners.size != 4) return@withContext bitmap

        var srcMat: Mat? = null
        var warpedMat: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            // Order points: top-left, top-right, bottom-right, bottom-left
            val ordered = orderPoints(corners)

            // Calculate output dimensions
            val widthA = distance(ordered[2], ordered[3])
            val widthB = distance(ordered[1], ordered[0])
            val maxWidth = max(widthA, widthB).toInt()

            val heightA = distance(ordered[1], ordered[2])
            val heightB = distance(ordered[0], ordered[3])
            val maxHeight = max(heightA, heightB).toInt()

            // Source points
            val srcPoints = MatOfPoint2f(
                Point(ordered[0].x.toDouble(), ordered[0].y.toDouble()),
                Point(ordered[1].x.toDouble(), ordered[1].y.toDouble()),
                Point(ordered[2].x.toDouble(), ordered[2].y.toDouble()),
                Point(ordered[3].x.toDouble(), ordered[3].y.toDouble())
            )

            // Destination points (rectangle)
            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(maxWidth.toDouble(), 0.0),
                Point(maxWidth.toDouble(), maxHeight.toDouble()),
                Point(0.0, maxHeight.toDouble())
            )

            // Compute homography matrix
            val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)

            // Apply warp perspective
            warpedMat = Mat()
            Imgproc.warpPerspective(srcMat, warpedMat, transformMatrix, Size(maxWidth.toDouble(), maxHeight.toDouble()))

            // Convert back to bitmap
            val result = Bitmap.createBitmap(maxWidth, maxHeight, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(warpedMat, result)

            srcPoints.release()
            dstPoints.release()
            transformMatrix.release()

            result

        } catch (e: Exception) {
            Log.e(TAG, "Perspective correction failed", e)
            bitmap
        } finally {
            srcMat?.release()
            warpedMat?.release()
        }
    }

    /**
     * Auto-crop: remove excess background around document.
     * Uses contour detection to find tight bounding box.
     */
    suspend fun autoCrop(bitmap: Bitmap, corners: List<PointF>): Bitmap = withContext(Dispatchers.Default) {
        if (corners.size != 4) return@withContext bitmap

        var srcMat: Mat? = null
        var grayMat: Mat? = null
        var threshMat: Mat? = null
        var points: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            threshMat = Mat()
            Imgproc.threshold(grayMat, threshMat, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            // Find non-zero region (document content)
            points = Mat()
            Core.findNonZero(threshMat, points)

            val result = if (points.rows() > 0) {
                val boundingRect = Imgproc.boundingRect(points)
                // Add small padding
                val padding = 5
                val x = (boundingRect.x - padding).coerceAtLeast(0)
                val y = (boundingRect.y - padding).coerceAtLeast(0)
                val width = (boundingRect.width + padding * 2).coerceAtMost(srcMat.cols() - x)
                val height = (boundingRect.height + padding * 2).coerceAtMost(srcMat.rows() - y)

                val croppedMat = Mat(srcMat, Rect(x, y, width, height))
                val outBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(croppedMat, outBitmap)
                croppedMat.release()
                outBitmap
            } else {
                bitmap
            }
            result

        } catch (e: Exception) {
            Log.e(TAG, "Auto-crop failed", e)
            bitmap
        } finally {
            srcMat?.release()
            grayMat?.release()
            threshMat?.release()
            points?.release()
        }
    }

    /**
     * Shadow removal using morphological operations.
     * Algorithm: Estimate background with large blur, subtract from original.
     */
    suspend fun removeShadows(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        var srcMat: Mat? = null
        var rgbMat: Mat? = null
        var dilatedMat: Mat? = null
        var bgMat: Mat? = null
        var diffMat: Mat? = null
        var normMat: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            // Convert to RGB if needed
            rgbMat = Mat()
            if (srcMat.channels() == 4) {
                Imgproc.cvtColor(srcMat, rgbMat, Imgproc.COLOR_BGRA2BGR)
            } else {
                srcMat.copyTo(rgbMat)
            }

            // Dilate to remove text (keep background)
            dilatedMat = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(25.0, 25.0))
            Imgproc.dilate(rgbMat, dilatedMat, kernel)

            // Blur to smooth background estimate
            bgMat = Mat()
            Imgproc.medianBlur(dilatedMat, bgMat, 21)

            // Calculate difference
            diffMat = Mat()
            Core.absdiff(rgbMat, bgMat, diffMat)

            // Invert: background - image
            Core.bitwise_not(diffMat, diffMat)

            // Normalize to full range
            normMat = Mat()
            Core.normalize(diffMat, normMat, 0.0, 255.0, Core.NORM_MINMAX)

            // Convert back to bitmap
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(normMat, result)
            result

        } catch (e: Exception) {
            Log.e(TAG, "Shadow removal failed", e)
            bitmap
        } finally {
            srcMat?.release()
            rgbMat?.release()
            dilatedMat?.release()
            bgMat?.release()
            diffMat?.release()
            normMat?.release()
        }
    }

    /**
     * Receipt enhancement: high contrast, B&W conversion, noise reduction.
     */
    suspend fun enhanceReceipt(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        var srcMat: Mat? = null
        var grayMat: Mat? = null
        var enhancedMat: Mat? = null
        var binaryMat: Mat? = null
        var cleanedMat: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            grayMat = Mat()
            Imgproc.cvtColor(srcMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // Apply CLAHE for local contrast enhancement
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            enhancedMat = Mat()
            clahe.apply(grayMat, enhancedMat)
            clahe.collectGarbage()

            // Adaptive threshold for clean B&W
            binaryMat = Mat()
            Imgproc.adaptiveThreshold(
                enhancedMat, binaryMat, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY, 15, 10.0
            )

            // Morphological opening to remove noise
            cleanedMat = Mat()
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            Imgproc.morphologyEx(binaryMat, cleanedMat, Imgproc.MORPH_OPEN, kernel)

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(cleanedMat, result)
            result

        } catch (e: Exception) {
            Log.e(TAG, "Receipt enhancement failed", e)
            bitmap
        } finally {
            srcMat?.release()
            grayMat?.release()
            enhancedMat?.release()
            binaryMat?.release()
            cleanedMat?.release()
        }
    }

    /**
     * Whiteboard enhancement: glare reduction, contrast boost, color correction.
     */
    suspend fun enhanceWhiteboard(bitmap: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        var srcMat: Mat? = null
        var labMat: Mat? = null
        var resultMat: Mat? = null
        var sharpened: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            // Convert to LAB for better color processing
            labMat = Mat()
            Imgproc.cvtColor(srcMat, labMat, Imgproc.COLOR_BGR2Lab)

            // Split channels
            val channels = ArrayList<Mat>(3)
            Core.split(labMat, channels)

            // CLAHE on L channel
            val clahe = Imgproc.createCLAHE(3.0, Size(8.0, 8.0))
            val enhancedL = Mat()
            clahe.apply(channels[0], enhancedL)
            clahe.collectGarbage()

            // Merge back
            channels[0] = enhancedL
            Core.merge(channels, labMat)

            // Convert back to BGR
            resultMat = Mat()
            Imgproc.cvtColor(labMat, resultMat, Imgproc.COLOR_Lab2BGR)

            // Slight sharpening
            sharpened = Mat()
            val sharpenKernel = Mat(3, 3, CvType.CV_32F)
            sharpenKernel.put(0, 0, 0.0, -0.5, 0.0)
            sharpenKernel.put(1, 0, -0.5, 3.0, -0.5)
            sharpenKernel.put(2, 0, 0.0, -0.5, 0.0)
            Imgproc.filter2D(resultMat, sharpened, -1, sharpenKernel)
            sharpenKernel.release()

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(sharpened, result)
            result

        } catch (e: Exception) {
            Log.e(TAG, "Whiteboard enhancement failed", e)
            bitmap
        } finally {
            srcMat?.release()
            labMat?.release()
            resultMat?.release()
            sharpened?.release()
        }
    }

    /**
     * General contrast enhancement with configurable parameters.
     */
    suspend fun enhanceGeneral(
        bitmap: Bitmap,
        params: EnhancementParams
    ): Bitmap = withContext(Dispatchers.Default) {
        var srcMat: Mat? = null
        var enhancedMat: Mat? = null

        try {
            srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)

            enhancedMat = Mat()
            srcMat.copyTo(enhancedMat)

            // Apply contrast and brightness
            if (params.contrast != 1.0f || params.brightness != 0.0f) {
                enhancedMat.convertTo(
                    enhancedMat,
                    -1,
                    params.contrast.toDouble(),
                    params.brightness.toDouble()
                )
            }

            // CLAHE for local contrast
            if (params.contrast > 1.2f) {
                val labMat = Mat()
                Imgproc.cvtColor(enhancedMat, labMat, Imgproc.COLOR_BGR2Lab)
                val channels = ArrayList<Mat>()
                Core.split(labMat, channels)

                val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
                val enhancedL = Mat()
                clahe.apply(channels[0], enhancedL)
                clahe.collectGarbage()

                channels[0] = enhancedL
                Core.merge(channels, labMat)
                Imgproc.cvtColor(labMat, enhancedMat, Imgproc.COLOR_Lab2BGR)
                labMat.release()
                for (ch in channels) {
                    ch.release()
                }
            }

            // Sharpen if requested
            if (params.sharpen) {
                val sharpened = Mat()
                val kernel = Mat(3, 3, CvType.CV_32F)
                kernel.put(0, 0, 0.0, -0.5, 0.0)
                kernel.put(1, 0, -0.5, 3.0, -0.5)
                kernel.put(2, 0, 0.0, -0.5, 0.0)
                Imgproc.filter2D(enhancedMat, sharpened, -1, kernel)
                kernel.release()
                enhancedMat.release()
                enhancedMat = sharpened
            }

            // Convert to grayscale/B&W if requested
            if (params.blackAndWhite) {
                val grayMat = Mat()
                Imgproc.cvtColor(enhancedMat, grayMat, Imgproc.COLOR_BGR2GRAY)
                val binaryMat = Mat()
                Imgproc.adaptiveThreshold(
                    grayMat, binaryMat, 255.0,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    Imgproc.THRESH_BINARY, 11, 2.0
                )
                val colorMat = Mat()
                Imgproc.cvtColor(binaryMat, colorMat, Imgproc.COLOR_GRAY2BGR)
                enhancedMat.release()
                enhancedMat = colorMat
                grayMat.release()
                binaryMat.release()
            }

            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(enhancedMat, result)
            result

        } catch (e: Exception) {
            Log.e(TAG, "General enhancement failed", e)
            bitmap
        } finally {
            srcMat?.release()
            enhancedMat?.release()
        }
    }

    /**
     * Generate a memory-efficient thumbnail.
     */
    suspend fun generateThumbnail(bitmap: Bitmap, maxDimension: Int): Bitmap = withContext(Dispatchers.Default) {
        val scale = min(
            maxDimension.toFloat() / bitmap.width,
            maxDimension.toFloat() / bitmap.height
        )

        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // ===== Helper Methods =====

    /**
     * Order 4 points to: top-left, top-right, bottom-right, bottom-left.
     */
    private fun orderPoints(points: List<PointF>): List<PointF> {
        if (points.size != 4) return points

        val sortedBySum = points.sortedBy { it.x + it.y }
        val topLeft = sortedBySum[0]
        val bottomRight = sortedBySum[3]

        val remaining = points.filter { it != topLeft && it != bottomRight }
        val topRight = if (remaining[0].x > remaining[1].x) remaining[0] else remaining[1]
        val bottomLeft = if (remaining[0].x > remaining[1].x) remaining[1] else remaining[0]

        return listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun distance(p1: PointF, p2: PointF): Double {
        return sqrt((p2.x - p1.x).toDouble().pow(2.0) + (p2.y - p1.y).toDouble().pow(2.0))
    }
}
