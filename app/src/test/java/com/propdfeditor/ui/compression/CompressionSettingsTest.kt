package com.propdfeditor.ui.compression

import com.propdf.core.domain.model.CompressionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the options the Compress screen exposes and for the size arithmetic it displays. */
class CompressionSettingsTest {

    @Test
    fun toConfig_switchesOffEverythingNotVerified() {
        val config = CompressionSettings().toConfig()
        assertFalse(config.removeUnusedObjects)
        assertFalse(config.optimizeFonts)
        assertFalse(config.linearize)
        assertFalse(config.compressStreams)
        assertTrue(config.optimizeImages)
        assertTrue(config.preserveStructure)
        assertEquals(CompressionStrategy.CUSTOM, config.strategy)
        assertEquals(null, config.targetSizeBytes)
    }

    @Test
    fun toConfig_mapsQualitySizeAndMetadata() {
        val config = CompressionSettings(imageQuality = 55, imageSize = ImageSizeChoice.MEDIUM, removeMetadata = true).toConfig()
        assertEquals(55, config.imageQuality)
        assertEquals(1600, config.maxImageDimension)
        assertTrue(config.removeMetadata)
    }

    @Test
    fun toConfig_clampsQualityToSupportedRange() {
        assertEquals(CompressionSettings.MIN_QUALITY, CompressionSettings(imageQuality = -5).toConfig().imageQuality)
        assertEquals(CompressionSettings.MAX_QUALITY, CompressionSettings(imageQuality = 500).toConfig().imageQuality)
    }

    @Test
    fun keepSize_neverDownsamples() {
        assertEquals(Int.MAX_VALUE, CompressionSettings(imageSize = ImageSizeChoice.KEEP).toConfig().maxImageDimension)
    }

    @Test
    fun defaultsDoNotRemoveMetadata() {
        assertFalse(CompressionSettings().removeMetadata)
    }

    @Test
    fun comparison_smaller() {
        val c = SizeComparison(originalBytes = 1_000, outputBytes = 400)
        assertEquals(SizeComparison.Outcome.SMALLER, c.outcome)
        assertEquals(600L, c.savedBytes)
        assertEquals(60.0, c.savedPercent, 0.0001)
    }

    @Test
    fun comparison_larger_isNegative() {
        val c = SizeComparison(originalBytes = 1_000, outputBytes = 1_250)
        assertEquals(SizeComparison.Outcome.LARGER, c.outcome)
        assertEquals(-250L, c.savedBytes)
        assertEquals(-25.0, c.savedPercent, 0.0001)
    }

    @Test
    fun comparison_same() {
        val c = SizeComparison(500, 500)
        assertEquals(SizeComparison.Outcome.SAME, c.outcome)
        assertEquals(0.0, c.savedPercent, 0.0001)
    }

    @Test
    fun comparison_zeroOriginal_doesNotDivideByZero() {
        assertEquals(0.0, SizeComparison(0, 10).savedPercent, 0.0)
    }

    @Test
    fun formatBytes_readsNaturally() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("0 B", formatBytes(-4))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1_572_864))
    }
}
