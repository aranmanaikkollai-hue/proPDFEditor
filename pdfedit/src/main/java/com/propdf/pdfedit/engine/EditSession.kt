package com.propdf.pdfedit.engine

import com.propdf.pdfedit.ops.EditLog
import com.propdf.pdfedit.ops.EditOperation
import java.io.File
import java.util.UUID

/**
 * One open document being edited: the untouched [baseFile] plus an [EditLog]. Nothing is written until [save];
 * undo and redo are cursor moves on the log.
 */
class EditSession(private val engine: PdfEditEngine, val baseFile: File) {
    val log = EditLog()

    fun apply(op: EditOperation) = log.apply(op)
    fun undo() = log.undo()
    fun redo() = log.redo()

    /** Replays the active operations into [out] (for a preview or a test) and verifies the result. */
    fun materialize(out: File): EditReport = engine.materialize(baseFile, log.active, out)

    /**
     * Replays the active operations into a temp file in [workDir], verifies it, then publishes it as [target].
     * [target] must not be the base file. The temp file is always removed.
     */
    fun save(target: File, workDir: File): EditReport {
        workDir.mkdirs()
        val temp = File(workDir, "edit-" + UUID.randomUUID().toString() + ".pdf")
        try {
            val report = engine.materialize(baseFile, log.active, temp)
            AtomicFilePublisher.publish(temp, target, baseFile)
            return report
        } finally {
            if (temp.exists()) temp.delete()
        }
    }
}
