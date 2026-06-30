package com.propdf.viewer.model

/**
 * Priority levels for page render requests. Lower ordinal = higher priority
 * (processed first by AsyncPageRenderer's priority queue).
 */
enum class RenderPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}
