package com.propdf.editor.ui.files

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.editor.ui.components.DocumentListItem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentManagerScreen(
    navController: NavController,
    onOpenDocument: (String) -> Unit,
    onNavigateToViewer: () -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateToFolder: () -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var isSelectionMode by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // "Add document" previously had a completely empty onClick -- tapping it
    // did nothing at all. This picks a PDF via SAF and adds it to the
    // document library using the same RecentFile-based path FileManagerScreen
    // already uses for its own "open document" picker (the app-level
    // DocumentRepository interface has no direct "insert a PdfDocument"
    // method, only insertOrUpdateRecentFile -- reusing that rather than
    // widening the interface for this).
    val addDocumentLauncher = rememberLauncherForActivityResult(
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
            var name = it.lastPathSegment ?: "document.pdf"
            var size = 0L
            context.contentResolver.query(it, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
            viewModel.addDocument(it.toString(), name, size)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Documents") },
                actions = {
                    IconButton(onClick = { isSelectionMode = !isSelectionMode }) {
                        Icon(
                            if (isSelectionMode) Icons.Default.Close else Icons.Default.SelectAll,
                            contentDescription = "Select"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addDocumentLauncher.launch(arrayOf("application/pdf")) }) {
                Icon(Icons.Default.Add, contentDescription = "Add")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(uiState.documents) { doc ->
                DocumentListItem(
                    document = doc,
                    onClick = { onOpenDocument(doc.uri.toString()) },
                    onFavoriteClick = { viewModel.toggleFavorite(doc.id, !doc.isFavorite) },
                    onDeleteClick = {
                        viewModel.deleteDocument(doc.id)
                        // deleteDocument soft-deletes (moveToRecycleBin), so
                        // this Undo is a real, working recovery path, not
                        // just reassurance text -- there was previously no
                        // way at all to see or restore a soft-deleted
                        // document from this screen.
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "Document deleted",
                                actionLabel = "Undo"
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                viewModel.restoreDocument(doc.id)
                            }
                        }
                    }
                )
            }
        }
    }
}
