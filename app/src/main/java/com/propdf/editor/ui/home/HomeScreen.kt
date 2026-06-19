package com.propdf.editor.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.with
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.ui.components.HomeSkeletonLoader
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.theme.pdf_amber
import com.propdf.editor.ui.theme.pdf_blue
import com.propdf.editor.ui.theme.pdf_green
import com.propdf.editor.ui.theme.pdf_orange
import com.propdf.editor.ui.theme.pdf_purple
import com.propdf.editor.ui.theme.pdf_red
import com.propdf.editor.ui.theme.pdf_teal

// Domain models referenced from core module
data class StorageStats(
    val totalDocuments: Int = 0,
    val totalSize: Long = 0,
    val favoriteCount: Int = 0,
    val deletedCount: Int = 0,
    val storageUsedPercent: Float = 0f
)

data class Folder(
    val id: Long = 0,
    val name: String = "",
    val color: Long = 0xFF1E88E5,
    val documentCount: Int = 0
)

enum class DocumentCategory(val displayName: String) {
    WORK("Work"),
    PERSONAL("Personal"),
    EDUCATION("Education"),
    FINANCE("Finance"),
    HEALTH("Health"),
    UNCATEGORIZED("Uncategorized")
}

data class PdfDocument(
    val id: Long = 0,
    val uri: String = "",
    val displayName: String = "",
    val fileSize: Long = 0,
    val pageCount: Int = 0,
    val lastOpenedAt: Long = 0,
    val isFavorite: Boolean = false,
    val isDeleted: Boolean = false,
    val category: DocumentCategory = DocumentCategory.UNCATEGORIZED,
    val cloudProvider: String? = null
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun HomeScreen(
    navController: NavController,
    mainViewModel: MainViewModel,
    onOpenPdf: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "ProPDF Editor",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Your offline workspace",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { navController.navigate("files") },
                        modifier = Modifier.semantics { contentDescription = "Search files" }
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                    }
                    IconButton(
                        onClick = { navController.navigate("settings") },
                        modifier = Modifier.semantics { contentDescription = "Settings" }
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallFloatingActionButton(
                    onClick = { navController.navigate("scanner") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.semantics { contentDescription = "Open scanner" }
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null)
                }
                FloatingActionButton(
                    onClick = onOpenPdf,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.semantics { contentDescription = "Open PDF file" }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
        }
    ) { padding ->
        AnimatedContent(
            targetState = uiState.isLoading,
            transitionSpec = {
                fadeIn() with fadeOut()
            },
            label = "home_content"
        ) { isLoading ->
            if (isLoading) {
                HomeSkeletonLoader()
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Continue Reading Section (NEW)
                    val lastRead = uiState.recentFiles.firstOrNull()
                    if (lastRead != null) {
                        item {
                            ContinueReadingCard(
                                document = lastRead,
                                onClick = { mainViewModel.openPdfString(lastRead.uri) }
                            )
                        }
                    }

                    // Storage Overview
                    item {
                        StorageOverviewCard(
                            stats = uiState.storageStats,
                            onClick = { navController.navigate("files") }
                        )
                    }

                    // Quick Actions
                    item {
                        QuickActionsRow(navController)
                    }

                    // Recent Scans (NEW)
                    if (uiState.recentScans.isNotEmpty()) {
                        item { SectionTitle("Recent Scans") }
                        item {
                            RecentScansRow(
                                scans = uiState.recentScans,
                                onScanClick = { mainViewModel.openPdfString(it.uri) }
                            )
                        }
                    }

                    // Favorites (NEW)
                    val favorites = uiState.recentFiles.filter { it.isFavorite }
                    if (favorites.isNotEmpty()) {
                        item { SectionTitle("Favorites") }
                        items(favorites.take(3)) { doc ->
                            CompactDocumentItem(
                                document = doc,
                                onClick = { mainViewModel.openPdfString(doc.uri) },
                                onFavoriteClick = { viewModel.toggleFavorite(doc.id, doc.isFavorite) }
                            )
                        }
                    }

                    // Folders
                    item { SectionTitle("Folders") }
                    item {
                        FoldersRow(
                            folders = uiState.folders,
                            navController = navController
                        )
                    }

                    // Recent Files
                    item { SectionTitle("Recent Files") }
                    items(uiState.recentFiles.take(5)) { doc ->
                        DocumentListItem(
                            document = doc,
                            onClick = { mainViewModel.openPdfString(doc.uri) },
                            onFavoriteClick = { viewModel.toggleFavorite(doc.id, doc.isFavorite) }
                        )
                    }

                    // Categories
                    item { SectionTitle("Categories") }
                    item {
                        CategoriesGrid(navController)
                    }
                }
            }
        }
    }
}

// ===================== NEW COMPONENTS =====================

@Composable
fun ContinueReadingCard(
    document: PdfDocument,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Continue Reading",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
                Text(
                    document.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "Page 1 of ${document.pageCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
fun RecentScansRow(
    scans: List<PdfDocument>,
    onScanClick: (PdfDocument) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        items(scans) { scan ->
            ScanThumbnailCard(
                document = scan,
                onClick = { onScanClick(scan) }
            )
        }
    }
}

@Composable
fun ScanThumbnailCard(
    document: PdfDocument,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.width(120.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.75f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(pdf_teal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhotoCamera,
                    contentDescription = null,
                    tint = pdf_teal,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = document.displayName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatFileSize(document.fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun CompactDocumentItem(
    document: PdfDocument,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(pdf_amber.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null,
                    tint = pdf_amber,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "Remove from favorites",
                    tint = pdf_amber
                )
            }
        }
    }
}

// ===================== EXISTING COMPONENTS (ENHANCED) =====================

@Composable
fun StorageOverviewCard(
    stats: StorageStats,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Storage Overview",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                TextButton(onClick = onClick) {
                    Text("View All", style = MaterialTheme.typography.labelMedium)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            // Storage usage bar (NEW)
            LinearProgressIndicator(
                progress = { stats.storageUsedPercent.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.1f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "${(stats.storageUsedPercent * 100).toInt()}% used",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem("${stats.totalDocuments}", "Documents", pdf_blue)
                StatItem(formatFileSize(stats.totalSize), "Total Size", pdf_green)
                StatItem("${stats.favoriteCount}", "Favorites", pdf_orange)
                StatItem("${stats.deletedCount}", "Recycle Bin", pdf_red)
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
fun QuickActionsRow(navController: NavController, modifier: Modifier = Modifier) {
    val actions = listOf(
        QuickAction("Files", Icons.Default.FolderOpen, pdf_blue, "files"),
        QuickAction("Favorites", Icons.Default.Star, pdf_amber, "files"),
        QuickAction("Scanner", Icons.Default.CameraAlt, pdf_teal, "scanner"),
        QuickAction("Tools", Icons.Default.Description, pdf_red, "tools")
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
    ) {
        items(actions) { action ->
            Card(
                onClick = { navController.navigate(action.route) },
                modifier = Modifier
                    .width(85.dp)
                    .aspectRatio(1f),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = action.color.copy(alpha = 0.1f)
                ),
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

data class QuickAction(val label: String, val icon: ImageVector, val color: Color, val route: String)

@Composable
fun FoldersRow(
    folders: List<Folder>,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    if (folders.isEmpty()) {
        EmptyStateMini(
            icon = Icons.Default.FolderOpen,
            title = "No folders yet",
            subtitle = "Create folders to organize your PDFs"
        )
    } else {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier
        ) {
            items(folders) { folder ->
                Card(
                    onClick = { navController.navigate("folder/${folder.id}") },
                    modifier = Modifier.width(140.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(folder.color).copy(alpha = 0.15f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = Color(folder.color),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            folder.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${folder.documentCount} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoriesGrid(navController: NavController, modifier: Modifier = Modifier) {
    val categories = DocumentCategory.values().filter { it != DocumentCategory.UNCATEGORIZED }
    val colors = listOf(pdf_blue, pdf_green, pdf_orange, pdf_purple, pdf_teal, pdf_red, pdf_amber)

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
    ) {
        categories.chunked(2).forEachIndexed { rowIndex, rowCats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowCats.forEachIndexed { index, cat ->
                    val color = colors[(rowIndex * 2 + index) % colors.size]
                    CategoryChip(
                        name = cat.displayName,
                        color = color,
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("category/${cat.name}")
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

@Composable
fun DocumentListItem(
    document: PdfDocument,
    onClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val color = when {
        document.isDeleted -> pdf_red
        document.isFavorite -> pdf_amber
        else -> pdf_blue
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
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
                    .background(color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (document.isDeleted) Icons.Default.Bookmark else Icons.Default.Description,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${formatFileSize(document.fileSize)} · ${document.category.displayName}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (document.cloudProvider != null) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "Cloud",
                    tint = pdf_teal,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    imageVector = if (document.isFavorite) Icons.Default.Star else Icons.Default.Bookmark,
                    contentDescription = if (document.isFavorite) "Remove favorite" else "Add favorite",
                    tint = if (document.isFavorite) pdf_amber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(vertical = 8.dp)
    )
}

@Composable
fun EmptyStateMini(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
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

fun formatFileSize(size: Long): String {
    return when {
        size >= 1024 * 1024 * 1024 -> "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
        size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
        size >= 1024 -> "%.1f KB".format(size / 1024.0)
        else -> "$size B"
    }
}
