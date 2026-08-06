package com.propdfeditor.ui.adaptive

import androidx.compose.foundation.layout.*
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Adaptive home that switches between bottom nav (phone), rail (tablet),
 * and permanent drawer (desktop/large tablet).
 */
@Composable
fun AdaptiveHomeScaffold(
    navigationItems: List<NavigationItem>,
    selectedItem: Int,
    onItemSelected: (Int) -> Unit,
    content: @Composable () -> Unit
) {
    when {
        isExpandedWidth() -> {
            // Permanent navigation drawer for large tablets/desktop
            PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(modifier = Modifier.width(240.dp)) {
                        Spacer(modifier = Modifier.height(16.dp))
                        navigationItems.forEachIndexed { index, item ->
                            NavigationDrawerItem(
                                icon = item.icon,
                                label = item.label,
                                selected = selectedItem == index,
                                onClick = { onItemSelected(index) }
                            )
                        }
                    }
                },
                content = content
            )
        }
        isMediumWidth() -> {
            // Navigation rail for medium tablets
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRail {
                    navigationItems.forEachIndexed { index, item ->
                        NavigationRailItem(
                            icon = item.icon,
                            label = item.label,
                            selected = selectedItem == index,
                            onClick = { onItemSelected(index) }
                        )
                    }
                }
                content()
            }
        }
        else -> {
            // Bottom navigation for phones (handled by caller)
            content()
        }
    }
}

data class NavigationItem(
    val icon: @Composable () -> Unit,
    val label: @Composable () -> Unit
)
