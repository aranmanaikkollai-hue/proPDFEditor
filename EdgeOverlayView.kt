
// FLAT REPO ROOT -- codemagic.yaml copies to:

// app/src/main/java/com/propdf/editor/ui/scanner/EdgeOverlayView.kt

//

// Draws the detected document quad on top of the camera preview.

// Also shows blur warning text when blur score is low.

//

// RULES OBEYED:

// - Pure ASCII

// - No Paint.getTag() / setTag()

// - All float literals use f suffix

// - No FrameLayout.LayoutParams(w,h,weight)

// - No smart quotes, no em-dashes

package com.propdf.editor.ui.scanner

import android.content.Context

import android.graphics.Canvas

import android.graphics.Color

import android.graphics.DashPathEffect

import android.graphics.Paint

import android.graphics.Path

import android.graphics.PointF

import android.util.AttributeSet

import android.view.View

import com.propdf.editor.data.repository.EdgeDetectionProcessor

class EdgeOverlayView \@JvmOverloads constructor(

context: Context,

attrs: AttributeSet? = null

) : View(context, attrs) {

// The detected quad corners in VIEW coordinates (scaled from detection
coords)

private var quad: EdgeDetectionProcessor.Quad? = null

// Source image dimensions used during detection (for scaling to view
coords)

private var srcW: Int = 1

private var srcH: Int = 1

private var showBlurWarning: Boolean = false

// Corner dot radius in px

private val dotRadius = 14f

private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.parseColor(\"#33AAFF\")

style = Paint.Style.STROKE

strokeWidth = 3f

pathEffect = DashPathEffect(floatArrayOf(16f, 8f), 0f)

}

private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.parseColor(\"#33AAFF\")

style = Paint.Style.FILL

}

private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.parseColor(\"#1A33AAFF\")

style = Paint.Style.FILL

}

private val warningPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.parseColor(\"#FF4444\")

style = Paint.Style.FILL

textSize = 42f

textAlign = Paint.Align.CENTER

isFakeBoldText = true

}

private val warningBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.parseColor(\"#CC000000\")

style = Paint.Style.FILL

}

// Call from camera analysis thread (via post() for thread safety)

fun updateDetection(

detected: EdgeDetectionProcessor.Quad?,

imageSrcW: Int,

imageSrcH: Int,

blurry: Boolean

) {

post {

quad = detected

srcW = imageSrcW

srcH = imageSrcH

showBlurWarning = blurry

invalidate()

}

}

override fun onDraw(canvas: Canvas) {

super.onDraw(canvas)

val q = quad

if (q != null) {

val scaleX = width.toFloat() / srcW.toFloat().coerceAtLeast(1f)

val scaleY = height.toFloat() / srcH.toFloat().coerceAtLeast(1f)

val tl = PointF(q.topLeft.x * scaleX, q.topLeft.y * scaleY)

val tr = PointF(q.topRight.x * scaleX, q.topRight.y * scaleY)

val br = PointF(q.bottomRight.x * scaleX, q.bottomRight.y * scaleY)

val bl = PointF(q.bottomLeft.x * scaleX, q.bottomLeft.y * scaleY)

// Fill

val fill = Path().apply {

moveTo(tl.x, tl.y)

lineTo(tr.x, tr.y)

lineTo(br.x, br.y)

lineTo(bl.x, bl.y)

close()

}

canvas.drawPath(fill, fillPaint)

// Edges

canvas.drawPath(fill, edgePaint)

// Corner dots

for (pt in listOf(tl, tr, br, bl)) {

canvas.drawCircle(pt.x, pt.y, dotRadius, cornerPaint)

}

}

if (showBlurWarning) {

val msg = \"Move closer -- image blurry\"

val cx = width / 2f

val cy = height - 160f

val textW = warningPaint.measureText(msg)

canvas.drawRoundRect(

cx - textW / 2f - 24f, cy - 48f,

cx + textW / 2f + 24f, cy + 20f,

16f, 16f, warningBgPaint

)

canvas.drawText(msg, cx, cy, warningPaint)

}

}

}

**5.2 Fixed Files --- Scanner**

**DocumentScannerActivity.kt**

Full replacement v4.1. Fixes: added ImageProxy import, manual RGBA_8888
toBitmap conversion, resolves EdgeDetectionProcessor and EdgeOverlayView
references, wires perspective warp and blur detection.

**Deployed to:** app/src/main/java/com/propdf/editor/ui/scanner/
