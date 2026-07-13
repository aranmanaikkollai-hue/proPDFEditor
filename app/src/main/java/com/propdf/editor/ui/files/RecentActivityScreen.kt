package com.propdf.editor.ui.files

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentActivityScreen(
    navController: NavController,
    viewModel: RecentActivityViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent Activity") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(uiState.activities) { activity ->
                ActivityItem(activity)
            }
        }
    }
}

@Composable
private fun ActivityItem(activity: ActivityItem) {
    ListItem(
        headlineContent = { Text(activity.description) },
        supportingContent = { Text(activity.timestamp) },
        leadingContent = {
            Icon(
                when (activity.type) {
                    ActivityType.OPENED -> Icons.Default.OpenInNew
                    ActivityType.EDITED -> Icons.Default.Edit
                    ActivityType.DELETED -> Icons.Default.Delete
                    ActivityType.SHARED -> Icons.Default.Share
                    ActivityType.CREATED -> Icons.Default.Add
                },
                contentDescription = null
            )
        }
    )
}

data class ActivityItem(
    val description: String,
    val timestamp: String,
    val type: ActivityType
)

enum class ActivityType {
    OPENED, EDITED, DELETED, SHARED, CREATED
}

class RecentActivityViewModel : androidx.lifecycle.ViewModel() {
    data class UiState(
        val activities: List<ActivityItem> = emptyList()
    )
    private val _uiState = androidx.compose.runtime.mutableStateOf(UiState())
    val uiState: androidx.compose.runtime.State<UiState> = _uiState
}
