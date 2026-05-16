package com.propdf.viewer.annotation.ui

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import androidx.core.content.ContextCompat
import com.propdf.viewer.annotation.touch.AnnotationTouchEngine
import com.propdf.viewer.R

/**
* Premium annotation toolbar with all tools, color picker, and stroke width control.
* Material Design 3 styling with animated tool selection.
*/
class AnnotationToolbarView @JvmOverloads constructor(
context: Context,
attrs: AttributeSet? = null,
defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

private val toolButtons = mutableMapOf<AnnotationTouchEngine.ToolMode, ImageButton>()
private var selectedTool: AnnotationTouchEngine.ToolMode = AnnotationTouchEngine.ToolMode.NONE
private var onToolSelectedListener: ((AnnotationTouchEngine.ToolMode) -> Unit)? = null
private var onColorSelectedListener: ((Int) -> Unit)? = null
private var onStrokeWidthListener: ((Float) -> Unit)? = null
private var onUndoListener: (() -> Unit)? = null
private var onRedoListener: (() -> Unit)? = null
private var onClearListener: (() -> Unit)? = null
private var onExportListener: (() -> Unit)? = null

private lateinit var rootLayout: LinearLayout
private lateinit var colorIndicator: View

private val colors = listOf(
Color.YELLOW,
Color.RED,
Color.GREEN,
Color.BLUE,
Color.MAGENTA,
Color.CYAN,
Color.BLACK,
Color.parseColor("#FF9800"),
Color.parseColor("#9C27B0"),
Color.parseColor("#795548")
)

private var currentColorIndex = 0

init {
initView()
}

private fun initView() {
val inflater = LayoutInflater.from(context)
val view = inflater.inflate(R.layout.view_annotation_toolbar, this, true)

rootLayout = view.findViewById(R.id.toolbar_container)
colorIndicator = view.findViewById(R.id.color_indicator)

// Setup tool buttons
setupToolButton(R.id.btn_select, AnnotationTouchEngine.ToolMode.SELECT, R.drawable.ic_select)
setupToolButton(R.id.btn_highlight, AnnotationTouchEngine.ToolMode.HIGHLIGHT, R.drawable.ic_highlight)
setupToolButton(R.id.btn_underline, AnnotationTouchEngine.ToolMode.UNDERLINE, R.drawable.ic_underline)
setupToolButton(R.id.btn_strikeout, AnnotationTouchEngine.ToolMode.STRIKEOUT, R.drawable.ic_strikeout)
setupToolButton(R.id.btn_pencil, AnnotationTouchEngine.ToolMode.PENCIL, R.drawable.ic_pencil)
setupToolButton(R.id.btn_freehand, AnnotationTouchEngine.ToolMode.FREEHAND, R.drawable.ic_brush)
setupToolButton(R.id.btn_text, AnnotationTouchEngine.ToolMode.TEXT_COMMENT, R.drawable.ic_text_comment)
setupToolButton(R.id.btn_sticky, AnnotationTouchEngine.ToolMode.STICKY_NOTE, R.drawable.ic_sticky_note)
setupToolButton(R.id.btn_arrow, AnnotationTouchEngine.ToolMode.ARROW, R.drawable.ic_arrow)
setupToolButton(R.id.btn_rectangle, AnnotationTouchEngine.ToolMode.RECTANGLE, R.drawable.ic_rectangle)
setupToolButton(R.id.btn_circle, AnnotationTouchEngine.ToolMode.CIRCLE, R.drawable.ic_circle)
setupToolButton(R.id.btn_signature, AnnotationTouchEngine.ToolMode.SIGNATURE, R.drawable.ic_signature)
setupToolButton(R.id.btn_image_stamp, AnnotationTouchEngine.ToolMode.IMAGE_STAMP, R.drawable.ic_image)
setupToolButton(R.id.btn_eraser, AnnotationTouchEngine.ToolMode.ERASER, R.drawable.ic_eraser)

// Action buttons
view.findViewById<ImageButton>(R.id.btn_undo)?.setOnClickListener { onUndoListener?.invoke() }
view.findViewById<ImageButton>(R.id.btn_redo)?.setOnClickListener { onRedoListener?.invoke() }
view.findViewById<ImageButton>(R.id.btn_clear)?.setOnClickListener { onClearListener?.invoke() }
view.findViewById<ImageButton>(R.id.btn_export)?.setOnClickListener { onExportListener?.invoke() }

// Color picker
view.findViewById<ImageButton>(R.id.btn_color)?.setOnClickListener { showColorPicker() }

// Stroke width
view.findViewById<ImageButton>(R.id.btn_stroke_width)?.setOnClickListener { showStrokeWidthPicker() }

updateColorIndicator()
}

private fun setupToolButton(buttonId: Int, tool: AnnotationTouchEngine.ToolMode, iconRes: Int) {
val button = rootLayout.findViewById<ImageButton>(buttonId) ?: return
button.setImageResource(iconRes)
button.setOnClickListener { selectTool(tool) }
toolButtons[tool] = button
}

fun selectTool(tool: AnnotationTouchEngine.ToolMode) {
// Deselect previous
val prevButton = toolButtons[selectedTool]
prevButton?.setBackgroundResource(R.drawable.bg_tool_button_normal)

selectedTool = tool

// Select new
val newButton = toolButtons[tool]
newButton?.setBackgroundResource(R.drawable.bg_tool_button_selected)

onToolSelectedListener?.invoke(tool)
}

fun setUndoEnabled(enabled: Boolean) {
rootLayout.findViewById<ImageButton>(R.id.btn_undo)?.alpha = if (enabled) 1.0f else 0.4f
}

fun setRedoEnabled(enabled: Boolean) {
rootLayout.findViewById<ImageButton>(R.id.btn_redo)?.alpha = if (enabled) 1.0f else 0.4f
}

private fun showColorPicker() {
val popup = PopupMenu(context, colorIndicator)
for (i in colors.indices) {
popup.menu.add(0, i, i, "Color ${i + 1}")
}
popup.setOnMenuItemClickListener { item ->
currentColorIndex = item.itemId
val color = colors[currentColorIndex]
updateColorIndicator()
onColorSelectedListener?.invoke(color)
true
}
popup.show()
}

private fun showStrokeWidthPicker() {
val widths = listOf(1f, 2f, 3f, 5f, 8f, 12f, 16f, 20f)
val popup = PopupMenu(context, colorIndicator)
widths.forEachIndexed { index, width ->
popup.menu.add(0, index, index, "${width.toInt()}px")
}
popup.setOnMenuItemClickListener { item ->
onStrokeWidthListener?.invoke(widths[item.itemId])
true
}
popup.show()
}

private fun updateColorIndicator() {
colorIndicator.setBackgroundColor(colors[currentColorIndex])
}

// ==================== LISTENERS ====================

fun setOnToolSelectedListener(listener: (AnnotationTouchEngine.ToolMode) -> Unit) {
onToolSelectedListener = listener
}

fun setOnColorSelectedListener(listener: (Int) -> Unit) {
onColorSelectedListener = listener
}

fun setOnStrokeWidthListener(listener: (Float) -> Unit) {
onStrokeWidthListener = listener
}

fun setOnUndoListener(listener: () -> Unit) {
onUndoListener = listener
}

fun setOnRedoListener(listener: () -> Unit) {
onRedoListener = listener
}

fun setOnClearListener(listener: () -> Unit) {
onClearListener = listener
}

fun setOnExportListener(listener: () -> Unit) {
onExportListener = listener
}

fun getSelectedTool(): AnnotationTouchEngine.ToolMode = selectedTool
}
