package com.propdf.editor.ui.files

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.PdfDocument
import com.propdf.core.domain.model.StorageAnalysis
import com.propdf.editor.ui.components.EmptyState
import com.propdf.editor.ui.components.LoadingOverlay
import com.propdf.editor.ui.home.formatFileSize
import com.propdf.editor.ui.theme.pdf_blue
import com.propdf.editor.ui.theme.pdf_green
import com.propdf.editor.ui.theme.pdf_orange
import com.propdf.editor.ui.theme.pdf_red
import com.propdf.editor.ui.theme.pdf_teal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageAnalyzerScreen(
    onOpenDocument: (PdfDocument) -> Unit,
    onBack: () -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    LaunchedEffect(Unit) {
        viewModel.analyzeStorage()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage Analyzer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.storageAnalysis == null -> {
                    LoadingOverlay("Analyzing storage...")
                }
                uiState.storageAnalysis == null -> {
                    EmptyState(
                        icon = Icons.Default.Storage,
                        title = "No data",
                        subtitle = "Unable to analyze storage"
                    )
                }
                else -> {
                    StorageAnalysisContent(
                        analysis = uiState.storageAnalysis!!,
                        onOpenDocument = onOpenDocument,
                        onFindDuplicates = { viewModel.findDuplicates() }
                    )
                }
            }
        }
    }
}

@Composable
private fun StorageAnalysisContent(
    analysis: StorageAnalysis,
    onOpenDocument: (PdfDocument) -> Unit,
    onFindDuplicates: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage Overview Card
        StorageOverviewCard(analysis)
        
        // PDF Stats
        PdfStatsCard(analysis)
        
        // Largest Files
        if (analysis.largestFiles.isNotEmpty()) {
            LargestFilesCard(analysis.largestFiles, onOpenDocument)
        }
        
        // Duplicates
        DuplicateSummaryCard(analysis, onFindDuplicates)
        
        // Collection Breakdown
        if (analysis.collectionBreakdown.isNotEmpty()) {
            CollectionBreakdownCard(analysis.collectionBreakdown)
        }
    }
}

@Composable
private fun StorageOverviewCard(analysis: StorageAnalysis) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "Storage Overview",
                style = MaterialTheme.typography.titleLarge
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val usedPercent = (analysis.usedStorageBytes.toFloat() / analysis.totalStorageBytes * 100).toInt()
            val pdfPercent = (analysis.pdfFilesBytes.toFloat() / analysis.totalStorageBytes * 100)
            
            // Progress bar
            LinearProgressIndicator(
                progress = { usedPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = pdf_blue,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Total", formatFileSize(analysis.totalStorageBytes), pdf_teal)
                StatItem("Used", formatFileSize(analysis.usedStorageBytes), pdf_orange)
                StatItem("PDFs", formatFileSize(analysis.pdfFilesBytes), pdf_blue)
            }
        }
    }
}

@Composable
private fun PdfStatsCard(analysis: StorageAnalysis) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("PDF Statistics", style = MaterialTheme.typography.titleLarge)
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    icon = Icons.Default.PictureAsPdf,
                    value = analysis.pdfFileCount.toString(),
                    label = "Files"
                )
                StatItem(
                    icon = Icons.Default.ContentCopy,
                    value = analysis.duplicateGroupCount.toString(),
                    label = "Duplicate Groups"
                )
                StatItem(
                    icon = Icons.Default.Warning,
                    value = formatFileSize(analysis.duplicateFilesBytes),
                    label = "Wasted Space"
                )
            }
        }
    }
}

@Composable
private fun LargestFilesCard(
    files: List<PdfDocument>,
    onOpenDocument: (PdfDocument) -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Largest Files", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Top ${files.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            files.forEach { doc ->
                ListItem(
                    headlineContent = { Text(doc.displayName, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                    supportingContent = { Text("${doc.pageCount} pages") },
                    trailingContent = { Text(formatFileSize(doc.sizeBytes), color = pdf_red) },
                    leadingContent = {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            null,
                            tint = pdf_blue
                        )
                    },
                    modifier = Modifier.clickable { onOpenDocument(doc) }
                )
            }
        }
    }
}

@Composable
private fun DuplicateSummaryCard(
    analysis: StorageAnalysis,
    onFindDuplicates: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (analysis.duplicateGroupCount > 0) 
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else 
                MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Duplicate Files", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${analysis.duplicateGroupCount} groups found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (analysis.duplicateGroupCount > 0) {
                    Button(onClick = onFindDuplicates) {
                        Text("Review")
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            null,
                            modifier = Modifier.padding(start = 4.dp).size(18.dp)
                        )
                    }
                }
            }
            
            if (analysis.duplicateFilesBytes > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "You can free up ${formatFileSize(analysis.duplicateFilesBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun CollectionBreakdownCard(breakdown: Map<String, Long>) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Folder Breakdown", style = MaterialTheme.typography.titleLarge)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            breakdown.entries.sortedByDescending { it.value }.forEach { (folder, size) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = pdf_teal,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        folder,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        formatFileSize(size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    icon: ImageVector? = null,
    value: String,
    label: String,
    color: Color = MaterialTheme.colorScheme.onSurface
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = color
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
