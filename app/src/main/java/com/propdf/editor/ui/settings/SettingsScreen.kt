package com.propdf.editor.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    var darkMode by remember { mutableStateOf(false) }
    var dynamicColors by remember { mutableStateOf(true) }
    var notifications by remember { mutableStateOf(true) }
    var autoSave by remember { mutableStateOf(true) }
    var compressionQuality by remember { mutableStateOf(0.7f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text("Appearance", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                SettingSwitchItem(
                    icon = Icons.Default.DarkMode,
                    title = "Dark Mode",
                    subtitle = "Use dark theme",
                    checked = darkMode,
                    onCheckedChange = { darkMode = it }
                )
            }
            item {
                SettingSwitchItem(
                    icon = Icons.Default.Palette,
                    title = "Dynamic Colors",
                    subtitle = "Use system wallpaper colors",
                    checked = dynamicColors,
                    onCheckedChange = { dynamicColors = it }
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Notifications", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                SettingSwitchItem(
                    icon = Icons.Default.Notifications,
                    title = "Push Notifications",
                    subtitle = "Get notified about updates",
                    checked = notifications,
                    onCheckedChange = { notifications = it }
                )
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("Editor", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                SettingSwitchItem(
                    icon = Icons.Default.Save,
                    title = "Auto Save",
                    subtitle = "Automatically save changes",
                    checked = autoSave,
                    onCheckedChange = { autoSave = it }
                )
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Compress, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Compression Quality", style = MaterialTheme.typography.labelLarge)
                                Text("${(compressionQuality * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = compressionQuality,
                            onValueChange = { compressionQuality = it },
                            valueRange = 0.3f..1f,
                            steps = 6
                        )
                    }
                }
            }
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text("About", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                SettingClickItem(
                    icon = Icons.Default.Info,
                    title = "About ProPDF Editor",
                    subtitle = "Version 2.0.0",
                    onClick = { }
                )
            }
            item {
                SettingClickItem(
                    icon = Icons.Default.Help,
                    title = "Help & Support",
                    subtitle = "FAQs, contact us",
                    onClick = { }
                )
            }
            item {
                SettingClickItem(
                    icon = Icons.Default.Policy,
                    title = "Privacy Policy",
                    subtitle = "Read our privacy policy",
                    onClick = { }
                )
            }
        }
    }
}

@Composable
fun SettingSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
fun SettingClickItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.labelLarge)
                Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}
