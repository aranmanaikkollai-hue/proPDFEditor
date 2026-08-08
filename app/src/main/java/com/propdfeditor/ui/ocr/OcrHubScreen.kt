package com.propdfeditor.ui.ocr

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrHubScreen(
    documentUri: String? = null,
    onNavigateBack: () -> Unit,
    viewModel: OcrViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedLanguage by remember { mutableStateOf("en") }
    val languages = listOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "zh" to "Chinese")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Language", style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 8.dp)
                ) {
                    languages.forEach { (code, name) ->
                        FilterChip(
                            selected = selectedLanguage == code,
                            onClick = { selectedLanguage = code },
                            label = { Text(name) }
                        )
                    }
                }
            }

            item {
                if (documentUri != null) {
                    Button(
                        onClick = { viewModel.performOcr(Uri.parse(documentUri), selectedLanguage) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = uiState !is OcrUiState.Processing
                    ) {
                        if (uiState is OcrUiState.Processing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.TextFields, null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Extract Text from PDF")
                    }
                }

                OutlinedButton(
                    onClick = { /* Launch image picker for OCR */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Image, null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("OCR from Image")
                }
            }

            if (uiState is OcrUiState.Success) {
                val success = uiState as OcrUiState.Success
                item {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Extracted Text", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = success.text,
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.copyToClipboard(success.text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentCopy, null)
                            Text("Copy")
                        }
                        OutlinedButton(
                            onClick = { viewModel.exportText(success.text) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, null)
                            Text("Share")
                        }
                    }
                }
            }
        }
    }

    if (uiState is OcrUiState.Error) {
        val error = uiState as OcrUiState.Error
        LaunchedEffect(error) {
            snackbarHostState.showSnackbar(error.message)
        }
    }
}
