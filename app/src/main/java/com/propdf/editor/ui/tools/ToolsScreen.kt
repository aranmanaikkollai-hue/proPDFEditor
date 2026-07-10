package com.propdf.editor.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class ToolItem(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val route: String? = null,
    val isNew: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onOpenScanner: () -> Unit,
    onOpenStorageAnalyzer: () -> Unit,
    onOpenDuplicateFinder: () -> Unit,
    onOpenRecentActivity: () -> Unit,
    openLegacyTools: () -> Unit
) {
    val documentTools = listOf(
        ToolItem("Storage Analyzer", "Analyze storage usage and find large files", Icons.Default.Analytics, "storage_analyzer"),
        ToolItem("Duplicate Finder", "Find and remove duplicate PDFs", Icons.Default.ContentCopy, "duplicate_finder", isNew = true),
        ToolItem("Recent Activity", "View all document activities", Icons.Default.History, "recent_activity"),
        ToolItem("Hidden Files", "Manage hidden documents", Icons.Default.VisibilityOff, "hidden_files"),
        ToolItem("Folder Browser", "Browse files by folder", Icons.Default.Folder, "folder_browser")
    )

    val pdfTools = listOf(
        ToolItem("Merge PDFs", "Combine multiple PDFs into one", Icons.Default.MergeType),
        ToolItem("Split PDF", "Extract pages from a PDF", Icons.Default.CallSplit),
        ToolItem("Compress PDF", "Reduce file size", Icons.Default.Compress),
        ToolItem("Watermark", "Add text or image watermarks", Icons.Default.WaterDrop),
        ToolItem("Page Numbers", "Add page numbering", Icons.Default.FormatListNumbered),
        ToolItem("Encrypt PDF", "Password protect your PDFs", Icons.Default.Lock),
        ToolItem("Extract Text", "OCR and text extraction", Icons.Default.TextFields)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "Document Manager",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            items(documentTools) { tool ->
                ToolCard(tool) {
                    when (tool.route) {
                        "storage_analyzer" -> onOpenStorageAnalyzer()
                        "duplicate_finder" -> onOpenDuplicateFinder()
                        "recent_activity" -> onOpenRecentActivity()
                        "hidden_files" -> { /* Navigate to hidden files */ }
                        "folder_browser" -> { /* Navigate to folder browser */ }
                        else -> {}
                    }
                }
            }

            item {
                Text(
                    "PDF Tools",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(pdfTools) { tool ->
                ToolCard(tool) {
                    openLegacyTools()
                }
            }
        }
    }
}

@Composable
fun ToolCard(tool: ToolItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        ListItem(
            headlineContent = {
                Row {
                    Text(tool.name)
                    if (tool.isNew) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text("NEW", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            },
            supportingContent = { Text(tool.description) },
            leadingContent = {
                Icon(tool.icon, null, tint = MaterialTheme.colorScheme.primary)
            },
            trailingContent = {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
            }
        )
    }
}
