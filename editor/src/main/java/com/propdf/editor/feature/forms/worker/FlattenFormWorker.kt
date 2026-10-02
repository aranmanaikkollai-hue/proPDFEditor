package com.propdf.editor.feature.forms.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.feature.forms.engine.PdfFormEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

@HiltWorker
class FlattenFormWorker @AssistedInject constructor(
    @Assisted context: Context, @Assisted params: WorkerParameters, private val engine: PdfFormEngine
) : CoroutineWorker(context, params) {
    companion object { const val KEY_INPUT_URI = "input_uri"; const val KEY_OUTPUT_PATH = "output_path"; const val KEY_RESULT_URI = "result_uri"; const val KEY_ERROR = "error_message" }

    override suspend fun doWork(): Result {
        val input = inputData.getString(KEY_INPUT_URI)?.let(Uri::parse) ?: return failure("Missing input URI")
        val destination = inputData.getString(KEY_OUTPUT_PATH)?.let(Uri::parse) ?: return failure("Missing output path")
        val directory = File(applicationContext.cacheDir, "form_operations").apply { mkdirs() }
        val stagedInput = File(directory, "flatten_input_${id}.pdf")
        val stagedOutput = File(directory, "flatten_output_${id}.pdf")
        try {
            stageInput(input, stagedInput)
            coroutineContext.ensureActive()
            when (val result = engine.flattenForm(stagedInput, stagedOutput)) {
                is AppResult.Success -> {
                    coroutineContext.ensureActive()
                    publish(stagedOutput, destination)
                    Result.success(workDataOf(KEY_RESULT_URI to destination.toString()))
                }
                is AppResult.Error -> failure(result.message ?: "Flatten failed")
                is AppResult.Loading -> failure("Unexpected flatten result")
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) { failure(error.message ?: "Flatten failed")
        } finally { stagedInput.delete(); stagedOutput.delete() }
    }

    private fun stageInput(uri: Uri, target: File) {
        when (uri.scheme) {
            "file" -> requireNotNull(uri.path) { "File URI has no path" }.let(::File).inputStream()
            "content" -> applicationContext.contentResolver.openInputStream(uri) ?: error("Cannot open input URI")
            else -> error("Unsupported input URI scheme: ${uri.scheme}")
        }.use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun publish(source: File, destination: Uri) {
        if (destination.scheme == "content") {
            (applicationContext.contentResolver.openOutputStream(destination, "w") ?: error("Cannot open output URI"))
                .use { output -> source.inputStream().use { it.copyTo(output) } }
            return
        }
        require(destination.scheme == "file" || destination.scheme == null) { "Unsupported output URI scheme: ${destination.scheme}" }
        val destinationFile = if (destination.scheme == "file") requireNotNull(destination.path) { "File URI has no path" }.let(::File) else File(destination.toString())
        destinationFile.parentFile?.mkdirs()
        val partial = File(destinationFile.parentFile ?: destinationFile.absoluteFile.parentFile, ".${destinationFile.name}.partial")
        partial.delete()
        try { source.copyTo(partial, overwrite = true); if (!partial.renameTo(destinationFile)) partial.copyTo(destinationFile, overwrite = true) } finally { partial.delete() }
    }
    private fun failure(message: String) = Result.failure(workDataOf(KEY_ERROR to message))
}
