package com.propdf.pdfedit.convert

import android.content.Context
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import java.io.IOException
import java.io.OutputStream

/**
 * Writes converted files into a folder chosen with the Storage Access Framework (or any DocumentFile folder).
 * A file that already exists is never overwritten or touched: the new file gets "(1)", "(2)", ... in its name.
 * The folder is listed once; names created here are tracked, so a 300-page conversion does not list it 300 times.
 */
class DocumentTreeSink(private val context: Context, private val root: DocumentFile) : OutputSink {
    private val taken: MutableSet<String> by lazy {
        root.listFiles().mapNotNull { it.name }.toMutableSet()
    }
    private val created = LinkedHashMap<String, DocumentFile>()

    /** Names of the files created so far, as they appear in the folder. */
    val createdNames: List<String> get() = created.values.mapNotNull { it.name }

    override fun open(name: String, mimeType: String): OutputStream {
        // Some providers add the extension of the MIME type to the name, so "x.png" is also taken when "x.png.png" exists.
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
        val unique = UniqueName.pick(name) { n -> n in taken || (ext != null && (n + "." + ext) in taken) }
        val doc = root.createFile(mimeType, unique) ?: throw IOException("Cannot create " + unique)
        val actual = doc.name ?: unique
        taken.add(actual)
        created[name] = doc
        val stream = try {
            context.contentResolver.openOutputStream(doc.uri, "w")
        } catch (e: Exception) {
            null
        }
        if (stream == null) {
            doc.delete()
            created.remove(name)
            taken.remove(actual)
            throw IOException("Cannot write " + unique)
        }
        return stream
    }

    override fun delete(name: String) {
        val doc = created.remove(name) ?: return
        doc.name?.let { taken.remove(it) }
        doc.delete()
    }
}
