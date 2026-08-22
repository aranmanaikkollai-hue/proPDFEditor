package com.propdfeditor.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.propdf.core.domain.model.DashboardData
import com.propdf.core.domain.model.RecentFile
import com.propdfeditor.ui.home.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeDashboardScreen(
    onOpenFile: (String) -> Unit,
    onNavigateToFileManager: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onContinueReading: (String, Int) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The FAB/empty-state "Open PDF" actions previously called
    // HomeEvent.LaunchFilePicker, which was a no-op ("Handled by
    // Activity/SAF launcher" -- but no such launcher actually existed
    // anywhere). That's why tapping "Open PDF" did nothing. This registers
    // a real SAF document picker here and opens the chosen file directly.
    //
    // takePersistableUriPermission is required here: without it, the read
    // grant SAF hands back is not guaranteed to still be valid by the time
    // the viewer actually opens the file (navigation + async loading can
    // outlive the transient grant), which is what caused
    // "Permission Denial: ... requires ACTION_OPEN_DOCUMENT or related APIs"
    // even though the correct content:// Uri was being used.
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: SecurityException) {
                // Some providers don't support persistable grants; the
                // transient grant from the picker result may still be
                // enough for this session.
            }
            onOpenFile(it.toString())
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("ProPDF Editor") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Open PDF") }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is HomeUiState.Loading -> DashboardSkeleton(modifier = Modifier.padding(padding))
            is HomeUiState.Empty -> DashboardEmpty(
                onOpenFile = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                onScanDocument = onNavigateToScanner,
                modifier = Modifier.padding(padding)
            )
            is HomeUiState.Success -> DashboardContent(
                data = state.data,
                onOpenFile = onOpenFile,
                onContinueReading = onContinueReading,
                onNavigateToFileManager = onNavigateToFileManager,
                onNavigateToScanner = onNavigateToScanner,
                onNavigateToTools = onNavigateToTools,
                onPinFile = { viewModel.onEvent(HomeEvent.PinFile(it)) },
                onFavoriteFile = { viewModel.onEvent(HomeEvent.FavoriteFile(it)) },
                onDeleteFile = {
                    scope.launch {
                        viewModel.onEvent(HomeEvent.DeleteFile(it))
                        snackbarHostState.showSnackbar("Moved to Recycle Bin")
                    }
                },
                modifier = Modifier.padding(padding)
            )
            is HomeUiState.Error -> DashboardError(
                message = state.message,
                onRetry = { viewModel.onEvent(HomeEvent.Refresh) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun DashboardContent(
    data: DashboardData,
    onOpenFile: (String) -> Unit,
    onContinueReading: (String, Int) -> Unit,
    onNavigateToFileManager: () -> Unit,
    onNavigateToScanner: () -> Unit,
    onNavigateToTools: () -> Unit,
    onPinFile: (String) -> Unit,
    onFavoriteFile: (String) -> Unit,
    onDeleteFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Quick Actions
        item {
            QuickActionsRow(
                onOpenFile = onNavigateToFileManager,
                onScan = onNavigateToScanner,
                onTools = onNavigateToTools,
                onFavorites = { /* Navigate to favorites filter */ }
            )
        }

        // Continue Reading
        data.continueReading?.let { progress ->
            item {
                ContinueReadingCard(
                    file = progress.file,
                    page = progress.currentPage,
                    totalPages = progress.totalPages,
                    onClick = { onContinueReading(progress.file.uri, progress.currentPage) }
                )
            }
        }

        // Storage Stats
        item {
            StorageUsageCard(
                usedBytes = data.storageUsed,
                totalBytes = data.storageTotal,
                recycleBinCount = data.recycleBinCount
            )
        }

        // Pinned Files
        if (data.pinnedFiles.isNotEmpty()) {
            item {
                SectionHeader(title = "Pinned", action = "See All", onAction = onNavigateToFileManager)
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(data.pinnedFiles, key = { it.uri }) { file ->
                        PinnedFileCard(
                            file = file,
                            onClick = { onOpenFile(file.uri) },
                            onUnpin = { onPinFile(file.uri) }
                        )
                    }
                }
            }
        }

        // Recent Files
        if (data.recentFiles.isNotEmpty()) {
            item {
                SectionHeader(title = "Recent Files", action = "See All", onAction = onNavigateToFileManager)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    data.recentFiles.take(5).forEach { file ->
                        RecentFileItem(
                            file = file,
                            onClick = { onOpenFile(file.uri) },
                            onPin = { onPinFile(file.uri) },
                            onFavorite = { onFavoriteFile(file.uri) },
                            onDelete = { onDeleteFile(file.uri) }
                        )
                    }
                }
            }
        }

        // Recent OCR
        if (data.recentOcr.isNotEmpty()) {
            item {
                SectionHeader(title = "Recent OCR")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.recentOcr, key = { it.id }) { ocr ->
                        OcrHistoryCard(ocr = ocr, onClick = { /* Open OCR result */ })
                    }
                }
            }
        }

        // Recent Scans
        if (data.recentScans.isNotEmpty()) {
            item {
                SectionHeader(title = "Recent Scans")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(data.recentScans, key = { it.uri }) { scan ->
                        ScanHistoryCard(scan = scan, onClick = { onOpenFile(scan.uri) })
                    }
                }
            }
        }

        // Smart Suggestions
        if (data.suggestions.isNotEmpty()) {
            item {
                SuggestionsChips(
                    suggestions = data.suggestions,
                    onSuggestionClick = { suggestion ->
                        when (suggestion.action) {
                            "open" -> onOpenFile(suggestion.target)
                            "scan" -> onNavigateToScanner()
                            "compress" -> onNavigateToTools()
                        }
                    }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(action)
            }
        }
    }
}

@Composable
private fun QuickActionsRow(
    onOpenFile: () -> Unit,
    onScan: () -> Unit,
    onTools: () -> Unit,
    onFavorites: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        QuickActionButton(
            icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            label = "Files",
            onClick = onOpenFile
        )
        QuickActionButton(
            icon = Icons.Default.DocumentScanner,
            label = "Scan",
            onClick = onScan
        )
        QuickActionButton(
            icon = Icons.Default.Build,
            label = "Tools",
            onClick = onTools
        )
        QuickActionButton(
            icon = Icons.Default.Favorite,
            label = "Favorites",
            onClick = onFavorites
        )
    }
}

@Composable
private fun QuickActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
