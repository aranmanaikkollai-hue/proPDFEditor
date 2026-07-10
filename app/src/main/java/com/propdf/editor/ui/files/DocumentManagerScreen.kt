package com.propdf.editor.ui.files

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.propdf.core.domain.model.*
import com.propdf.editor.ui.components.DocumentListItem
import com.propdf.editor.utils.formatFileSize
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DocumentManagerScreen(
    viewModel: DocumentManagerViewModel = hiltViewModel(),
    onNavigateToViewer: (Long) -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateToFolder: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { DocumentViewType.values().size })

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    Scaffold(
        topBar = {
            DocumentManagerTopBar(
                uiState = uiState,
                onSearch = viewModel::search,
                onSort = viewModel::setSortOption,
                onToggleSelection = viewModel::clearSelection,
                onMerge = onNavigateToMerge,
                onBatchDelete = viewModel::batchDelete,
                onBatchFavorite = { viewModel.batchFavorite(true) }
            )
        },
        bottomBar = {
            if (uiState.isSelectionMode) {
                SelectionBottomBar(
                    selectedCount = uiState.selectedDocuments.size,
                    onDelete = viewModel::batchDelete,
                    onFavorite = { viewModel.batchFavorite(true) },
                    onShare = { /* TODO */ },
                    onMore = { /* TODO */ }
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (!uiState.isSelectionMode) {
                FloatingActionButton(onClick = { /* TODO: Add document */ }) {
                    Icon(Icons.Default.Add, null)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                modifier = Modifier.fillMaxWidth()
            ) {
                DocumentViewType.values().forEachIndexed { index, viewType ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { 
                            scope.launch { pagerState.animateScrollToPage(index) }
                            viewModel.setViewType(viewType)
                        },
                        text = { Text(viewType.name.replace("_", " ")) }
                    )
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val viewType = DocumentViewType.values()[page]
                when (viewType) {
                    DocumentViewType.ALL -> DocumentList(
                        documents = uiState.documents,
                        selectedIds = uiState.selectedDocuments,
                        isSelectionMode = uiState.isSelectionMode,
                        onDocumentClick = { doc ->
                            if (isSelectionMode) {
                                viewModel.toggleSelection(doc.id)
                            } else {
                                onNavigateToViewer(doc.id)
                            }
                        },
                        onDocumentLongClick = { doc ->
                            viewModel.toggleSelection(doc.id)
                        },
                        onFavoriteClick = { doc -> viewModel.toggleFavorite(doc.id) }
                    )
                    DocumentViewType.COLLECTIONS -> CollectionsGrid(
                        collections = uiState.collections,
                        onCollectionClick = { collection ->
                            viewModel.setViewType(DocumentViewType.COLLECTIONS, collection.id.toString())
                        }
                    )
                    DocumentViewType.TAGS -> TagsCloud(
                        tags = uiState.tags,
                        onTagClick = { tag ->
                            viewModel.setViewType(DocumentViewType.TAGS, tag.id.toString())
                        }
                    )
                    DocumentViewType.FOLDER_BROWSER -> FolderBrowser(
                        currentPath = uiState.currentFolderPath,
                        onFolderClick = onNavigateToFolder
                    )
                    DocumentViewType.DUPLICATE_FINDER -> DuplicateFinderScreen(
                        duplicateGroups = uiState.duplicateGroups,
                        onFindDuplicates = viewModel::findDuplicates
                    )
                    DocumentViewType.STORAGE_ANALYZER -> StorageAnalyzerScreen(
                        analysis = uiState.storageAnalysis,
                        onAnalyze = viewModel::analyzeStorage
                    )
                    DocumentViewType.RECENT_ACTIVITY -> RecentActivityScreen(
                        activities = uiState.recentActivities
                    )
                    else -> DocumentList(
                        documents = uiState.documents,
                        selectedIds = uiState.selectedDocuments,
                        isSelectionMode = uiState.isSelectionMode,
                        onDocumentClick = { doc ->
                            if (isSelectionMode) {
                                viewModel.toggleSelection(doc.id)
                            } else {
                                onNavigateToViewer(doc.id)
                            }
                        },
                        onDocumentLongClick = { doc ->
                            viewModel.toggleSelection(doc.id)
                        },
                        onFavoriteClick = { doc -> viewModel.toggleFavorite(doc.id) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentManagerTopBar(
    uiState: DocumentManagerUiState,
    onSearch: (String) -> Unit,
    onSort: (SortOption) -> Unit,
    onToggleSelection: () -> Unit,
    onMerge: () -> Unit,
    onBatchDelete: () -> Unit,
    onBatchFavorite: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            if (uiState.isSelectionMode) {
                Text("${uiState.selectedDocuments.size} selected")
            } else {
                Text("Documents")
            }
        },
        navigationIcon = {
            if (uiState.isSelectionMode) {
                IconButton(onClick = onToggleSelection) {
                    Icon(Icons.Default.Close, null)
                }
            } else {
                IconButton(onClick = { /* Open drawer */ }) {
                    Icon(Icons.Default.Menu, null)
                }
            }
        },
        actions = {
            if (!uiState.isSelectionMode) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        onSearch(it)
                    },
                    placeholder = { Text("Search") },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.width(200.dp),
                    singleLine = true
                )
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, null)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    SortOption.values().forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                onSort(option)
                                showSortMenu = false
                            }
                        )
                    }
                }
            } else {
                IconButton(onClick = onBatchFavorite) {
                    Icon(Icons.Default.Star, null)
                }
                IconButton(onClick = onBatchDelete) {
                    Icon(Icons.Default.Delete, null)
                }
            }
        }
    )
}

@Composable
private fun DocumentList(
    documents: List<PdfDocument>,
    selectedIds: Set<Long>,
    isSelectionMode: Boolean,
    onDocumentClick: (PdfDocument) -> Unit,
    onDocumentLongClick: (PdfDocument) -> Unit,
    onFavoriteClick: (PdfDocument) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(documents) { document ->
            val isSelected = document.id in selectedIds
            DocumentListItem(
                document = document,
                onClick = { onDocumentClick(document) },
                onFavoriteClick = { onFavoriteClick(document) }
            )
        }
    }
}

@Composable
private fun CollectionsGrid(
    collections: List<DocumentCollection>,
    onCollectionClick: (DocumentCollection) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(collections) { collection ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onCollectionClick(collection) },
                colors = CardDefaults.cardColors(
                    containerColor = Color(collection.color).copy(alpha = 0.15f)
                )
            ) {
                ListItem(
                    headlineContent = { Text(collection.name) },
                    supportingContent = collection.description?.let { { Text(it) } },
                    leadingContent = {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color(collection.color))
                        )
                    },
                    trailingContent = {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
                    }
                )
            }
        }
    }
}

@Composable
private fun TagsCloud(
    tags: List<DocumentTag>,
    onTagClick: (DocumentTag) -> Unit
) {
    FlowRow(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tags.forEach { tag ->
            AssistChip(
                onClick = { onTagClick(tag) },
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
}

@Composable
private fun SelectionBottomBar(
    selectedCount: Int,
    onDelete: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit
) {
    BottomAppBar {
        IconButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Delete, null)
                Text("Delete", style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onFavorite, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Star, null)
                Text("Favorite", style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onShare, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Share, null)
                Text("Share", style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(onClick = onMore, modifier = Modifier.weight(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.MoreVert, null)
                Text("More", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
