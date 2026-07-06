package com.propdf.editor.ui.ocr

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.propdf.core.domain.model.OcrTable

class OcrTableView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var table: OcrTable? = null
    private val gridPaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f; style = Paint.Style.STROKE }
    private val textPaint = Paint().apply { color = Color.BLACK; textSize = 24f; isAntiAlias = true }
    private val cellPadding = 8f

    fun setTable(table: OcrTable) { this.table = table; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val table = this.table ?: return
        val cellWidth = width.toFloat() / table.cols
        val cellHeight = height.toFloat() / table.rows
        for (row in 0..table.rows) { val y = row * cellHeight; canvas.drawLine(0f, y, width.toFloat(), y, gridPaint) }
        for (col in 0..table.cols) { val x = col * cellWidth; canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint) }
        table.cells.forEach { cell ->
            canvas.drawText(cell.text, cell.col * cellWidth + cellPadding, (cell.row + 1) * cellHeight - cellPadding, textPaint)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val table = this.table
        if (table != null && table.rows > 0) {
            val desiredHeight = table.rows * 60
            val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
                MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
                MeasureSpec.AT_MOST -> minOf(desiredHeight, MeasureSpec.getSize(heightMeasureSpec))
                else -> desiredHeight
            }
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
