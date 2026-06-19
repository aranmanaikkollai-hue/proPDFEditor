package com.propdf.editor.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import kotlinx.coroutines.flow.Flow

/**
 * Premium gesture feedback with reduced motion support.
 * Scales down to 0.96 on press, springs back on release.
 */
fun Modifier.premiumClickable(
    enabled: Boolean = true,
    role: Role? = null,
    reducedMotion: Flow<Boolean>,
    onClick: () -> Unit
): Modifier = composed {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale = remember { Animatable(1f) }
    
    // Collect reduced motion preference
    val isReducedMotion by reducedMotion.collectAsStateWithLifecycle(initialValue = false)

    val targetScale = when {
        !enabled -> 1f
        isPressed -> if (isReducedMotion) 0.98f else 0.96f
        else -> 1f
    }

    LaunchedEffect(targetScale, isReducedMotion) {
        val spec: AnimationSpec<Float> = if (isReducedMotion) {
            tween(durationMillis = 100)
        } else {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            )
        }
        scale.animateTo(targetScale, spec)
    }

    this
        .scale(scale.value)
        .clickable(
            interactionSource = interactionSource,
            indication = null, // Custom ripple handled by Material components
            enabled = enabled,
            role = role,
            onClick = onClick
        )
}

// Helper for non-animated fallback
@Composable
private fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(initialValue) }
    LaunchedEffect(this) {
        this@collectAsStateWithLifecycle.collect { state.value = it }
    }
    return state
}
