package com.propdfeditor.ui.filemanager

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.RecentFile
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.pow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    onOpenPdf: (String) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: FileManagerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClearRecentConfirm by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers don't support persistable grants.
            }
            viewModel.addRecentFile(it.toString())
            onOpenPdf(it.toString())
        }
    }

    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadFolder(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.loadRecentFiles()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) }) {
                        Icon(Icons.Default.FileOpen, contentDescription = "Open")
                    }
                    IconButton(onClick = { openTreeLauncher.launch(null) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Folder")
                    }
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "Sort")
                        }
                        DropdownMenu(
                            expanded = showSortMenu,
                            onDismissRequest = { showSortMenu = false }
                        ) {
                            FileSort.values().forEach { sort ->
                                DropdownMenuItem(
                                    text = { Text(sort.label) },
                                    onClick = {
                                        viewModel.setSort(sort)
                                        showSortMenu = false
                                    }
                                )
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear Recent Files") },
                                onClick = {
                                    showOverflowMenu = false
                                    showClearRecentConfirm = true
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Open PDF") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.search(it)
                },
                placeholder = { Text("Search files...") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                singleLine = true
            )

            when (val state = uiState) {
                is FileManagerUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is FileManagerUiState.Empty -> {
                    EmptyFileState(
                        onOpenFile = { openDocumentLauncher.launch(arrayOf("application/pdf")) }
                    )
                }
                is FileManagerUiState.Success -> {
                    FileList(
                        files = state.files,
                        onOpenFile = onOpenPdf,
                        onPin = { viewModel.pinFile(it) },
                        onFavorite = { viewModel.favoriteFile(it) },
                        onDelete = {
                            scope.launch {
                                viewModel.deleteFile(it)
                                snackbarHostState.showSnackbar("Moved to Recycle Bin")
                            }
                        },
                        onShare = { uri ->
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "application/pdf"
                                putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
                        }
                    )
                }
                is FileManagerUiState.Error -> {
                    ErrorFileState(
                        message = state.message,
                        onRetry = { viewModel.loadRecentFiles() }
                    )
                }
            }
        }
    }

    if (showClearRecentConfirm) {
        AlertDialog(
            onDismissRequest = { showClearRecentConfirm = false },
            title = { Text("Clear Recent Files?") },
            text = {
                Text("This only clears your recent-files history. Pinned and favorited files are kept, and no PDFs are deleted.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearRecentConfirm = false
                    viewModel.clearRecentFiles()
                    scope.launch { snackbarHostState.showSnackbar("Recent files history cleared") }
                }) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearRecentConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun FileList(
    files: List<RecentFile>,
    onOpenFile: (String) -> Unit,
    onPin: (String) -> Unit,
    onFavorite: (String) -> Unit,
    onDelete: (String) -> Unit,
    onShare: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(files, key = { it.uri }) { file ->
            FileListItem(
                file = file,
                onClick = { onOpenFile(file.uri) },
                onPin = { onPin(file.uri) },
                onFavorite = { onFavorite(file.uri) },
                onDelete = { onDelete(file.uri) },
                onShare = { onShare(file.uri) }
            )
        }
    }
}

@Composable
private fun FileListItem(
    file: RecentFile,
    onClick: () -> Unit,
    onPin: () -> Unit,
    onFavorite: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatBytes(file.size)} • ${formatDate(file.lastOpened)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (file.isPinned) {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            if (file.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = "Favorite",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More")
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (file.isPinned) "Unpin" else "Pin") },
                        leadingIcon = {
                            Icon(
                                if (file.isPinned) Icons.Default.PushPin else Icons.Default.PushPin,
                                null
                            )
                        },
                        onClick = { expanded = false; onPin() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (file.isFavorite) "Remove Favorite" else "Favorite") },
                        leadingIcon = {
                            Icon(
                                if (file.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                null
                            )
                        },
                        onClick = { expanded = false; onFavorite() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, null) },
                        onClick = { expanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { expanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyFileState(onOpenFile: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FolderOpen,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text("No PDFs found", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Open a PDF to get started",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onOpenFile) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Browse")
        }
    }
}

@Composable
private fun ErrorFileState(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
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

private fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
}

enum class FileSort(val label: String) {
    NAME("Name"),
    DATE("Date Modified"),
    SIZE("Size"),
    RECENTLY_OPENED("Recently Opened")
}
