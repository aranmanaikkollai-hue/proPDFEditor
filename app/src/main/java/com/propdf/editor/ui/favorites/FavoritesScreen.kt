package com.propdf.editor.ui.favorites

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import com.propdf.editor.ui.files.DocumentManagerScreen
import com.propdf.editor.ui.files.DocumentManagerViewModel
import com.propdf.editor.ui.files.DocumentViewType

/**
 * Legacy FavoritesScreen - now delegates to unified DocumentManagerScreen
 */
@Composable
fun FavoritesScreen(
    navController: NavController,
    viewModel: DocumentManagerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.setViewType(DocumentViewType.FAVORITES)
    }
    
    DocumentManagerScreen(
        onOpenDocument = { document ->
            navController.navigate("viewer/${document.id}")
        },
        viewModel = viewModel
    )
}
