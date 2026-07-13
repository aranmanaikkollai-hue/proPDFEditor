package com.propdf.editor.ui.forms.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField
import kotlin.math.min

/**
 * Custom overlay view that renders interactive PDF form fields on top of a PDF page.
 * Supports tap-to-edit, drag-to-move, and resize handles.
 */
class FormFieldOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val fields = mutableListOf<PdfFormField>()
    private val fieldValues = mutableMapOf<String, String>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 36f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        alpha = 60
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLUE
        strokeWidth = 4f
    }

    private var scaleFactor = 1f
    private var pageWidth = 0f
    private var pageHeight = 0f
    private var selectedFieldId: Long? = null

    var onFieldTap: ((PdfFormField) -> Unit)? = null
    var onFieldLongPress: ((PdfFormField) -> Unit)? = null
    var isEditMode: Boolean = false

    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var draggingField: PdfFormField? = null
    private var dragOffsetX = 0f
    private var dragOffsetY = 0f

    fun setPageDimensions(width: Float, height: Float) {
        this.pageWidth = width
        this.pageHeight = height
        requestLayout()
        invalidate()
    }

    fun setScaleFactor(factor: Float) {
        this.scaleFactor = factor
        invalidate()
    }

    fun setFields(newFields: List<PdfFormField>) {
        fields.clear()
        fields.addAll(newFields)
        invalidate()
    }

    fun setFieldValue(fieldName: String, value: String) {
        fieldValues[fieldName] = value
        invalidate()
    }

    fun setFieldValues(values: Map<String, String>) {
        fieldValues.clear()
        fieldValues.putAll(values)
        invalidate()
    }

    fun selectField(fieldId: Long?) {
        selectedFieldId = fieldId
        invalidate()
    }

    fun getFieldAt(x: Float, y: Float): PdfFormField? {
        for (i in fields.size - 1 downTo 0) {
            val field = fields[i]
            val rect = scaleRect(field.rect)
            if (rect.contains(x, y)) {
                return field
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        fields.forEach { field ->
            val rect = scaleRect(field.rect)
            val isSelected = field.id == selectedFieldId

            fillPaint.color = field.backgroundColor ?: getDefaultFillColor(field.fieldType)
            canvas.drawRect(rect, fillPaint)

            borderPaint.color = field.borderColor ?: getDefaultBorderColor(field.fieldType)
            borderPaint.strokeWidth = field.borderWidth * scaleFactor
            if (borderPaint.strokeWidth < 1f) borderPaint.strokeWidth = 1f
            canvas.drawRect(rect, borderPaint)

            if (isSelected && isEditMode) {
                canvas.drawRect(rect, selectedPaint)
                drawResizeHandles(canvas, rect)
            }

            drawFieldContent(canvas, field, rect)
        }
    }

    private fun drawFieldContent(canvas: Canvas, field: PdfFormField, rect: RectF) {
        val value = fieldValues[field.fieldName] ?: field.value ?: field.defaultValue

        when (field.fieldType) {
            FormFieldType.TEXTBOX, FormFieldType.DATE_PICKER -> {
                drawTextContent(canvas, value ?: "", rect, field)
            }
            FormFieldType.CHECKBOX -> {
                drawCheckbox(canvas, rect, value == "Yes" || value == "true")
            }
            FormFieldType.RADIO_BUTTON -> {
                drawRadioButton(canvas, rect, value == "Yes" || value == "true")
            }
            FormFieldType.DROPDOWN -> {
                drawDropdown(canvas, rect, value ?: field.defaultValue ?: "", field.options)
            }
            FormFieldType.LIST_BOX -> {
                drawListBox(canvas, rect, field.options, value)
            }
            FormFieldType.SIGNATURE -> {
                drawSignaturePlaceholder(canvas, rect)
            }
            FormFieldType.IMAGE -> {
                drawImagePlaceholder(canvas, rect)
            }
            FormFieldType.BUTTON -> {
                drawButton(canvas, rect, value ?: "Button")
            }
            else -> {}
        }
    }

    private fun drawTextContent(canvas: Canvas, text: String, rect: RectF, field: PdfFormField) {
        textPaint.textSize = field.fontSize * scaleFactor
        textPaint.color = field.textColor

        val padding = 8 * scaleFactor
        val availableWidth = rect.width() - padding * 2

        var displayText = text
        while (textPaint.measureText(displayText) > availableWidth && displayText.isNotEmpty()) {
            displayText = displayText.dropLast(1)
        }
        if (displayText.length < text.length) displayText += "..."

        val baseline = rect.centerY() + (textPaint.textSize / 3)
        canvas.drawText(displayText, rect.left + padding, baseline, textPaint)
    }

    private fun drawCheckbox(canvas: Canvas, rect: RectF, isChecked: Boolean) {
        val size = min(rect.width(), rect.height()) * 0.7f
        val left = rect.left + (rect.width() - size) / 2
        val top = rect.top + (rect.height() - size) / 2

        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = Color.DKGRAY
        canvas.drawRect(left, top, left + size, top + size, borderPaint)

        if (isChecked) {
            borderPaint.style = Paint.Style.STROKE
            borderPaint.color = Color.BLACK
            borderPaint.strokeWidth = 3f
            canvas.drawLine(left + size * 0.2f, top + size * 0.5f,
                left + size * 0.4f, top + size * 0.8f, borderPaint)
            canvas.drawLine(left + size * 0.4f, top + size * 0.8f,
                left + size * 0.8f, top + size * 0.2f, borderPaint)
        }
    }

    private fun drawRadioButton(canvas: Canvas, rect: RectF, isSelected: Boolean) {
        val radius = min(rect.width(), rect.height()) * 0.35f
        val cx = rect.centerX()
        val cy = rect.centerY()

        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = Color.DKGRAY
        canvas.drawCircle(cx, cy, radius, borderPaint)

        if (isSelected) {
            fillPaint.color = Color.BLACK
            fillPaint.alpha = 255
            canvas.drawCircle(cx, cy, radius * 0.5f, fillPaint)
            fillPaint.alpha = 60
        }
    }

    private fun drawDropdown(canvas: Canvas, rect: RectF, value: String, options: List<String>) {
        drawTextContent(canvas, value, rect, PdfFormField(
            documentUri = "", fieldName = "", fieldType = FormFieldType.TEXTBOX,
            pageIndex = 0, rect = RectF(), fontSize = 12f
        ).copy(rect = rect))

        val arrowSize = 16f * scaleFactor
        val arrowX = rect.right - arrowSize - 8 * scaleFactor
        val arrowY = rect.centerY()

        borderPaint.style = Paint.Style.FILL
        borderPaint.color = Color.GRAY
        val path = Path().apply {
            moveTo(arrowX, arrowY - arrowSize / 3)
            lineTo(arrowX + arrowSize, arrowY - arrowSize / 3)
            lineTo(arrowX + arrowSize / 2, arrowY + arrowSize / 3)
            close()
        }
        canvas.drawPath(path, borderPaint)
    }

    private fun drawListBox(canvas: Canvas, rect: RectF, options: List<String>, selectedValue: String?) {
        val itemHeight = 40f * scaleFactor
        val padding = 4f * scaleFactor

        options.take((rect.height() / itemHeight).toInt().coerceAtLeast(1)).forEachIndexed { index, option ->
            val itemTop = rect.top + index * itemHeight
            val itemRect = RectF(rect.left, itemTop, rect.right, itemTop + itemHeight)

            if (option == selectedValue) {
                fillPaint.color = Color.LTGRAY
                fillPaint.alpha = 180
                canvas.drawRect(itemRect, fillPaint)
                fillPaint.alpha = 60
            }

            textPaint.textSize = 14f * scaleFactor
            textPaint.color = Color.BLACK
            canvas.drawText(option, itemRect.left + padding, itemRect.centerY() + textPaint.textSize / 3, textPaint)
        }
    }

    private fun drawSignaturePlaceholder(canvas: Canvas, rect: RectF) {
        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = Color.GRAY
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        canvas.drawRect(rect, borderPaint)
        borderPaint.pathEffect = null

        textPaint.textSize = 14f * scaleFactor
        textPaint.color = Color.GRAY
        val text = "Signature"
        val textWidth = textPaint.measureText(text)
        canvas.drawText(text, rect.centerX() - textWidth / 2, rect.centerY(), textPaint)
    }

    private fun drawImagePlaceholder(canvas: Canvas, rect: RectF) {
        borderPaint.style = Paint.Style.STROKE
        borderPaint.color = Color.GRAY
        borderPaint.pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
        canvas.drawRect(rect, borderPaint)
        borderPaint.pathEffect = null

        textPaint.textSize = 14f * scaleFactor
        textPaint.color = Color.GRAY
        val text = "Image"
        val textWidth = textPaint.measureText(text)
        canvas.drawText(text, rect.centerX() - textWidth / 2, rect.centerY(), textPaint)
    }

    private fun drawButton(canvas: Canvas, rect: RectF, label: String) {
        fillPaint.color = Color.parseColor("#1976D2")
        fillPaint.alpha = 220
        canvas.drawRect(rect, fillPaint)
        fillPaint.alpha = 60

        textPaint.textSize = 16f * scaleFactor
        textPaint.color = Color.WHITE
        val textWidth = textPaint.measureText(label)
        canvas.drawText(label, rect.centerX() - textWidth / 2, rect.centerY() + textPaint.textSize / 3, textPaint)
    }

    private fun drawResizeHandles(canvas: Canvas, rect: RectF) {
        val handleSize = 16f * scaleFactor
        val handles = listOf(
            RectF(rect.left - handleSize/2, rect.top - handleSize/2, rect.left + handleSize/2, rect.top + handleSize/2),
            RectF(rect.right - handleSize/2, rect.top - handleSize/2, rect.right + handleSize/2, rect.top + handleSize/2),
            RectF(rect.left - handleSize/2, rect.bottom - handleSize/2, rect.left + handleSize/2, rect.bottom + handleSize/2),
            RectF(rect.right - handleSize/2, rect.bottom - handleSize/2, rect.right + handleSize/2, rect.bottom + handleSize/2)
        )

        paint.color = Color.BLUE
        handles.forEach { canvas.drawRect(it, paint) }
    }

    private fun scaleRect(rect: RectF): RectF {
        return RectF(
            rect.left * scaleFactor,
            rect.top * scaleFactor,
            rect.right * scaleFactor,
            rect.bottom * scaleFactor
        )
    }

    private fun getDefaultFillColor(type: FormFieldType): Int {
        return when (type) {
            FormFieldType.TEXTBOX -> Color.parseColor("#FFF9C4")
            FormFieldType.CHECKBOX -> Color.parseColor("#E8F5E9")
            FormFieldType.RADIO_BUTTON -> Color.parseColor("#E3F2FD")
            FormFieldType.DROPDOWN -> Color.parseColor("#F3E5F5")
            FormFieldType.LIST_BOX -> Color.parseColor("#F3E5F5")
            FormFieldType.SIGNATURE -> Color.parseColor("#FFF3E0")
            FormFieldType.IMAGE -> Color.parseColor("#E0F2F1")
            FormFieldType.BUTTON -> Color.parseColor("#1976D2")
            else -> Color.LTGRAY
        }
    }

    private fun getDefaultBorderColor(type: FormFieldType): Int {
        return when (type) {
            FormFieldType.TEXTBOX -> Color.parseColor("#FBC02D")
            FormFieldType.CHECKBOX -> Color.parseColor("#388E3C")
            FormFieldType.RADIO_BUTTON -> Color.parseColor("#1976D2")
            FormFieldType.DROPDOWN -> Color.parseColor("#7B1FA2")
            FormFieldType.LIST_BOX -> Color.parseColor("#7B1FA2")
            FormFieldType.SIGNATURE -> Color.parseColor("#E65100")
            FormFieldType.IMAGE -> Color.parseColor("#00796B")
            FormFieldType.BUTTON -> Color.parseColor("#0D47A1")
            else -> Color.GRAY
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStartX = event.x
                touchStartY = event.y
                val field = getFieldAt(event.x, event.y)

                if (field != null) {
                    if (isEditMode) {
                        selectedFieldId = field.id
                        draggingField = field
                        dragOffsetX = event.x - scaleRect(field.rect).left
                        dragOffsetY = event.y - scaleRect(field.rect).top
                        invalidate()
                    } else {
                        onFieldTap?.invoke(field)
                    }
                    return true
                } else {
                    selectedFieldId = null
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isEditMode && draggingField != null) {
                    val dx = event.x - touchStartX
                    val dy = event.y - touchStartY

                    if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) {
                        val newRect = RectF(draggingField!!.rect)
                        newRect.offset(dx / scaleFactor, dy / scaleFactor)
                    }
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                draggingField = null
                val dx = event.x - touchStartX
                val dy = event.y - touchStartY
                if (kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop) {
                    getFieldAt(event.x, event.y)?.let { field ->
                        if (!isEditMode) {
                            onFieldTap?.invoke(field)
                        }
                    }
                }
            }
        }
        return super.onTouchEvent(event)
    }
}
