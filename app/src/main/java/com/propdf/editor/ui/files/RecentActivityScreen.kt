package com.propdf.editor.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.propdf.core.domain.model.RecentActivity
import com.propdf.core.domain.model.ActivityType
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun RecentActivityScreen(activities: List<RecentActivity>) {
    if (activities.isEmpty()) {
        EmptyRecentActivity()
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(activities) { activity ->
                ActivityItem(activity = activity)
            }
        }
    }
