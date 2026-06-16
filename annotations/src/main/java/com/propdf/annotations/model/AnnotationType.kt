// annotations/src/main/java/com/propdf/annotations/model/AnnotationType.kt
package com.propdf.annotations.model

enum class AnnotationType {
    INK,           // Freehand stroke
    HIGHLIGHT,     // Text highlight
    UNDERLINE,     // Text underline
    STRIKEOUT,     // Text strikeout
    RECTANGLE,     // Rectangle shape
    CIRCLE,        // Circle/ellipse shape
    LINE,          // Straight line
    ARROW,         // Arrow line
    TEXT,          // Free text box
    SIGNATURE,     // Signature ink
    STAMP          // Image stamp
}
