package com.propdf.editor.utils

import kotlin.math.ln
import kotlin.math.pow

/**
 * Formats a byte count into a human-readable string (e.g. "1.5 MB").
 */
fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.size - 1)
    val value = bytes / 1024.0.pow(digitGroups.toDouble())
    return if (digitGroups == 0) {
        "${bytes} ${units[0]}"
    } else {
        "%.1f %s".format(value, units[digitGroups])
    }
}
