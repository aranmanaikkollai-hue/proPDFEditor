package com.propdf.annotations.transform

import com.propdf.annotations.model.Annotation
import com.propdf.annotations.model.StrokeAnnotation
import com.propdf.annotations.model.ShapeAnnotation
import com.propdf.annotations.model.TextAnnotation
import com.propdf.annotations.model.HighlightAnnotation

/**
 * Handles move, resize, rotate, recolor, and opacity changes for annotations.
 */
class AnnotationTransformer {

    fun changeColor(annotation: Annotation, newColor: Int): Annotation {
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(color = newColor, modifiedAt = System.currentTimeMillis())
            is ShapeAnnotation -> annotation.copy(color = newColor, modifiedAt = System.currentTimeMillis())
            is TextAnnotation -> annotation.copy(color = newColor, modifiedAt = System.currentTimeMillis())
            is HighlightAnnotation -> annotation.copy(color = newColor, modifiedAt = System.currentTimeMillis())
            else -> annotation
        }
    }

    fun changeOpacity(annotation: Annotation, newOpacity: Float): Annotation {
        val clamped = newOpacity.coerceIn(0f, 1f)
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(opacity = clamped, modifiedAt = System.currentTimeMillis())
            is ShapeAnnotation -> annotation.copy(opacity = clamped, modifiedAt = System.currentTimeMillis())
            is TextAnnotation -> annotation.copy(opacity = clamped, modifiedAt = System.currentTimeMillis())
            is HighlightAnnotation -> annotation.copy(opacity = clamped, modifiedAt = System.currentTimeMillis())
            else -> annotation
        }
    }

    fun changeStrokeWidth(annotation: Annotation, newWidth: Float): Annotation {
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(strokeWidth = newWidth, modifiedAt = System.currentTimeMillis())
            is ShapeAnnotation -> annotation.copy(strokeWidth = newWidth, modifiedAt = System.currentTimeMillis())
            else -> annotation
        }
    }

    fun changeText(annotation: TextAnnotation, newText: String): Annotation {
        return annotation.copy(text = newText, modifiedAt = System.currentTimeMillis())
    }

    fun changeFontSize(annotation: TextAnnotation, newSize: Float): Annotation {
        return annotation.copy(fontSize = newSize, modifiedAt = System.currentTimeMillis())
    }
}
