package com.propdf.editor.ui.forms.dialog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFieldEditDialog(
    field: PdfFormField?,
    onDismiss: () -> Unit,
    onSave: (PdfFormField) -> Unit,
    onDelete: ((Long) -> Unit)? = null
) {
    var fieldName by remember { mutableStateOf(field?.fieldName ?: "") }
    var fieldType by remember { mutableStateOf(field?.fieldType ?: FormFieldType.TEXTBOX) }
    var defaultValue by remember { mutableStateOf(field?.defaultValue ?: "") }
    var isRequired by remember { mutableStateOf(field?.isRequired ?: false) }
    var isReadOnly by remember { mutableStateOf(field?.isReadOnly ?: false) }
    var fontSize by remember { mutableStateOf(field?.fontSize?.toString() ?: "12") }
    var optionsText by remember { mutableStateOf(field?.options?.joinToString("\n") ?: "") }

    val typeOptions = FormFieldType.entries.filter { it != FormFieldType.UNKNOWN }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (field == null) "Add Form Field" else "Edit Form Field",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = fieldName,
                    onValueChange = { fieldName = it },
                    label = { Text("Field Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                Spacer(modifier = Modifier.height(8.dp))

                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = fieldType.name.replace("_", " "),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Field Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )

                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        typeOptions.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type.name.replace("_", " ")) },
                                onClick = {
                                    fieldType = type
                                    expanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = defaultValue,
                    onValueChange = { defaultValue = it },
                    label = { Text("Default Value") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (fieldType == FormFieldType.DROPDOWN || fieldType == FormFieldType.LIST_BOX) {
                    OutlinedTextField(
                        value = optionsText,
                        onValueChange = { optionsText = it },
                        label = { Text("Options (one per line)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 5
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = fontSize,
                    onValueChange = { fontSize = it },
                    label = { Text("Font Size") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isRequired, onCheckedChange = { isRequired = it })
                    Text("Required")
                    Spacer(modifier = Modifier.width(16.dp))
                    Checkbox(checked = isReadOnly, onCheckedChange = { isReadOnly = it })
                    Text("Read Only")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (field != null && onDelete != null) {
                        TextButton(
                            onClick = {
                                onDelete(field.id)
                                onDismiss()
                            },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete")
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Row {
                        TextButton(onClick = onDismiss) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val updatedField = (field ?: PdfFormField(
                                    documentUri = "",
                                    fieldName = fieldName,
                                    fieldType = fieldType,
                                    pageIndex = 0,
                                    rect = android.graphics.RectF(100f, 100f, 300f, 140f)
                                )).copy(
                                    fieldName = fieldName,
                                    fieldType = fieldType,
                                    defaultValue = defaultValue.takeIf { it.isNotEmpty() },
                                    isRequired = isRequired,
                                    isReadOnly = isReadOnly,
                                    fontSize = fontSize.toFloatOrNull() ?: 12f,
                                    options = optionsText.lines().filter { it.isNotBlank() }
                                )
                                onSave(updatedField)
                                onDismiss()
                            },
                            enabled = fieldName.isNotBlank()
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
