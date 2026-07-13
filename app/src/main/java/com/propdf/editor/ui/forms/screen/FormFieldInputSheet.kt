package com.propdf.editor.ui.forms.screen

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
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.FormFieldType
import com.propdf.core.domain.model.PdfFormField
import com.propdf.editor.ui.forms.dialog.DatePickerFormField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormFieldInputSheet(
    field: PdfFormField,
    currentValue: String?,
    onValueChange: (String) -> Unit,
    onSignatureRequest: () -> Unit,
    onImageRequest: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = field.fieldName,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = field.fieldType.name.replace("_", " "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (field.fieldType) {
                FormFieldType.TEXTBOX -> {
                    OutlinedTextField(
                        value = currentValue ?: "",
                        onValueChange = onValueChange,
                        label = { Text("Enter text") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        singleLine = true
                    )
                }

                FormFieldType.CHECKBOX -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = currentValue == "Yes" || currentValue == "true",
                            onCheckedChange = { checked ->
                                onValueChange(if (checked) "Yes" else "Off")
                            }
                        )
                        Text("Check this field")
                    }
                }

                FormFieldType.RADIO_BUTTON -> {
                    val isSelected = currentValue == "Yes" || currentValue == "true"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onValueChange(if (!isSelected) "Yes" else "Off")
                            }
                        )
                        Text("Select this option")
                    }
                }

                FormFieldType.DROPDOWN -> {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = currentValue ?: field.defaultValue ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Select option") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            field.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        onValueChange(option)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                FormFieldType.LIST_BOX -> {
                    Text(
                        text = "Select from list:",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.align(Alignment.Start)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    field.options.forEach { option ->
                        ListItem(
                            headlineContent = { Text(option) },
                            leadingContent = {
                                RadioButton(
                                    selected = currentValue == option,
                                    onClick = { onValueChange(option) }
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                FormFieldType.DATE_PICKER -> {
                    DatePickerFormField(
                        currentValue = currentValue,
                        onDateSelected = onValueChange,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                FormFieldType.SIGNATURE -> {
                    Button(
                        onClick = onSignatureRequest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Draw, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Capture Signature")
                    }
                    if (currentValue != null) {
                        Text(
                            text = "Signature captured",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                FormFieldType.IMAGE -> {
                    Button(
                        onClick = onImageRequest,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select Image")
                    }
                }

                FormFieldType.BUTTON -> {
                    Button(
                        onClick = { onValueChange("clicked") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(field.value ?: "Button")
                    }
                }

                else -> {
                    Text("Unsupported field type")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Button(onClick = onDismiss) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Confirm")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
