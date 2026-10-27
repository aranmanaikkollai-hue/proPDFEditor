package com.propdf.core.share

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * The app's FileProvider must hand out only what was deliberately staged for sharing (cache/shared/) plus the
 * narrow scanner and conversion output folders, and must refuse everything else, in particular imported
 * signing certificates, decrypted working copies, databases and the shared-storage root. These tests use the
 * real provider declared in the manifest and `res/xml/file_paths.xml`.
 *
 * Not covered: a different app actually reading a granted URI (needs a second app); behaviour on every OEM.
 * Android instrumented test; NOT RUN in the authoring environment.
 */
@RunWith(AndroidJUnit4::class)
class ShareStagingInstrumentedTest {

    private lateinit var context: Context
    private val created = mutableListOf<File>()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        created.forEach { it.delete() }
        ShareStaging.purgeOlderThan(context, maxAgeMs = -1L) // remove everything staged by these tests
    }

    private fun file(dir: File, name: String, text: String = "data"): File {
        dir.mkdirs()
        return File(dir, name).apply { writeText(text); created.add(this) }
    }

    private fun authority() = ShareStaging.authority(context)

    private fun expectRefused(file: File, label: String) {
        try {
            FileProvider.getUriForFile(context, authority(), file)
            fail("the provider must refuse: $label")
        } catch (e: IllegalArgumentException) {
            // expected: "Failed to find configured root that contains ..."
        }
    }

    // ---- staging --------------------------------------------------------------------------------

    @Test
    fun stageFile_copiesTheBytes_andTheUriIsReadable() {
        val source = file(File(context.filesDir, "stage_src"), "doc.pdf", "hello pdf")
        val uri = ShareStaging.stageFile(context, source)
        assertEquals("content", uri.scheme)
        assertEquals(authority(), uri.authority)
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertArrayEquals("hello pdf".toByteArray(), bytes)
        assertTrue("the original must be untouched", source.exists())
    }

    @Test
    fun stageFile_sameNameTwice_usesSeparateDirectories() {
        val source = file(File(context.filesDir, "stage_src"), "same.pdf")
        val a = ShareStaging.stageFile(context, source)
        val b = ShareStaging.stageFile(context, source)
        assertNotEquals(a, b)
        assertTrue(a.lastPathSegment == b.lastPathSegment)
    }

    @Test
    fun stageFile_sanitisesTheDisplayName() {
        val source = file(File(context.filesDir, "stage_src"), "plain.pdf")
        val uri = ShareStaging.stageFile(context, source, "../../evil:name?.pdf")
        val name = uri.lastPathSegment!!
        assertFalse(name.contains("/") || name.contains(":") || name.contains("?"))
        assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().isNotEmpty() })
    }

    @Test
    fun stageFile_missingSource_isRefused_andStagesNothing() {
        val before = ShareStaging.rootDir(context).listFiles().orEmpty().size
        try {
            ShareStaging.stageFile(context, File(context.filesDir, "does_not_exist_${UUID.randomUUID()}.pdf"))
            fail("a missing file must be refused")
        } catch (e: IllegalArgumentException) {
        }
        assertEquals(before, ShareStaging.rootDir(context).listFiles().orEmpty().size)
    }

    @Test
    fun newFileAndUriFor_supportTheCropScreenPattern() {
        val target = ShareStaging.newFile(context, "crop_temp.jpg")
        target.writeBytes(byteArrayOf(1, 2, 3))
        val uri = ShareStaging.uriFor(context, target)
        assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test
    fun uriFor_refusesFilesThatWereNotStaged() {
        val outside = file(File(context.filesDir, "keystores"), "cert_${UUID.randomUUID()}.p12", "secret")
        try {
            ShareStaging.uriFor(context, outside)
            fail("uriFor must refuse a file outside the staging directory")
        } catch (e: IllegalArgumentException) {
        }
    }

    // ---- the provider configuration itself ----------------------------------------------------------

    @Test
    fun provider_refusesPrivateAndBroadLocations() {
        expectRefused(file(File(context.filesDir, "keystores"), "k_${UUID.randomUUID()}.p12"), "files/keystores")
        expectRefused(file(File(context.filesDir, "annotations"), "a_${UUID.randomUUID()}.json"), "files/annotations")
        expectRefused(file(context.filesDir, "root_${UUID.randomUUID()}.pdf"), "files root")
        expectRefused(file(File(context.cacheDir, "viewer_unlocked"), "u_${UUID.randomUUID()}.pdf"), "cache/viewer_unlocked")
        expectRefused(file(context.cacheDir, "root_${UUID.randomUUID()}.pdf"), "cache root")
        val db = context.getDatabasePath("probe_${UUID.randomUUID()}.db").apply { parentFile?.mkdirs(); writeText("x"); created.add(this) }
        expectRefused(db, "databases")
        val downloads = File(Environment.getExternalStorageDirectory(), "Download")
        if (downloads.isDirectory && downloads.canWrite()) {
            expectRefused(file(downloads, "probe_${UUID.randomUUID()}.pdf"), "shared storage Download")
        }
        context.getExternalFilesDir(null)?.let { expectRefused(file(it, "ext_${UUID.randomUUID()}.pdf"), "external files root") }
    }

    @Test
    fun provider_acceptsOnlyTheNarrowAllowedFolders() {
        val allowed = mutableListOf(
            file(File(context.filesDir, "scanner/processed"), "p_${UUID.randomUUID()}.jpg"),
            file(File(context.filesDir, "scanner/thumbnails"), "t_${UUID.randomUUID()}.jpg"),
            file(File(context.filesDir, "scanner/original"), "o_${UUID.randomUUID()}.jpg"),
            file(File(context.cacheDir, "conversions"), "c_${UUID.randomUUID()}.pdf"),
            file(File(context.cacheDir, "shared/manual"), "s_${UUID.randomUUID()}.pdf")
        )
        context.getExternalFilesDir(null)?.let {
            allowed.add(file(File(it, "Documents/conversions"), "e_${UUID.randomUUID()}.pdf"))
        }
        for (f in allowed) {
            val uri = FileProvider.getUriForFile(context, authority(), f)
            assertEquals("content", uri.scheme)
        }
    }

    @Test
    fun provider_isNotExported_andGrantsOnlyPerUriPermissions() {
        val info = context.packageManager.getProviderInfo(
            ComponentName(context.packageName, "androidx.core.content.FileProvider"), PackageManager.GET_META_DATA
        )
        assertFalse("the provider must not be exported", info.exported)
        assertTrue("access is only via granted URI permissions", info.grantUriPermissions)
        assertEquals(authority(), info.authority)
    }

    // ---- cleanup ------------------------------------------------------------------------------------

    @Test
    fun purge_removesOldStagedCopies_andKeepsFreshOnes() {
        val source = file(File(context.filesDir, "stage_src"), "p.pdf")
        val old = ShareStaging.stageFile(context, source)
        val fresh = ShareStaging.stageFile(context, source)
        val oldFile = File(ShareStaging.rootDir(context), old.pathSegments[old.pathSegments.size - 2]) // the random subdirectory
        assertTrue(oldFile.setLastModified(System.currentTimeMillis() - 48L * 60 * 60 * 1000))

        val removed = ShareStaging.purgeOlderThan(context, maxAgeMs = 24L * 60 * 60 * 1000)
        assertTrue(removed >= 1)
        assertFalse(oldFile.exists())
        assertTrue(context.contentResolver.openInputStream(fresh)!!.use { it.readBytes().isNotEmpty() })
    }
}
