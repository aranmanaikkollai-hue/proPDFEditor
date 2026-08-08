package com.propdfeditor.ui.tools

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHubScreen(
    onNavigateToCompression: () -> Unit,
    onNavigateToOcr: () -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val tools = listOf(
        ToolItem("Compress", Icons.Default.Compress, onNavigateToCompression),
        ToolItem("OCR", Icons.Default.TextFields, onNavigateToOcr),
        ToolItem("Merge", Icons.Default.MergeType, onNavigateToMerge),
        ToolItem("Split", Icons.Default.CallSplit, onNavigateToSplit),
        ToolItem("Protect", Icons.Default.Security, {}),
        ToolItem("Sign", Icons.Default.Draw, {}),
        ToolItem("Redact", Icons.Default.FormatColorReset, {}),
        ToolItem("Compare", Icons.Default.CompareArrows, {}),
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 120.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(tools, key = { it.name }) { tool ->
                ToolCard(tool = tool)
            }
        }
    }
}

@Composable
private fun ToolCard(tool: ToolItem) {
    ElevatedCard(
        onClick = tool.onClick,
        modifier = Modifier.aspectRatio(1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = tool.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = tool.name,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private data class ToolItem(
    val name: String,
    val icon: ImageVector,
    val onClick: () -> Unit
)
