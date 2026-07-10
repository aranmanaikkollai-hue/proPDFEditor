package com.propdf.editor.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.core.domain.model.*
import com.propdf.editor.ui.components.*
import com.propdf.editor.utils.formatFileSize
import com.propdf.editor.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    onOpenDocument: (PdfDocument) -> Unit,
    onNavigateToFiles: () -> Unit,
    onNavigateToRecent: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToTools: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ProPDF Editor", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { navController.navigate("search") }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallFloatingActionButton(
                    onClick = { navController.navigate("scanner") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Scan")
                }
                FloatingActionButton(
                    onClick = onNavigateToFiles,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add PDF")
                }
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingOverlay("Loading your workspace...")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { StorageOverviewCard(uiState.storageStats) }
                item { QuickActionsRow(navController, onNavigateToRecent, onNavigateToFavorites, onNavigateToTools) }
                item { SectionTitle("Collections") }
                item { CollectionsRow(uiState.collections, navController) }
                item { SectionTitle("Recent Files") }
                items(uiState.recentFiles.take(5)) { doc ->
                    HomeDocumentListItem(
                        document = doc,
                        onClick = { onOpenDocument(doc) },
                        onFavoriteClick = { viewModel.toggleFavorite(doc.id, doc.isFavorite) }
                    )
                }
                item { SectionTitle("Tags") }
                item { TagsRow(uiState.tags) }
                item { SectionTitle("Categories") }
                item { CategoriesGrid(navController) }
            }
        }
    }
}

@Composable
fun StorageOverviewCard(stats: StorageStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Storage Overview",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("${stats.totalDocuments}", "Documents", pdf_blue)
                StatItem(formatFileSize(stats.totalSize), "Total Size", pdf_green)
                StatItem("${stats.favoriteCount}", "Favorites", pdf_amber)
                StatItem("${stats.deletedCount}", "Recycle Bin", pdf_red)
            }
            
            if (stats.duplicateWastedBytes > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.ContentCopy, null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "${formatFileSize(stats.duplicateWastedBytes)} wasted in duplicates",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { /* Navigate to duplicate finder */ }) {
                            Text("Clean Up", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionsRow(
    navController: NavController,
    onNavigateToRecent: () -> Unit,
    onNavigateToFavorites: () -> Unit,
    onNavigateToTools: () -> Unit
) {
    val actions = listOf(
        QuickAction("Files", Icons.Default.FolderOpen, pdf_blue, onNavigateToRecent),
        QuickAction("Favorites", Icons.Default.Star, pdf_amber, onNavigateToFavorites),
        QuickAction("Scanner", Icons.Default.CameraAlt, pdf_teal, { navController.navigate("scanner") }),
        QuickAction("Tools", Icons.Default.Build, pdf_red, onNavigateToTools)
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(actions) { action ->
            Card(
                modifier = Modifier
                    .width(85.dp)
                    .aspectRatio(1f)
                    .clickable(onClick = action.onClick),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = action.color.copy(alpha = 0.1f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = action.color,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = action.color
                    )
                }
            }
        }
    }
}

data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
fun CollectionsRow(
    collections: List<DocumentCollection>,
    navController: NavController
) {
    if (collections.isEmpty()) {
        EmptyStateMini(
            Icons.Default.CollectionsBookmark,
            "No collections yet",
            "Create collections to organize your PDFs"
        )
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(collections) { collection ->
                CollectionCard(collection) {
                    navController.navigate("collection/${collection.id}")
                }
            }
        }
    }
}

@Composable
fun CollectionCard(
    collection: DocumentCollection,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(160.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(collection.color).copy(alpha = 0.15f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(collection.color).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CollectionsBookmark,
                        null,
                        tint = Color(collection.color),
                        modifier = Modifier.size(24.dp)
                    )
                }
                Text(
                    "${collection.documentCount}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(collection.color)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                collection.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            collection.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun TagsRow(tags: List<DocumentTag>) {
    if (tags.isEmpty()) {
        EmptyStateMini(
            Icons.Default.Label,
            "No tags yet",
            "Add tags to categorize your files"
        )
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tags.forEach { tag ->
                SuggestionChip(
                    onClick = { /* Navigate to tag filter */ },
                    label = { Text(tag.name) },
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(tag.color))
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun CategoriesGrid(navController: NavController) {
    val categories = listOf(
        CategoryItem("All Files", Icons.Default.Folder, pdf_blue, "files"),
        CategoryItem("Recent", Icons.Default.History, pdf_teal, "recent"),
        CategoryItem("Large Files", Icons.Default.Storage, pdf_orange, "large_files"),
        CategoryItem("Hidden", Icons.Default.VisibilityOff, pdf_purple, "hidden"),
        CategoryItem("Recycle Bin", Icons.Default.DeleteOutline, pdf_red, "recycle_bin"),
        CategoryItem("Storage Analyzer", Icons.Default.Analytics, pdf_green, "storage_analyzer")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(2).forEach { rowCats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowCats.forEach { cat ->
                    CategoryChip(
                        name = cat.name,
                        icon = cat.icon,
                        color = cat.color,
                        modifier = Modifier.weight(1f),
                        onClick = { navController.navigate(cat.route) }
                    )
                }
            }
        }
    }
}

data class CategoryItem(
    val name: String,
    val icon: ImageVector,
    val color: Color,
    val route: String
)

@Composable
fun CategoryChip(
    name: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
fun HomeDocumentListItem(
    document: PdfDocument,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    val iconColor = when {
        document.isFavorite -> pdf_amber
        else -> pdf_blue
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        formatFileSize(document.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    document.lastOpened?.let {
                        Text(
                            "• ${dateFormat.format(Date(it))}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (document.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = null,
                    tint = if (document.isFavorite) pdf_amber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StatItem(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun EmptyStateMini(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon,
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

data class StorageStats(
    val totalDocuments: Int = 0,
    val totalSize: Long = 0,
    val favoriteCount: Int = 0,
    val deletedCount: Int = 0,
    val duplicateWastedBytes: Long = 0,
    val storageUsedPercent: Float = 0f
)
