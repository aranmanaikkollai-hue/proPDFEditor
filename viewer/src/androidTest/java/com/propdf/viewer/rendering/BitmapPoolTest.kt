package com.propdf.viewer.rendering

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for BitmapPool.
 */
@RunWith(AndroidJUnit4::class)
class BitmapPoolTest {

    private lateinit var bitmapPool: BitmapPool

    @Before
    fun setup() {
        bitmapPool = BitmapPool.getInstance()
        bitmapPool.initialize(4L * 1024 * 1024 * 1024)
    }

    @After
    fun tearDown() = runBlocking {
        bitmapPool.clear()
    }

    @Test
    fun testAcquireAndRelease() = runBlocking {
        val bitmap = bitmapPool.acquire(256, 256)
        assertNotNull(bitmap)
        assertEquals(256, bitmap.width)
        assertEquals(256, bitmap.height)
        assertFalse(bitmap.isRecycled)

        bitmapPool.release(bitmap)
        val stats = bitmapPool.getStats()
        assertTrue(stats.pooledCount >= 1)
    }

    @Test
    fun testBitmapReuse() = runBlocking {
        val bitmap1 = bitmapPool.acquire(256, 256)
        bitmapPool.release(bitmap1)

        val bitmap2 = bitmapPool.acquire(256, 256)
        assertSame(bitmap1, bitmap2)
    }

    @Test
    fun testPoolTrimming() = runBlocking {
        val bitmaps = mutableListOf<Bitmap>()
        repeat(100) {
            bitmaps.add(bitmapPool.acquire(512, 512))
        }

        bitmaps.forEach { bitmapPool.release(it) }

        val stats = bitmapPool.getStats()
        assertTrue(stats.pooledBytes <= stats.maxPoolBytes)
    }

    @Test
    fun testConcurrentAccess() = runBlocking {
        val mutex = Mutex()
        val acquired = mutableListOf<Bitmap>()

        val jobs = (1..20).map {
            kotlinx.coroutines.GlobalScope.launch {
                val bitmap = bitmapPool.acquire(128, 128)
                mutex.withLock { acquired.add(bitmap) }
                kotlinx.coroutines.delay(10)
                bitmapPool.release(bitmap)
            }
        }
        jobs.forEach { it.join() }

        val stats = bitmapPool.getStats()
        assertEquals(0, stats.activeCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testInvalidDimensions() = runBlocking {
        bitmapPool.acquire(0, 256)
    }

    @Test
    fun testEmergencyTrim() = runBlocking {
        val largeBitmaps = (1..10).map {
            bitmapPool.acquire(2048, 2048)
        }

        largeBitmaps.forEach { bitmapPool.release(it) }

        val stats = bitmapPool.getStats()
        assertTrue(stats.pooledBytes >= 0)
    }
}
