package com.propdf.editor.ui.tools

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(navController: NavController) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Tools", style = MaterialTheme.typography.titleLarge) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    "PDF Tools",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            val pdfTools = listOf(
                ToolItem("Merge PDFs", "Combine multiple PDFs into one", Icons.Outlined.MergeType, pdf_blue),
                ToolItem("Split PDF", "Extract pages from a PDF", Icons.Outlined.ContentCut, pdf_green),
                ToolItem("Compress PDF", "Reduce file size", Icons.Outlined.Compress, pdf_orange),
                ToolItem("Convert to Images", "Export PDF pages as images", Icons.Outlined.Image, pdf_purple),
                ToolItem("Add Password", "Protect with encryption", Icons.Outlined.Lock, pdf_teal),
                ToolItem("Remove Password", "Decrypt protected PDFs", Icons.Outlined.LockOpen, pdf_red),
                ToolItem("Rotate Pages", "Change page orientation", Icons.Outlined.RotateRight, pdf_amber),
                ToolItem("Reorder Pages", "Change page order", Icons.Outlined.Reorder, pdf_blue)
            )

            items(pdfTools.size) { index ->
                ToolCard(tool = pdfTools[index], onClick = { /* Navigate to tool */ })
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Document Tools",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            val docTools = listOf(
                ToolItem("OCR Text", "Extract text from images", Icons.Outlined.TextFields, pdf_green),
                ToolItem("Sign Document", "Add digital signature", Icons.Outlined.Gesture, pdf_blue),
                ToolItem("Watermark", "Add text or image watermark", Icons.Outlined.WaterDrop, pdf_teal),
                ToolItem("Page Numbers", "Add page numbering", Icons.Outlined.FormatListNumbered, pdf_purple)
            )

            items(docTools.size) { index ->
                ToolCard(tool = docTools[index], onClick = { /* Navigate to tool */ })
            }
        }
    }
}

data class ToolItem(
    val name: String,
    val description: String,
    val icon: ImageVector,
    val color: androidx.compose.ui.graphics.Color
)

@Composable
fun ToolCard(tool: ToolItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = tool.color.copy(alpha = 0.12f),
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        tool.icon,
                        null,
                        tint = tool.color,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.labelLarge)
                Text(
                    tool.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
