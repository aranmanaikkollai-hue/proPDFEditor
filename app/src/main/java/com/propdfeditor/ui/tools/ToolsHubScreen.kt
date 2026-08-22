package com.propdfeditor.ui.tools

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * Protect/Sign/Redact previously had empty `{}` onClick callbacks -- tapping them did
 * nothing at all. They need a source PDF before they can do anything, and unlike
 * Compress/OCR/Merge/Split (which have their own in-screen file pickers), there was no
 * document context here. Each now launches a SAF document picker and, once a PDF is
 * picked, hands off to the Security Hub (which does the actual work -- see
 * SecurityViewModel/SecurityHubScreen). Compare has no underlying implementation
 * anywhere in the codebase, so instead of a silent no-op it now shows an honest
 * "coming soon" message.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsHubScreen(
    onNavigateToCompression: () -> Unit,
    onNavigateToOcr: () -> Unit,
    onNavigateToMerge: () -> Unit,
    onNavigateToSplit: () -> Unit,
    onNavigateToSecurity: (Uri) -> Unit,
    onNavigateBack: () -> Unit
) {
    var comingSoonFeature by remember { mutableStateOf<String?>(null) }
    val pickDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { onNavigateToSecurity(it) }
    }

    val tools = listOf(
        ToolItem("Compress", Icons.Default.Compress) { onNavigateToCompression() },
        ToolItem("OCR", Icons.Default.TextFields) { onNavigateToOcr() },
        ToolItem("Merge", Icons.Default.MergeType) { onNavigateToMerge() },
        ToolItem("Split", Icons.Default.CallSplit) { onNavigateToSplit() },
        ToolItem("Protect", Icons.Default.Security) { pickDocumentLauncher.launch(arrayOf("application/pdf")) },
        ToolItem("Sign", Icons.Default.Draw) { pickDocumentLauncher.launch(arrayOf("application/pdf")) },
        ToolItem("Redact", Icons.Default.FormatColorReset) { pickDocumentLauncher.launch(arrayOf("application/pdf")) },
        ToolItem("Compare", Icons.Default.CompareArrows) { comingSoonFeature = "Compare" },
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

    comingSoonFeature?.let { feature ->
        AlertDialog(
            onDismissRequest = { comingSoonFeature = null },
            title = { Text(feature) },
            text = { Text("$feature isn't available yet. It's on the roadmap.") },
            confirmButton = {
                TextButton(onClick = { comingSoonFeature = null }) { Text("OK") }
            }
        )
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
