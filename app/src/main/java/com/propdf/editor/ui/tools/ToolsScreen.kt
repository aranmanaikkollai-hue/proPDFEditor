package com.propdf.editor.ui.tools

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.theme.pdf_amber
import com.propdf.editor.ui.theme.pdf_blue
import com.propdf.editor.ui.theme.pdf_green
import com.propdf.editor.ui.theme.pdf_orange
import com.propdf.editor.ui.theme.pdf_purple
import com.propdf.editor.ui.theme.pdf_red
import com.propdf.editor.ui.theme.pdf_teal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(navController: NavController) {
    var selectedCategory by remember { mutableStateOf<ToolCategory?>(null) }

    val tools = listOf(
        Tool("Merge PDFs", "Combine multiple PDFs into one", Icons.Default.MergeType, pdf_blue, ToolCategory.ORGANIZE),
        Tool("Split PDF", "Extract pages or ranges", Icons.Default.ContentCut, pdf_red, ToolCategory.ORGANIZE),
        Tool("Rotate Pages", "Rotate selected pages", Icons.Default.RotateRight, pdf_orange, ToolCategory.EDIT),
        Tool("Add Text", "Insert text annotations", Icons.Default.TextFields, pdf_green, ToolCategory.EDIT),
        Tool("Add Image", "Insert images into PDF", Icons.Default.Image, pdf_purple, ToolCategory.EDIT),
        Tool("Compress", "Reduce file size", Icons.Default.Straighten, pdf_teal, ToolCategory.OPTIMIZE),
        Tool("Protect", "Password protect PDF", Icons.Default.Lock, pdf_amber, ToolCategory.SECURITY),
        Tool("OCR", "Extract text from scanned PDF", Icons.Default.Search, pdf_blue, ToolCategory.EDIT),
        Tool("Extract Text", "Copy text from PDF", Icons.Default.ContentCopy, pdf_green, ToolCategory.EDIT),
        Tool("Delete Pages", "Remove unwanted pages", Icons.Default.Delete, pdf_red, ToolCategory.ORGANIZE),
        Tool("Share", "Share via Android Sharesheet", Icons.Default.Share, pdf_teal, ToolCategory.EXPORT),
        Tool("Export Images", "Save pages as images", Icons.Default.PictureAsPdf, pdf_purple, ToolCategory.EXPORT),
    )

    val categories = ToolCategory.values().toList()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tools")
                        Text("${tools.size} offline tools available", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                CategoryFilterRow(categories, selectedCategory) { selectedCategory = it }
            }
            val filtered = if (selectedCategory != null) tools.filter { it.category == selectedCategory } else tools
            items(filtered, key = { it.name }) { tool ->
                ToolCard(tool = tool, onClick = {
                    when (tool.name) {
                        "OCR" -> navController.navigate("ocr")
                        "Merge PDFs" -> navController.navigate("merge")
                        "Split PDF" -> navController.navigate("split")
                        else -> {}
                    }
                })
            }
        }
    }
}

enum class ToolCategory(val displayName: String) {
    ALL("All"), EDIT("Edit"), ORGANIZE("Organize"),
    OPTIMIZE("Optimize"), SECURITY("Security"), EXPORT("Export")
}

data class Tool(val name: String, val description: String, val icon: ImageVector, val color: Color, val category: ToolCategory)

@Composable
fun CategoryFilterRow(categories: List<ToolCategory>, selectedCategory: ToolCategory?, onCategorySelected: (ToolCategory?) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CategoryChip("All", selectedCategory == null, Modifier.weight(1f)) { onCategorySelected(null) }
        categories.filter { it != ToolCategory.ALL }.forEach { cat ->
            CategoryChip(cat.displayName, selectedCategory == cat, Modifier.weight(1f)) { onCategorySelected(cat) }
        }
    }
}

@Composable
fun CategoryChip(name: String, isSelected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurfaceVariant

    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.05f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "category_scale"
    )

    Card(
        onClick = onClick, modifier = modifier.scale(scale), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor, contentColor = contentColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 2.dp else 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(name, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun ToolCard(tool: Tool, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                .background(tool.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(tool.icon, null, tint = tool.color, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(tool.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tool.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
