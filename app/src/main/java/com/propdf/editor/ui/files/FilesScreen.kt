package com.propdf.editor.ui.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.RecentFile
import com.propdf.editor.ui.home.formatFileSize
import com.propdf.editor.ui.main.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(viewModel: MainViewModel, onOpenPdf: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { TopAppBar(title = { Text("Files") }) },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = onOpenPdf, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Open PDF") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    singleLine = true,
                    label = { Text("Search offline library") }
                )
            }
            if (state.files.isEmpty()) {
                item { EmptyFilesCard(onOpenPdf) }
            } else {
                items(state.files, key = { it.uri }) { file -> RecentFileRow(file, { viewModel.openPdfString(file.uri) }, { viewModel.toggleFavourite(file.uri) }) }
            }
        }
    }
}

@Composable
private fun RecentFileRow(file: RecentFile, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                Text(file.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(2.dp))
                Text("${formatFileSize(file.fileSizeBytes)} · ${file.pageCount} pages", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onFavorite) { Icon(if (file.isFavourite) Icons.Default.Star else Icons.Default.StarBorder, contentDescription = "Favorite") }
        }
    }
}

@Composable
private fun EmptyFilesCard(onOpenPdf: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Build your offline workspace", style = MaterialTheme.typography.titleMedium)
            Text("Open PDFs with scoped storage access. Files stay on this device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.height(16.dp))
            ExtendedFloatingActionButton(onClick = onOpenPdf, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Open PDF") })
        }
    }
}
