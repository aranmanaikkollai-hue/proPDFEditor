package com.propdf.editor.ui.recent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import com.propdf.editor.ui.files.DocumentManagerScreen
import com.propdf.editor.ui.files.DocumentManagerViewModel
import com.propdf.editor.ui.files.DocumentViewType

/**
 * Legacy RecentFilesScreen - now delegates to unified DocumentManagerScreen
 */
@Composable
fun RecentFilesScreen(
    navController: NavController,
    viewModel: DocumentManagerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.setViewType(DocumentViewType.RECENT)
    }
    
    DocumentManagerScreen(
        onOpenDocument = { document ->
            navController.navigate("viewer/${document.id}")
        },
        viewModel = viewModel
    )
}
