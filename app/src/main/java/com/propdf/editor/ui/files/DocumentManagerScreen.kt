package com.propdf.editor.ui.files

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.*
import com.propdf.editor.ui.components.EmptyState
import com.propdf.editor.ui.components.LoadingOverlay
import com.propdf.editor.ui.home.formatFileSize
import com.propdf.editor.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentManagerScreen(
    onOpenDocument: (PdfDocument) -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    
    var showSortMenu by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showBatchActions by remember { mutableStateOf(false) }
    var viewMode by remember { mutableStateOf(ViewMode.LIST) }
    var currentNavItem by remember { mutableStateOf(DocumentNavItem.ALL) }
    
    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is DocumentManagerEvent.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(event.message)
                }
                is DocumentManagerEvent.ShareDocument -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, Uri.parse(event.uriString))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share PDF"))
                }
                else -> {}
            }
        }
    }
    
    // Document picker
    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Handle picked document
        }
    }
    
    // Create folder picker for move/copy
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        treeUri?.let { uri ->
            // Handle folder selection for batch operations
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            DocumentManagerTopBar(
                uiState = uiState,
                currentNavItem = currentNavItem,
                viewMode = viewMode,
                scrollBehavior = scrollBehavior,
                onViewModeChange = { viewMode = it },
                onSortClick = { showSortMenu = true },
                onFilterClick = { showFilterSheet = true },
                onSearchClick = { /* Open search */ },
                onClearSelection = { viewModel.clearSelection() },
                onSelectAll = { viewModel.selectAll() }
            )
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                BatchActionsBar(
                    selectedCount = uiState.selectedDocuments.size,
                    onDelete = { viewModel.batchDelete() },
                    onMove = { folderPicker.launch(null) },
                    onCopy = { viewModel.batchCopy("/storage/emulated/0/Documents") },
                    onFavorite = { viewModel.batchFavorite(true) },
                    onShare = { /* Share selected */ },
                    onMore = { showBatchActions = true }
                )
            } else {
                DocumentManagerBottomNav(
                    currentItem = currentNavItem,
                    onItemSelected = { item ->
                        currentNavItem = item
                        viewModel.setViewType(item.viewType)
                    }
                )
            }
        },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                ExtendedFloatingActionButton(
                    onClick = { pdfPicker.launch(arrayOf("application/pdf")) },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Add PDF") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.documents.isEmpty() -> {
                    LoadingOverlay("Loading documents...")
                }
                uiState.documents.isEmpty() -> {
                    EmptyState(
                        icon = currentNavItem.emptyIcon,
                        title = currentNavItem.emptyTitle,
                        subtitle = currentNavItem.emptySubtitle,
                        actionLabel = if (currentNavItem == DocumentNavItem.ALL) "Browse Files" else null,
                        onAction = if (currentNavItem == DocumentNavItem.ALL) {
                            { pdfPicker.launch(arrayOf("application/pdf")) }
                        } else null
                    )
                }
                else -> {
                    DocumentList(
                        documents = uiState.documents,
                        selectedIds = uiState.selectedDocuments,
                        isSelectionMode = uiState.isSelectionMode,
                        viewMode = viewMode,
                        onDocumentClick = { doc ->
                            if (uiState.isSelectionMode) {
                                viewModel.toggleSelection(doc.id)
                            } else {
                                onOpenDocument(doc)
                            }
                        },
                        onDocumentLongClick = { doc ->
                            viewModel.toggleSelection(doc.id)
                        },
                        onFavoriteClick = { doc ->
                            viewModel.toggleFavorite(doc.id)
                        },
                        contentPadding = PaddingValues(16.dp)
                    )
                }
            }
        }
    }
    
    // Sort Menu
    if (showSortMenu) {
        SortBottomSheet(
            currentSort = uiState.sortOption,
            onSortSelected = { sort ->
                viewModel.setSortOption(sort)
                showSortMenu = false
            },
            onDismiss = { showSortMenu = false }
        )
    }
    
    // Filter Sheet
    if (showFilterSheet) {
        FilterBottomSheet(
            currentFilter = uiState.filter,
            tags = uiState.tags,
            collections = uiState.collections,
            onFilterApplied = { filter ->
                // Apply filter
                showFilterSheet = false
            },
            onDismiss = { showFilterSheet = false }
        )
    }
    
    // Batch Actions Bottom Sheet
    if (showBatchActions) {
        BatchActionsBottomSheet(
            onAddToCollection = { /* Show collection picker */ },
            onAddTags = { /* Show tag picker */ },
            onHide = { viewModel.batchHide(true) },
            onRename = { /* Show rename dialog */ },
            onDismiss = { showBatchActions = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentManagerTopBar(
    uiState: DocumentManagerUiState,
    currentNavItem: DocumentNavItem,
    viewMode: ViewMode,
    scrollBehavior: TopAppBarScrollBehavior,
    onViewModeChange: (ViewMode) -> Unit,
    onSortClick: () -> Unit,
    onFilterClick: () -> Unit,
    onSearchClick: () -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit
) {
    LargeTopAppBar(
        title = {
            if (uiState.isSelectionMode) {
                Text("${uiState.selectedDocuments.size} selected")
            } else {
                Column {
                    Text(currentNavItem.title)
                    Text(
                        text = "${uiState.documents.size} documents",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        navigationIcon = {
            if (uiState.isSelectionMode) {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Default.Close, "Clear selection")
                }
            }
        },
        actions = {
            if (uiState.isSelectionMode) {
                IconButton(onClick = onSelectAll) {
                    Icon(Icons.Default.SelectAll, "Select all")
                }
            } else {
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Default.Search, "Search")
                }
                IconButton(onClick = { onViewModeChange(if (viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }) {
                    Icon(
                        imageVector = if (viewMode == ViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                        contentDescription = "Toggle view"
                    )
                }
                IconButton(onClick = onSortClick) {
                    Icon(Icons.AutoMirrored.Filled.Sort, "Sort")
                }
                IconButton(onClick = onFilterClick) {
                    Icon(Icons.Default.FilterList, "Filter")
                }
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.largeTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
private fun DocumentManagerBottomNav(
    currentItem: DocumentNavItem,
    onItemSelected: (DocumentNavItem) -> Unit
) {
    NavigationBar {
        DocumentNavItem.entries.forEach { item ->
            NavigationBarItem(
                icon = { Icon(item.icon, item.label) },
                label = { Text(item.label) },
                selected = currentItem == item,
                onClick = { onItemSelected(item) }
            )
        }
    }
}

@Composable
private fun BatchActionsBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit
) {
    BottomAppBar(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            BatchAction(Icons.Default.Delete, "Delete", onDelete)
            BatchAction(Icons.Default.DriveFileMove, "Move", onMove)
            BatchAction(Icons.Default.ContentCopy, "Copy", onCopy)
            BatchAction(Icons.Default.Star, "Favorite", onFavorite)
            BatchAction(Icons.Default.Share, "Share", onShare)
            BatchAction(Icons.Default.MoreVert, "More", onMore)
        }
    }
}

@Composable
private fun BatchAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(icon, label, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortBottomSheet(
    currentSort: SortOption,
    onSortSelected: (SortOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
            Text(
                "Sort by",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            SortOption.entries.forEach { option ->
                ListItem(
                    headlineContent = { Text(option.displayName) },
                    leadingContent = {
                        Icon(
                            imageVector = option.icon,
                            contentDescription = null,
                            tint = if (currentSort == option) 
                                MaterialTheme.colorScheme.primary 
                            else 
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingContent = if (currentSort == option) {
                        { Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                    modifier = Modifier.clickable { onSortSelected(option) }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    currentFilter: DocumentFilter,
    tags: List<DocumentTag>,
    collections: List<DocumentCollection>,
    onFilterApplied: (DocumentFilter) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    var localFilter by remember { mutableStateOf(currentFilter) }
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Filter",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            // File Type Filter
            Text("File Type", style = MaterialTheme.typography.titleMedium)
            FlowRow(
                modifier = Modifier.padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileTypeFilter.entries.forEach { type ->
                    FilterChip(
                        selected = localFilter.fileTypeFilter == type,
                        onClick = { localFilter = localFilter.copy(fileTypeFilter = type) },
                        label = { Text(type.displayName) }
                    )
                }
            }
            
            Divider(modifier = Modifier.padding(vertical = 16.dp))
            
            // Collections
            if (collections.isNotEmpty()) {
                Text("Collections", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    collections.forEach { collection ->
                        FilterChip(
                            selected = collection.id in localFilter.collections,
                            onClick = {
                                val newCollections = if (collection.id in localFilter.collections) {
                                    localFilter.collections - collection.id
                                } else {
                                    localFilter.collections + collection.id
                                }
                                localFilter = localFilter.copy(collections = newCollections)
                            },
                            label = { Text(collection.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(collection.color))
                                )
                            }
                        )
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 16.dp))
            }
            
            // Tags
            if (tags.isNotEmpty()) {
                Text("Tags", style = MaterialTheme.typography.titleMedium)
                FlowRow(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        FilterChip(
                            selected = tag.id in localFilter.tags,
                            onClick = {
                                val newTags = if (tag.id in localFilter.tags) {
                                    localFilter.tags - tag.id
                                } else {
                                    localFilter.tags + tag.id
                                }
                                localFilter = localFilter.copy(tags = newTags)
                            },
                            label = { Text(tag.name) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(Color(tag.color))
                                )
                            }
                        )
                    }
                }
                Divider(modifier = Modifier.padding(vertical = 16.dp))
            }
            
            // Include hidden
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Include hidden files", modifier = Modifier.weight(1f))
                Switch(
                    checked = localFilter.includeHidden,
                    onCheckedChange = { localFilter = localFilter.copy(includeHidden = it) }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Button(
                onClick = { onFilterApplied(localFilter) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply Filters")
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchActionsBottomSheet(
    onAddToCollection: () -> Unit,
    onAddTags: () -> Unit,
    onHide: () -> Unit,
    onRename: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)) {
            Text("More Actions", style = MaterialTheme.typography.headlineSmall)
            
            ListItem(
                headlineContent = { Text("Add to Collection") },
                leadingContent = { Icon(Icons.Default.Folder, null) },
                modifier = Modifier.clickable { onAddToCollection(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Add Tags") },
                leadingContent = { Icon(Icons.Default.Label, null) },
                modifier = Modifier.clickable { onAddTags(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Hide") },
                leadingContent = { Icon(Icons.Default.VisibilityOff, null) },
                modifier = Modifier.clickable { onHide(); onDismiss() }
            )
            ListItem(
                headlineContent = { Text("Rename") },
                leadingContent = { Icon(Icons.Default.Edit, null) },
                modifier = Modifier.clickable { onRename(); onDismiss() }
            )
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// Navigation Items
enum class DocumentNavItem(
    val label: String,
    val icon: ImageVector,
    val viewType: DocumentViewType,
    val title: String,
    val emptyIcon: ImageVector,
    val emptyTitle: String,
    val emptySubtitle: String
) {
    ALL(
        "All",
        Icons.Default.Folder,
        DocumentViewType.ALL,
        "All Documents",
        Icons.Default.FolderOpen,
        "No documents yet",
        "Add PDFs to get started"
    ),
    RECENT(
        "Recent",
        Icons.Default.History,
        DocumentViewType.RECENT,
        "Recent",
        Icons.Default.History,
        "No recent files",
        "Files you open will appear here"
    ),
    FAVORITES(
        "Favorites",
        Icons.Default.Star,
        DocumentViewType.FAVORITES,
        "Favorites",
        Icons.Default.StarBorder,
        "No favorites yet",
        "Star files to see them here"
    ),
    COLLECTIONS(
        "Collections",
        Icons.Default.CollectionsBookmark,
        DocumentViewType.COLLECTIONS,
        "Collections",
        Icons.Default.CollectionsBookmark,
        "No collections",
        "Create collections to organize files"
    ),
    MORE(
        "More",
        Icons.Default.MoreHoriz,
        DocumentViewType.ALL,
        "More",
        Icons.Default.MoreHoriz,
        "",
        ""
    )
}

// Extensions
private val SortOption.displayName: String
    get() = when (this) {
        SortOption.NAME_ASC -> "Name (A-Z)"
        SortOption.NAME_DESC -> "Name (Z-A)"
        SortOption.DATE_MODIFIED_ASC -> "Date Modified (Oldest)"
        SortOption.DATE_MODIFIED_DESC -> "Date Modified (Newest)"
        SortOption.DATE_OPENED_ASC -> "Date Opened (Oldest)"
        SortOption.DATE_OPENED_DESC -> "Date Opened (Newest)"
        SortOption.SIZE_ASC -> "Size (Smallest)"
        SortOption.SIZE_DESC -> "Size (Largest)"
        SortOption.PAGE_COUNT_ASC -> "Pages (Fewest)"
        SortOption.PAGE_COUNT_DESC -> "Pages (Most)"
    }

private val SortOption.icon: ImageVector
    get() = when (this) {
        SortOption.NAME_ASC, SortOption.NAME_DESC -> Icons.AutoMirrored.Filled.SortByAlpha
        SortOption.DATE_MODIFIED_ASC, SortOption.DATE_MODIFIED_DESC, 
        SortOption.DATE_OPENED_ASC, SortOption.DATE_OPENED_DESC -> Icons.Default.CalendarToday
        SortOption.SIZE_ASC, SortOption.SIZE_DESC -> Icons.Default.Storage
        SortOption.PAGE_COUNT_ASC, SortOption.PAGE_COUNT_DESC -> Icons.Default.MenuBook
    }

private val FileTypeFilter.displayName: String
    get() = when (this) {
        FileTypeFilter.ALL -> "All"
        FileTypeFilter.PDF -> "PDFs"
        FileTypeFilter.IMAGES -> "Images"
        FileTypeFilter.SCANNED -> "Scanned"
        FileTypeFilter.SECURED -> "Secured"
        FileTypeFilter.RECENTLY_MODIFIED -> "Recently Modified"
        FileTypeFilter.LARGE_FILES -> "Large Files"
    }
