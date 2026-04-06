
// FLAT REPO ROOT -- codemagic.yaml copies to:

// app/src/main/java/com/propdf/editor/ui/viewer/SignaturePadView.kt

//

// FEATURE: Signature drawing pad

// - Smooth Bezier-curve path on Canvas

// - Exports as Bitmap / PNG bytes for storage

// - Integrated with existing annotation system (TextAnnot / Stroke)

//

// RULES OBEYED:

// - No Paint.getTag()/setTag() -- not used (rule #12)

// - No paint.density assignment (rule #16)

// - Pure ASCII (rule #32)

// - All floats use f suffix (rule #12)

package com.propdf.editor.ui.viewer

import android.content.Context

import android.graphics.*

import android.util.AttributeSet

import android.view.MotionEvent

import android.view.View

import java.io.ByteArrayOutputStream

class SignaturePadView @JvmOverloads constructor(

context: Context,

attrs: AttributeSet? = null

) : View(context, attrs) {

private val path = Path()

private var lastX = 0f

private var lastY = 0f

private var isEmpty = true

private val inkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {

color = Color.BLACK

strokeWidth = 4f

style = Paint.Style.STROKE

strokeJoin = Paint.Join.ROUND

strokeCap = Paint.Cap.ROUND

}

private val bgPaint = Paint().apply {

color = Color.WHITE

style = Paint.Style.FILL

}

// Current ink color (changeable before signing)

var inkColor: Int = Color.BLACK

set(v) { field = v; inkPaint.color = v; invalidate() }

// Stroke width in dp-equivalent screen pixels

var inkStrokeWidth: Float = 4f

set(v) { field = v; inkPaint.strokeWidth = v; invalidate() }

override fun onDraw(canvas: Canvas) {

canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

canvas.drawPath(path, inkPaint)

}

override fun onTouchEvent(event: MotionEvent): Boolean {

val x = event.x; val y = event.y

when (event.actionMasked) {

MotionEvent.ACTION_DOWN -> {

path.moveTo(x, y)

lastX = x; lastY = y

isEmpty = false

}

MotionEvent.ACTION_MOVE -> {

// Bezier curve through midpoint for smooth result

val midX = (lastX + x) / 2f

val midY = (lastY + y) / 2f

path.quadTo(lastX, lastY, midX, midY)

lastX = x; lastY = y

}

MotionEvent.ACTION_UP -> {

path.lineTo(x, y)

}

}

invalidate()

return true

}

fun clear() {

path.reset()

isEmpty = true

invalidate()

}

fun hasSignature(): Boolean = !isEmpty

// Export signature as Bitmap (white background, transparent bg version
available)

fun exportBitmap(transparent: Boolean = false): Bitmap {

val bmp = Bitmap.createBitmap(

width.coerceAtLeast(1),

height.coerceAtLeast(1),

Bitmap.Config.ARGB_8888

)

val canvas = Canvas(bmp)

if (!transparent) {

canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

}

canvas.drawPath(path, inkPaint)

return bmp

}

// Export as PNG bytes ready for SharedPreferences or iText embedding

fun exportPngBytes(transparent: Boolean = true): ByteArray {

val bmp = exportBitmap(transparent)

val baos = ByteArrayOutputStream()

bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)

bmp.recycle()

return baos.toByteArray()

}

}

