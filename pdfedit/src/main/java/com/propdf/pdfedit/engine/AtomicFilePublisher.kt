package com.propdf.pdfedit.engine

import java.io.File
import java.io.IOException

/**
 * Publishes a finished temp file as the output without ever touching the original: the target must be a different
 * file from the source, the data is first copied next to the target and then renamed into place.
 */
object AtomicFilePublisher {

    fun publish(temp: File, target: File, source: File) {
        if (target.canonicalFile == source.canonicalFile) {
            throw EditRefusedException(EditRefusedException.Reason.SAME_AS_SOURCE, "The output must not replace the original file")
        }
        val staging = File(target.canonicalFile.parentFile, "." + target.name + ".part")
        try {
            temp.inputStream().use { input -> staging.outputStream().use { output -> input.copyTo(output) } }
            if (!staging.renameTo(target)) {
                // Some file systems refuse to rename over an existing file; the old output (never the source) may go.
                if (target.exists() && !target.delete()) throw IOException("Cannot replace " + target.name)
                if (!staging.renameTo(target)) throw IOException("Cannot move the finished file into place")
            }
        } finally {
            if (staging.exists()) staging.delete()
        }
    }
}
