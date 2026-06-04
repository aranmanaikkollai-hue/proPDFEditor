package com.propdf.viewer.annotation.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import com.propdf.viewer.R
import com.propdf.viewer.gesture.UnifiedGestureCoordinator

class AnnotationToolbarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var onToolSelected: ((UnifiedGestureCoordinator.ToolMode) -> Unit)? = null
    private var onColorSelected: ((Int) -> Unit)? = null
    private var onStrokeWidth: ((Float) -> Unit)? = null
    private var onUndo: (() -> Unit)? = null
    private var onRedo: (() -> Unit)? = null
    private var onClear: (() -> Unit)? = null
    private var onExport: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_annotation_toolbar, this, true)
    }

    fun setOnToolSelectedListener(listener: (UnifiedGestureCoordinator.ToolMode) -> Unit) {
        onToolSelected = listener
    }

    fun setOnColorSelectedListener(listener: (Int) -> Unit) {
        onColorSelected = listener
    }

    fun setOnStrokeWidthListener(listener: (Float) -> Unit) {
        onStrokeWidth = listener
    }

    fun setOnUndoListener(listener: () -> Unit) {
        onUndo = listener
    }

    fun setOnRedoListener(listener: () -> Unit) {
        onRedo = listener
    }

    fun setOnClearListener(listener: () -> Unit) {
        onClear = listener
    }

    fun setOnExportListener(listener: () -> Unit) {
        onExport = listener
    }

    fun setUndoEnabled(enabled: Boolean) {}
    fun setRedoEnabled(enabled: Boolean) {}
}
