package com.propdf.viewer.annotation.model

/**
* All supported annotation types for the premium annotation engine.
*/
enum class AnnotationType {
    HIGHLIGHT,
    UNDERLINE,
    STRIKEOUT,
    FREEHAND,
    PENCIL,
    TEXT_COMMENT,
    STICKY_NOTE,
    ARROW,
    RECTANGLE,
    CIRCLE,
    SIGNATURE,
    IMAGE_STAMP
}

enum class AnnotationState {
    CREATED,
    EDITING,
    SELECTED,
    LOCKED
}
