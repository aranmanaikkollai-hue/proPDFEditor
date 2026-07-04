package com.propdf.editor.ui.tools

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.theme.*

data class ToolCategory(
    val title: String,
    val tools: List<ToolItem>
)

data class ToolItem(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val route: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    navController: NavController,
    onOpenPdfPicker: () -> Unit
) {
    val categories = listOf(
        ToolCategory("Page Operations", listOf(
            ToolItem("Page Editor", "Delete, duplicate, move, reorder, extract pages", Icons.Default.Edit, "page_editor"),
            ToolItem("Rotate Pages", "Rotate specific pages", Icons.Default.RotateRight, "rotate"),
            ToolItem("Crop Pages", "Crop page margins", Icons.Default.Crop, "crop"),
            ToolItem("Resize Pages", "Change page dimensions", Icons.Default.AspectRatio, "resize"),
            ToolItem("Mirror Pages", "Flip pages horizontally or vertically", Icons.Default.Flip, "mirror")
        )),
        ToolCategory("Insert & Combine", listOf(
            ToolItem("Insert Blank Page", "Add empty pages", Icons.Default.Add, "insert_blank"),
            ToolItem("Insert Image", "Embed images into PDF", Icons.Default.Image, "insert_image"),
            ToolItem("Insert PDF", "Merge pages from another PDF", Icons.Default.Description, "insert_pdf"),
            ToolItem("Merge PDFs", "Combine multiple PDFs", Icons.AutoMirrored.Filled.MergeType, "merge"),
            ToolItem("Images to PDF", "Convert images to PDF", Icons.Default.PhotoLibrary, "images_to_pdf")
        )),
        ToolCategory("Split & Organize", listOf(
            ToolItem("Split by Size", "Split into size-limited files", Icons.Default.Storage, "split_size"),
            ToolItem("Split by Bookmark", "Split using bookmarks", Icons.Default.Bookmark, "split_bookmark"),
            ToolItem("Split Every N Pages", "Split at regular intervals", Icons.Default.CallSplit, "split_n")
        )),
        ToolCategory("Document Enhancement", listOf(
            ToolItem("Page Numbers", "Add automatic page numbering", Icons.Default.FormatListNumbered, "page_numbers"),
            ToolItem("Header & Footer", "Add headers and footers", Icons.Default.Title, "header_footer"),
            ToolItem("Watermark", "Add text or image watermarks", Icons.Default.WaterDrop, "watermark"),
            ToolItem("Background", "Add colored or image backgrounds", Icons.Default.Wallpaper, "background")
        )),
        ToolCategory("Optimization", listOf(
            ToolItem("Compress PDF", "Reduce file size", Icons.Default.Compress, "compress"),
            ToolItem("Optimize PDF", "Clean up and optimize", Icons.Default.CleaningServices, "optimize")
        ))
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PDF Tools") },
                navigationIcon = {
                    IconButton(onClick = { navController.navigateUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                FilledTonalButton(
                    onClick = onOpenPdfPicker,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null)
                    Text("Select PDF to Edit", modifier = Modifier.padding(start = 8.dp))
                }
            }

            categories.forEach { category ->
                item {
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(category.tools) { tool ->
                    ToolCard(tool = tool, onClick = {
                        when (tool.route) {
                            "page_editor" -> onOpenPdfPicker()
                            else -> navController.navigate("tools/${tool.route}")
                        }
                    })
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolCard(tool: ToolItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleSmall
                )
            },
            supportingContent = {
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            leadingContent = {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingContent = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "Go",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }
}
