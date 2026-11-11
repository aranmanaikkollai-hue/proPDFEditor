package com.propdfeditor.ui.compression

import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionStrategy
import kotlin.math.log10
import kotlin.math.pow

/** Largest image side (pixels) kept by each downsampling choice. [Int.MAX_VALUE] means "do not downsample". */
enum class ImageSizeChoice(val label: String, val maxImageDimension: Int) {
    KEEP("Keep size", Int.MAX_VALUE),
    LARGE("Large (2400 px)", 2400),
    MEDIUM("Medium (1600 px)", 1600),
    SMALL("Small (1024 px)", 1024)
}

/**
 * The only options the Compress screen offers. Every field maps to behaviour of the shared PDFBox compression
 * repository that is covered by tests: JPEG re-encoding quality, downsampling of oversized images and metadata
 * removal. Nothing else is switched on.
 */
data class CompressionSettings(
    val imageQuality: Int = DEFAULT_QUALITY,
    val imageSize: ImageSizeChoice = ImageSizeChoice.LARGE,
    val removeMetadata: Boolean = false
) {
    fun toConfig(): CompressionConfig = CompressionConfig(
        strategy = CompressionStrategy.CUSTOM,
        imageQuality = imageQuality.coerceIn(MIN_QUALITY, MAX_QUALITY),
        maxImageDimension = imageSize.maxImageDimension,
        removeMetadata = removeMetadata,
        // Steps that are unverified or do nothing in the engine stay off (same policy as the Page Editor).
        removeUnusedObjects = false,
        optimizeFonts = false,
        linearize = false,
        compressStreams = false,
        targetSizeBytes = null,
        preserveStructure = true,
        optimizeImages = true
    )

    companion object {
        const val MIN_QUALITY = 10
        const val MAX_QUALITY = 100
        const val DEFAULT_QUALITY = 70
    }
}

/** Before/after sizes measured from real files. No estimates. */
data class SizeComparison(val originalBytes: Long, val outputBytes: Long) {
    enum class Outcome { SMALLER, SAME, LARGER }

    val savedBytes: Long get() = originalBytes - outputBytes

    val outcome: Outcome
        get() = when {
            outputBytes < originalBytes -> Outcome.SMALLER
            outputBytes == originalBytes -> Outcome.SAME
            else -> Outcome.LARGER
        }

    /** Percentage of the original size that was saved; negative when the output is larger. 0 if the original is empty. */
    val savedPercent: Double
        get() = if (originalBytes > 0) savedBytes * 100.0 / originalBytes else 0.0
}

/** Human readable size, e.g. "1.5 MB". Non-positive values read "0 B". */
fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val group = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return if (group == 0) "$bytes B" else String.format(java.util.Locale.US, "%.1f %s", bytes / 1024.0.pow(group), units[group])
}
