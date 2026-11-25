package com.propdf.pdfedit.convert

import android.content.Context
import java.io.File

/** Copies chosen files (content URIs) into a work folder, because the converters need real files. */
object InputStager {

    /** Returns the copies in the same order as [inputs]. Throws [ConvertException] UNREADABLE when a file cannot be read. */
    fun stage(context: Context, inputs: List<ConvertInput>, dir: File): List<File> {
        dir.mkdirs()
        return inputs.mapIndexed { index, input ->
            val file = File(dir, safeName(index, input.displayName))
            try {
                val stream = context.contentResolver.openInputStream(input.uri)
                    ?: throw ConvertException(ConvertException.Reason.UNREADABLE, "Cannot open " + input.displayName)
                stream.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
            } catch (e: ConvertException) {
                throw e
            } catch (e: Exception) {
                throw ConvertException(ConvertException.Reason.UNREADABLE, "Cannot read " + input.displayName, e)
            }
            file
        }
    }

    /** "003_name.ext": sortable, ASCII-safe, at most 80 characters. */
    fun safeName(index: Int, displayName: String): String {
        val cleaned = displayName.substringAfterLast('/').map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-') it else '_' }
            .joinToString("").take(80)
        return index.toString().padStart(3, '0') + "_" + (if (cleaned.isBlank()) "file" else cleaned)
    }
}
