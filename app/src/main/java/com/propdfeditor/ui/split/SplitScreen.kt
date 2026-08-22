package com.propdfeditor.ui.split

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
fun SplitScreen(
    onNavigateBack: () -> Unit,
    viewModel: SplitViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val pageRanges by viewModel.pageRanges.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadDocument(it) }
    }

    val saveFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.splitToFolder(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Split PDF") },
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
        ) {
            when (val state = uiState) {
                is SplitUiState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(
                            onClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) }
                        ) {
                            Icon(Icons.Default.FileOpen, null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Select PDF")
                        }
                    }
                }
                is SplitUiState.Loaded -> {
                    Text(
                        "${state.fileName} — ${state.pageCount} pages",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )

                    // Split mode selector
                    var splitMode by remember { mutableStateOf(SplitMode.RANGE) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = splitMode == SplitMode.RANGE,
                            onClick = { splitMode = SplitMode.RANGE },
                            label = { Text("By Range") }
                        )
                        FilterChip(
                            selected = splitMode == SplitMode.EVERY,
                            onClick = { splitMode = SplitMode.EVERY },
                            label = { Text("Every N Pages") }
                        )
                    }

                    if (splitMode == SplitMode.RANGE) {
                        // Range inputs
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(pageRanges) { range ->
                                RangeItem(
                                    range = range,
                                    onUpdate = { start, end ->
                                        viewModel.updateRange(range.id, start, end)
                                    },
                                    onRemove = { viewModel.removeRange(range.id) }
                                )
                            }
                            item {
                                OutlinedButton(
                                    onClick = { viewModel.addRange() },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Text("Add Range")
                                }
                            }
                        }
                    } else {
                        // Every N pages
                        var everyN by remember { mutableIntStateOf(1) }
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Split every", style = MaterialTheme.typography.bodyLarge)
                            Slider(
                                value = everyN.toFloat(),
                                onValueChange = { everyN = it.toInt() },
                                valueRange = 1f..state.pageCount.toFloat(),
                                steps = state.pageCount - 2
                            )
                            Text("$everyN pages", style = MaterialTheme.typography.bodyMedium)
                            Button(
                                onClick = { viewModel.splitEveryN(everyN) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Generate Ranges")
                            }
                        }
                    }

                    Button(
                        onClick = { saveFolderLauncher.launch(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        enabled = pageRanges.isNotEmpty()
                    ) {
                        Icon(Icons.Default.CallSplit, null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Split PDF")
                    }
                }
                is SplitUiState.Splitting -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Splitting...")
                        }
                    }
                }
                is SplitUiState.Done -> {
                    SuccessSplitState(
                        fileCount = state.fileCount,
                        onDone = onNavigateBack
                    )
                }
                is SplitUiState.Error -> {
                    ErrorSplitState(
                        message = state.message,
                        onRetry = { viewModel.reset() }
                    )
                }
            }
        }
    }
}

@Composable
private fun RangeItem(
    range: PageRange,
    onUpdate: (Int, Int) -> Unit,
    onRemove: () -> Unit
) {
    var start by remember(range.id) { mutableIntStateOf(range.start) }
    var end by remember(range.id) { mutableIntStateOf(range.end) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = start.toString(),
                onValueChange = {
                    start = it.toIntOrNull() ?: 1
                    onUpdate(start, end)
                },
                label = { Text("From") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = end.toString(),
                onValueChange = {
                    end = it.toIntOrNull() ?: 1
                    onUpdate(start, end)
                },
                label = { Text("To") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Remove")
            }
        }
    }
}

@Composable
private fun SuccessSplitState(fileCount: Int, onDone: () -> Unit) {
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
        Text("Split Complete!", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Created $fileCount files",
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun ErrorSplitState(message: String, onRetry: () -> Unit) {
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

data class PageRange(val id: Int, var start: Int, var end: Int)

enum class SplitMode { RANGE, EVERY }
