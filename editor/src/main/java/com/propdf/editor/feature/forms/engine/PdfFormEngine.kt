package com.propdf.editor.feature.forms.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.itextpdf.forms.PdfAcroForm
import com.itextpdf.forms.fields.PdfButtonFormField
import com.itextpdf.forms.fields.PdfChoiceFormField
import com.itextpdf.forms.fields.PdfFormField
import com.itextpdf.forms.fields.PdfSignatureFormField
import com.itextpdf.forms.fields.PdfTextFormField
import com.itextpdf.io.image.ImageData
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.canvas.PdfCanvas
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField as DomainFormField
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.feature.forms.xfdf.XFDFSerializer
import java.io.ByteArrayOutputStream
import java.io.File

class PdfFormEngine {

    private val xfdfSerializer = XFDFSerializer()

    fun extractFields(pdfFile: File): AppResult<List<DomainFormField>> {
        return try {
            PdfDocument(PdfReader(pdfFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, false)
                    ?: return@use AppResult.Success(emptyList())

                val fields = mutableListOf<DomainFormField>()
                val documentUri = pdfFile.toURI().toString()

                acroForm.getAllFormFields().forEach { (name, field) ->
                    val type = determineFieldType(field)
                    val widgets = field.widgets

                    widgets.forEach { widget ->
                        val page = widget.page
                        val pageNum = pdfDoc.getPageNumber(page)
                        val rect = widget.rectangle?.toRectangle()?.toRectangleF() ?: RectF()

                        fields.add(
                            DomainFormField(
                                documentUri = documentUri,
                                fieldName = name,
                                fieldType = type,
                                pageIndex = pageNum - 1,
                                rect = rect,
                                value = field.valueAsString,
                                defaultValue = getDefaultValueAsString(field),
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
        field: DomainFormField
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

                val pdfField: PdfFormField = when (field.fieldType) {
                    FormFieldType.TEXTBOX -> {
                        PdfTextFormField.createText(
                            pdfDoc, rect, field.fieldName, field.defaultValue ?: ""
                        ).apply {
                            setFontSize(field.fontSize)
                            if (field.isRequired) setRequired(true)
                            if (field.isReadOnly) setReadOnly(true)
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
                        val radioGroup = PdfFormField.createRadioGroup(
                            pdfDoc, field.fieldName, field.value ?: ""
                        )
                        PdfFormField.createRadioButton(
                            pdfDoc, rect, radioGroup, field.groupName ?: field.fieldName
                        )
                        radioGroup
                    }
                    FormFieldType.DROPDOWN -> {
                        PdfChoiceFormField.createComboBox(
                            pdfDoc, rect, field.fieldName, field.defaultValue ?: "",
                            field.options.toTypedArray()
                        )
                    }
                    FormFieldType.LIST_BOX -> {
                        PdfChoiceFormField.createList(
                            pdfDoc, rect, field.fieldName, field.defaultValue ?: "",
                            field.options.toTypedArray()
                        )
                    }
                    FormFieldType.SIGNATURE -> {
                        PdfSignatureFormField.createSignature(
                            pdfDoc, rect
                        ).apply {
                            setFieldName(field.fieldName)
                        }
                    }
                    FormFieldType.IMAGE -> {
                        PdfButtonFormField.createPushButton(
                            pdfDoc, rect, field.fieldName, field.value ?: ""
                        )
                    }
                    FormFieldType.BUTTON -> {
                        PdfButtonFormField.createPushButton(
                            pdfDoc, rect, field.fieldName, field.value ?: "Button"
                        )
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
            val imageData = ImageDataFactory.create(baos.toByteArray())

            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
                val field = acroForm.getField(fieldName) as? PdfSignatureFormField
                    ?: return@use AppResult.Error("Signature field not found: $fieldName")

                applyImageAppearance(field, pdfDoc, imageData)

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
            val imageData = ImageDataFactory.create(baos.toByteArray())

            PdfDocument(PdfReader(pdfFile), PdfWriter(outputFile)).use { pdfDoc ->
                val acroForm = PdfAcroForm.getAcroForm(pdfDoc, true)
                val field = acroForm.getField(fieldName) as? PdfButtonFormField
                    ?: return@use AppResult.Error("Button field not found: $fieldName")

                applyImageAppearance(field, pdfDoc, imageData)

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

                val values = acroForm.getAllFormFields().mapValues { (_, field) ->
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

    /** Draws [imageData] into the normal appearance of the field's first widget. */
    private fun applyImageAppearance(field: PdfFormField, pdfDoc: PdfDocument, imageData: ImageData) {
        val widget = field.widgets.firstOrNull() ?: return
        val widgetRect = widget.rectangle?.toRectangle() ?: return
        val localRect = Rectangle(0f, 0f, widgetRect.width, widgetRect.height)

        val xObject = PdfFormXObject(localRect)
        val canvas = PdfCanvas(xObject, pdfDoc)
        canvas.addImageFittedIntoRectangle(imageData, localRect, false)

        field.getFirstFormAnnotation()?.setAppearance(PdfName.N, null, xObject.pdfObject)
    }

    private fun determineFieldType(field: PdfFormField): FormFieldType {
        return when {
            field is PdfTextFormField -> FormFieldType.TEXTBOX
            field is PdfButtonFormField && field.isPushButton -> FormFieldType.BUTTON
            field is PdfButtonFormField && field.isRadio -> FormFieldType.RADIO_BUTTON
            field is PdfButtonFormField -> FormFieldType.CHECKBOX
            field is PdfChoiceFormField && field.isCombo -> FormFieldType.DROPDOWN
            field is PdfChoiceFormField -> FormFieldType.LIST_BOX
            field is PdfSignatureFormField -> FormFieldType.SIGNATURE
            else -> FormFieldType.UNKNOWN
        }
    }

    private fun extractOptions(field: PdfFormField): List<String> {
        return if (field is PdfChoiceFormField) {
            field.options?.map { it.toString() } ?: emptyList()
        } else emptyList()
    }

    private fun extractGroupName(field: PdfFormField): String? {
        return field.parentField?.fieldName?.toUnicodeString()
    }

    /** Reads the field's default value (DV) directly from the underlying PDF dictionary. */
    private fun getDefaultValueAsString(field: PdfFormField): String? {
        return field.pdfObject.getAsString(PdfName.DV)?.toUnicodeString()
    }

    private fun applyFieldStyling(pdfField: PdfFormField, field: DomainFormField) {
        val annotation = pdfField.getFirstFormAnnotation()

        field.backgroundColor?.let { color ->
            val r = android.graphics.Color.red(color) / 255f
            val g = android.graphics.Color.green(color) / 255f
            val b = android.graphics.Color.blue(color) / 255f
            annotation?.setBackgroundColor(DeviceRgb(r, g, b))
        }

        field.borderColor?.let { color ->
            val r = android.graphics.Color.red(color) / 255f
            val g = android.graphics.Color.green(color) / 255f
            val b = android.graphics.Color.blue(color) / 255f
            annotation?.setBorderColor(DeviceRgb(r, g, b))
        }

        if (field.borderWidth > 0) {
            annotation?.setBorderWidth(field.borderWidth)
        }
    }

    private fun Rectangle.toRectangleF(): RectF {
        return RectF(x, y, x + width, y + height)
    }
}
