package com.propdfeditor.ui.adaptive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Two-pane layout for tablets and foldables in expanded/medium width.
 * Shows file list on the left and PDF viewer on the right.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun <T> TwoPaneFileViewer(
    items: List<T>,
    selectedItem: T?,
    onItemSelected: (T) -> Unit,
    listContent: @Composable (T, Boolean, () -> Unit) -> Unit,
    detailContent: @Composable (T) -> Unit,
    modifier: Modifier = Modifier
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<T>()

    ListDetailPaneScaffold(
        directive = navigator.scaffoldDirective,
        value = navigator.scaffoldValue,
        listPane = {
            AnimatedPane {
                Column(modifier = Modifier.fillMaxSize()) {
                    items.forEach { item ->
                        val isSelected = item == selectedItem
                        listContent(item, isSelected) {
                            onItemSelected(item)
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, item)
                        }
                    }
                }
            }
        },
        detailPane = {
            AnimatedPane {
                selectedItem?.let { detailContent(it) }
            }
        },
        modifier = modifier
    )
}
