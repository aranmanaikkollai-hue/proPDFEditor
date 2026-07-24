package com.propdf.editor.ui.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.propdf.editor.domain.model.*
import com.propdf.editor.ui.components.*
import com.propdf.editor.ui.main.MainViewModel
import com.propdf.editor.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    mainViewModel: MainViewModel,
    onOpenPdf: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val isTablet = LocalConfiguration.current.screenWidthDp >= 600

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text(
                            "ProPDF Editor",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            "Your professional PDF workspace",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate("search") }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)),
                exit = scaleOut()
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { navController.navigate("scanner") },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = "Scan Document"
                        )
                    }
                    FloatingActionButton(
                        onClick = onOpenPdf,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        shape = CircleShape,
                        elevation = FloatingActionButtonDefaults.elevation(
                            defaultElevation = 6.dp,
                            pressedElevation = 12.dp
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add PDF"
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (uiState.isLoading) {
            LoadingOverlay("Loading your workspace...")
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(500)) + slideInVertically()
                    ) {
                        StorageOverviewCard(uiState.storageStats)
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(600, delayMillis = 100)) + slideInVertically()
                    ) {
                        QuickActionsRow(navController)
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(700, delayMillis = 200)) + slideInVertically()
                    ) {
                        SectionHeader(
                            title = "Folders",
                            actionLabel = "See All",
                            onAction = { navController.navigate("folders") }
                        )
                    }
                }

                item {
                    FoldersRow(folders = uiState.folders, navController = navController)
                }

                item {
                    SectionHeader(
                        title = "Recent Files",
                        actionLabel = "See All",
                        onAction = { navController.navigate("recent") }
                    )
                }

                items(
                    items = uiState.recentFiles.take(if (isTablet) 8 else 5),
                    key = { it.id }
                ) { doc ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(400)) + slideInHorizontally()
                    ) {
                        DocumentListItem(
                            document = doc,
                            onClick = { mainViewModel.openPdfString(doc.uri.toString()) },
                            onFavoriteClick = { viewModel.toggleFavorite(doc.id, doc.isFavorite) }
                        )
                    }
                }

                item {
                    SectionHeader(
                        title = "Categories",
                        actionLabel = null,
                        onAction = null
                    )
                }

                item {
                    CategoriesGrid(navController)
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
fun StorageOverviewCard(stats: StorageStats) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Column {
                Text(
                    "Storage Overview",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatItem(
                        value = "${stats.totalDocuments}",
                        label = "Documents",
                        color = pdf_blue,
                        icon = Icons.Outlined.Description
                    )
                    StatItem(
                        value = formatFileSize(stats.totalSize),
                        label = "Total Size",
                        color = pdf_green,
                        icon = Icons.Outlined.Storage
                    )
                    StatItem(
                        value = "${stats.favoriteCount}",
                        label = "Favorites",
                        color = pdf_amber,
                        icon = Icons.Outlined.StarBorder
                    )
                    StatItem(
                        value = "${stats.deletedCount}",
                        label = "Recycle Bin",
                        color = pdf_red,
                        icon = Icons.Outlined.DeleteOutline
                    )
                }
            }
        }
    }
}

@Composable
fun StatItem(
    value: String,
    label: String,
    color: Color,
    icon: ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = color.copy(alpha = 0.15f),
            modifier = Modifier.size(44.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun QuickActionsRow(navController: NavController) {
    val actions = listOf(
        QuickAction("All Files", Icons.Outlined.FolderOpen, pdf_blue, "files"),
        QuickAction("Favorites", Icons.Outlined.StarBorder, pdf_amber, "favorites"),
        QuickAction("Scanner", Icons.Outlined.DocumentScanner, pdf_teal, "scanner"),
        QuickAction("Tools", Icons.Outlined.Build, pdf_purple, "tools")
    )

    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(actions) { action ->
            val infiniteTransition = rememberInfiniteTransition(label = "idle")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 1.02f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2000, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "breathing"
            )

            Card(
                modifier = Modifier
                    .width(85.dp)
                    .aspectRatio(0.9f)
                    .scale(scale)
                    .clickable { navController.navigate(action.route) },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = action.color.copy(alpha = 0.08f)
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = action.color.copy(alpha = 0.15f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = action.icon,
                                contentDescription = action.label,
                                tint = action.color,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = action.color
                    )
                }
            }
        }
    }
}

data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val color: Color,
    val route: String
)

@Composable
fun SectionHeader(
    title: String,
    actionLabel: String?,
    onAction: (() -> Unit)?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    actionLabel,
                    style = MaterialTheme.typography.labelMedium
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun FoldersRow(
    folders: List<Folder>,
    navController: NavController
) {
    if (folders.isEmpty()) {
        EmptyStateMini(
            icon = Icons.Outlined.FolderOpen,
            title = "No folders yet",
            subtitle = "Create folders to organize your PDFs"
        )
    } else {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            items(folders, key = { it.id }) { folder ->
                Card(
                    modifier = Modifier
                        .width(150.dp)
                        .clickable { navController.navigate("folder/${folder.id}") },
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(folder.color).copy(alpha = 0.12f)
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color(folder.color).copy(alpha = 0.2f),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = Color(folder.color),
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Text(
                            folder.name,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${folder.documentCount} files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoriesGrid(navController: NavController) {
    val categories = DocumentCategory.values().filter { it != DocumentCategory.UNCATEGORIZED }
    val colors = listOf(pdf_blue, pdf_green, pdf_orange, pdf_purple, pdf_teal, pdf_red, pdf_amber)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        categories.chunked(2).forEachIndexed { rowIndex, rowCats ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowCats.forEachIndexed { index, cat ->
                    val color = colors[(rowIndex * 2 + index) % colors.size]
                    CategoryChip(
                        name = cat.displayName,
                        color = color,
                        modifier = Modifier.weight(1f)
                    ) {
                        navController.navigate("category/${cat.name}")
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryChip(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = color
            )
        }
    }
}

fun formatFileSize(size: Long): String {
    return when {
        size >= 1024 * 1024 * 1024 -> "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
        size >= 1024 * 1024 -> "%.1f MB".format(size / (1024.0 * 1024.0))
        size >= 1024 -> "%.1f KB".format(size / 1024.0)
        else -> "$size B"
    }
}
