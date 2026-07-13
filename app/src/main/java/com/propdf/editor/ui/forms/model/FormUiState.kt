package com.propdf.editor.ui.forms.model

import com.propdf.core.domain.model.PdfFormField

sealed class FormUiState {
    object Idle : FormUiState()
    object Loading : FormUiState()
    data class Success(val fields: List<PdfFormField>) : FormUiState()
    data class Error(val message: String) : FormUiState()
}

sealed class FormOperationState {
    object Idle : FormOperationState()
    object Saving : FormOperationState()
    object Success : FormOperationState()
    data class Error(val message: String) : FormOperationState()
}
