package com.propdf.editor.ui.forms.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.FormFieldType

@Composable
fun FormToolbar(
    isFormMode: Boolean,
    onToggleFormMode: () -> Unit,
    onAddField: (FormFieldType) -> Unit,
    onFillForm: () -> Unit,
    onSaveForm: () -> Unit,
    onFlattenForm: () -> Unit,
    onExportXFDF: () -> Unit,
    onImportXFDF: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        tonalElevation = 3.dp,
        color = if (isFormMode) MaterialTheme.colorScheme.primaryContainer 
                else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FilterChip(
                selected = isFormMode,
                onClick = onToggleFormMode,
                label = { Text("Forms") },
                leadingIcon = if (isFormMode) {
                    { Icon(Icons.Default.Check, contentDescription = null, Modifier.size(18.dp)) }
                } else null
            )

            Box {
                IconButton(
                    onClick = { showAddMenu = true },
                    enabled = isFormMode
                ) {
                    Icon(Icons.Default.AddCircle, contentDescription = "Add Field")
                }
                DropdownMenu(
                    expanded = showAddMenu,
                    onDismissRequest = { showAddMenu = false }
                ) {
                    FormFieldType.entries.filter { it != FormFieldType.UNKNOWN }.forEach { type ->
                        DropdownMenuItem(
                            text = { Text(type.name.replace("_", " ")) },
                            leadingIcon = {
                                Icon(
                                    when (type) {
                                        FormFieldType.TEXTBOX -> Icons.Default.TextFields
                                        FormFieldType.CHECKBOX -> Icons.Default.CheckBox
                                        FormFieldType.RADIO_BUTTON -> Icons.Default.RadioButtonChecked
                                        FormFieldType.DROPDOWN -> Icons.Default.ArrowDropDown
                                        FormFieldType.LIST_BOX -> Icons.Default.List
                                        FormFieldType.DATE_PICKER -> Icons.Default.DateRange
                                        FormFieldType.SIGNATURE -> Icons.Default.Draw
                                        FormFieldType.IMAGE -> Icons.Default.Image
                                        FormFieldType.BUTTON -> Icons.Default.SmartButton
                                        else -> Icons.Default.Help
                                    },
                                    contentDescription = null
                                )
                            },
                            onClick = {
                                onAddField(type)
                                showAddMenu = false
                            }
                        )
                    }
                }
            }

            IconButton(onClick = onFillForm, enabled = isFormMode) {
                Icon(Icons.Default.EditNote, contentDescription = "Fill Form")
            }

            Box {
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Save Form") },
                        leadingIcon = { Icon(Icons.Default.Save, contentDescription = null) },
                        onClick = { onSaveForm(); showMoreMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Flatten Form") },
                        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
                        onClick = { onFlattenForm(); showMoreMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Export XFDF") },
                        leadingIcon = { Icon(Icons.Default.Upload, contentDescription = null) },
                        onClick = { onExportXFDF(); showMoreMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text("Import XFDF") },
                        leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                        onClick = { onImportXFDF(); showMoreMenu = false }
                    )
                }
            }
        }
    }
}
