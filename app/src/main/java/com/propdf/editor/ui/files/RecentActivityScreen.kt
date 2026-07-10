package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.propdf.core.domain.model.ActivityAction
import com.propdf.core.domain.model.RecentActivity
import com.propdf.editor.ui.components.EmptyState
import com.propdf.editor.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentActivityScreen(
    onBack: () -> Unit,
    viewModel: DocumentManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    
    LaunchedEffect(Unit) {
        viewModel.loadRecentActivities()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent Activity") },
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
            if (uiState.recentActivities.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.History,
                    title = "No activity yet",
                    subtitle = "Actions you take will appear here"
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.recentActivities) { activity ->
                        ActivityItem(activity, dateFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityItem(
    activity: RecentActivity,
    dateFormat: SimpleDateFormat
) {
    val actionConfig = activity.action.config
    
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Action icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(actionConfig.color.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = actionConfig.icon,
                    contentDescription = null,
                    tint = actionConfig.color,
                    modifier = Modifier.size(24.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    activity.documentName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1
                )
                Text(
                    "${actionConfig.label}${activity.details?.let { ": $it" } ?: ""}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Text(
                dateFormat.format(Date(activity.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private data class ActionConfig(
    val icon: ImageVector,
    val label: String,
    val color: Color
)

private val ActivityAction.config: ActionConfig
    get() = when (this) {
        ActivityAction.OPENED -> ActionConfig(Icons.Default.Visibility, "Opened", pdf_blue)
        ActivityAction.EDITED -> ActionConfig(Icons.Default.Edit, "Edited", pdf_teal)
        ActivityAction.SHARED -> ActionConfig(Icons.Default.Share, "Shared", pdf_green)
        ActivityAction.RENAMED -> ActionConfig(Icons.Default.DriveFileRenameOutline, "Renamed", pdf_orange)
        ActivityAction.MOVED -> ActionConfig(Icons.Default.DriveFileMove, "Moved", pdf_orange)
        ActivityAction.COPIED -> ActionConfig(Icons.Default.ContentCopy, "Copied", pdf_teal)
        ActivityAction.DELETED -> ActionConfig(Icons.Default.Delete, "Deleted", MaterialTheme.colorScheme.error)
        ActivityAction.RESTORED -> ActionConfig(Icons.Default.Restore, "Restored", pdf_green)
        ActivityAction.FAVORITED -> ActionConfig(Icons.Default.Star, "Favorited", pdf_amber)
        ActivityAction.UNFAVORITED -> ActionConfig(Icons.Default.StarBorder, "Unfavorited", MaterialTheme.colorScheme.onSurfaceVariant)
        ActivityAction.TAGGED -> ActionConfig(Icons.Default.Label, "Tagged", pdf_purple)
        ActivityAction.COLLECTION_ADDED -> ActionConfig(Icons.Default.Folder, "Added to collection", pdf_blue)
        ActivityAction.EXPORTED -> ActionConfig(Icons.Default.FileDownload, "Exported", pdf_teal)
        ActivityAction.PRINTED -> ActionConfig(Icons.Default.Print, "Printed", MaterialTheme.colorScheme.onSurfaceVariant)
    }
