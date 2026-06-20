package com.propdf.editor.ui.files

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.with
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.ui.components.FilesGridSkeleton
import com.propdf.editor.ui.components.FilesListSkeleton
import com.propdf.editor.ui.home.formatFileSize
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.theme.pdf_blue
import com.propdf.editor.ui.theme.pdf_teal

enum class FileViewMode { LIST, GRID }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun FilesScreen(viewModel: MainViewModel, onOpenPdf: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var viewMode by remember { mutableStateOf(FileViewMode.LIST) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Files") },
                actions = {
                    IconButton(
                        onClick = { viewMode = if (viewMode == FileViewMode.LIST) FileViewMode.GRID else FileViewMode.LIST },
                        modifier = Modifier.semantics {
                            contentDescription = if (viewMode == FileViewMode.LIST) "Switch to grid view" else "Switch to list view"
                        }
                    ) {
                        Icon(
                            if (viewMode == FileViewMode.LIST) Icons.Default.GridView else Icons.Default.ViewList,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onOpenPdf, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Open PDF") })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.searchQuery, onValueChange = viewModel::setSearchQuery,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true, label = { Text("Search offline library") },
                shape = RoundedCornerShape(16.dp)
            )
            AnimatedContent(
                targetState = state.isLoading to viewMode,
                transitionSpec = { fadeIn(tween(220)) with fadeOut(tween(220)) },
                label = "files_content"
            ) { (isLoading, mode) ->
                when {
                    isLoading -> when (mode) { FileViewMode.LIST -> FilesListSkeleton(); FileViewMode.GRID -> FilesGridSkeleton() }
                    state.files.isEmpty() -> EmptyFilesCard(onOpenPdf)
                    else -> when (mode) {
                        FileViewMode.LIST -> FilesListView(
                            files = state.files,
                            onFileClick = { viewModel.openPdfString(it.uri) },
                            onFavoriteClick = { viewModel.toggleFavourite(it.uri) }
                        )
                        FileViewMode.GRID -> FilesGridView(
                            files = state.files,
                            onFileClick = { viewModel.openPdfString(it.uri) },
                            onFavoriteClick = { viewModel.toggleFavourite(it.uri) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FilesListView(files: List<RecentFile>, onFileClick: (RecentFile) -> Unit, onFavoriteClick: (RecentFile) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(files, key = { it.uri }) { file ->
            RecentFileRow(file = file, onClick = { onFileClick(file) }, onFavorite = { onFavoriteClick(file) })
        }
    }
}

@Composable
fun FilesGridView(files: List<RecentFile>, onFileClick: (RecentFile) -> Unit, onFavoriteClick: (RecentFile) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp), modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(files, key = { it.uri }) { file ->
            FileGridCard(file = file, onClick = { onFileClick(file) }, onFavorite = { onFavoriteClick(file) })
        }
    }
}

@Composable
fun FileGridCard(file: RecentFile, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp))
                .background(pdf_blue.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PictureAsPdf, null, tint = pdf_blue, modifier = Modifier.size(48.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(file.displayName, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth())
            Text(formatFileSize(file.fileSizeBytes), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            IconButton(onClick = onFavorite) {
                Icon(
                    if (file.isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    if (file.isFavourite) "Remove favorite" else "Add favorite",
                    tint = if (file.isFavourite) pdf_teal else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun RecentFileRow(file: RecentFile, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .background(pdf_blue.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PictureAsPdf, null, tint = pdf_blue, modifier = Modifier.size(24.dp))
            }
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text("${formatFileSize(file.fileSizeBytes)} · ${file.pageCount} pages",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) {
                Icon(if (file.isFavourite) Icons.Default.Star else Icons.Default.StarBorder,
                    "Favorite", tint = if (file.isFavourite) pdf_teal else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun EmptyFilesCard(onOpenPdf: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Card(shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(modifier = Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(16.dp))
                Text("Build your offline workspace", style = MaterialTheme.typography.titleMedium)
                Text("Open PDFs with scoped storage access. Files stay on this device.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(top = 8.dp))
                Spacer(modifier = Modifier.height(24.dp))
                ExtendedFloatingActionButton(onClick = onOpenPdf, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Open PDF") })
            }
        }
    }
}
