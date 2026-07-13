package com.propdf.core.domain.model

enum class FormFieldType {
    TEXTBOX,
    CHECKBOX,
    RADIO_BUTTON,
    DROPDOWN,
    LIST_BOX,
    DATE_PICKER,
    SIGNATURE,
    IMAGE,
    BUTTON,
    UNKNOWN;

    companion object {
        fun fromString(value: String): FormFieldType {
            return entries.find { it.name.equals(value, ignoreCase = true) } ?: UNKNOWN
        }
    }
}
