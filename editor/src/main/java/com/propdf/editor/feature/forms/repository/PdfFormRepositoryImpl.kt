package com.propdf.editor.feature.forms.repository

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.propdf.core.data.entity.FormFieldEntity
import com.propdf.core.data.local.dao.FormDataDao
import com.propdf.core.data.local.dao.FormFieldDao
import com.propdf.core.domain.model.PdfFormField
import com.propdf.core.domain.repository.PdfFormRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.feature.forms.engine.PdfFormEngine
import com.propdf.editor.feature.forms.xfdf.XFDFSerializer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

@Singleton
class PdfFormRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val formFieldDao: FormFieldDao,
    private val formDataDao: FormDataDao,
    private val engine: PdfFormEngine
) : PdfFormRepository {

    private val cacheDir = File(context.cacheDir, "form_operations").apply { mkdirs() }

    override suspend fun getFields(documentUri: String): Flow<List<PdfFormField>> {
        return formFieldDao.getFieldsForDocument(documentUri).map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override suspend fun getFieldsForPage(documentUri: String, pageIndex: Int): List<PdfFormField> {
        return formFieldDao.getFieldsForPage(documentUri, pageIndex).map { it.toDomainModel() }
    }

    override suspend fun addField(field: PdfFormField): AppResult<Long> {
        return try {
            val id = formFieldDao.insertField(field.toEntity())
            AppResult.Success(id)
        } catch (e: Exception) {
            AppResult.Error("Failed to add field: ${e.message}", e)
        }
    }

    override suspend fun updateField(field: PdfFormField): AppResult<Unit> {
        return try {
            formFieldDao.updateField(field.toEntity())
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to update field: ${e.message}", e)
        }
    }

    override suspend fun deleteField(fieldId: Long): AppResult<Unit> {
        return try {
            formFieldDao.deleteField(
                formFieldDao.getFieldById(fieldId) ?: return AppResult.Error("Field not found")
            )
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to delete field: ${e.message}", e)
        }
    }

    override suspend fun deleteAllFields(documentUri: String): AppResult<Unit> {
        return try {
            formFieldDao.deleteAllForDocument(documentUri)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to delete fields: ${e.message}", e)
        }
    }

    override suspend fun fillForm(
        documentUri: Uri,
        fields: Map<String, String>
    ): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val inputFile = uriToFile(documentUri)
            val outputFile = File(cacheDir, "filled_${System.currentTimeMillis()}.pdf")

            engine.fillFields(inputFile, outputFile, fields)

            fields.forEach { (name, value) ->
                formDataDao.insertOrUpdate(
                    com.propdf.core.data.entity.FormDataEntity(
                        documentUri = documentUri.toString(),
                        fieldName = name,
                        fieldValue = value
                    )
                )
            }

            AppResult.Success(outputFile)
        } catch (e: Exception) {
            AppResult.Error("Failed to fill form: ${e.message}", e)
        }
    }

    override suspend fun saveForm(documentUri: Uri, outputFile: File): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val inputFile = uriToFile(documentUri)
            val tempFile = File(cacheDir, "temp_save_${System.currentTimeMillis()}.pdf")

            val values = mutableMapOf<String, String>()
            formDataDao.getFormDataForDocument(documentUri.toString()).collect { dataList ->
                dataList.forEach { values[it.fieldName] = it.fieldValue }
            }

            engine.fillFields(inputFile, tempFile, values)
            tempFile.copyTo(outputFile, overwrite = true)
            tempFile.delete()

            AppResult.Success(outputFile)
        } catch (e: Exception) {
            AppResult.Error("Failed to save form: ${e.message}", e)
        }
    }

    override suspend fun flattenForm(documentUri: Uri, outputFile: File): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val inputFile = uriToFile(documentUri)
            engine.flattenForm(inputFile, outputFile)
            AppResult.Success(outputFile)
        } catch (e: Exception) {
            AppResult.Error("Failed to flatten form: ${e.message}", e)
        }
    }

    override suspend fun exportXFDF(documentUri: Uri): AppResult<String> = withContext(Dispatchers.IO) {
        try {
            val file = uriToFile(documentUri)
            engine.exportXFDF(file)
        } catch (e: Exception) {
            AppResult.Error("Failed to export XFDF: ${e.message}", e)
        }
    }

    override suspend fun importXFDF(documentUri: Uri, xfdfData: String): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val inputFile = uriToFile(documentUri)
            val outputFile = File(cacheDir, "imported_${System.currentTimeMillis()}.pdf")
            engine.importXFDF(inputFile, outputFile, xfdfData)

            val values = XFDFSerializer().deserialize(xfdfData)
            values.forEach { (name, value) ->
                formDataDao.insertOrUpdate(
                    com.propdf.core.data.entity.FormDataEntity(
                        documentUri = documentUri.toString(),
                        fieldName = name,
                        fieldValue = value
                    )
                )
            }

            AppResult.Success(Unit)
        } catch (e: Exception) {
            AppResult.Error("Failed to import XFDF: ${e.message}", e)
        }
    }

    override suspend fun importXFDF(documentUri: Uri, xfdfFile: File): AppResult<Unit> {
        return try {
            val data = xfdfFile.readText()
            importXFDF(documentUri, data)
        } catch (e: Exception) {
            AppResult.Error("Failed to read XFDF file: ${e.message}", e)
        }
    }

    override suspend fun addSignature(
        documentUri: Uri,
        fieldName: String,
        signatureBitmap: Bitmap,
        outputFile: File
    ): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val inputFile = uriToFile(documentUri)
            engine.addSignature(inputFile, outputFile, fieldName, signatureBitmap)
            AppResult.Success(outputFile)
        } catch (e: Exception) {
            AppResult.Error("Failed to add signature: ${e.message}", e)
        }
    }

    override suspend fun addImageToField(
        documentUri: Uri,
        fieldName: String,
        imageBitmap: Bitmap,
        outputFile: File
    ): AppResult<File> = withContext(Dispatchers.IO) {
        try {
            val inputFile = uriToFile(documentUri)
            engine.addImageToField(inputFile, outputFile, fieldName, imageBitmap)
            AppResult.Success(outputFile)
        } catch (e: Exception) {
            AppResult.Error("Failed to add image: ${e.message}", e)
        }
    }

    override suspend fun extractFormFields(documentUri: Uri): AppResult<List<PdfFormField>> = withContext(Dispatchers.IO) {
        try {
            val file = uriToFile(documentUri)
            engine.extractFields(file)
        } catch (e: Exception) {
            AppResult.Error("Failed to extract fields: ${e.message}", e)
        }
    }

    override suspend fun getFormFieldValues(documentUri: Uri): AppResult<Map<String, String>> = withContext(Dispatchers.IO) {
        try {
            val file = uriToFile(documentUri)
            val result = mutableMapOf<String, String>()

            when (val extractResult = engine.extractFields(file)) {
                is AppResult.Success -> {
                    extractResult.data.forEach { field ->
                        field.value?.let { result[field.fieldName] = it }
                    }
                    AppResult.Success(result)
                }
                is AppResult.Error -> extractResult
                is AppResult.Loading -> AppResult.Success(emptyMap())
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to get field values: ${e.message}", e)
        }
    }

    private fun uriToFile(uri: Uri): File {
        return when (uri.scheme) {
            "file" -> File(uri.path!!)
            else -> {
                val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }
        }
    }

    private fun FormFieldEntity.toDomainModel(): PdfFormField {
        return PdfFormField(
            id = id,
            documentUri = documentUri,
            fieldName = fieldName,
            fieldType = com.propdf.core.domain.model.FormFieldType.fromString(fieldType),
            pageIndex = pageIndex,
            rect = android.graphics.RectF(rectLeft, rectTop, rectRight, rectBottom),
            value = value,
            defaultValue = defaultValue,
            options = optionsJson?.let { json ->
                try {
                    JSONArray(json).let { arr ->
                        List(arr.length()) { arr.getString(it) }
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            } ?: emptyList(),
            isRequired = isRequired,
            isReadOnly = isReadOnly,
            fontSize = fontSize,
            textColor = textColor,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            borderWidth = borderWidth,
            rotation = rotation,
            groupName = groupName
        )
    }

    private fun PdfFormField.toEntity(): FormFieldEntity {
        return FormFieldEntity(
            id = id,
            documentUri = documentUri,
            fieldName = fieldName,
            fieldType = fieldType.name,
            pageIndex = pageIndex,
            rectLeft = rect.left,
            rectTop = rect.top,
            rectRight = rect.right,
            rectBottom = rect.bottom,
            value = value,
            defaultValue = defaultValue,
            optionsJson = if (options.isNotEmpty()) {
                JSONArray(options).toString()
            } else null,
            isRequired = isRequired,
            isReadOnly = isReadOnly,
            fontSize = fontSize,
            textColor = textColor,
            backgroundColor = backgroundColor,
            borderColor = borderColor,
            borderWidth = borderWidth,
            rotation = rotation,
            groupName = groupName
        )
    }
}
