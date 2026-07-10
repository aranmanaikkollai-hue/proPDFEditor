package com.propdf.editor.ui.search

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavController
import com.propdf.editor.ui.files.DocumentManagerScreen
import com.propdf.editor.ui.files.DocumentManagerViewModel
import com.propdf.editor.ui.files.DocumentViewType

/**
 * Legacy SearchScreen - now delegates to unified DocumentManagerScreen
 */
@Composable
fun SearchScreen(
    navController: NavController,
    initialQuery: String = ""
) {
    val viewModel: DocumentManagerViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    
    LaunchedEffect(initialQuery) {
        viewModel.setViewType(DocumentViewType.SEARCH)
        if (initialQuery.isNotBlank()) {
            viewModel.setSearchQuery(initialQuery)
        }
    }
    
    DocumentManagerScreen(
        onOpenDocument = { document ->
            navController.navigate("viewer/${document.id}")
        },
        viewModel = viewModel
    )
}
