package com.propdfeditor.ui.compression

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressionScreen(
    onNavigateBack: () -> Unit,
    viewModel: CompressionViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedQuality by remember { mutableFloatStateOf(0.7f) }
    var removeImages by remember { mutableStateOf(false) }
    var flattenForms by remember { mutableStateOf(false) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadDocument(it) }
    }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        uri?.let { viewModel.saveCompressed(it, selectedQuality, removeImages, flattenForms) }
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
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
                }
                is CompressionUiState.Loaded -> {
                    // File info
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                state.fileName,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Original: ${formatBytes(state.originalSize)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Estimated: ${formatBytes(state.estimatedSize)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    // Quality slider
                    Text("Compression Quality", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = selectedQuality,
                        onValueChange = { selectedQuality = it },
                        valueRange = 0.1f..1.0f,
                        steps = 8
                    )
                    Text(
                        when {
                            selectedQuality >= 0.9f -> "Maximum Quality (Minimal Compression)"
                            selectedQuality >= 0.7f -> "High Quality"
                            selectedQuality >= 0.5f -> "Balanced"
                            selectedQuality >= 0.3f -> "Small File Size"
                            else -> "Maximum Compression"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )

                    // Options
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        FilterChip(
                            selected = removeImages,
                            onClick = { removeImages = !removeImages },
                            label = { Text("Remove Images") }
                        )
                        FilterChip(
                            selected = flattenForms,
                            onClick = { flattenForms = !flattenForms },
                            label = { Text("Flatten Forms") }
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    Button(
                        onClick = { saveDocumentLauncher.launch("compressed_${state.fileName}") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Compress, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Compress & Save")
                    }
                }
                is CompressionUiState.Compressing -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Compressing...")
                        }
                    }
                }
                is CompressionUiState.Done -> {
                    SuccessCompressionState(
                        originalSize = state.originalSize,
                        compressedSize = state.compressedSize,
                        onDone = onNavigateBack
                    )
                }
                is CompressionUiState.Error -> {
                    ErrorCompressionState(
                        message = state.message,
                        onRetry = { viewModel.reset() }
                    )
                }
            }
        }
    }

    if (uiState is CompressionUiState.Error) {
        val error = uiState as CompressionUiState.Error
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error.message)
        }
    }
}

@Composable
private fun SuccessCompressionState(
    originalSize: Long,
    compressedSize: Long,
    onDone: () -> Unit
) {
    val savings = if (originalSize > 0) {
        ((originalSize - compressedSize) * 100 / originalSize)
    } else 0

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("Compression Complete!", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "Saved ${formatBytes(originalSize - compressedSize)} ($savings%)",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}

@Composable
private fun ErrorCompressionState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (kotlin.math.log10(bytes.toDouble()) / kotlin.math.log10(1024.0)).toInt()
    return "%.1f %s".format(
        bytes / 1024.0.pow(digitGroups.toDouble()),
        units[digitGroups.coerceAtMost(units.size - 1)]
    )
}
