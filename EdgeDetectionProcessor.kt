
// FLAT REPO ROOT -- codemagic.yaml copies to:

//
app/src/main/java/com/propdf/editor/data/repository/EdgeDetectionProcessor.kt

//

// FEATURES IMPLEMENTED:

// 1. Auto edge detection (pure Kotlin Canny -- no OpenCV/JitPack)

// 2. Perspective warp (4-point homography using
android.graphics.Matrix)

// 3. Blur detection warning (Laplacian variance in pure Kotlin)

//

// RULES OBEYED:

// - No JitPack, no OpenCV AAR (blocked on Codemagic free tier)

// - Pure ASCII (no smart quotes, no em-dashes)

// - No .use{} on PdfDocument (not needed here -- pure bitmap work)

// - No Paint.getTag()/setTag() (not used here)

// - All floats use f suffix

// - No FrameLayout.LayoutParams(w,h,weight) constructor

package com.propdf.editor.data.repository

import android.graphics.Bitmap

import android.graphics.Canvas

import android.graphics.Matrix

import android.graphics.PointF

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.withContext

import javax.inject.Inject

import javax.inject.Singleton

import kotlin.math.abs

import kotlin.math.hypot

import kotlin.math.pow

import kotlin.math.sqrt

\@Singleton

class EdgeDetectionProcessor \@Inject constructor() {

//
-----------------------------------------------------------------------

// PUBLIC DATA TYPES

//
-----------------------------------------------------------------------

data class Quad(

val topLeft: PointF,

val topRight: PointF,

val bottomRight: PointF,

val bottomLeft: PointF

)

data class EdgeResult(

val quad: Quad?,

val isBlurry: Boolean,

val blurScore: Double // higher = sharper; <80.0 = blurry

)

//
-----------------------------------------------------------------------

// BLUR DETECTION (Laplacian variance -- pure Kotlin)

//
-----------------------------------------------------------------------

// Sample the bitmap at reduced resolution for speed.

// Returns variance of Laplacian; values below threshold = blurry.

suspend fun blurScore(src: Bitmap): Double =
withContext(Dispatchers.Default) {

val scale = 320f / src.width.toFloat().coerceAtLeast(1f)

val w = (src.width * scale).toInt().coerceAtLeast(1)

val h = (src.height * scale).toInt().coerceAtLeast(1)

val small = Bitmap.createScaledBitmap(src, w, h, false)

val gray = toGrayArray(small)

small.recycle()

laplacianVariance(gray, w, h)

}

fun isBlurry(score: Double, threshold: Double = 80.0) = score <
threshold

//
-----------------------------------------------------------------------

// EDGE / CORNER DETECTION

//
-----------------------------------------------------------------------

// Full pipeline: downscale -> grayscale -> Canny -> Hough-lite line

// grouping -> find largest quad -> return corners in original coords.

suspend fun detectDocumentCorners(src: Bitmap): EdgeResult =
withContext(Dispatchers.Default) {

val blur = blurScore(src)

if (isBlurry(blur)) return@withContext EdgeResult(null, true, blur)

val scale = 640f / src.width.toFloat().coerceAtLeast(1f)

val w = (src.width * scale).toInt().coerceAtLeast(1)

val h = (src.height * scale).toInt().coerceAtLeast(1)

val small = Bitmap.createScaledBitmap(src, w, h, false)

val gray = toGrayArray(small)

small.recycle()

val smoothed = gaussianBlur5x5(gray, w, h)

val edges = cannyEdges(smoothed, w, h, lowThresh = 40, highThresh = 100)

val quad = findLargestQuad(edges, w, h)

// Scale corners back to original image coordinates

val scaledQuad = quad?.let { q ->

Quad(

topLeft = PointF(q.topLeft.x / scale, q.topLeft.y / scale),

topRight = PointF(q.topRight.x / scale, q.topRight.y / scale),

bottomRight = PointF(q.bottomRight.x / scale, q.bottomRight.y / scale),

bottomLeft = PointF(q.bottomLeft.x / scale, q.bottomLeft.y / scale)

)

}

EdgeResult(scaledQuad, false, blur)

}

//
-----------------------------------------------------------------------

// PERSPECTIVE WARP (feature 2)

//
-----------------------------------------------------------------------

// Given the 4 detected corners, produce a flat, de-skewed bitmap.

// Uses Android Matrix.setPolyToPoly (supports 4-point homography).

suspend fun perspectiveWarp(src: Bitmap, quad: Quad): Bitmap =
withContext(Dispatchers.Default) {

val tl = quad.topLeft

val tr = quad.topRight

val br = quad.bottomRight

val bl = quad.bottomLeft

val outW = ((hypot(

(tr.x - tl.x).toDouble(),

(tr.y - tl.y).toDouble()

) + hypot(

(br.x - bl.x).toDouble(),

(br.y - bl.y).toDouble()

)) / 2.0).toInt().coerceIn(100, 4000)

val outH = ((hypot(

(bl.x - tl.x).toDouble(),

(bl.y - tl.y).toDouble()

) + hypot(

(br.x - tr.x).toDouble(),

(br.y - tr.y).toDouble()

)) / 2.0).toInt().coerceIn(100, 5000)

val srcPts = floatArrayOf(

tl.x, tl.y,

tr.x, tr.y,

br.x, br.y,

bl.x, bl.y

)

val dstPts = floatArrayOf(

0f, 0f,

outW.toFloat(), 0f,

outW.toFloat(), outH.toFloat(),

0f, outH.toFloat()

)

val matrix = Matrix()

// setPolyToPoly with 4 points = perspective / homography transform

matrix.setPolyToPoly(srcPts, 0, dstPts, 0, 4)

val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)

val canvas = Canvas(result)

canvas.drawBitmap(src, matrix, null)

result

}

//
-----------------------------------------------------------------------

// PRIVATE: grayscale, Gaussian, Canny, quad finder

//
-----------------------------------------------------------------------

// Flatten ARGB pixels to luminance IntArray (0-255)

private fun toGrayArray(bmp: Bitmap): IntArray {

val w = bmp.width; val h = bmp.height

val pixels = IntArray(w * h)

bmp.getPixels(pixels, 0, w, 0, 0, w, h)

val gray = IntArray(w * h)

for (i in pixels.indices) {

val c = pixels[i]

val r = (c shr 16) and 0xFF

val g = (c shr 8) and 0xFF

val b = c and 0xFF

gray[i] = (r * 77 + g * 150 + b * 29) shr 8 // fast luma

}

return gray

}

// 5x5 Gaussian blur (sigma \~1.0)

private fun gaussianBlur5x5(src: IntArray, w: Int, h: Int): IntArray {

val kernel = intArrayOf(2, 4, 5, 4, 2,

4, 9, 12,9, 4,

5,12,15,12, 5,

4, 9, 12,9, 4,

2, 4, 5, 4, 2)

val kSum = 159

val dst = IntArray(w * h)

for (y in 2 until h - 2) {

for (x in 2 until w - 2) {

var acc = 0

var ki = 0

for (ky in -2..2) {

for (kx in -2..2) {

acc += src[(y + ky) * w + (x + kx)] * kernel[ki++]

}

}

dst[y * w + x] = acc / kSum

}

}

return dst

}

// Canny edge detection (Sobel + double threshold + hysteresis)

private fun cannyEdges(

src: IntArray, w: Int, h: Int,

lowThresh: Int, highThresh: Int

): BooleanArray {

val gx = IntArray(w * h)

val gy = IntArray(w * h)

val mag = IntArray(w * h)

// Sobel

for (y in 1 until h - 1) {

for (x in 1 until w - 1) {

val i = y * w + x

val gxv = -src[(y-1)*w+(x-1)] + src[(y-1)*w+(x+1)] +

-2*src[y*w+(x-1)] + 2*src[y*w+(x+1)] +

-src[(y+1)*w+(x-1)] + src[(y+1)*w+(x+1)]

val gyv = -src[(y-1)*w+(x-1)] - 2*src[(y-1)*w+x] -
src[(y-1)*w+(x+1)] +

src[(y+1)*w+(x-1)] + 2*src[(y+1)*w+x] + src[(y+1)*w+(x+1)]

gx[i] = gxv; gy[i] = gyv

mag[i] = (abs(gxv) + abs(gyv)).coerceAtMost(255)

}

}

// Non-max suppression + double threshold

val strong = BooleanArray(w * h)

val weak = BooleanArray(w * h)

for (y in 1 until h - 1) {

for (x in 1 until w - 1) {

val i = y * w + x

val m = mag[i]; if (m < lowThresh) continue

// Approximate angle to 0/45/90/135

val ax = abs(gx[i]); val ay = abs(gy[i])

val n1: Int; val n2: Int

if (ay > ax * 2) {

n1 = mag[(y-1)*w+x]; n2 = mag[(y+1)*w+x]

} else if (ax > ay * 2) {

n1 = mag[y*w+(x-1)]; n2 = mag[y*w+(x+1)]

} else if ((gx[i] > 0) == (gy[i] > 0)) {

n1 = mag[(y-1)*w+(x-1)]; n2 = mag[(y+1)*w+(x+1)]

} else {

n1 = mag[(y-1)*w+(x+1)]; n2 = mag[(y+1)*w+(x-1)]

}

if (m >= n1 && m >= n2) {

if (m >= highThresh) strong[i] = true

else if (m >= lowThresh) weak[i] = true

}

}

}

// Hysteresis: promote weak pixels connected to strong

val edges = BooleanArray(w * h)

for (y in 1 until h - 1) {

for (x in 1 until w - 1) {

val i = y * w + x

if (strong[i]) { edges[i] = true; continue }

if (weak[i]) {

for (dy in -1..1) for (dx in -1..1) {

if (strong[(y+dy)*w+(x+dx)]) { edges[i] = true; break }

}

}

}

}

return edges

}

// Find the largest quadrilateral in the edge map.

// Strategy: sample edge pixels, build convex hull, approximate to 4
corners.

private fun findLargestQuad(edges: BooleanArray, w: Int, h: Int): Quad?
{

// Collect edge points (sample for speed)

val pts = mutableListOf<PointF>()

val step = 3

for (y in 0 until h step step) {

for (x in 0 until w step step) {

if (edges[y * w + x]) pts.add(PointF(x.toFloat(), y.toFloat()))

}

}

if (pts.size < 10) return null

// Convex hull (Graham scan)

val hull = convexHull(pts)

if (hull.size < 4) return null

// Find 4 extreme corners:

// topLeft = min(x+y), topRight = max(x-y),

// bottomRight = max(x+y), bottomLeft = min(x-y)

var tl = hull[0]; var tr = hull[0]; var br = hull[0]; var bl =
hull[0]

for (p in hull) {

if (p.x + p.y < tl.x + tl.y) tl = p

if (p.x - p.y > tr.x - tr.y) tr = p

if (p.x + p.y > br.x + br.y) br = p

if (p.x - p.y < bl.x - bl.y) bl = p

}

// Sanity check: quad must cover at least 15% of the image area

val approxArea = quadArea(tl, tr, br, bl)

if (approxArea < w.toFloat() * h.toFloat() * 0.15f) return null

return Quad(tl, tr, br, bl)

}

// Graham scan convex hull

private fun convexHull(pts: List<PointF>): List<PointF> {

if (pts.size < 3) return pts

val sorted = pts.sortedWith(compareBy({ it.x }, { it.y }))

val lower = mutableListOf<PointF>()

for (p in sorted) {

while (lower.size >= 2 && cross(lower[lower.size-2],
lower[lower.size-1], p) <= 0f)

lower.removeAt(lower.size - 1)

lower.add(p)

}

val upper = mutableListOf<PointF>()

for (p in sorted.reversed()) {

while (upper.size >= 2 && cross(upper[upper.size-2],
upper[upper.size-1], p) <= 0f)

upper.removeAt(upper.size - 1)

upper.add(p)

}

lower.removeAt(lower.size - 1)

upper.removeAt(upper.size - 1)

return lower + upper

}

private fun cross(o: PointF, a: PointF, b: PointF): Float =

(a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

private fun quadArea(tl: PointF, tr: PointF, br: PointF, bl: PointF):
Float {

// Shoelace formula

val pts = listOf(tl, tr, br, bl)

var area = 0f

for (i in pts.indices) {

val j = (i + 1) % pts.size

area += pts[i].x * pts[j].y

area -= pts[j].x * pts[i].y

}

return abs(area) / 2f

}

// Laplacian variance for blur detection

private fun laplacianVariance(gray: IntArray, w: Int, h: Int): Double {

// 3x3 Laplacian kernel: [0,1,0, 1,-4,1, 0,1,0]

var sum = 0.0; var sumSq = 0.0; var count = 0

for (y in 1 until h - 1) {

for (x in 1 until w - 1) {

val lap = (gray[(y-1)*w+x] + gray[(y+1)*w+x] +

gray[y*w+(x-1)] + gray[y*w+(x+1)] -

4 * gray[y*w+x])

sum += lap; sumSq += lap.toDouble().pow(2); count++

}

}

if (count == 0) return 0.0

val mean = sum / count

return (sumSq / count) - mean * mean

}

}

**EdgeOverlayView.kt**

Transparent View drawn over CameraX preview. Renders detected document
quad with dashed border, corner dots, semi-transparent fill, and blur
warning text.

**Deployed to:** app/src/main/java/com/propdf/editor/ui/scanner/
