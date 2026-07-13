package com.propdf.editor.ui.forms.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField
import com.propdf.core.domain.repository.PdfFormRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.ui.forms.model.FormOperationState
import com.propdf.editor.ui.forms.model.FormUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class PdfFormViewModel @Inject constructor(
    application: Application,
    private val formRepository: PdfFormRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<FormUiState>(FormUiState.Idle)
    val uiState: StateFlow<FormUiState> = _uiState.asStateFlow()

    private val _operationState = MutableStateFlow<FormOperationState>(FormOperationState.Idle)
    val operationState: StateFlow<FormOperationState> = _operationState.asStateFlow()

    private val _fieldValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val fieldValues: StateFlow<Map<String, String>> = _fieldValues.asStateFlow()

    private val _isFormMode = MutableStateFlow(false)
    val isFormMode: StateFlow<Boolean> = _isFormMode.asStateFlow()

    private var currentDocumentUri: Uri? = null

    fun loadDocument(documentUri: Uri) {
        currentDocumentUri = documentUri
        viewModelScope.launch {
            _uiState.value = FormUiState.Loading
            try {
                // Load fields from database
                formRepository.getFields(documentUri.toString()).collect { fields ->
                    _uiState.value = FormUiState.Success(fields)
                }

                // Also extract native PDF form fields if none in DB
                when (val extracted = formRepository.extractFormFields(documentUri)) {
                    is AppResult.Success -> {
                        if (extracted.data.isNotEmpty()) {
                            // Merge with DB fields
                            extracted.data.forEach { field ->
                                formRepository.addField(field)
                            }
                        }
                    }
                    else -> { /* ignore extraction errors */ }
                }

                // Load current values
                when (val values = formRepository.getFormFieldValues(documentUri)) {
                    is AppResult.Success -> _fieldValues.value = values.data
                    else -> { }
                }
            } catch (e: Exception) {
                _uiState.value = FormUiState.Error(e.message ?: "Failed to load form fields")
            }
        }
    }

    fun toggleFormMode() {
        _isFormMode.value = !_isFormMode.value
    }

    fun setFormMode(enabled: Boolean) {
        _isFormMode.value = enabled
    }

    fun addField(field: PdfFormField) {
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            when (val result = formRepository.addField(field)) {
                is AppResult.Success -> {
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to add field")
                }
                else -> { }
            }
        }
    }

    fun updateField(field: PdfFormField) {
        viewModelScope.launch {
            when (val result = formRepository.updateField(field)) {
                is AppResult.Success -> {
                    // Refresh
                    currentDocumentUri?.let { loadDocument(it) }
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to update field")
                }
                else -> { }
            }
        }
    }

    fun deleteField(fieldId: Long) {
        viewModelScope.launch {
            when (val result = formRepository.deleteField(fieldId)) {
                is AppResult.Success -> {
                    currentDocumentUri?.let { loadDocument(it) }
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to delete field")
                }
                else -> { }
            }
        }
    }

    fun updateFieldValue(fieldName: String, value: String) {
        val current = _fieldValues.value.toMutableMap()
        current[fieldName] = value
        _fieldValues.value = current
    }

    fun fillForm() {
        val uri = currentDocumentUri ?: return
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            val values = _fieldValues.value
            when (val result = formRepository.fillForm(uri, values)) {
                is AppResult.Success -> {
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to fill form")
                }
                else -> { }
            }
        }
    }

    fun saveForm(outputFile: File) {
        val uri = currentDocumentUri ?: return
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            when (val result = formRepository.saveForm(uri, outputFile)) {
                is AppResult.Success -> {
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to save form")
                }
                else -> { }
            }
        }
    }

    fun flattenForm(outputFile: File) {
        val uri = currentDocumentUri ?: return
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            when (val result = formRepository.flattenForm(uri, outputFile)) {
                is AppResult.Success -> {
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to flatten form")
                }
                else -> { }
            }
        }
    }

    fun exportXFDF(): Flow<String?> = flow {
        val uri = currentDocumentUri ?: run { emit(null); return@flow }
        when (val result = formRepository.exportXFDF(uri)) {
            is AppResult.Success -> emit(result.data)
            else -> emit(null)
        }
    }

    fun importXFDF(xfdfData: String) {
        val uri = currentDocumentUri ?: return
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            when (val result = formRepository.importXFDF(uri, xfdfData)) {
                is AppResult.Success -> {
                    currentDocumentUri?.let { loadDocument(it) }
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to import XFDF")
                }
                else -> { }
            }
        }
    }

    fun addSignature(fieldName: String, bitmap: Bitmap, outputFile: File) {
        val uri = currentDocumentUri ?: return
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            when (val result = formRepository.addSignature(uri, fieldName, bitmap, outputFile)) {
                is AppResult.Success -> {
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to add signature")
                }
                else -> { }
            }
        }
    }

    fun addImageToField(fieldName: String, bitmap: Bitmap, outputFile: File) {
        val uri = currentDocumentUri ?: return
        viewModelScope.launch {
            _operationState.value = FormOperationState.Saving
            when (val result = formRepository.addImageToField(uri, fieldName, bitmap, outputFile)) {
                is AppResult.Success -> {
                    _operationState.value = FormOperationState.Success
                }
                is AppResult.Error -> {
                    _operationState.value = FormOperationState.Error(result.message ?: "Failed to add image")
                }
                else -> { }
            }
        }
    }

    fun clearOperationState() {
        _operationState.value = FormOperationState.Idle
    }
}
