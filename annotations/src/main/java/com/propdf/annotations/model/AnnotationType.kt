package com.propdf.annotations.model

/**
 * Complete annotation type enumeration covering all PDF annotation types
 * and drawing tools supported by the proPDFEditor annotation system.
 */
enum class AnnotationType {
    // Text Markup Annotations
    HIGHLIGHT,
    UNDERLINE,
    STRIKEOUT,
    SQUIGGLY,

    // Text Content Annotations
    TEXT,           // Free text (floating)
    STICKY_NOTE,    // Popup note annotation
    TEXTBOX,        // Bounded text box
    FREE_TEXT,      // Free-flowing text

    // Shape Annotations
    RECTANGLE,
    CIRCLE,
    LINE,
    ARROW,
    POLYGON,
    CLOUD,

    // Stamp Annotations
    STAMP,
    DATE_STAMP,

    // Ink/Drawing Annotations
    INK,            // Generic ink
    SIGNATURE,      // Digital signature ink
    PEN,            // Standard pen
    CALLIGRAPHY,    // Calligraphy pen with variable width
    MARKER,         // Highlighter-like marker
    PENCIL,         // Pencil sketch effect
    ERASER,         // Eraser tool (removes ink)

    // Selection
    LASSO_SELECT,   // Lasso selection tool

    // Special
    UNKNOWN
}
