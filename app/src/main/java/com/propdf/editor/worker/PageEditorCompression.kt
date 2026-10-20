package com.propdf.editor.worker

import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.CompressionConfig
import com.propdf.core.domain.model.CompressionStrategy

/**
 * Maps the Page Editor's compress / optimize requests onto the existing PDFBox compression
 * repository (`:core` `PdfBoxCompressionRepository`, reached through `PdfProcessor.compressPdf`).
 *
 * Deliberately conservative: steps whose behaviour is not yet verified by tests are switched off
 * ([CompressionConfig.removeUnusedObjects], [CompressionConfig.compressStreams],
 * [CompressionConfig.optimizeFonts], [CompressionConfig.linearize]). What remains is image
 * re-encoding / downsampling and optional metadata removal, which have engine tests
 * (`CompressionOutputInstrumentedTest`, `PdfProcessorSignedAndCompressionTest`).
 *
 * Pure Kotlin so it can be unit-tested on the JVM.
 */
object PageEditorCompression {

    /** Reason shown when a requested option cannot be honoured; never silently ignored. */
    const val FLATTEN_FORMS_UNSUPPORTED =
        "Flattening form fields while compressing is not supported. Turn that option off and try again."

    fun compressionConfigFor(config: CompressConfig): CompressionConfig {
        val strategy = when {
            config.level <= 3 -> CompressionStrategy.FAST
            config.level <= 6 -> CompressionStrategy.BALANCED
            else -> CompressionStrategy.MAXIMUM
        }
        return CompressionConfig(
            strategy = strategy,
            imageQuality = config.imageQuality.coerceIn(MIN_QUALITY, 100),
            maxImageDimension = maxDimensionFor(strategy),
            downsampleDpi = config.targetDpi.coerceIn(MIN_DPI, MAX_DPI).toFloat(),
            removeMetadata = config.removeMetadata,
            removeUnusedObjects = false,
            optimizeFonts = false,
            linearize = false,
            compressStreams = false,
            targetSizeBytes = config.targetSizeBytes,
            preserveStructure = true,
            optimizeImages = config.compressImages
        )
    }

    fun optimizeConfig(aggressive: Boolean): CompressionConfig {
        val strategy = if (aggressive) CompressionStrategy.MAXIMUM else CompressionStrategy.BALANCED
        return CompressionConfig(
            strategy = strategy,
            imageQuality = if (aggressive) 55 else 75,
            maxImageDimension = maxDimensionFor(strategy),
            downsampleDpi = if (aggressive) 110f else 150f,
            removeMetadata = false,
            removeUnusedObjects = false,
            optimizeFonts = false,
            linearize = false,
            compressStreams = false,
            targetSizeBytes = null,
            preserveStructure = true,
            optimizeImages = true
        )
    }

    private fun maxDimensionFor(strategy: CompressionStrategy): Int = when (strategy) {
        CompressionStrategy.FAST -> 2400
        CompressionStrategy.BALANCED, CompressionStrategy.CUSTOM -> 2048
        CompressionStrategy.MAXIMUM -> 1536
    }

    private const val MIN_QUALITY = 10
    private const val MIN_DPI = 36
    private const val MAX_DPI = 600
}
