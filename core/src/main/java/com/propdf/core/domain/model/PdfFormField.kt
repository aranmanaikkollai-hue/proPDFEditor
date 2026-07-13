package com.propdf.core.domain.model

import android.graphics.RectF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class PdfFormField(
    val id: Long = 0,
    val documentUri: String,
    val fieldName: String,
    val fieldType: FormFieldType,
    val pageIndex: Int,
    val rect: RectF,
    val value: String? = null,
    val defaultValue: String? = null,
    val options: List<String> = emptyList(),
    val isRequired: Boolean = false,
    val isReadOnly: Boolean = false,
    val fontSize: Float = 12f,
    val textColor: Int = 0xFF000000.toInt(),
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val borderWidth: Float = 0f,
    val rotation: Int = 0,
    val groupName: String? = null
) : Parcelable {

    val isTextField: Boolean
        get() = fieldType == FormFieldType.TEXTBOX || 
                fieldType == FormFieldType.DATE_PICKER

    val isChoiceField: Boolean
        get() = fieldType == FormFieldType.DROPDOWN || 
                fieldType == FormFieldType.LIST_BOX

    val isBooleanField: Boolean
        get() = fieldType == FormFieldType.CHECKBOX || 
                fieldType == FormFieldType.RADIO_BUTTON
}
