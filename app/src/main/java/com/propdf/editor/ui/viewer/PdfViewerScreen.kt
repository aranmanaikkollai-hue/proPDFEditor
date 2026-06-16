package com.propdf.editor.ui.viewer

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.propdf.editor.ui.components.AnimatedFloatingToolbar
import com.propdf.editor.ui.components.ToolbarAction
import com.propdf.editor.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PdfViewerScreen(
    navController: NavController,
    fileName: String = "Document.pdf"
) {
    var showToolbar by remember { mutableStateOf(true) }
    var currentPage by remember { mutableStateOf(1) }
    val totalPages = remember { 24 }
    val pagerState = rememberPagerState(pageCount = { totalPages })

    LaunchedEffect(pagerState.currentPage) {
        currentPage = pagerState.currentPage + 1
    }

    Scaffold(
        topBar = {
            AnimatedVisibility(visible = showToolbar, enter = slideInVertically(), exit = slideOutVertically()) {
                TopAppBar(
                    title = {
                        Column {
                            Text(fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                            Text("Page $currentPage of $totalPages", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.Search, contentDescription = "Search")
                        }
                        IconButton(onClick = { }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                    )
                )
            }
        },
        bottomBar = {
            AnimatedFloatingToolbar(
                visible = showToolbar,
                actions = listOf(
                    ToolbarAction(Icons.Default.Edit, "Annotate", {}),
                    ToolbarAction(Icons.Default.Highlight, "Highlight", {}),
                    ToolbarAction(Icons.Default.TextFields, "Text", {}),
                    // Icons.Default.Signature does not exist — using Draw as substitute
                    ToolbarAction(Icons.Default.Draw, "Sign", {}),
                    ToolbarAction(Icons.Default.Share, "Share", {})
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .wrapContentWidth(Alignment.CenterHorizontally)
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) // In PDFViewerScreen.kt, inside the PDFCanvas Box:
Box(modifier = Modifier.fillMaxSize()) {
    // PDF content (existing)
    PDFCanvas(...)

    // Annotation overlay (new)
    val annotationViewModel: AnnotationViewModel = hiltViewModel()
    val currentTool by annotationViewModel.currentTool.collectAsState()
    val currentColor by annotationViewModel.currentColor.collectAsState()
    val currentStrokeWidth by annotationViewModel.currentStrokeWidth.collectAsState()

    AnnotationOverlay(
        pageIndex = currentPage,
        layerManager = remember { annotationViewModel.layers.value.firstOrNull()?.let { ... } ?: LayerManager() },
        currentTool = currentTool,
        currentColor = currentColor,
        currentStrokeWidth = currentStrokeWidth,
        onAnnotationCreated = { annotationViewModel.createAnnotation(it) },
        onAnnotationSelected = { annotationViewModel.selectAnnotation(it) },
        modifier = Modifier.fillMaxSize()
    )
}

// Toolbar at bottom
AnnotationToolbar(
    currentTool = currentTool,
    onToolSelected = { annotationViewModel.setTool(it) },
    currentColor = currentColor,
    onColorSelected = { annotationViewModel.setColor(it) },
    currentStrokeWidth = currentStrokeWidth,
    onStrokeWidthChanged = { annotationViewModel.setStrokeWidth(it) },
    historyManager = annotationViewModel.historyManager,
    onUndo = { annotationViewModel.undo() },
    onRedo = { annotationViewModel.redo() }
) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Page ${page + 1}",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "PDF rendering integration here",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Page indicator
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 80.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f)
            ) {
                Text(
                    "$currentPage / $totalPages",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}
