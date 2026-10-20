package com.propdf.viewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ViewerUnlockedCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(): File = tmp.newFolder("cache")

    private fun unlockedFile(cache: File, name: String, modified: Long): File {
        val dir = ViewerUnlockedCache.dir(cache).apply { mkdirs() }
        return File(dir, name).apply {
            writeText("decrypted")
            assertTrue(setLastModified(modified))
        }
    }

    @Test
    fun directoryName_matchesTheViewerLocation() {
        assertEquals("viewer_unlocked", ViewerUnlockedCache.DIR_NAME)
        val cache = cache()
        assertEquals(File(cache, "viewer_unlocked"), ViewerUnlockedCache.dir(cache))
    }

    @Test
    fun purge_removesStaleCopies() {
        val cache = cache()
        val a = unlockedFile(cache, "a.pdf", 1_000L)
        val b = unlockedFile(cache, "b.pdf", 2_000L)
        assertEquals(2, ViewerUnlockedCache.purge(cache))
        assertFalse(a.exists())
        assertFalse(b.exists())
    }

    @Test
    fun purge_keepsTheFileInUse() {
        val cache = cache()
        val active = unlockedFile(cache, "active.pdf", 1_000L)
        val stale = unlockedFile(cache, "stale.pdf", 1_000L)
        assertEquals(1, ViewerUnlockedCache.purge(cache, except = active))
        assertTrue(active.exists())
        assertFalse(stale.exists())
    }

    @Test
    fun purge_atStartup_keepsCopiesCreatedAfterProcessStart() {
        val cache = cache()
        val processStart = 10_000L
        val old = unlockedFile(cache, "old.pdf", 5_000L)
        val fresh = unlockedFile(cache, "fresh.pdf", 10_500L)
        assertEquals(1, ViewerUnlockedCache.purge(cache, null, processStart))
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun purge_neverTouchesFilesOutsideTheUnlockedDirectory() {
        val cache = cache()
        unlockedFile(cache, "x.pdf", 1_000L)
        val sibling = File(cache, "pdf_bitmap_cache.bin").apply { writeText("keep"); setLastModified(1_000L) }
        val userOriginal = tmp.newFile("original.pdf").apply { writeText("user data") }
        ViewerUnlockedCache.purge(cache)
        assertTrue(sibling.exists())
        assertTrue(userOriginal.exists())
    }

    @Test
    fun purge_removesNestedDirectories_andToleratesMissingDirectory() {
        val cache = cache()
        assertEquals(0, ViewerUnlockedCache.purge(cache)) // directory does not exist yet
        val dir = ViewerUnlockedCache.dir(cache).apply { mkdirs() }
        val nested = File(dir, "nested").apply { mkdirs() }
        File(nested, "inner.pdf").writeText("x")
        nested.setLastModified(1_000L)
        assertEquals(1, ViewerUnlockedCache.purge(cache))
        assertFalse(nested.exists())
    }
}
