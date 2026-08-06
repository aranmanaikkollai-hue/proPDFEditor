package com.propdfeditor.ui.adaptive

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowWidthSizeClass

/**
 * Adaptive layout helpers for phones, tablets, and foldables.
 */

@Composable
fun rememberWindowSizeClass(): WindowSizeClass {
    return currentWindowAdaptiveInfo().windowSizeClass
}

@Composable
fun isCompactWidth(): Boolean {
    val windowClass = rememberWindowSizeClass()
    return windowClass.windowWidthSizeClass == WindowWidthSizeClass.COMPACT
}

@Composable
fun isExpandedWidth(): Boolean {
    val windowClass = rememberWindowSizeClass()
    return windowClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED
}

@Composable
fun isMediumWidth(): Boolean {
    val windowClass = rememberWindowSizeClass()
    return windowClass.windowWidthSizeClass == WindowWidthSizeClass.MEDIUM
}

/**
 * Determines if the current device should use a two-pane layout.
 */
@Composable
fun shouldUseTwoPane(): Boolean = isExpandedWidth() || isMediumWidth()

/**
 * Adaptive navigation type based on window size.
 */
@Composable
fun adaptiveNavigationType(): NavigationSuiteType {
    return if (isCompactWidth()) {
        NavigationSuiteType.NavigationBar
    } else {
        NavigationSuiteType.NavigationRail
    }
}
