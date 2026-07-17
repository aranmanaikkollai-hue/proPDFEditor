package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Professional compression configuration supporting granular control
 * and quality presets for all compression strategies.
 */
@Parcelize
data class CompressionConfig(
    val strategy: CompressionStrategy = CompressionStrategy.BALANCED,
    val imageQuality: Int = 80,                    // JPEG quality 1-100
    val maxImageDimension: Int = 2048,            // Max width/height in pixels
    val downsampleDpi: Float = 150f,              // Target DPI for image resampling
    val removeMetadata: Boolean = true,           // Strip XMP, EXIF, document info
    val removeUnusedObjects: Boolean = true,      // Garbage collect unused PDF objects
    val optimizeFonts: Boolean = true,            // Subset and compress embedded fonts
    val linearize: Boolean = false,               // Enable fast web view (linearization)
    val compressStreams: Boolean = true,          // Recompress all streams with Flate
    val targetSizeBytes: Long? = null,            // Optional target file size
    val preserveStructure: Boolean = true         // Keep tags, accessibility
) : Parcelable

enum class CompressionStrategy {
    FAST,       // Speed priority - basic stream compression
    BALANCED,   // Default - image downsampling + metadata removal
    MAXIMUM,    // Aggressive - maximum downsampling, font subsetting, object cleanup
    CUSTOM      // User-defined settings
}

enum class QualityPreset(
    val displayName: String,
    val config: CompressionConfig
) {
    SCREEN(
        "Screen (72 DPI)",
        CompressionConfig(
            strategy = CompressionStrategy.FAST,
            imageQuality = 60,
            maxImageDimension = 1024,
            downsampleDpi = 72f,
            removeMetadata = true,
            removeUnusedObjects = true,
            optimizeFonts = true,
            linearize = true,
            compressStreams = true
        )
    ),
    EBOOK(
        "eBook (150 DPI)",
        CompressionConfig(
            strategy = CompressionStrategy.BALANCED,
            imageQuality = 75,
            maxImageDimension = 1536,
            downsampleDpi = 150f,
            removeMetadata = true,
            removeUnusedObjects = true,
            optimizeFonts = true,
            linearize = true,
            compressStreams = true
        )
    ),
    PRINTER(
        "Printer (300 DPI)",
        CompressionConfig(
            strategy = CompressionStrategy.BALANCED,
            imageQuality = 90,
            maxImageDimension = 2400,
            downsampleDpi = 300f,
            removeMetadata = false,
            removeUnusedObjects = true,
            optimizeFonts = true,
            linearize = false,
            compressStreams = true
        )
    ),
    PREPRESS(
        "Prepress (300+ DPI)",
        CompressionConfig(
            strategy = CompressionStrategy.MAXIMUM,
            imageQuality = 95,
            maxImageDimension = 3508,
            downsampleDpi = 300f,
            removeMetadata = false,
            removeUnusedObjects = false,
            optimizeFonts = false,
            linearize = false,
            compressStreams = true
        )
    ),
    ARCHIVE(
        "Maximum Compression",
        CompressionConfig(
            strategy = CompressionStrategy.MAXIMUM,
            imageQuality = 50,
            maxImageDimension = 1024,
            downsampleDpi = 72f,
            removeMetadata = true,
            removeUnusedObjects = true,
            optimizeFonts = true,
            linearize = true,
            compressStreams = true
        )
    )
}

/**
 * Detailed result of a compression operation including before/after metrics.
 */
@Parcelize
data class CompressionResult(
    val originalUri: String,
    val compressedUri: String,
    val originalSizeBytes: Long,
    val compressedSizeBytes: Long,
    val pageCount: Int,
    val imagesProcessed: Int,
    val fontsOptimized: Int,
    val metadataRemoved: Boolean,
    val objectsRemoved: Int,
    val linearized: Boolean,
    val durationMs: Long,
    val qualityScore: Float // Estimated quality retention 0.0-1.0
) : Parcelable {
    val compressionRatio: Float
        get() = if (originalSizeBytes > 0) {
            1f - (compressedSizeBytes.toFloat() / originalSizeBytes.toFloat())
        } else 0f

    val spaceSavedBytes: Long
        get() = originalSizeBytes - compressedSizeBytes

    val compressionPercentage: Int
        get() = (compressionRatio * 100).toInt()
}

/**
 * Preview of compression without full execution.
 */
@Parcelize
data class CompressionPreview(
    val estimatedSizeBytes: Long,
    val estimatedRatio: Float,
    val estimatedQualityScore: Float,
    val willDownsampleImages: Boolean,
    val willRemoveMetadata: Boolean,
    val willOptimizeFonts: Boolean,
    val warnings: List<String>
) : Parcelable
