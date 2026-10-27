package com.propdf.core.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ShareStagingTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- sanitizeName ---------------------------------------------------------------------------

    @Test
    fun sanitizeName_keepsOrdinaryNames() {
        assertEquals("report 2026.pdf", ShareStaging.sanitizeName("report 2026.pdf"))
        assertEquals("\u0ba4\u0bae\u0bbf\u0bb4\u0bcd.pdf", ShareStaging.sanitizeName("\u0ba4\u0bae\u0bbf\u0bb4\u0bcd.pdf"))
    }

    @Test
    fun sanitizeName_removesPathSeparatorsAndTraversal() {
        val n = ShareStaging.sanitizeName("../../etc/passwd")
        assertFalse(n.contains("/"))
        assertFalse(n.startsWith("."))
        assertFalse(ShareStaging.sanitizeName("a\\b\\c.pdf").contains("\\"))
    }

    @Test
    fun sanitizeName_replacesReservedAndControlCharacters() {
        assertEquals("a_b_c_d_e_f.pdf", ShareStaging.sanitizeName("a:b*c?d\"e|f.pdf"))
        assertEquals("x_y.pdf", ShareStaging.sanitizeName("x\u0000y.pdf"))
    }

    @Test
    fun sanitizeName_neverReturnsEmptyOrHidden() {
        assertEquals("file", ShareStaging.sanitizeName(""))
        assertEquals("file", ShareStaging.sanitizeName("   "))
        assertEquals("file", ShareStaging.sanitizeName("...."))
        assertEquals("hidden.pdf", ShareStaging.sanitizeName(".hidden.pdf"))
    }

    @Test
    fun sanitizeName_limitsLength_andKeepsTheExtension() {
        val n = ShareStaging.sanitizeName("a".repeat(300) + ".pdf")
        assertTrue(n.length <= 120)
        assertTrue(n.endsWith(".pdf"))
        assertTrue(ShareStaging.sanitizeName("b".repeat(300)).length <= 120)
    }

    // ---- isInside ---------------------------------------------------------------------------------

    @Test
    fun isInside_acceptsFilesBelowTheRoot_only() {
        val root = tmp.newFolder("shared")
        val inside = File(File(root, "u1").apply { mkdirs() }, "a.pdf").apply { writeText("x") }
        val outside = tmp.newFile("outside.pdf")
        assertTrue(ShareStaging.isInside(root, inside))
        assertFalse(ShareStaging.isInside(root, outside))
        assertFalse("the root itself is not a shareable file", ShareStaging.isInside(root, root))
    }

    @Test
    fun isInside_rejectsDotDotTraversal() {
        val root = tmp.newFolder("shared")
        val outside = tmp.newFile("secret.txt")
        val sneaky = File(root, "../" + outside.name)
        assertFalse(ShareStaging.isInside(root, sneaky))
    }

    @Test
    fun isInside_rejectsSiblingDirectoryWithTheSamePrefix() {
        val root = tmp.newFolder("shared")
        val sibling = tmp.newFolder("shared_private")
        val f = File(sibling, "k.bin").apply { writeText("x") }
        assertFalse("'shared_private' must not count as inside 'shared'", ShareStaging.isInside(root, f))
    }

    // ---- purgeDirectory -----------------------------------------------------------------------------

    @Test
    fun purge_removesOldEntries_keepsNewOnes_andNothingOutside() {
        val root = tmp.newFolder("shared")
        val oldDir = File(root, "old").apply { mkdirs() }
        File(oldDir, "a.pdf").writeText("x")
        assertTrue(oldDir.setLastModified(1_000L))
        val newDir = File(root, "new").apply { mkdirs() }
        File(newDir, "b.pdf").writeText("x")
        assertTrue(newDir.setLastModified(9_000L))
        val unrelated = tmp.newFile("unrelated.txt")

        assertEquals(1, ShareStaging.purgeDirectory(root, cutoffMillis = 5_000L))
        assertFalse(oldDir.exists())
        assertTrue(newDir.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun purge_toleratesAMissingDirectory() {
        assertEquals(0, ShareStaging.purgeDirectory(File(tmp.root, "nope"), 5_000L))
    }
}
