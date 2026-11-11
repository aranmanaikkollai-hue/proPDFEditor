package com.propdfeditor.ui.compression

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressionScreen(
    onNavigateBack: () -> Unit,
    viewModel: CompressionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var quality by rememberSaveable { mutableStateOf(CompressionSettings.DEFAULT_QUALITY.toFloat()) }
    var sizeChoice by rememberSaveable { mutableStateOf(ImageSizeChoice.LARGE) }
    var removeMetadata by rememberSaveable { mutableStateOf(false) }

    fun currentSettings() = CompressionSettings(
        imageQuality = quality.roundToInt(),
        imageSize = sizeChoice,
        removeMetadata = removeMetadata
    )

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadDocument(it) }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { viewModel.compress(it, currentSettings()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Compress PDF") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (val state = uiState) {
                is CompressionUiState.Idle -> {
                    Button(
                        onClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Select PDF to Compress")
                    }
                    InfoNote(
                        "Compression re-encodes embedded images and can remove document metadata. " +
                            "PDFs with few or no large images may not get smaller. " +
                            "Digitally signed and password-protected PDFs cannot be compressed. " +
                            "Your original file is never changed; the result is saved as a new file."
                    )
                }

                is CompressionUiState.Inspecting -> {
                    Text("Checking PDF...", style = MaterialTheme.typography.bodyLarge)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                is CompressionUiState.Loaded -> {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(state.fileName, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Original size: ${formatBytes(state.originalSize)}  |  ${state.pageCount} pages",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Text("Image quality: ${quality.roundToInt()}", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = quality,
                        onValueChange = { quality = it },
                        valueRange = CompressionSettings.MIN_QUALITY.toFloat()..CompressionSettings.MAX_QUALITY.toFloat(),
                        steps = 8
                    )
                    Text(
                        "Lower quality makes images smaller but less sharp. Text and vector content are not re-encoded.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text("Image size", style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ImageSizeChoice.values().forEach { choice ->
                            FilterChip(
                                selected = sizeChoice == choice,
                                onClick = { sizeChoice = choice },
                                label = { Text(choice.label) }
                            )
                        }
                    }
                    Text(
                        "Images larger than the chosen size (longest side) are scaled down.",
                        style = MaterialTheme.typography.bodySmall
                    )

                    SwitchRow("Remove document metadata", removeMetadata, enabled = true) { removeMetadata = it }
                    SwitchRow("Remove images (not available)", false, enabled = false) { }
                    SwitchRow("Flatten forms (not available)", false, enabled = false) { }

                    Button(
                        onClick = { saveDocumentLauncher.launch("compressed_${state.fileName}") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Compress, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Compress & Save As...")
                    }
                    OutlinedButton(onClick = { viewModel.reset() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Choose a different PDF")
                    }
                }

                is CompressionUiState.Compressing -> {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Compressing...", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(onClick = { viewModel.cancel() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }

                is CompressionUiState.Done -> ResultCard(state, onDone = onNavigateBack)

                is CompressionUiState.Error -> {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text(state.message, style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { viewModel.dismissError() }) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun InfoNote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ResultCard(state: CompressionUiState.Done, onDone: () -> Unit) {
    val c = state.comparison
    val headline = when (c.outcome) {
        SizeComparison.Outcome.SMALLER -> "Saved a smaller copy"
        SizeComparison.Outcome.SAME -> "Saved a copy (same size)"
        SizeComparison.Outcome.LARGER -> "Saved a copy, but it is not smaller"
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(headline, style = MaterialTheme.typography.headlineSmall)
        Text("Original: ${formatBytes(c.originalBytes)}", style = MaterialTheme.typography.bodyLarge)
        Text("Compressed: ${formatBytes(c.outputBytes)}", style = MaterialTheme.typography.bodyLarge)
        Text(
            when (c.outcome) {
                SizeComparison.Outcome.SMALLER ->
                    "Saved ${formatBytes(c.savedBytes)} (${String.format(java.util.Locale.US, "%.1f", c.savedPercent)}%)"
                SizeComparison.Outcome.SAME -> "No size change."
                SizeComparison.Outcome.LARGER ->
                    "${formatBytes(abs(c.savedBytes))} larger. This PDF has little to compress; keep the original."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            "${state.pageCount} pages verified. Images recompressed: ${state.imagesRecompressed}." +
                if (state.metadataRemoved) " Metadata removed." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Your original PDF was not changed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = onDone) { Text("Done") }
    }
}
