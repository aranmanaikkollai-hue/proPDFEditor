package com.propdf.editor.feature.forms.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField as DomainFormField
import com.propdf.core.domain.result.AppResult
import com.propdf.editor.feature.forms.xfdf.XFDFSerializer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDAcroForm
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDComboBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDListBox
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDPushButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDRadioButton
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDSignatureField
import com.tom_roush.pdfbox.pdmodel.interactive.form.PDTextField
import java.io.File
import java.util.concurrent.CancellationException
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext

/** PDFBox Android implementation of the active AcroForm read/write path. */
class PdfFormEngine @Inject constructor(@ApplicationContext context: Context) {
    private val xfdfSerializer = XFDFSerializer()

    init { PDFBoxResourceLoader.init(context.applicationContext) }

    fun extractFields(pdfFile: File): AppResult<List<DomainFormField>> = runCatchingResult("extract form fields") {
        PDDocument.load(pdfFile).use { document ->
            val form = document.documentCatalog.acroForm ?: return@use emptyList()
            form.fieldTree.flatMap { field -> field.widgets.map { widget -> field.toDomain(document, widget, pdfFile) } }
        }
    }

    fun createField(pdfFile: File, outputFile: File, field: DomainFormField): AppResult<Unit> =
        runCatchingResult("create field") {
            PDDocument.load(pdfFile).use { document ->
                val form = document.documentCatalog.acroForm ?: PDAcroForm(document).also { document.documentCatalog.acroForm = it }
                val pdfField: PDField = when (field.fieldType) {
                    FormFieldType.TEXTBOX -> PDTextField(form).apply { partialName = field.fieldName; value = field.defaultValue ?: "" }
                    FormFieldType.CHECKBOX -> PDCheckBox(form).apply { partialName = field.fieldName; if (field.value == onValue) check() }
                    FormFieldType.RADIO_BUTTON -> PDRadioButton(form).apply { partialName = field.fieldName; if (!field.value.isNullOrBlank()) value = field.value }
                    FormFieldType.DROPDOWN -> PDComboBox(form).apply { partialName = field.fieldName; options = field.options; setValue(field.value ?: field.defaultValue ?: "") }
                    FormFieldType.LIST_BOX -> PDListBox(form).apply { partialName = field.fieldName; options = field.options; setValue(field.value ?: field.defaultValue ?: "") }
                    FormFieldType.SIGNATURE -> PDSignatureField(form).apply { partialName = field.fieldName }
                    FormFieldType.IMAGE, FormFieldType.BUTTON -> PDPushButton(form).apply { partialName = field.fieldName }
                    else -> throw IllegalArgumentException("Unsupported field type: ${field.fieldType}")
                }
                pdfField.isRequired = field.isRequired
                pdfField.isReadOnly = field.isReadOnly
                form.fields = form.fields + pdfField
                saveAtomically(document, outputFile)
            }
        }

    fun fillFields(pdfFile: File, outputFile: File, values: Map<String, String>): AppResult<Unit> =
        runCatchingResult("fill form") {
            PDDocument.load(pdfFile).use { document ->
                val form = document.documentCatalog.acroForm ?: throw IllegalArgumentException("PDF has no AcroForm")
                form.needAppearances = false
                values.forEach { (name, value) -> form.getField(name)?.setValue(value) }
                saveAtomically(document, outputFile)
            }
        }

    fun flattenForm(pdfFile: File, outputFile: File): AppResult<Unit> = runCatchingResult("flatten form") {
        PDDocument.load(pdfFile).use { document ->
            val form = document.documentCatalog.acroForm ?: throw IllegalArgumentException("PDF has no AcroForm")
            if (form.fieldTree.any { it is PDSignatureField && it.signature != null }) {
                throw IllegalStateException("Refusing to flatten a PDF containing a signed signature field")
            }
            form.needAppearances = false
            form.flatten()
            saveAtomically(document, outputFile)
        }
    }

    /** This feature only fills an existing signature field's visual value; it never signs CMS data. */
    fun addSignature(pdfFile: File, outputFile: File, fieldName: String, signatureBitmap: Bitmap): AppResult<Unit> =
        AppResult.Error("Visual signature images are not supported by the PDFBox Forms migration; cryptographic signatures are intentionally unchanged")

    fun addImageToField(pdfFile: File, outputFile: File, fieldName: String, imageBitmap: Bitmap): AppResult<Unit> =
        AppResult.Error("Button image appearances are not supported by the PDFBox Forms migration")

    fun exportXFDF(pdfFile: File): AppResult<String> = runCatchingResult("export XFDF") {
        PDDocument.load(pdfFile).use { document ->
            val form = document.documentCatalog.acroForm
            xfdfSerializer.serialize(form?.fieldTree?.associate { it.fullyQualifiedName to (it.valueAsString ?: "") } ?: emptyMap())
        }
    }

    fun importXFDF(pdfFile: File, outputFile: File, xfdfData: String): AppResult<Unit> =
        fillFields(pdfFile, outputFile, xfdfSerializer.deserialize(xfdfData))

    private fun PDField.toDomain(document: PDDocument, widget: PDAnnotationWidget, pdfFile: File): DomainFormField {
        val rect = widget.rectangle
        return DomainFormField(
            documentUri = pdfFile.toURI().toString(), fieldName = fullyQualifiedName,
            fieldType = fieldType(), pageIndex = pageIndex(document, widget),
            rect = RectF(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY),
            value = valueAsString, defaultValue = cosObject.getString(COSName.DV), options = options(),
            isRequired = isRequired, isReadOnly = isReadOnly,
            groupName = if (this is PDRadioButton) fullyQualifiedName else null
        )
    }

    private fun PDField.fieldType() = when (this) {
        is PDTextField -> FormFieldType.TEXTBOX
        is PDCheckBox -> FormFieldType.CHECKBOX
        is PDRadioButton -> FormFieldType.RADIO_BUTTON
        is PDComboBox -> FormFieldType.DROPDOWN
        is PDListBox -> FormFieldType.LIST_BOX
        is PDSignatureField -> FormFieldType.SIGNATURE
        is PDPushButton -> FormFieldType.BUTTON
        else -> FormFieldType.UNKNOWN
    }

    /** Choice options and radio /Opt values are semantic PDF values, never UI-label guesses. */
    private fun PDField.options(): List<String> = when (this) {
        is PDChoice -> options
        is PDRadioButton -> cosObject.getDictionaryObject(COSName.OPT).asStrings().ifEmpty { appearanceStates() }
        is PDCheckBox -> listOf(onValue)
        else -> emptyList()
    }

    private fun PDRadioButton.appearanceStates(): List<String> = widgets.flatMap { widget ->
        val normal = (widget.cosObject.getDictionaryObject(COSName.AP) as? COSDictionary)
            ?.getDictionaryObject(COSName.N) as? COSDictionary
        normal?.keySet()?.map { it.name }?.filter { it != COSName.OFF.name } ?: emptyList()
    }.distinct()

    private fun Any?.asStrings(): List<String> = (this as? COSArray)?.mapNotNull {
        when (it) { is COSString -> it.string; is COSArray -> (it.getObject(1) as? COSString)?.string; else -> null }
    } ?: emptyList()

    private fun pageIndex(document: PDDocument, widget: PDAnnotationWidget): Int {
        var index = 0
        for (page in document.pages) {
            if (page.annotations.any { it.cosObject == widget.cosObject }) return index
            index++
        }
        return 0
    }

    private fun saveAtomically(document: PDDocument, outputFile: File) {
        outputFile.parentFile?.mkdirs()
        val temporary = File(outputFile.parentFile ?: outputFile.absoluteFile.parentFile, ".${outputFile.name}.partial")
        temporary.delete()
        try {
            document.save(temporary)
            if (!temporary.renameTo(outputFile)) temporary.copyTo(outputFile, overwrite = true)
        } finally { temporary.delete() }
    }

    private inline fun <T> runCatchingResult(operation: String, block: () -> T): AppResult<T> = try {
        AppResult.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        AppResult.Error("Failed to $operation: ${error.message}", error)
    }
}
