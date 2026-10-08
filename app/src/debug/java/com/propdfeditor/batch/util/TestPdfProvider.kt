package com.propdfeditor.batch.util

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * DEBUG-BUILD ONLY. Serves files from the app's cacheDir/testpdfs as content://com.propdfeditor.test.pdfs/<name>
 * so instrumented tests can exercise real content:// URIs.
 *
 * It lives in the app's debug source set (not the androidTest APK) on purpose: a provider declared in the test APK runs in
 * the test package's process, which has no Kotlin runtime and a different cache directory than the app under test.
 * Not exported: the tests run inside the app process.
 */
class TestPdfProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val dir = File(context!!.cacheDir, "testpdfs").apply { mkdirs() }
        val name = requireNotNull(uri.lastPathSegment)
        require(!name.contains('/') && !name.contains("..")) { "bad name" }
        return ParcelFileDescriptor.open(File(dir, name), ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/pdf"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<String>?) = 0
}
