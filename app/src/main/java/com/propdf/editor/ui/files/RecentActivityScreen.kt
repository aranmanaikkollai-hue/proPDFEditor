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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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

@HiltViewModel
class RecentActivityViewModel @Inject constructor(
    private val activityRepository: com.propdf.core.domain.repository.ActivityRepository
) : androidx.lifecycle.ViewModel() {
    data class UiState(
        val activities: List<ActivityItem> = emptyList(),
        val isLoading: Boolean = false
    )
    private val _uiState = androidx.compose.runtime.mutableStateOf(UiState())
    val uiState: androidx.compose.runtime.State<UiState> = _uiState

    private val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

    init {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            activityRepository.getRecentActivities(100).collectLatest { activities ->
                _uiState.value = UiState(
                    activities = activities.map { it.toActivityItem() },
                    isLoading = false
                )
            }
        }
    }

    private fun com.propdf.core.domain.model.RecentActivity.toActivityItem(): ActivityItem {
        val verb = when (action) {
            com.propdf.core.domain.model.ActivityAction.OPENED -> "Opened"
            com.propdf.core.domain.model.ActivityAction.SHARED -> "Shared"
            com.propdf.core.domain.model.ActivityAction.DELETED -> "Deleted"
            com.propdf.core.domain.model.ActivityAction.RESTORED -> "Restored"
            com.propdf.core.domain.model.ActivityAction.FAVORITED -> "Favorited"
            com.propdf.core.domain.model.ActivityAction.UNFAVORITED -> "Unfavorited"
            com.propdf.core.domain.model.ActivityAction.RENAMED -> "Renamed"
            com.propdf.core.domain.model.ActivityAction.MOVED -> "Moved"
            com.propdf.core.domain.model.ActivityAction.COPIED -> "Copied"
            com.propdf.core.domain.model.ActivityAction.TAGGED -> "Tagged"
            com.propdf.core.domain.model.ActivityAction.COLLECTION_ADDED -> "Added to collection"
            com.propdf.core.domain.model.ActivityAction.EXPORTED -> "Exported"
            com.propdf.core.domain.model.ActivityAction.PRINTED -> "Printed"
            else -> "Edited"
        }
        val type = when (action) {
            com.propdf.core.domain.model.ActivityAction.OPENED -> ActivityType.OPENED
            com.propdf.core.domain.model.ActivityAction.SHARED, com.propdf.core.domain.model.ActivityAction.EXPORTED -> ActivityType.SHARED
            com.propdf.core.domain.model.ActivityAction.DELETED -> ActivityType.DELETED
            else -> ActivityType.EDITED
        }
        return ActivityItem(
            description = "$verb \"$documentName\"" + (details?.let { " · $it" } ?: ""),
            timestamp = dateFormat.format(Date(timestamp)),
            type = type
        )
    }
}
