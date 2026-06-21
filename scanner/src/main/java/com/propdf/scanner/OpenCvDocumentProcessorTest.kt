package com.propdfeditor.scanner

import android.graphics.Bitmap
import com.propdfeditor.scanner.data.processing.OpenCvDocumentProcessor
import com.propdfeditor.scanner.domain.model.EnhancementParams
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OpenCvDocumentProcessorTest {

    private lateinit var processor: OpenCvDocumentProcessor

    @Before
    fun setup() {
        processor = OpenCvDocumentProcessor()
        // Mock OpenCV initialization for unit tests
        // In real tests, use OpenCVLoader.initDebug()
    }

    @Test
    fun `detectDocumentEdges returns 4 corners`() = runBlocking {
        // Create a synthetic test bitmap with clear edges
        val testBitmap = Bitmap.createBitmap(500, 700, Bitmap.Config.ARGB_8888)
        // Fill with white
        testBitmap.eraseColor(0xFFFFFFFF.toInt())
        // Draw black rectangle (simulated document)
        val canvas = android.graphics.Canvas(testBitmap)
        val paint = android.graphics.Paint()
        paint.color = 0xFF000000.toInt()
        canvas.drawRect(50f, 50f, 450f, 650f, paint)

        val result = processor.detectDocumentEdges(testBitmap)

        assertEquals(4, result.corners.size)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun `applyPerspectiveCorrection returns rectangular bitmap`() = runBlocking {
        val sourceBitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        sourceBitmap.eraseColor(0xFFFFFFFF.toInt())

        val corners = listOf(
            com.propdfeditor.scanner.domain.model.PointF(0.1f, 0.1f),
            com.propdfeditor.scanner.domain.model.PointF(0.9f, 0.1f),
            com.propdfeditor.scanner.domain.model.PointF(0.9f, 0.9f),
            com.propdfeditor.scanner.domain.model.PointF(0.1f, 0.9f)
        )

        val result = processor.applyPerspectiveCorrection(sourceBitmap, corners)

        assertNotNull(result)
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
    }

    @Test
    fun `generateThumbnail scales bitmap proportionally`() = runBlocking {
        val sourceBitmap = Bitmap.createBitmap(2000, 3000, Bitmap.Config.ARGB_8888)
        val maxDimension = 256

        val thumbnail = processor.generateThumbnail(sourceBitmap, maxDimension)

        assertTrue(thumbnail.width <= maxDimension)
        assertTrue(thumbnail.height <= maxDimension)
        // Aspect ratio should be preserved
        val sourceRatio = sourceBitmap.width.toFloat() / sourceBitmap.height
        val thumbRatio = thumbnail.width.toFloat() / thumbnail.height
        assertEquals(sourceRatio, thumbRatio, 0.01f)
    }

    @Test
    fun `enhanceGeneral with B&W params produces valid bitmap`() = runBlocking {
        val sourceBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        sourceBitmap.eraseColor(0xFF808080.toInt())

        val params = EnhancementParams(blackAndWhite = true)
        val result = processor.enhanceGeneral(sourceBitmap, params)

        assertNotNull(result)
        // Result should be valid bitmap
        assertTrue(result.width > 0)
        assertTrue(result.height > 0)
    }
}
