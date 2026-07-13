package com.propdf.editor.ui.files

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/**
 * Legacy FilesScreen - now delegates to unified DocumentManagerScreen
 * Maintains backward compatibility with existing navigation
 */
@Composable
fun FilesScreen(
    navController: NavController,
    onOpenPdf: () -> Unit,
    viewModel: DocumentManagerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    DocumentManagerScreen(
        navController = navController,
        onOpenDocument = { documentId ->
            navController.navigate("viewer/$documentId")
        },
        onNavigateToViewer = onOpenPdf,
        onNavigateToMerge = { },
        onNavigateToSplit = { },
        onNavigateToFolder = { },
        viewModel = viewModel
    )
}
