package com.propdf.editor.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(navController: NavController) {
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(800)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ProPDF Editor", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
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
                    onClick = { },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Scan")
                }
                FloatingActionButton(
                    onClick = { },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Create PDF")
                }
            }
        }
    ) { padding ->
        AnimatedVisibility(visible = isLoading, enter = fadeIn(), exit = fadeOut()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp), color = MaterialTheme.colorScheme.primary)
                    Text("Loading your workspace...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        AnimatedVisibility(visible = !isLoading, enter = fadeIn(tween(500)) + slideInVertically(), exit = fadeOut()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { QuickActionsRow() }
                item { SectionTitle("Recent Files") }
                item { RecentFilesCarousel() }
                item { SectionTitle("Tools") }
                item { ToolsGrid() }
                item { SectionTitle("Storage") }
                item { StorageOverviewCard() }
            }
        }
    }
}

@Composable
fun QuickActionsRow() {
    val actions = listOf(
        QuickAction("Open", Icons.Default.FolderOpen, pdf_blue),
        QuickAction("Create", Icons.Default.Create, pdf_green),
        QuickAction("Scan", Icons.Default.Scanner, pdf_orange),
        QuickAction("Merge", Icons.Default.MergeType, pdf_purple)
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        items(actions) { action ->
            val scale by animateFloatAsState(1f, tween(300))
            Card(
                modifier = Modifier.width(100.dp).aspectRatio(1f).scale(scale).clickable { },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = action.color.copy(alpha = 0.1f)),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(imageVector = action.icon, contentDescription = action.label, tint = action.color, modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = action.label, style = MaterialTheme.typography.labelMedium, color = action.color)
                }
            }
        }
    }
}

data class QuickAction(val label: String, val icon: ImageVector, val color: Color)

@Composable
fun SectionTitle(title: String) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 8.dp))
}

@Composable
fun RecentFilesCarousel() {
    val files = remember {
        listOf(
            PdfFile("Contract.pdf", "2.4 MB", "Today", pdf_red),
            PdfFile("Invoice_2024.pdf", "1.1 MB", "Yesterday", pdf_blue),
            PdfFile("Report_Q1.pdf", "5.8 MB", "2 days ago", pdf_green),
            PdfFile("Manual.pdf", "12.3 MB", "Last week", pdf_orange)
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
        items(files) { file ->
            Card(
                modifier = Modifier.width(160.dp).clickable { },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(12.dp)).background(file.color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                        Icon(imageVector = Icons.Default.FolderOpen, contentDescription = null, tint = file.color, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = file.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(text = "${file.size} · ${file.date}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

data class PdfFile(val name: String, val size: String, val date: String, val color: Color)

@Composable
fun ToolsGrid() {
    val tools = listOf(
        ToolItem("Merge", Icons.Default.MergeType, pdf_blue, "Merge multiple PDFs into one"),
        ToolItem("Split", Icons.Default.Speed, pdf_green, "Extract pages from PDF"),
        ToolItem("Compress", Icons.Default.Speed, pdf_orange, "Reduce file size"),
        ToolItem("Protect", Icons.Default.Security, pdf_red, "Password & encryption"),
        ToolItem("OCR", Icons.Default.Scanner, pdf_teal, "Text recognition"),
        ToolItem("Annotate", Icons.Default.Create, pdf_purple, "Add notes & highlights")
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.chunked(2).forEach { rowTools ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowTools.forEach { tool ->
                    ToolCard(tool = tool, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

data class ToolItem(val name: String, val icon: ImageVector, val color: Color, val desc: String)

@Composable
fun ToolCard(tool: ToolItem, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.clickable { },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(tool.color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                Icon(imageVector = tool.icon, contentDescription = tool.name, tint = tool.color, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = tool.name, style = MaterialTheme.typography.labelLarge)
                Text(text = tool.desc, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun StorageOverviewCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = "Storage Overview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StorageStat("PDFs", "24", pdf_blue)
                StorageStat("Total Size", "156 MB", pdf_green)
                StorageStat("Favorites", "8", pdf_orange)
            }
        }
    }
}

@Composable
fun StorageStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
    }
}
