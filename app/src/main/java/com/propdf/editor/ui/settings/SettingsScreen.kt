package com.propdf.editor.ui.settings

import android.widget.Toast
import com.propdfeditor.BuildConfig
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    val sheetState = rememberModalBottomSheetState()
    var showThemeSheet by remember { mutableStateOf(false) }
    var showStorageSheet by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showEmptyRecycleBinConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val actionResult by settingsViewModel.actionResult.collectAsState()

    LaunchedEffect(actionResult) {
        actionResult?.let {
            Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show()
            settingsViewModel.consumeActionResult()
        }
    }

    val darkMode by settingsViewModel.isDarkMode.collectAsState()
    val dynamicColors by settingsViewModel.isDynamicColor.collectAsState()
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
                        onCheckedChange = { settingsViewModel.setDarkMode(it) }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsSwitchItem(
                        icon = Icons.Outlined.Palette,
                        title = "Dynamic Colors",
                        subtitle = "Use system wallpaper colors (Android 12+)",
                        checked = dynamicColors,
                        onCheckedChange = { settingsViewModel.setDynamicColor(it) }
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

            // These three screens (DocumentManagerScreen, FolderBrowserScreen,
            // RecentActivityScreen) were fully built with real, working ViewModels
            // wired to existing repositories, but had no navigation route and no
            // entry point anywhere in the app -- the same "complete but unreachable"
            // gap found and fixed for the page editor and signature features earlier.
            item {
                SettingsSectionHeader("Library")
            }

            item {
                SettingsCard {
                    SettingsClickItem(
                        icon = Icons.Outlined.Description,
                        title = "Manage Documents",
                        subtitle = "Browse, favorite, and delete documents",
                        onClick = { navController.navigate("document_manager") }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.Folder,
                        title = "Folders",
                        subtitle = "Organize documents into folders",
                        onClick = { navController.navigate("folder_browser") }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.History,
                        title = "Recent Activity",
                        subtitle = "See what you've opened, edited, and shared",
                        onClick = { navController.navigate("recent_activity") }
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
                        subtitle = "Version ${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}",
                        onClick = { showAboutDialog = true }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.Policy,
                        title = "Privacy Policy",
                        subtitle = "Read our privacy policy",
                        onClick = { showPrivacyDialog = true }
                    )
                    Divider(modifier = Modifier.padding(horizontal = 16.dp))
                    SettingsClickItem(
                        icon = Icons.Outlined.HelpOutline,
                        title = "Help & Support",
                        subtitle = "FAQs, guides, and contact",
                        onClick = { showHelpDialog = true }
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
                    modifier = Modifier.clickable { settingsViewModel.clearCache() }
                )
                ListItem(
                    headlineContent = { Text("Empty Recycle Bin") },
                    supportingContent = { Text("Permanently delete all recycled files") },
                    leadingContent = {
                        Icon(Icons.Outlined.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickable { showEmptyRecycleBinConfirm = true }
                )
                ListItem(
                    headlineContent = { Text("Export Data") },
                    supportingContent = { Text("Backup your documents and settings") },
                    leadingContent = {
                        Icon(Icons.Outlined.Backup, null)
                    },
                    modifier = Modifier.clickable {
                        // TODO: a real :backup module (BackupRepositoryImpl,
                        // BackupEncryption, BackupWorker) already exists but has
                        // never been linked into :app or verified — wiring it in
                        // blind isn't a safe quick fix like Clear Cache/Empty
                        // Recycle Bin were. Needs its own pass to verify it first.
                        Toast.makeText(context, "Export Data is coming soon", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (showEmptyRecycleBinConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyRecycleBinConfirm = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Empty recycle bin?") },
            text = { Text("This permanently deletes every file currently in the recycle bin. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    showEmptyRecycleBinConfirm = false
                    settingsViewModel.emptyRecycleBin()
                }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyRecycleBinConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Outlined.Info, contentDescription = null) },
            title = { Text("About ProPDF") },
            text = {
                Column {
                    Text("Version ${BuildConfig.VERSION_NAME} • Build ${BuildConfig.VERSION_CODE}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "An offline-first PDF suite: view, annotate, edit, scan, and OCR your documents.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("OK") }
            }
        )
    }

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            icon = { Icon(Icons.Outlined.Policy, contentDescription = null) },
            title = { Text("Privacy Policy") },
            text = {
                Text(
                    "ProPDF processes documents entirely on-device. No document content is " +
                        "uploaded to any server. Full published policy text is not yet linked " +
                        "in this build.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("OK") }
            }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            icon = { Icon(Icons.Outlined.HelpOutline, contentDescription = null) },
            title = { Text("Help & Support") },
            text = {
                Text(
                    "In-app FAQs and contact options are coming soon. For now, use your " +
                        "device's app-store listing to leave feedback.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("OK") }
            }
        )
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
