package com.propdf.annotations.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.propdf.annotations.model.DateFormatOption
import com.propdf.annotations.model.StampAnnotation

/** What the user configured before tapping "Place" -- consumed by AnnotationOverlay to call viewModel.createStampAnnotation(). */
data class StampPlacement(
    val stampType: StampAnnotation.StampType,
    val customText: String = "",
    val imagePath: String? = null,
    val color: Int? = null,
    val backgroundColor: Int? = null,
    val borderColor: Int? = null,
    val fontSize: Float = 24f,
    val dateFormatPattern: String = DateFormatOption.DEFAULT_PATTERN
)

private enum class StampTab { PREDEFINED, CUSTOM_TEXT, CUSTOM_IMAGE, DATE }

private val PREDEFINED_STAMPS = listOf(
    StampAnnotation.StampType.APPROVED,
    StampAnnotation.StampType.REJECTED,
    StampAnnotation.StampType.DRAFT,
    StampAnnotation.StampType.FINAL,
    StampAnnotation.StampType.COMPLETED,
    StampAnnotation.StampType.CONFIDENTIAL,
    StampAnnotation.StampType.VOID,
    StampAnnotation.StampType.FOR_REVIEW
)

private val STAMP_COLOR_SWATCHES = listOf(
    android.graphics.Color.BLACK,
    android.graphics.Color.parseColor("#F44336"),
    android.graphics.Color.parseColor("#2196F3"),
    android.graphics.Color.parseColor("#4CAF50"),
    android.graphics.Color.parseColor("#FF9800"),
    android.graphics.Color.parseColor("#9C27B0")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StampPickerDialog(
    onDismiss: () -> Unit,
    onPlace: (StampPlacement) -> Unit
) {
    var tab by remember { mutableStateOf(StampTab.PREDEFINED) }

    // Predefined
    var selectedPredefined by remember { mutableStateOf(StampAnnotation.StampType.APPROVED) }

    // Custom text
    var customText by remember { mutableStateOf("") }
    var customTextColor by remember { mutableStateOf(STAMP_COLOR_SWATCHES[0]) }
    var customFontSize by remember { mutableStateOf(24f) }

    // Custom image
    var pickedImageUri by remember { mutableStateOf<Uri?>(null) }
    val pickImageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> pickedImageUri = uri }

    // Date stamp
    var selectedDateFormat by remember { mutableStateOf(DateFormatOption.PRESETS[0]) }
    var customPattern by remember { mutableStateOf("") }
    var useCustomPattern by remember { mutableStateOf(false) }
    val effectivePattern = if (useCustomPattern) customPattern else selectedDateFormat.pattern
    val datePreview = remember(effectivePattern) {
        if (DateFormatOption.isValidPattern(effectivePattern)) DateFormatOption.format(effectivePattern) else "Invalid pattern"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Stamp") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = tab.ordinal) {
                    Tab(selected = tab == StampTab.PREDEFINED, onClick = { tab = StampTab.PREDEFINED }, text = { Text("Stamp") })
                    Tab(selected = tab == StampTab.CUSTOM_TEXT, onClick = { tab = StampTab.CUSTOM_TEXT }, text = { Text("Text") })
                    Tab(selected = tab == StampTab.CUSTOM_IMAGE, onClick = { tab = StampTab.CUSTOM_IMAGE }, text = { Text("Image") })
                    Tab(selected = tab == StampTab.DATE, onClick = { tab = StampTab.DATE }, text = { Text("Date") })
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (tab) {
                    StampTab.PREDEFINED -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PREDEFINED_STAMPS) { type ->
                                val label = type.name.replace('_', ' ')
                                FilterChip(
                                    selected = selectedPredefined == type,
                                    onClick = { selectedPredefined = type },
                                    label = { Text(label, fontSize = 12.sp) }
                                )
                            }
                        }
                    }
                    StampTab.CUSTOM_TEXT -> {
                        OutlinedTextField(
                            value = customText,
                            onValueChange = { customText = it },
                            label = { Text("Stamp text") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Font size: ${customFontSize.toInt()}sp", style = MaterialTheme.typography.labelMedium)
                        Slider(value = customFontSize, onValueChange = { customFontSize = it }, valueRange = 12f..48f)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Color", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                            STAMP_COLOR_SWATCHES.forEach { c ->
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .background(Color(c))
                                        .border(
                                            width = if (customTextColor == c) 3.dp else 1.dp,
                                            color = if (customTextColor == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                            shape = CircleShape
                                        )
                                        .clickable { customTextColor = c }
                                )
                            }
                        }
                    }
                    StampTab.CUSTOM_IMAGE -> {
                        OutlinedButton(onClick = { pickImageLauncher.launch("image/*") }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (pickedImageUri == null) "Choose Image" else "Change Image")
                        }
                        pickedImageUri?.let {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Image selected", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    StampTab.DATE -> {
                        Text("Format", style = MaterialTheme.typography.labelMedium)
                        Column(modifier = Modifier.fillMaxWidth()) {
                            DateFormatOption.PRESETS.forEach { option ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedDateFormat = option; useCustomPattern = false }
                                        .padding(vertical = 4.dp)
                                ) {
                                    RadioButton(
                                        selected = !useCustomPattern && selectedDateFormat == option,
                                        onClick = { selectedDateFormat = option; useCustomPattern = false }
                                    )
                                    Text("${option.label}  (${DateFormatOption.format(option.pattern)})", fontSize = 13.sp)
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                RadioButton(selected = useCustomPattern, onClick = { useCustomPattern = true })
                                OutlinedTextField(
                                    value = customPattern,
                                    onValueChange = { customPattern = it; useCustomPattern = true },
                                    label = { Text("Custom pattern") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Preview: $datePreview", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val placement = when (tab) {
                        StampTab.PREDEFINED -> StampPlacement(stampType = selectedPredefined)
                        StampTab.CUSTOM_TEXT -> StampPlacement(
                            stampType = StampAnnotation.StampType.CUSTOM_TEXT,
                            customText = customText.ifBlank { "Custom" },
                            color = customTextColor,
                            fontSize = customFontSize
                        )
                        StampTab.CUSTOM_IMAGE -> StampPlacement(
                            stampType = StampAnnotation.StampType.CUSTOM_IMAGE,
                            imagePath = pickedImageUri?.toString()
                        )
                        StampTab.DATE -> StampPlacement(
                            stampType = StampAnnotation.StampType.DATE_STAMP,
                            dateFormatPattern = effectivePattern.ifBlank { DateFormatOption.DEFAULT_PATTERN }
                        )
                    }
                    if (tab != StampTab.CUSTOM_IMAGE || pickedImageUri != null) {
                        onPlace(placement)
                    }
                },
                enabled = tab != StampTab.CUSTOM_IMAGE || pickedImageUri != null
            ) { Text("Place on Page") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
