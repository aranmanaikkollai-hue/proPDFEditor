package com.propdf.editor.ui.viewer

import android.graphics.Bitmap
import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay

// ==================== Thumbnail sidebar ====================

@Composable
fun ThumbnailSidebar(
    pdfViewModel: PdfViewerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pageCount by pdfViewModel.pageCount.collectAsState()
    val currentPage by pdfViewModel.currentPage.collectAsState()
    val bookmarks by pdfViewModel.bookmarks.collectAsState()

    Surface(
        modifier = modifier.width(160.dp).fillMaxHeight(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Pages", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(count = pageCount) { index ->
                    ThumbnailItem(
                        pageIndex = index,
                        isCurrent = index == currentPage,
                        isBookmarked = index in bookmarks,
                        pdfViewModel = pdfViewModel,
                        onClick = { pdfViewModel.jumpToPage(index) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailItem(
    pageIndex: Int,
    isCurrent: Boolean,
    isBookmarked: Boolean,
    pdfViewModel: PdfViewerViewModel,
    onClick: () -> Unit
) {
    var bitmap by remember(pageIndex) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(pageIndex) {
        bitmap = pdfViewModel.renderThumbnail(pageIndex)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (isCurrent) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(
                width = if (isCurrent) 2.dp else 1.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            val bmp = bitmap
            if (bmp != null) {
                AndroidView(
                    factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                    update = { it.setImageBitmap(bmp) },
                    modifier = Modifier.fillMaxWidth().height(120.dp)
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }
            }
            if (isBookmarked) {
                Icon(
                    Icons.Default.Bookmark,
                    contentDescription = "Bookmarked",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).size(16.dp)
                )
            }
        }
        Text(
            text = "${pageIndex + 1}",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}

// ==================== Bookmarks panel ====================

@Composable
fun BookmarksPanel(
    pdfViewModel: PdfViewerViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bookmarks by pdfViewModel.bookmarks.collectAsState()
    val sorted = remember(bookmarks) { bookmarks.sorted() }

    Surface(
        modifier = modifier.width(220.dp).fillMaxHeight(),
        tonalElevation = 4.dp,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Bookmarks", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()
            if (sorted.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No bookmarks yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = sorted) { page ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pdfViewModel.jumpToPage(page) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Page ${page + 1}", style = MaterialTheme.typography.bodyMedium)
                            IconButton(
                                onClick = { pdfViewModel.toggleBookmark(page) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.BookmarkRemove,
                                    contentDescription = "Remove bookmark",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

// ==================== Page navigator dialog ====================

@Composable
fun PageNavigatorDialog(
    pageCount: Int,
    currentPage: Int,
    onNavigate: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf((currentPage + 1).toString()) }
    var sliderValue by remember { mutableFloatStateOf(currentPage.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to page") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input ->
                        text = input.filter { it.isDigit() }
                        text.toIntOrNull()?.let { sliderValue = (it - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0)).toFloat() }
                    },
                    label = { Text("Page (1-$pageCount)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Slider(
                    value = sliderValue,
                    onValueChange = {
                        sliderValue = it
                        text = (it.toInt() + 1).toString()
                    },
                    valueRange = 0f..(pageCount - 1).coerceAtLeast(0).toFloat(),
                    steps = (pageCount - 2).coerceAtLeast(0)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val target = (text.toIntOrNull()?.minus(1)) ?: sliderValue.toInt()
                onNavigate(target.coerceIn(0, (pageCount - 1).coerceAtLeast(0)))
            }) { Text("Go") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ==================== Search bar ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfSearchBar(
    pdfViewModel: PdfViewerViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val query by pdfViewModel.searchQuery.collectAsState()
    val results by pdfViewModel.searchResults.collectAsState()
    val currentIndex by pdfViewModel.currentSearchMatchIndex.collectAsState()
    val isSearching by pdfViewModel.isSearching.collectAsState()
    var localText by remember { mutableStateOf(query) }

    LaunchedEffect(localText) {
        delay(350) // debounce so we don't re-scan the whole PDF on every keystroke
        if (localText != query) pdfViewModel.search(localText)
    }

    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search")
            }
            OutlinedTextField(
                value = localText,
                onValueChange = { localText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search in document") },
                trailingIcon = {
                    if (isSearching) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (localText.isNotEmpty()) {
                        IconButton(onClick = { localText = ""; pdfViewModel.clearSearch() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                }
            )
            if (results.isNotEmpty()) {
                Text(
                    "${currentIndex + 1}/${results.size}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Clip
                )
                IconButton(onClick = { pdfViewModel.previousSearchMatch() }) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = { pdfViewModel.nextSearchMatch() }) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next match")
                }
            } else if (localText.isNotBlank() && !isSearching) {
                Text(
                    "No results",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

// ==================== Floating page indicator ====================

@Composable
fun FloatingPageIndicator(
    currentPage: Int,
    pageCount: Int,
    visibleTrigger: Any?,
    modifier: Modifier = Modifier
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(visibleTrigger) {
        visible = true
        delay(1500)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.85f),
            modifier = Modifier.padding(12.dp)
        ) {
            Text(
                text = "${(currentPage + 1).coerceAtLeast(1)} / ${pageCount.coerceAtLeast(1)}",
                color = MaterialTheme.colorScheme.inverseOnSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}

// ==================== Display settings sheet (night/sepia/brightness/keep-screen-on/view mode) ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsSheet(
    pdfViewModel: PdfViewerViewModel,
    onDismiss: () -> Unit
) {
    val viewMode by pdfViewModel.viewMode.collectAsState()
    val colorMode by pdfViewModel.colorMode.collectAsState()
    val keepScreenOn by pdfViewModel.keepScreenOn.collectAsState()
    val autoBrightness by pdfViewModel.autoBrightness.collectAsState()
    val manualBrightness by pdfViewModel.manualBrightness.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).padding(bottom = 24.dp)) {
            Text("Layout", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Vertical", viewMode == PdfViewMode.VERTICAL_CONTINUOUS) {
                    pdfViewModel.setViewMode(PdfViewMode.VERTICAL_CONTINUOUS)
                }
                ModeChip("Horizontal", viewMode == PdfViewMode.HORIZONTAL_CONTINUOUS) {
                    pdfViewModel.setViewMode(PdfViewMode.HORIZONTAL_CONTINUOUS)
                }
                ModeChip("Single page", viewMode == PdfViewMode.SINGLE_PAGE) {
                    pdfViewModel.setViewMode(PdfViewMode.SINGLE_PAGE)
                }
                ModeChip("Two page", viewMode == PdfViewMode.TWO_PAGE) {
                    pdfViewModel.setViewMode(PdfViewMode.TWO_PAGE)
                }
            }

            Spacer(Modifier.height(20.dp))
            Text("Reading color", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Normal", colorMode == PdfColorMode.NORMAL) { pdfViewModel.setColorMode(PdfColorMode.NORMAL) }
                ModeChip("Night", colorMode == PdfColorMode.NIGHT) { pdfViewModel.setColorMode(PdfColorMode.NIGHT) }
                ModeChip("Sepia", colorMode == PdfColorMode.SEPIA) { pdfViewModel.setColorMode(PdfColorMode.SEPIA) }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Keep screen on", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = keepScreenOn, onCheckedChange = { pdfViewModel.toggleKeepScreenOn() })
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Auto brightness", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = autoBrightness, onCheckedChange = { pdfViewModel.toggleAutoBrightness() })
            }
            if (!autoBrightness) {
                Text("Brightness", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = manualBrightness,
                    onValueChange = { pdfViewModel.setManualBrightness(it) },
                    valueRange = 0.05f..1f
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
