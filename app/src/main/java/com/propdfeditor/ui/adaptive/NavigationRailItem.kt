package com.propdfeditor.ui.adaptive

import androidx.compose.material3.*
import androidx.compose.runtime.Composable

@Composable
fun NavigationRailItem(
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    selected: Boolean,
    onClick: () -> Unit
) {
    NavigationRailItem(
        icon = icon,
        label = label,
        selected = selected,
        onClick = onClick
    )
}
