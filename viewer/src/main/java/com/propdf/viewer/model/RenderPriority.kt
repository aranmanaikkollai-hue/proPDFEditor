package com.propdf.viewer.model

/**
 * Priority levels for async page rendering requests.
 */
enum class RenderPriority {
    CRITICAL,   // Currently visible page
    HIGH,       // Adjacent pages
    NORMAL,     // Nearby thumbnails
    LOW         // Far-away thumbnails
}
