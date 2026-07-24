package com.propdf.editor.ui.files

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.editor.domain.model.PdfDocument
import com.propdf.editor.domain.model.ViewMode
import com.propdf.editor.ui.components.*
import com.propdf.editor.ui.home.formatFileSize
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    navController: NavController,
    mainViewModel: MainViewModel,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val scope = rememberCoroutineScope()
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600
    val isLandscape = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    var showSortSheet by remember { mutableStateOf(false) }
    var showViewModeSheet by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf<PdfDocument?>(null) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "All Files",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "${uiState.files.size} documents",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showSortSheet = true }) {
                        Icon(Icons.Outlined.Sort, contentDescription = "Sort")
                    }
                    IconButton(onClick = { showViewModeSheet = true }) {
                        Icon(
                            when (uiState.viewMode) {
                                ViewMode.LIST -> Icons.Outlined.ViewList
                                ViewMode.GRID -> Icons.Outlined.GridView
                                ViewMode.TILE -> Icons.Outlined.ViewCompact
                            },
                            contentDescription = "View mode"
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (uiState.files.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.FolderOpen,
                    title = "No files yet",
                    subtitle = "Tap the + button to add your first PDF document",
                    actionLabel = "Add PDF",
                    onAction = { /* Open picker */ }
                )
            } else {
                when (uiState.viewMode) {
                    ViewMode.LIST -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = uiState.files,
                                key = { it.id }
                            ) { doc ->
                                DocumentListItem(
                                    document = doc,
                                    onClick = { mainViewModel.openPdfString(doc.uri.toString()) },
                                    onFavoriteClick = { viewModel.toggleFavorite(doc.id) },
                                    onDeleteClick = { viewModel.moveToRecycleBin(doc.id) },
                                    onMoreClick = { showContextMenu = doc }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                    ViewMode.GRID -> {
                        val columns = when {
                            isTablet && isLandscape -> 4
                            isTablet -> 3
                            isLandscape -> 3
                            else -> 2
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = uiState.files,
                                key = { it.id }
                            ) { doc ->
                                DocumentGridItem(
                                    document = doc,
                                    onClick = { mainViewModel.openPdfString(doc.uri.toString()) },
                                    onFavoriteClick = { viewModel.toggleFavorite(doc.id) }
                                )
                            }
                        }
                    }
                    ViewMode.TILE -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(
                                items = uiState.files,
                                key = { it.id }
                            ) { doc ->
                                DocumentTileItem(
                                    document = doc,
                                    onClick = { mainViewModel.openPdfString(doc.uri.toString()) },
                                    onFavoriteClick = { viewModel.toggleFavorite(doc.id) }
                                )
                            }
                            item { Spacer(modifier = Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }
    }

    // Sort Bottom Sheet
    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "Sort by",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                val sortOptions = listOf(
                    "Date (newest first)" to { viewModel.setSort(SortField.DATE, false); showSortSheet = false },
                    "Date (oldest first)" to { viewModel.setSort(SortField.DATE, true); showSortSheet = false },
                    "Name (A-Z)" to { viewModel.setSort(SortField.NAME, true); showSortSheet = false },
                    "Name (Z-A)" to { viewModel.setSort(SortField.NAME, false); showSortSheet = false },
                    "Size (largest first)" to { viewModel.setSort(SortField.SIZE, false); showSortSheet = false },
                    "Size (smallest first)" to { viewModel.setSort(SortField.SIZE, true); showSortSheet = false }
                )
                sortOptions.forEach { (label, action) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        modifier = Modifier.clickable { action() },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // View Mode Bottom Sheet
    if (showViewModeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showViewModeSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    "View Mode",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                val viewModes = listOf(
                    "List" to ViewMode.LIST to Icons.Outlined.ViewList,
                    "Grid" to ViewMode.GRID to Icons.Outlined.GridView,
                    "Compact" to ViewMode.TILE to Icons.Outlined.ViewCompact
                )
                viewModes.forEach { (pair, icon) ->
                    val (label, mode) = pair
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { Icon(icon, null) },
                        modifier = Modifier.clickable {
                            viewModel.setViewMode(mode)
                            showViewModeSheet = false
                        },
                        colors = ListItemDefaults.colors(
                            containerColor = if (uiState.viewMode == mode)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Context Menu Bottom Sheet
    showContextMenu?.let { doc ->
        ModalBottomSheet(
            onDismissRequest = { showContextMenu = null },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(bottom = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = pdf_blue.copy(alpha = 0.15f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.PictureAsPdf,
                                null,
                                tint = pdf_blue,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            doc.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            formatFileSize(doc.fileSize),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                val actions = listOf(
                    "Open" to Icons.Outlined.OpenInNew to { mainViewModel.openPdfString(doc.uri.toString()); showContextMenu = null },
                    if (doc.isFavorite) "Remove from Favorites" to Icons.Outlined.Star else "Add to Favorites" to Icons.Outlined.StarBorder to {
                        viewModel.toggleFavorite(doc.id); showContextMenu = null
                    },
                    "Categorize" to Icons.Outlined.Label to { /* Show categorize dialog */ },
                    "Rename" to Icons.Outlined.Edit to { /* Show rename dialog */ },
                    "Share" to Icons.Outlined.Share to { /* Share file */ },
                    "Properties" to Icons.Outlined.Info to { /* Show properties */ },
                    "Move to Recycle Bin" to Icons.Outlined.Delete to { viewModel.moveToRecycleBin(doc.id); showContextMenu = null }
                )
                actions.forEach { (pair, action) ->
                    val (label, icon) = pair
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { Icon(icon, null) },
                        modifier = Modifier.clickable { action() },
                        colors = ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun DocumentGridItem(
    document: PdfDocument,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val color = if (document.isFavorite) pdf_amber else pdf_blue

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(color.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = color.copy(alpha = 0.15f),
                    modifier = Modifier.size(56.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Outlined.PictureAsPdf,
                            null,
                            tint = color,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                // Favorite indicator
                if (document.isFavorite) {
                    Surface(
                        shape = CircleShape,
                        color = pdf_amber.copy(alpha = 0.9f),
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                            .size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Star,
                                null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    document.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatFileSize(document.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun DocumentTileItem(
    document: PdfDocument,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val color = if (document.isFavorite) pdf_amber else pdf_blue

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = color.copy(alpha = 0.12f),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PictureAsPdf,
                        null,
                        tint = color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    document.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${formatFileSize(document.fileSize)} · ${document.pageCount} pages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onFavoriteClick, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (document.isFavorite) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (document.isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (document.isFavorite) pdf_amber else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

enum class SortField { DATE, NAME, SIZE }
