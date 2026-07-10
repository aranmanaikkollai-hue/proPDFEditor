package com.propdf.editor.ui.files

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import com.propdf.core.domain.model.PdfDocument

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
        onOpenDocument = { document ->
            navController.navigate("viewer/${document.id}")
        },
        viewModel = viewModel
    )
}
