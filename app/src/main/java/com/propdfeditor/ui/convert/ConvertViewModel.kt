package com.propdfeditor.ui.convert

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.pdfedit.convert.ConvertException
import com.propdf.pdfedit.convert.ConvertInput
import com.propdf.pdfedit.convert.ConvertJob
import com.propdf.pdfedit.convert.ConvertMode
import com.propdf.pdfedit.convert.ConvertReport
import com.propdf.pdfedit.convert.ConvertRunner
import com.propdf.pdfedit.convert.ImageFormat
import com.propdf.pdfedit.convert.ImagesToPdfOptions
import com.propdf.pdfedit.convert.PageMode
import com.propdf.pdfedit.convert.PageSelection
import com.propdf.pdfedit.convert.PageSeparator
import com.propdf.pdfedit.convert.PdfToImagesOptions
import com.propdf.pdfedit.convert.PdfToTextOptions
import com.propdf.pdfedit.convert.TextToPdfOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

class PickedFile(val uri: Uri, val name: String)

sealed interface ConvertStatus {
    object Idle : ConvertStatus
    class Running(val done: Int, val total: Int) : ConvertStatus
    class Done(val files: List<String>, val report: ConvertReport) : ConvertStatus
    class Failed(val reason: ConvertException.Reason, val detail: String?) : ConvertStatus
}

data class ConvertUiState(
    val mode: ConvertMode = ConvertMode.PDF_TO_IMAGES,
    val files: List<PickedFile> = emptyList(),
    val folderUri: Uri? = null,
    val folderName: String? = null,
    val outputName: String = "",
    // options
    val imageFormat: ImageFormat = ImageFormat.PNG,
    val dpi: Int = 150,
    val pageSpec: String = "",
    val separator: PageSeparator = PageSeparator.FORM_FEED,
    val pageMode: PageMode = PageMode.A4,
    val marginPts: Double = 18.0,
    val fontSizePt: Int = 11,
    val status: ConvertStatus = ConvertStatus.Idle,
    val askPassword: Boolean = false,
    val passwordWasWrong: Boolean = false
)

@HiltViewModel
class ConvertViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val runner = ConvertRunner(context)
    private val prefs = context.getSharedPreferences("convert_prefs", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(ConvertUiState())
    val ui: StateFlow<ConvertUiState> = _ui.asStateFlow()

    private var job: Job? = null

    init {
        PDFBoxResourceLoader.init(context)
        restoreFolder()
    }

    val isRunning: Boolean get() = _ui.value.status is ConvertStatus.Running

    fun setMode(mode: ConvertMode) {
        if (isRunning || mode == _ui.value.mode) return
        _ui.update {
            it.copy(
                mode = mode, files = emptyList(), outputName = "", status = ConvertStatus.Idle,
                // text files and images look best with sensible page settings for the new mode
                pageMode = if (mode == ConvertMode.IMAGES_TO_PDF) PageMode.A4 else if (it.pageMode == PageMode.MATCH_IMAGE) PageMode.A4 else it.pageMode,
                marginPts = if (mode == ConvertMode.TEXT_TO_PDF) 54.0 else 18.0
            )
        }
    }

    fun addFiles(uris: List<Uri>) {
        if (uris.isEmpty() || isRunning) return
        val picked = uris.map { PickedFile(it, displayName(it)) }
        _ui.update {
            val files = if (it.mode == ConvertMode.IMAGES_TO_PDF) it.files + picked else picked.take(1)
            it.copy(files = files, status = ConvertStatus.Idle, outputName = defaultOutputName(it.mode, files, it.outputName))
        }
    }

    fun removeFile(index: Int) = _ui.update {
        val files = it.files.filterIndexed { i, _ -> i != index }
        it.copy(files = files, status = ConvertStatus.Idle)
    }

    fun moveFile(index: Int, delta: Int) = _ui.update {
        val target = index + delta
        if (index !in it.files.indices || target !in it.files.indices) return@update it
        val list = it.files.toMutableList()
        list.add(target, list.removeAt(index))
        it.copy(files = list)
    }

    fun setFolder(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            // not every provider offers a persistable grant; the folder still works for this session
        }
        prefs.edit().putString("folder", uri.toString()).apply()
        _ui.update { it.copy(folderUri = uri, folderName = DocumentFile.fromTreeUri(context, uri)?.name, status = ConvertStatus.Idle) }
    }

    fun setImageFormat(v: ImageFormat) = _ui.update { it.copy(imageFormat = v) }
    fun setDpi(v: Int) = _ui.update { it.copy(dpi = v) }
    fun setPageSpec(v: String) = _ui.update { it.copy(pageSpec = v.take(200)) }
    fun setSeparator(v: PageSeparator) = _ui.update { it.copy(separator = v) }
    fun setPageMode(v: PageMode) = _ui.update { it.copy(pageMode = v) }
    fun setMargin(v: Double) = _ui.update { it.copy(marginPts = v) }
    fun setFontSize(v: Int) = _ui.update { it.copy(fontSizePt = v) }
    fun setOutputName(v: String) = _ui.update { it.copy(outputName = v.take(80)) }

    fun canStart(): Boolean {
        val s = _ui.value
        return s.files.isNotEmpty() && s.folderUri != null && s.status !is ConvertStatus.Running
    }

    fun start(password: String? = null) {
        val s = _ui.value
        val folder = s.folderUri?.let { DocumentFile.fromTreeUri(context, it) }
        if (s.files.isEmpty() || folder == null || !folder.canWrite() || isRunning) {
            _ui.update { it.copy(status = ConvertStatus.Failed(ConvertException.Reason.WRITE_FAILED, null)) }
            return
        }
        val selection = if (s.pageSpec.isBlank()) PageSelection.All else PageSelection.Ranges(s.pageSpec)
        val request = ConvertJob(
            mode = s.mode,
            inputs = s.files.map { ConvertInput(it.uri, it.name) },
            outputRoot = folder,
            outputName = s.outputName.ifBlank { defaultOutputName(s.mode, s.files, "") },
            password = password,
            pdfToImages = PdfToImagesOptions(format = s.imageFormat, dpi = s.dpi, pages = selection),
            pdfToText = PdfToTextOptions(pages = selection, separator = s.separator),
            imagesToPdf = ImagesToPdfOptions(pageMode = s.pageMode, marginPts = s.marginPts),
            textToPdf = TextToPdfOptions(
                pageMode = if (s.pageMode == PageMode.LETTER) PageMode.LETTER else PageMode.A4,
                marginPts = s.marginPts, fontSizePt = s.fontSizePt.toDouble()
            )
        )
        _ui.update { it.copy(status = ConvertStatus.Running(0, 0), askPassword = false) }
        this.job = viewModelScope.launch {
            try {
                val result = runner.run(request) { done, total -> _ui.update { u -> u.copy(status = ConvertStatus.Running(done, total)) } }
                _ui.update { it.copy(status = ConvertStatus.Done(result.outputNames, result.report)) }
            } catch (e: CancellationException) {
                _ui.update { it.copy(status = ConvertStatus.Idle) }
                throw e
            } catch (e: ConvertException) {
                _ui.update {
                    when (e.reason) {
                        ConvertException.Reason.PASSWORD_REQUIRED -> it.copy(status = ConvertStatus.Idle, askPassword = true, passwordWasWrong = false)
                        ConvertException.Reason.WRONG_PASSWORD -> it.copy(status = ConvertStatus.Idle, askPassword = true, passwordWasWrong = true)
                        else -> it.copy(status = ConvertStatus.Failed(e.reason, e.message))
                    }
                }
            } catch (e: Exception) {
                _ui.update { it.copy(status = ConvertStatus.Failed(ConvertException.Reason.WRITE_FAILED, null)) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismissPassword() = _ui.update { it.copy(askPassword = false, passwordWasWrong = false) }

    fun dismissResult() = _ui.update { it.copy(status = ConvertStatus.Idle) }

    /** Clears the chosen files and the result so another conversion can be set up; the folder and options stay. */
    fun convertAnother() = _ui.update { it.copy(files = emptyList(), outputName = "", status = ConvertStatus.Idle) }

    private fun restoreFolder() {
        val saved = prefs.getString("folder", null) ?: return
        val uri = Uri.parse(saved)
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        val doc = DocumentFile.fromTreeUri(context, uri)
        if (granted && doc != null && doc.canWrite()) {
            _ui.update { it.copy(folderUri = uri, folderName = doc.name) }
        }
    }

    private fun displayName(uri: Uri): String =
        DocumentFile.fromSingleUri(context, uri)?.name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file"

    private fun defaultOutputName(mode: ConvertMode, files: List<PickedFile>, current: String): String {
        if (current.isNotBlank()) return current
        return when (mode) {
            ConvertMode.IMAGES_TO_PDF -> "images"
            else -> files.firstOrNull()?.name?.substringBeforeLast('.', files.first().name) ?: ""
        }
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }
}
