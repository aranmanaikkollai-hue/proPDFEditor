package com.propdf.editor.feature.forms.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.itextpdf.forms.PdfAcroForm
import com.itextpdf.forms.fields.*
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.*
import com.itextpdf.kernel.pdf.xobject.PdfImageXObject
import com.itextpdf.io.image.ImageDataFactory
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.feature.forms.xfdf.XFDFSerializer
import java.io.ByteArrayOutputStream
import java.io.File

class PdfFormEngine {

    private val xfdfSerializer = XFDFSerializer()

    fun extractFields(pdfFile: File): AppResult<List<PdfFormField>> {
        return try {
            PdfDocument(PdfReader(pdfFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, false)
                    ?: return@use AppResult.Success(emptyList())

                val fields = mutableListOf<PdfFormField>()
                val documentUri = pdfFile.toURI().toString()

                acroForm.fields.forEach { (name, field) ->
                    val type = determineFieldType(field)
                    val widgets = field.widgets

                    widgets.forEach { widget ->
                        val page = widget.page
                        val pageNum = pdfDoc.getPageNumber(page)
                        val rect = widget.rectangle?.toRectangleF() ?: RectF()

                        fields.add(
                            PdfFormField(
                                documentUri = documentUri,
                                fieldName = name,
                                fieldType = type,
                                pageIndex = pageNum - 1,
                                rect = rect,
                                value = field.valueAsString,
                                defaultValue = field.defaultValueAsString,
                                options = extractOptions(field),
                                isRequired = field.isRequired,
                                isReadOnly = field.isReadOnly,
                                fontSize = 12f,
                                groupName = if (type == FormFieldType.RADIO_BUTTON) {
                                    extractGroupName(field)
                                } else null
                            )
                        )
                    }
                }

                AppResult.Success(fields)
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to extract form fields: ${e.message}", e)
        }
    }

    fun createField(
        pdfFile: File,
        outputFile: File,
        field: PdfFormField
    ): AppResult<Unit> {
        return try {
            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
                val page = pdfDoc.getPage(field.pageIndex + 1)
                val rect = Rectangle(
                    field.rect.left,
                    field.rect.top,
                    field.rect.right - field.rect.left,
                    field.rect.bottom - field.rect.top
                )

                val pdfField = when (field.fieldType) {
                    FormFieldType.TEXTBOX -> {
                        PdfTextFormField.createText(
                            pdfDoc, rect, field.fieldName, field.defaultValue ?: ""
                        ).apply {
                            setFontSize(field.fontSize)
                            if (field.isRequired) setRequired()
                            if (field.isReadOnly) setReadOnly()
                        }
                    }
                    FormFieldType.CHECKBOX -> {
                        PdfButtonFormField.createCheckBox(
                            pdfDoc, rect, field.fieldName, "Yes"
                        ).apply {
                            setCheckType(PdfButtonFormField.TYPE_CHECK)
                            if (field.value == "Yes") setValue("Yes")
                        }
                    }
                    FormFieldType.RADIO_BUTTON -> {
                        PdfButtonFormField.createRadioButton(
                            pdfDoc, rect, field.fieldName
                        ).apply {
                            addOptionToRadioGroup(field.groupName ?: field.fieldName, rect)
                        }
                    }
                    FormFieldType.DROPDOWN -> {
                        PdfChoiceFormField.createComboBox(
                            pdfDoc, rect, field.fieldName, field.defaultValue ?: ""
                        ).apply {
                            setOptions(field.options.toTypedArray())
                        }
                    }
                    FormFieldType.LIST_BOX -> {
                        PdfChoiceFormField.createList(
                            pdfDoc, rect, field.fieldName, field.defaultValue ?: ""
                        ).apply {
                            setOptions(field.options.toTypedArray())
                        }
                    }
                    FormFieldType.SIGNATURE -> {
                        PdfSignatureFormField.createSignature(
                            pdfDoc, rect, field.fieldName
                        )
                    }
                    FormFieldType.IMAGE -> {
                        PdfButtonFormField.createPushButton(
                            pdfDoc, rect, field.fieldName
                        )
                    }
                    FormFieldType.BUTTON -> {
                        PdfButtonFormField.createPushButton(
                            pdfDoc, rect, field.fieldName
                        ).apply {
                            setValue(field.value ?: "Button")
                        }
                    }
                    else -> throw IllegalArgumentException("Unsupported field type: ${field.fieldType}")
                }

                applyFieldStyling(pdfField, field)
                acroForm.addField(pdfField, page)

                AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to create field: ${e.message}", e)
        }
    }

    fun fillFields(
        pdfFile: File,
        outputFile: File,
        values: Map<String, String>
    ): AppResult<Unit> {
        return try {
            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)

                values.forEach { (name, value) ->
                    acroForm.getField(name)?.setValue(value)
                }

                AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to fill form: ${e.message}", e)
        }
    }

    fun flattenForm(pdfFile: File, outputFile: File): AppResult<Unit> {
        return try {
            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
                acroForm.flattenFields()
                AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to flatten form: ${e.message}", e)
        }
    }

    fun addSignature(
        pdfFile: File,
        outputFile: File,
        fieldName: String,
        signatureBitmap: Bitmap
    ): AppResult<Unit> {
        return try {
            val baos = ByteArrayOutputStream()
            signatureBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
            val imageData = baos.toByteArray()

            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
                val field = acroForm.getField(fieldName) as? PdfSignatureFormField
                    ?: return@use AppResult.Error("Signature field not found: $fieldName")

                val image = PdfImageXObject(ImageDataFactory.create(imageData))
                field.setImage(image)

                AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to add signature: ${e.message}", e)
        }
    }

    fun addImageToField(
        pdfFile: File,
        outputFile: File,
        fieldName: String,
        imageBitmap: Bitmap
    ): AppResult<Unit> {
        return try {
            val baos = ByteArrayOutputStream()
            imageBitmap.compress(Bitmap.CompressFormat.JPEG, 95, baos)
            val imageData = baos.toByteArray()

            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
                val field = acroForm.getField(fieldName) as? PdfButtonFormField
                    ?: return@use AppResult.Error("Button field not found: $fieldName")

                val image = PdfImageXObject(ImageDataFactory.create(imageData))
                field.setImage(image)

                AppResult.Success(Unit)
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to add image: ${e.message}", e)
        }
    }

    fun exportXFDF(pdfFile: File): AppResult<String> {
        return try {
            PdfDocument(PdfReader(pdfFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, false)
                    ?: return@use AppResult.Success(xfdfSerializer.createEmptyXFDF())

                val values = acroForm.fields.mapValues { (_, field) ->
                    field.valueAsString ?: ""
                }

                AppResult.Success(xfdfSerializer.serialize(values))
            }
        } catch (e: Exception) {
            AppResult.Error("Failed to export XFDF: ${e.message}", e)
        }
    }

    fun importXFDF(
        pdfFile: File,
        outputFile: File,
        xfdfData: String
    ): AppResult<Unit> {
        return try {
            val values = xfdfSerializer.deserialize(xfdfData)
            fillFields(pdfFile, outputFile, values)
        } catch (e: Exception) {
            AppResult.Error("Failed to import XFDF: ${e.message}", e)
        }
    }

    private fun determineFieldType(field: PdfFormField): FormFieldType {
        return when {
            field is PdfTextFormField -> FormFieldType.TEXTBOX
            field is PdfButtonFormField && field.isCheckBox -> FormFieldType.CHECKBOX
            field is PdfButtonFormField && field.isRadioButton -> FormFieldType.RADIO_BUTTON
            field is PdfChoiceFormField && field.isCombo -> FormFieldType.DROPDOWN
            field is PdfChoiceFormField -> FormFieldType.LIST_BOX
            field is PdfSignatureFormField -> FormFieldType.SIGNATURE
            field is PdfButtonFormField -> FormFieldType.BUTTON
            else -> FormFieldType.UNKNOWN
        }
    }

    private fun extractOptions(field: PdfFormField): List<String> {
        return if (field is PdfChoiceFormField) {
            field.options?.map { it.toString() } ?: emptyList()
        } else emptyList()
    }

    private fun extractGroupName(field: PdfFormField): String? {
        return field.parent?.pdfObject?.getAsString(PdfName.T)?.toString()
    }

    private fun applyFieldStyling(pdfField: PdfFormField, field: PdfFormField) {
        field.backgroundColor?.let { color ->
            val r = android.graphics.Color.red(color) / 255f
            val g = android.graphics.Color.green(color) / 255f
            val b = android.graphics.Color.blue(color) / 255f
            pdfField.backgroundColor = DeviceRgb(r, g, b)
        }

        field.borderColor?.let { color ->
            val r = android.graphics.Color.red(color) / 255f
            val g = android.graphics.Color.green(color) / 255f
            val b = android.graphics.Color.blue(color) / 255f
            pdfField.setBorderColor(DeviceRgb(r, g, b))
        }

        if (field.borderWidth > 0) {
            pdfField.setBorderWidth(field.borderWidth)
        }
    }

    private fun com.itextpdf.kernel.geom.Rectangle.toRectangleF(): RectF {
        return RectF(x.toFloat(), y.toFloat(), (x + width).toFloat(), (y + height).toFloat())
    }
}
