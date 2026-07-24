package com.propdf.editor.ui.settings

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val sheetState = rememberModalBottomSheetState()
    var showThemeSheet by remember { mutableStateOf(false) }
    var showStorageSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var darkMode by remember { mutableStateOf(false) }
    var dynamicColors by remember { mutableStateOf(true) }
    var autoDeleteDays by remember { mutableStateOf(30f) }
    var compactView by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SettingsSectionHeader("Appearance")
            }

            item {
                SettingsCard {
                    SettingsSwitchItem(
                        icon = Icons.Outlined.DarkMode,
                        title = "Dark Mode",
                        subtitle = "Use dark theme throughout the app",
                        checked = darkMode,
                        onCheckedChange = { darkMode = it }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchItem(
                        icon = Icons.Outlined.Palette,
                        title = "Dynamic Colors",
                        subtitle = "Use system wallpaper colors (Android 12+)",
                        checked = dynamicColors,
                        onCheckedChange = { dynamicColors = it }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.ColorLens,
                        title = "Theme Color",
                        subtitle = "Customize accent color",
                        onClick = { showThemeSheet = true }
                    )
                }
            }

            item {
                SettingsSectionHeader("Storage & Data")
            }

            item {
                SettingsCard {
                    SettingsClickItem(
                        icon = Icons.Outlined.Storage,
                        title = "Storage Management",
                        subtitle = "Clear cache and manage files",
                        onClick = { showStorageSheet = true }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSliderItem(
                        icon = Icons.Outlined.Timer,
                        title = "Auto-delete from recycle bin",
                        subtitle = "Files will be permanently deleted after",
                        value = autoDeleteDays,
                        onValueChange = { autoDeleteDays = it },
                        valueRange = 1f..90f,
                        valueLabel = "${autoDeleteDays.toInt()} days"
                    )
                }
            }

            item {
                SettingsSectionHeader("Reading")
            }

            item {
                SettingsCard {
                    SettingsSwitchItem(
                        icon = Icons.Outlined.ViewCompact,
                        title = "Compact View",
                        subtitle = "Show more items per screen",
                        checked = compactView,
                        onCheckedChange = { compactView = it }
                    )
                }
            }

            item {
                SettingsSectionHeader("About")
            }

            item {
                SettingsCard {
                    SettingsClickItem(
                        icon = Icons.Outlined.Info,
                        title = "About ProPDF",
                        subtitle = "Version 3.0.0 • Build 2024",
                        onClick = { }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.Policy,
                        title = "Privacy Policy",
                        subtitle = "Read our privacy policy",
                        onClick = { }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.HelpOutline,
                        title = "Help & Support",
                        subtitle = "FAQs, guides, and contact",
                        onClick = { }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Theme Bottom Sheet
    if (showThemeSheet) {
        ModalBottomSheet(
            onDismissRequest = { showThemeSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    "Choose Theme",
                    style = MaterialTheme.typography.headlineSmall
                )
                val themes = listOf(
                    "Blue" to Color(0xFF0061A4),
                    "Green" to Color(0xFF2E7D32),
                    "Purple" to Color(0xFF7B1FA2),
                    "Orange" to Color(0xFFE65100),
                    "Red" to Color(0xFFC62828)
                )
                themes.forEach { (name, color) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        leadingContent = {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = color,
                                modifier = Modifier.size(32.dp)
                            ) { }
                        },
                        modifier = Modifier.clickable {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                showThemeSheet = false
                            }
                        }
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Storage Bottom Sheet
    if (showStorageSheet) {
        ModalBottomSheet(
            onDismissRequest = { showStorageSheet = false },
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Storage Management",
                    style = MaterialTheme.typography.headlineSmall
                )
                ListItem(
                    headlineContent = { Text("Clear Cache") },
                    supportingContent = { Text("Free up temporary files") },
                    leadingContent = {
                        Icon(Icons.Outlined.CleaningServices, null)
                    },
                    modifier = Modifier.clickable { }
                )
                ListItem(
                    headlineContent = { Text("Empty Recycle Bin") },
                    supportingContent = { Text("Permanently delete all recycled files") },
                    leadingContent = {
                        Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { }
                )
                ListItem(
                    headlineContent = { Text("Export Data") },
                    supportingContent = { Text("Backup your documents and settings") },
                    leadingContent = {
                        Icon(Icons.Outlined.Backup, null)
                    },
                    modifier = Modifier.clickable { }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp)
    )
}

@Composable
fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(content = content)
    }
}

@Composable
fun SettingsSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
fun SettingsClickItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            Icons.Default.ChevronRight,
            null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun SettingsSliderItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        icon,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.padding(start = 56.dp, top = 8.dp),
            steps = (valueRange.endInclusive - valueRange.start).toInt() - 1
        )
    }
}
