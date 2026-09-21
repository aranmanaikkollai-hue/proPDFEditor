package com.propdf.editor.ui.forms.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.editor.ui.forms.viewmodel.FormsHostViewModel
import com.propdfeditor.core.util.toSafeUserMessage
import java.io.File

/**
 * Forms: fill in, sign, and flatten AcroForm fields on an existing PDF.
 *
 * The actual form engine (PdfFormRepositoryImpl/PdfFormEngine in :editor, Room-backed
 * field storage, PdfFormViewModel, and a fully-built fill/edit/sign/save/flatten UI in
 * PdfFormViewer) already existed and was complete -- it just had no host screen and no
 * navigation entry anywhere, so it was completely unreachable. This screen supplies the
 * missing piece: rendering each page as a bitmap and giving PdfFormViewer a real place to
 * draw its field overlay on top of, plus turning its cache-dir save/flatten output into a
 * real file the user can keep (via SAF), instead of leaving it stranded in the cache.
 *
 * NOT VERIFIED end-to-end on a device/emulator (this environment has no Android
 * toolchain) -- the field-type save/reopen/flatten behavior itself comes from the
 * pre-existing PdfFormEngine, which was not re-audited here. Test against real AcroForm
 * PDFs (text field, checkbox, radio, dropdown, signature) before treating this as done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormsScreen(
    documentUri: String,
    onNavigateBack: () -> Unit,
    viewModel: FormsHostViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var boxWidthPx by remember { mutableStateOf(0f) }
    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    var formErrorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(documentUri)
    }

    uiState.error?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearError()
        }
    }

    formErrorMessage?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg)
            formErrorMessage = null
        }
    }

    var savedMessage by remember { mutableStateOf<String?>(null) }
    savedMessage?.let { msg ->
        LaunchedEffect(msg) {
            snackbarHostState.showSnackbar(msg)
            savedMessage = null
        }
    }

    // PdfFormViewer's own Save/Flatten buttons write to a cache-dir File (see
    // PdfFormViewer.onSaved) since it has no SAF access of its own. Once that lands,
    // immediately offer a real save location instead of leaving the result stuck
    // somewhere the user can't get back to.
    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { outputUri: Uri? ->
        val source = pendingSaveFile
        pendingSaveFile = null
        if (outputUri == null || source == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(outputUri)?.use { out ->
                source.inputStream().use { input -> input.copyTo(out) }
            }
            savedMessage = "Saved"
        } catch (e: Exception) {
            formErrorMessage = e.toSafeUserMessage("This document could not be saved.")
        }
    }

    val pageWidthPt = uiState.pageWidthPt
    val pageHeightPt = uiState.pageHeightPt
    val scaleFactor = if (pageWidthPt > 0f) boxWidthPx / pageWidthPt else 1f

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (uiState.pageCount > 0) "Forms — Page ${uiState.currentPage + 1} of ${uiState.pageCount}"
                        else "Forms"
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading -> {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val bitmap = uiState.pageBitmap
                        if (bitmap != null && pageWidthPt > 0f && pageHeightPt > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .aspectRatio(pageWidthPt / pageHeightPt)
                                    .onSizeChanged { boxWidthPx = it.width.toFloat() }
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "Page ${uiState.currentPage + 1}",
                                    modifier = Modifier.fillMaxSize()
                                )
                                if (boxWidthPx > 0f) {
                                    PdfFormViewer(
                                        documentUri = Uri.parse(documentUri),
                                        pageIndex = uiState.currentPage,
                                        pageWidth = pageWidthPt,
                                        pageHeight = pageHeightPt,
                                        scaleFactor = scaleFactor,
                                        modifier = Modifier.fillMaxSize(),
                                        onSaved = { output ->
                                            pendingSaveFile = output
                                            saveAsLauncher.launch(output.name)
                                        },
                                        onError = { message ->
                                            pendingSaveFile = null
                                            formErrorMessage = message
                                        }
                                    )
                                }
                            }
                        } else if (uiState.isRenderingPage) {
                            CircularProgressIndicator()
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { viewModel.previousPage() },
                            enabled = uiState.currentPage > 0 && !uiState.isRenderingPage
                        ) { Text("Previous") }

                        Text("Page ${uiState.currentPage + 1} of ${uiState.pageCount}")

                        TextButton(
                            onClick = { viewModel.nextPage() },
                            enabled = uiState.currentPage < uiState.pageCount - 1 && !uiState.isRenderingPage
                        ) { Text("Next") }
                    }
                }
            }
        }
    }
}
