package com.propdf.editor.worker

import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.CompressionStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The mapping is pure Kotlin, so it runs on the JVM. Engine behaviour is covered by the instrumented tests. */
class PageEditorCompressionTest {

    @Test
    fun level_selectsStrategyBands() {
        assertEquals(CompressionStrategy.FAST, PageEditorCompression.compressionConfigFor(CompressConfig(level = 1)).strategy)
        assertEquals(CompressionStrategy.FAST, PageEditorCompression.compressionConfigFor(CompressConfig(level = 3)).strategy)
        assertEquals(CompressionStrategy.BALANCED, PageEditorCompression.compressionConfigFor(CompressConfig(level = 4)).strategy)
        assertEquals(CompressionStrategy.BALANCED, PageEditorCompression.compressionConfigFor(CompressConfig(level = 6)).strategy)
        assertEquals(CompressionStrategy.MAXIMUM, PageEditorCompression.compressionConfigFor(CompressConfig(level = 7)).strategy)
        assertEquals(CompressionStrategy.MAXIMUM, PageEditorCompression.compressionConfigFor(CompressConfig(level = 9)).strategy)
    }

    @Test
    fun unverifiedSteps_areAlwaysOff() {
        for (level in 1..9) {
            val c = PageEditorCompression.compressionConfigFor(
                CompressConfig(level = level, removeUnusedObjects = true)
            )
            assertFalse("removeUnusedObjects @ level $level", c.removeUnusedObjects)
            assertFalse("compressStreams @ level $level", c.compressStreams)
            assertFalse("optimizeFonts @ level $level", c.optimizeFonts)
            assertFalse("linearize @ level $level", c.linearize)
            assertTrue(c.preserveStructure)
        }
        for (aggressive in listOf(false, true)) {
            val c = PageEditorCompression.optimizeConfig(aggressive)
            assertFalse(c.removeUnusedObjects)
            assertFalse(c.compressStreams)
            assertFalse(c.optimizeFonts)
            assertFalse(c.linearize)
        }
    }

    @Test
    fun userChoices_arePassedThrough() {
        val c = PageEditorCompression.compressionConfigFor(
            CompressConfig(level = 5, imageQuality = 42, compressImages = false, removeMetadata = true, targetDpi = 200, targetSizeBytes = 123_456L)
        )
        assertEquals(42, c.imageQuality)
        assertFalse(c.optimizeImages)
        assertTrue(c.removeMetadata)
        assertEquals(200f, c.downsampleDpi, 0.001f)
        assertEquals(123_456L, c.targetSizeBytes)
    }

    @Test
    fun outOfRangeValues_areClamped() {
        val low = PageEditorCompression.compressionConfigFor(CompressConfig(imageQuality = -5, targetDpi = 1))
        assertEquals(10, low.imageQuality)
        assertEquals(36f, low.downsampleDpi, 0.001f)
        val high = PageEditorCompression.compressionConfigFor(CompressConfig(imageQuality = 500, targetDpi = 9999))
        assertEquals(100, high.imageQuality)
        assertEquals(600f, high.downsampleDpi, 0.001f)
    }

    @Test
    fun optimize_isNonDestructiveAndAggressiveIsStronger() {
        val normal = PageEditorCompression.optimizeConfig(aggressive = false)
        val aggressive = PageEditorCompression.optimizeConfig(aggressive = true)
        assertFalse(normal.removeMetadata)
        assertFalse(aggressive.removeMetadata)
        assertTrue(normal.optimizeImages && aggressive.optimizeImages)
        assertTrue(aggressive.imageQuality < normal.imageQuality)
        assertTrue(aggressive.maxImageDimension < normal.maxImageDimension)
        assertTrue(aggressive.downsampleDpi < normal.downsampleDpi)
        assertNull(normal.targetSizeBytes)
    }

    @Test
    fun flattenFormsMessage_isUserReadable() {
        assertTrue(PageEditorCompression.FLATTEN_FORMS_UNSUPPORTED.isNotBlank())
        assertFalse(PageEditorCompression.FLATTEN_FORMS_UNSUPPORTED.contains("Exception"))
    }
}
