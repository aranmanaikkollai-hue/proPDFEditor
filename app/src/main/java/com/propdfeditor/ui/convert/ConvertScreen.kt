package com.propdfeditor.ui.convert

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.pdfedit.convert.ConvertException
import com.propdf.pdfedit.convert.ConvertMode
import com.propdf.pdfedit.convert.ConvertWarning
import com.propdf.pdfedit.convert.ImageFormat
import com.propdf.pdfedit.convert.PageMode
import com.propdf.pdfedit.convert.PageSeparator
import com.propdfeditor.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onNavigateBack: () -> Unit,
    viewModel: ConvertViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val running = ui.status is ConvertStatus.Running

    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.addFiles(listOf(it)) }
    }
    val imagesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        viewModel.addFiles(uris)
    }
    val textPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { viewModel.addFiles(listOf(it)) }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { viewModel.setFolder(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.convert_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.convert_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- what to convert -------------------------------------------------------------------------
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in ConvertMode.values()) {
                    FilterChip(
                        selected = ui.mode == mode,
                        enabled = !running,
                        onClick = { viewModel.setMode(mode) },
                        label = { Text(stringResource(modeLabel(mode))) }
                    )
                }
            }

            Button(
                enabled = !running,
                onClick = {
                    when (ui.mode) {
                        ConvertMode.PDF_TO_IMAGES, ConvertMode.PDF_TO_TEXT -> pdfPicker.launch(arrayOf("application/pdf"))
                        ConvertMode.IMAGES_TO_PDF -> imagesPicker.launch(arrayOf("image/*"))
                        ConvertMode.TEXT_TO_PDF -> textPicker.launch(arrayOf("text/*"))
                    }
                }
            ) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(chooseLabel(ui.mode)))
            }

            if (ui.files.isEmpty()) {
                Text(stringResource(R.string.convert_no_file), style = MaterialTheme.typography.bodyMedium)
            } else {
                if (ui.mode == ConvertMode.IMAGES_TO_PDF) {
                    Text(stringResource(R.string.convert_images_order_hint), style = MaterialTheme.typography.bodySmall)
                }
                ui.files.forEachIndexed { index, file ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            text = (index + 1).toString() + ". " + file.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (ui.mode == ConvertMode.IMAGES_TO_PDF) {
                            IconButton(enabled = !running && index > 0, onClick = { viewModel.moveFile(index, -1) }) {
                                Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.convert_move_up, file.name))
                            }
                            IconButton(enabled = !running && index < ui.files.size - 1, onClick = { viewModel.moveFile(index, 1) }) {
                                Icon(Icons.Filled.ArrowDownward, contentDescription = stringResource(R.string.convert_move_down, file.name))
                            }
                        }
                        IconButton(enabled = !running, onClick = { viewModel.removeFile(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.convert_remove, file.name))
                        }
                    }
                }
            }

            // ---- options ---------------------------------------------------------------------------------
            Text(stringResource(R.string.convert_options), style = MaterialTheme.typography.titleMedium)
            when (ui.mode) {
                ConvertMode.PDF_TO_IMAGES -> {
                    ChipGroup(stringResource(R.string.convert_format)) {
                        FilterChip(ui.imageFormat == ImageFormat.PNG, { viewModel.setImageFormat(ImageFormat.PNG) }, { Text(stringResource(R.string.convert_format_png)) }, enabled = !running)
                        FilterChip(ui.imageFormat == ImageFormat.JPEG, { viewModel.setImageFormat(ImageFormat.JPEG) }, { Text(stringResource(R.string.convert_format_jpeg)) }, enabled = !running)
                    }
                    ChipGroup(stringResource(R.string.convert_resolution)) {
                        for (dpi in listOf(72, 150, 300)) {
                            FilterChip(ui.dpi == dpi, { viewModel.setDpi(dpi) }, { Text(stringResource(R.string.convert_dpi_value, dpi)) }, enabled = !running)
                        }
                    }
                    PageField(ui.pageSpec, !running, viewModel::setPageSpec)
                }
                ConvertMode.PDF_TO_TEXT -> {
                    ChipGroup(stringResource(R.string.convert_separator)) {
                        FilterChip(ui.separator == PageSeparator.FORM_FEED, { viewModel.setSeparator(PageSeparator.FORM_FEED) }, { Text(stringResource(R.string.convert_separator_form_feed)) }, enabled = !running)
                        FilterChip(ui.separator == PageSeparator.HEADER_LINE, { viewModel.setSeparator(PageSeparator.HEADER_LINE) }, { Text(stringResource(R.string.convert_separator_header)) }, enabled = !running)
                        FilterChip(ui.separator == PageSeparator.NONE, { viewModel.setSeparator(PageSeparator.NONE) }, { Text(stringResource(R.string.convert_separator_none)) }, enabled = !running)
                    }
                    PageField(ui.pageSpec, !running, viewModel::setPageSpec)
                }
                ConvertMode.IMAGES_TO_PDF -> {
                    ChipGroup(stringResource(R.string.convert_page_size)) {
                        FilterChip(ui.pageMode == PageMode.A4, { viewModel.setPageMode(PageMode.A4) }, { Text(stringResource(R.string.convert_page_a4)) }, enabled = !running)
                        FilterChip(ui.pageMode == PageMode.LETTER, { viewModel.setPageMode(PageMode.LETTER) }, { Text(stringResource(R.string.convert_page_letter)) }, enabled = !running)
                        FilterChip(ui.pageMode == PageMode.MATCH_IMAGE, { viewModel.setPageMode(PageMode.MATCH_IMAGE) }, { Text(stringResource(R.string.convert_page_match)) }, enabled = !running)
                    }
                    if (ui.pageMode != PageMode.MATCH_IMAGE) {
                        ChipGroup(stringResource(R.string.convert_margin)) {
                            FilterChip(ui.marginPts == 0.0, { viewModel.setMargin(0.0) }, { Text(stringResource(R.string.convert_margin_none)) }, enabled = !running)
                            FilterChip(ui.marginPts == 18.0, { viewModel.setMargin(18.0) }, { Text(stringResource(R.string.convert_margin_small)) }, enabled = !running)
                            FilterChip(ui.marginPts == 36.0, { viewModel.setMargin(36.0) }, { Text(stringResource(R.string.convert_margin_large)) }, enabled = !running)
                        }
                    }
                }
                ConvertMode.TEXT_TO_PDF -> {
                    ChipGroup(stringResource(R.string.convert_page_size)) {
                        FilterChip(ui.pageMode != PageMode.LETTER, { viewModel.setPageMode(PageMode.A4) }, { Text(stringResource(R.string.convert_page_a4)) }, enabled = !running)
                        FilterChip(ui.pageMode == PageMode.LETTER, { viewModel.setPageMode(PageMode.LETTER) }, { Text(stringResource(R.string.convert_page_letter)) }, enabled = !running)
                    }
                    ChipGroup(stringResource(R.string.convert_font_size)) {
                        for (size in listOf(10, 11, 12, 14)) {
                            FilterChip(ui.fontSizePt == size, { viewModel.setFontSize(size) }, { Text(stringResource(R.string.convert_font_size_value, size)) }, enabled = !running)
                        }
                    }
                }
            }

            // ---- where to save ---------------------------------------------------------------------------
            Text(stringResource(R.string.convert_output), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(enabled = !running, onClick = { folderPicker.launch(null) }) {
                Icon(Icons.Filled.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (ui.folderUri == null) R.string.convert_choose_folder else R.string.convert_change_folder))
            }
            Text(ui.folderName ?: stringResource(R.string.convert_no_folder), style = MaterialTheme.typography.bodyMedium)
            if (ui.mode == ConvertMode.IMAGES_TO_PDF || ui.mode == ConvertMode.TEXT_TO_PDF) {
                OutlinedTextField(
                    value = ui.outputName,
                    onValueChange = viewModel::setOutputName,
                    enabled = !running,
                    singleLine = true,
                    label = { Text(stringResource(R.string.convert_output_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Text(stringResource(R.string.convert_never_overwrite), style = MaterialTheme.typography.bodySmall)

            // ---- run -------------------------------------------------------------------------------------
            when (val status = ui.status) {
                is ConvertStatus.Running -> {
                    if (status.total > 0) {
                        LinearProgressIndicator(progress = status.done.toFloat() / status.total, modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.convert_progress, status.done, status.total))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.convert_working))
                    }
                    OutlinedButton(onClick = { viewModel.cancel() }) { Text(stringResource(R.string.convert_cancel)) }
                }
                else -> Button(
                    enabled = ui.files.isNotEmpty() && ui.folderUri != null,
                    onClick = { viewModel.start() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.convert_start)) }
            }

            when (val status = ui.status) {
                is ConvertStatus.Done -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.convert_done_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.convert_done_files, status.files.size))
                        val shown = status.files.take(12)
                        for (name in shown) Text(name, style = MaterialTheme.typography.bodySmall)
                        if (status.files.size > shown.size) Text("...", style = MaterialTheme.typography.bodySmall)
                        for (warning in status.report.warnings) Text(stringResource(warningText(warning)), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { viewModel.convertAnother() }) { Text(stringResource(R.string.convert_again)) }
                    }
                }
                is ConvertStatus.Failed -> Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(errorText(status.reason)), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { viewModel.dismissResult() }) { Text(stringResource(R.string.convert_password_ok)) }
                    }
                }
                else -> {}
            }
        }
    }

    if (ui.askPassword) {
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { viewModel.dismissPassword() },
            title = { Text(stringResource(R.string.convert_password_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (ui.passwordWasWrong) R.string.convert_error_wrong_password else R.string.convert_error_password_required))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.convert_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty(), onClick = { viewModel.start(password) }) {
                    Text(stringResource(R.string.convert_password_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissPassword() }) { Text(stringResource(R.string.convert_password_cancel)) }
            }
        )
    }
}

@Composable
private fun ChipGroup(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun PageField(value: String, enabled: Boolean, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.convert_pages_label)) },
        modifier = Modifier.fillMaxWidth()
    )
}

private fun modeLabel(mode: ConvertMode): Int = when (mode) {
    ConvertMode.PDF_TO_IMAGES -> R.string.convert_mode_pdf_to_images
    ConvertMode.PDF_TO_TEXT -> R.string.convert_mode_pdf_to_text
    ConvertMode.IMAGES_TO_PDF -> R.string.convert_mode_images_to_pdf
    ConvertMode.TEXT_TO_PDF -> R.string.convert_mode_text_to_pdf
}

private fun chooseLabel(mode: ConvertMode): Int = when (mode) {
    ConvertMode.PDF_TO_IMAGES, ConvertMode.PDF_TO_TEXT -> R.string.convert_choose_pdf
    ConvertMode.IMAGES_TO_PDF -> R.string.convert_choose_images
    ConvertMode.TEXT_TO_PDF -> R.string.convert_choose_text
}

private fun warningText(w: ConvertWarning): Int = when (w) {
    ConvertWarning.RESOLUTION_REDUCED -> R.string.convert_warning_reduced
    ConvertWarning.NO_TEXT_LAYER -> R.string.convert_warning_no_text
    ConvertWarning.SOME_PAGES_WITHOUT_TEXT -> R.string.convert_warning_some_no_text
    ConvertWarning.ENCODING_GUESSED -> R.string.convert_warning_encoding
    ConvertWarning.CHARACTERS_REPLACED -> R.string.convert_warning_chars
    ConvertWarning.IMAGE_DOWNSAMPLED -> R.string.convert_warning_downsampled
    ConvertWarning.FONT_UNAVAILABLE -> R.string.convert_warning_font
}

private fun errorText(reason: ConvertException.Reason): Int = when (reason) {
    ConvertException.Reason.PASSWORD_REQUIRED -> R.string.convert_error_password_required
    ConvertException.Reason.WRONG_PASSWORD -> R.string.convert_error_wrong_password
    ConvertException.Reason.NOT_PERMITTED -> R.string.convert_error_not_permitted
    ConvertException.Reason.UNREADABLE -> R.string.convert_error_unreadable
    ConvertException.Reason.INVALID_RANGE -> R.string.convert_error_invalid_range
    ConvertException.Reason.EMPTY_SELECTION -> R.string.convert_error_empty
    ConvertException.Reason.OUT_OF_MEMORY -> R.string.convert_error_memory
    ConvertException.Reason.WRITE_FAILED -> R.string.convert_error_write
}
