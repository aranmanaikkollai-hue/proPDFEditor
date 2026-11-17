package com.propdf.viewer.ui

/**
 * Reading colour scheme for the viewer. It is applied only when pages are drawn on screen, as a colour filter;
 * it never changes the PDF file, the rendered bitmaps in the cache, or anything that is exported or saved.
 *
 * [matrix] is a 4x5 row-major colour matrix in the same layout and 0..255 scale as android.graphics.ColorMatrix
 * and androidx.compose.ui.graphics.ColorMatrix, or null for no filter.
 */
enum class ReaderColorMode(val matrix: FloatArray?) {
    NORMAL(null),

    /** Inverts colours for low-glare reading. White pages become black. */
    NIGHT(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    ),

    /** Warm paper tone. White pages become a light cream. */
    SEPIA(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    );

    /** Applies this mode to one ARGB colour, the way the on-screen colour filter does. */
    fun transform(argb: Int): Int {
        val m = matrix ?: return argb
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        fun channel(row: Int): Int {
            val v = m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 3] * a + m[row * 5 + 4]
            return Math.round(v).coerceIn(0, 255)
        }
        return (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
    }

    /** Colour a blank white page takes on in this mode. */
    val pageBackgroundArgb: Int get() = transform(WHITE)

    /** Colour of the area behind and between pages in this mode. */
    val canvasBackgroundArgb: Int get() = transform(CANVAS_GREY)

    companion object {
        private const val WHITE = -1 // 0xFFFFFFFF
        private const val CANVAS_GREY = -2236963 // 0xFFDDDDDD

        fun fromName(name: String?): ReaderColorMode = values().firstOrNull { it.name == name } ?: NORMAL
    }
}
