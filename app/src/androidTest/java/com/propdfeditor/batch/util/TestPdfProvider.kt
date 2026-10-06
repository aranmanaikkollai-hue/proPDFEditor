package com.propdfeditor.batch.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/** Serves files from cacheDir/testpdfs as content://com.propdfeditor.test.pdfs/<name>. */
class TestPdfProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val dir = servedDir()
        val file = File(dir, requireNotNull(uri.lastPathSegment))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    /**
     * Tests write fixtures to the TARGET app's cacheDir/testpdfs. This provider is declared in the androidTest
     * manifest, so its own `context` may belong to the test package; prefer the instrumentation's target
     * context (same process) and only fall back to the provider's context if no instrumentation is registered.
     */
    private fun servedDir(): File {
        val base = try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        } catch (e: Throwable) {
            context!!.cacheDir
        }
        return File(base, "testpdfs").apply { mkdirs() }
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/pdf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
