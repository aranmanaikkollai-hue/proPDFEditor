package com.propdf.editor.ui.home

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.navigation.NavController
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.domain.model.*
import com.propdf.editor.ui.components.*
import com.propdf.editor.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    mainViewModel: MainViewModel,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

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
                    onClick = { /* Open file picker */ },
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
                item { QuickActionsRow(navController) }
                item { SectionTitle("Folders") }
                item { FoldersRow(uiState.folders, navController) }
                item { SectionTitle("Recent Files") }
                items(uiState.recentFiles.take(5)) { doc ->
                    DocumentListItem(
                        document = doc,
                        onClick = { mainViewModel.openPdfString(doc.uri.toString()) },
                        onFavoriteClick = { viewModel.toggleFavorite(doc.id, doc.isFavorite) }
                    )
                }
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
            Text("Storage Overview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
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
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}

@Composable
fun QuickActionsRow(navController: NavController) {
    val actions = listOf(
        QuickAction("Open", Icons.Default.FolderOpen, pdf_blue, "folders"),
        QuickAction("Favorites", Icons.Default.Star, pdf_amber, "favorites"),
        QuickAction("Cloud", Icons.Default.Cloud, pdf_teal, "cloud"),
        QuickAction("Recycle Bin", Icons.Default.Delete, pdf_red, "recyclebin")
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(actions) { action ->
            Card(
                modifier = Modifier.width(85.dp).aspectRatio(1f).clickable { navController.navigate(action.route) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = action.color.copy(alpha = 0.1f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(imageVector = action.icon, contentDescription = action.label, tint = action.color, modifier = Modifier.size(28.dp))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = action.label, style = MaterialTheme.typography.labelSmall, color = action.color)
                }
            }
        }
    }
}

data class QuickAction(val label: String, val icon: ImageVector, val color: Color, val route: String)

@Composable
fun FoldersRow(folders: List<Folder>, navController: NavController) {
    if (folders.isEmpty()) {
        EmptyStateMini(Icons.Default.FolderOpen, "No folders yet", "Create folders to organize your PDFs")
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(folders) { folder ->
                Card(
                    modifier = Modifier.width(140.dp).clickable { navController.navigate("folder/${folder.id}") },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(folder.color).copy(alpha = 0.15f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = Color(folder.color), modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(folder.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${folder.documentCount} files", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
fun CategoriesGrid(navController: NavController) {
    val categories = DocumentCategory.values().filter { it != DocumentCategory.UNCATEGORIZED }
    val colors = listOf(pdf_blue, pdf_green, pdf_orange, pdf_purple, pdf_teal, pdf_red, pdf_amber)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categories.chunked(2).forEachIndexed { rowIndex, rowCats ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowCats.forEachIndexed { index, cat ->
                    val color = colors[(rowIndex * 2 + index) % colors.size]
                    CategoryChip(cat.displayName, color, Modifier.weight(1f)) {
                        navController.navigate("category/${cat.name}")
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(name: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(modifier = Modifier.width(8.dp))
            Text(name, style = MaterialTheme.typography.labelMedium, color = color)
        }
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
fun EmptyStateMini(icon: ImageVector, title: String, subtitle: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        Spacer(modifier = Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    }
}
