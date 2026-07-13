package com.propdf.core.domain.repository

import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.domain.model.PdfFormField
import com.propdf.core.domain.result.AppResult
import kotlinx.coroutines.flow.Flow
import java.io.File

interface PdfFormRepository {

    suspend fun getFields(documentUri: String): Flow<List<PdfFormField>>
    suspend fun getFieldsForPage(documentUri: String, pageIndex: Int): List<PdfFormField>
    suspend fun addField(field: PdfFormField): AppResult<Long>
    suspend fun updateField(field: PdfFormField): AppResult<Unit>
    suspend fun deleteField(fieldId: Long): AppResult<Unit>
    suspend fun deleteAllFields(documentUri: String): AppResult<Unit>

    suspend fun fillForm(documentUri: Uri, fields: Map<String, String>): AppResult<File>
    suspend fun saveForm(documentUri: Uri, outputFile: File): AppResult<File>
    suspend fun flattenForm(documentUri: Uri, outputFile: File): AppResult<File>

    suspend fun exportXFDF(documentUri: Uri): AppResult<String>
    suspend fun importXFDF(documentUri: Uri, xfdfData: String): AppResult<Unit>
    suspend fun importXFDF(documentUri: Uri, xfdfFile: File): AppResult<Unit>

    suspend fun addSignature(
        documentUri: Uri,
        fieldName: String,
        signatureBitmap: Bitmap,
        outputFile: File
    ): AppResult<File>

    suspend fun addImageToField(
        documentUri: Uri,
        fieldName: String,
        imageBitmap: Bitmap,
        outputFile: File
    ): AppResult<File>

    suspend fun extractFormFields(documentUri: Uri): AppResult<List<PdfFormField>>
    suspend fun getFormFieldValues(documentUri: Uri): AppResult<Map<String, String>>
}
