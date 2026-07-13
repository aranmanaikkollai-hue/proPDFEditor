package com.propdf.editor.ui.forms.screen

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField
import com.propdf.editor.ui.forms.dialog.FormFieldEditDialog
import com.propdf.editor.ui.forms.dialog.SignatureCaptureDialog
import com.propdf.editor.ui.forms.model.FormOperationState
import com.propdf.editor.ui.forms.model.FormUiState
import com.propdf.editor.ui.forms.view.FormFieldOverlayView
import com.propdf.editor.ui.forms.viewmodel.PdfFormViewModel
import java.io.File

@Composable
fun PdfFormViewer(
    documentUri: Uri,
    pageIndex: Int,
    pageWidth: Float,
    pageHeight: Float,
    scaleFactor: Float,
    modifier: Modifier = Modifier,
    viewModel: PdfFormViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val fieldValues by viewModel.fieldValues.collectAsState()
    val isFormMode by viewModel.isFormMode.collectAsState()
    val operationState by viewModel.operationState.collectAsState()

    var showFieldSheet by remember { mutableStateOf<PdfFormField?>(null) }
    var showEditDialog by remember { mutableStateOf<PdfFormField?>(null) }
    var showSignatureDialog by remember { mutableStateOf(false) }
    var showAddFieldDialog by remember { mutableStateOf<FormFieldType?>(null) }
    var pendingSignatureField by remember { mutableStateOf<PdfFormField?>(null) }

    LaunchedEffect(documentUri) {
        viewModel.loadDocument(documentUri)
    }

    LaunchedEffect(operationState) {
        when (operationState) {
            is FormOperationState.Error -> {
                viewModel.clearOperationState()
            }
            is FormOperationState.Success -> {
                viewModel.clearOperationState()
            }
            else -> {}
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                FormFieldOverlayView(ctx).apply {
                    setPageDimensions(pageWidth, pageHeight)
                    setScaleFactor(scaleFactor)

                    onFieldTap = { field ->
                        if (isFormMode) {
                            showEditDialog = field
                        } else {
                            showFieldSheet = field
                        }
                    }

                    onFieldLongPress = { field ->
                        if (isFormMode) {
                            showEditDialog = field
                        }
                    }
                }
            },
            update = { view ->
                view.setScaleFactor(scaleFactor)
                view.isEditMode = isFormMode

                val pageFields = when (val state = uiState) {
                    is FormUiState.Success -> state.fields.filter { it.pageIndex == pageIndex }
                    else -> emptyList()
                }
                view.setFields(pageFields)
                view.setFieldValues(fieldValues)
            },
            modifier = Modifier.fillMaxSize()
        )

        FormToolbar(
            isFormMode = isFormMode,
            onToggleFormMode = { viewModel.toggleFormMode() },
            onAddField = { type ->
                showAddFieldDialog = type
            },
            onFillForm = { viewModel.fillForm() },
            onSaveForm = {
                val output = File(context.cacheDir, "saved_form_${System.currentTimeMillis()}.pdf")
                viewModel.saveForm(output)
            },
            onFlattenForm = {
                val output = File(context.cacheDir, "flattened_${System.currentTimeMillis()}.pdf")
                viewModel.flattenForm(output)
            },
            onExportXFDF = {},
            onImportXFDF = {},
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    showFieldSheet?.let { field ->
        FormFieldInputSheet(
            field = field,
            currentValue = fieldValues[field.fieldName] ?: field.value,
            onValueChange = { value ->
                viewModel.updateFieldValue(field.fieldName, value)
            },
            onSignatureRequest = {
                pendingSignatureField = field
                showSignatureDialog = true
                showFieldSheet = null
            },
            onImageRequest = {
                showFieldSheet = null
            },
            onDismiss = { showFieldSheet = null }
        )
    }

    showEditDialog?.let { field ->
        FormFieldEditDialog(
            field = field,
            onDismiss = { showEditDialog = null },
            onSave = { updated ->
                viewModel.updateField(updated)
                showEditDialog = null
            },
            onDelete = { fieldId ->
                viewModel.deleteField(fieldId)
            }
        )
    }

    showAddFieldDialog?.let { type ->
        FormFieldEditDialog(
            field = null,
            onDismiss = { showAddFieldDialog = null },
            onSave = { field ->
                val newField = field.copy(
                    documentUri = documentUri.toString(),
                    pageIndex = pageIndex,
                    fieldType = type
                )
                viewModel.addField(newField)
                showAddFieldDialog = null
            }
        )
    }

    if (showSignatureDialog) {
        SignatureCaptureDialog(
            onDismiss = { showSignatureDialog = false },
            onConfirm = { bitmap ->
                pendingSignatureField?.let { field ->
                    val output = File(context.cacheDir, "signed_${System.currentTimeMillis()}.pdf")
                    viewModel.addSignature(field.fieldName, bitmap, output)
                }
                showSignatureDialog = false
                pendingSignatureField = null
            }
        )
    }
}
