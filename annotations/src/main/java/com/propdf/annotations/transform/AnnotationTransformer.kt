package com.propdf.annotations.transform

import com.propdf.annotations.model.*
import com.propdf.annotations.model.Annotation

/**
 * Handles move, resize, rotate, recolor, and property changes for annotations.
 * All operations return new immutable instances (copy-on-write).
 * Thread-safe: no shared mutable state.
 */
class AnnotationTransformer {

    fun changeColor(annotation: Annotation, newColor: Int): Annotation {
        val modifiedAt = System.currentTimeMillis()
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(color = newColor, modifiedAt = modifiedAt)
            is ShapeAnnotation -> annotation.copy(color = newColor, modifiedAt = modifiedAt)
            is TextAnnotation -> annotation.copy(color = newColor, modifiedAt = modifiedAt)
            is HighlightAnnotation -> annotation.copy(color = newColor, modifiedAt = modifiedAt)
            is StampAnnotation -> annotation.copy(color = newColor, modifiedAt = modifiedAt)
            is LassoAnnotation -> annotation.copy(color = newColor, modifiedAt = modifiedAt)
            else -> annotation
        }
    }

    fun changeOpacity(annotation: Annotation, newOpacity: Float): Annotation {
        val clamped = newOpacity.coerceIn(0f, 1f)
        val modifiedAt = System.currentTimeMillis()
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(opacity = clamped, modifiedAt = modifiedAt)
            is ShapeAnnotation -> annotation.copy(opacity = clamped, modifiedAt = modifiedAt)
            is TextAnnotation -> annotation.copy(opacity = clamped, modifiedAt = modifiedAt)
            is HighlightAnnotation -> annotation.copy(opacity = clamped, modifiedAt = modifiedAt)
            is StampAnnotation -> annotation.copy(opacity = clamped, modifiedAt = modifiedAt)
            is LassoAnnotation -> annotation.copy(opacity = clamped, modifiedAt = modifiedAt)
            else -> annotation
        }
    }

    fun changeStrokeWidth(annotation: Annotation, newWidth: Float): Annotation {
        val modifiedAt = System.currentTimeMillis()
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(strokeWidth = newWidth, modifiedAt = modifiedAt)
            is ShapeAnnotation -> annotation.copy(strokeWidth = newWidth, modifiedAt = modifiedAt)
            else -> annotation
        }
    }

    fun changeFillColor(annotation: Annotation, fillColor: Int?): Annotation {
        val modifiedAt = System.currentTimeMillis()
        return when (annotation) {
            is ShapeAnnotation -> annotation.copy(fillColor = fillColor, modifiedAt = modifiedAt)
            is TextAnnotation -> annotation.copy(backgroundColor = fillColor, modifiedAt = modifiedAt)
            is StampAnnotation -> annotation.copy(backgroundColor = fillColor, modifiedAt = modifiedAt)
            else -> annotation
        }
    }

    fun changeText(annotation: TextAnnotation, newText: String): Annotation {
        return annotation.copy(text = newText, modifiedAt = System.currentTimeMillis())
    }

    fun changeFontSize(annotation: TextAnnotation, newSize: Float): Annotation {
        return annotation.copy(fontSize = newSize.coerceAtLeast(4f), modifiedAt = System.currentTimeMillis())
    }

    fun changeFontStyle(
        annotation: TextAnnotation, 
        bold: Boolean? = null, 
        italic: Boolean? = null
    ): Annotation {
        return annotation.copy(
            isBold = bold ?: annotation.isBold,
            isItalic = italic ?: annotation.isItalic,
            modifiedAt = System.currentTimeMillis()
        )
    }

    fun changeTextAlignment(
        annotation: TextAnnotation, 
        alignment: TextAnnotation.TextAlignment
    ): Annotation {
        return annotation.copy(textAlignment = alignment, modifiedAt = System.currentTimeMillis())
    }

    fun changeStampType(
        annotation: StampAnnotation, 
        newType: StampAnnotation.StampType
    ): Annotation {
        return annotation.copy(stampType = newType, modifiedAt = System.currentTimeMillis())
    }

    fun changeStampText(annotation: StampAnnotation, newText: String): Annotation {
        return annotation.copy(customText = newText, modifiedAt = System.currentTimeMillis())
    }

    fun toggleDashed(annotation: ShapeAnnotation): Annotation {
        return annotation.copy(
            isDashed = !annotation.isDashed, 
            modifiedAt = System.currentTimeMillis()
        )
    }

    fun toggleVisibility(annotation: Annotation): Annotation {
        val modifiedAt = System.currentTimeMillis()
        return when (annotation) {
            is StrokeAnnotation -> annotation.copy(
                isVisible = !annotation.isVisible, 
                modifiedAt = modifiedAt
            )
            is ShapeAnnotation -> annotation.copy(
                isVisible = !annotation.isVisible, 
                modifiedAt = modifiedAt
            )
            is TextAnnotation -> annotation.copy(
                isVisible = !annotation.isVisible, 
                modifiedAt = modifiedAt
            )
            is HighlightAnnotation -> annotation.copy(
                isVisible = !annotation.isVisible, 
                modifiedAt = modifiedAt
            )
            is StampAnnotation -> annotation.copy(
                isVisible = !annotation.isVisible, 
                modifiedAt = modifiedAt
            )
            else -> annotation
        }
    }

    /**
     * Batch transform multiple annotations.
     */
    fun batchTransform(
        annotations: List<Annotation>,
        transform: (Annotation) -> Annotation
    ): List<Annotation> {
        return annotations.map(transform)
    }
}
