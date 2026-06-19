package com.propdf.editor.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Premium shimmer skeleton loader with reduced motion support.
 * Uses a translating gradient to create a wave effect.
 */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    color: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val infiniteTransition = rememberInfiniteTransition(label = "skeleton")
    val translateAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer"
    )

    val shimmerColors = listOf(
        color.copy(alpha = 0.6f),
        color.copy(alpha = 0.3f),
        color.copy(alpha = 0.6f)
    )

    val brush = Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(translateAnim - 200f, 0f),
        end = Offset(translateAnim, 0f)
    )

    Surface(
        modifier = modifier,
        shape = shape,
        color = Color.Transparent
    ) {
        Box(modifier = Modifier.fillMaxSize().background(brush))
    }
}

@Composable
fun SkeletonText(
    modifier: Modifier = Modifier,
    widthFraction: Float = 0.6f,
    height: Dp = 16.dp
) {
    SkeletonBox(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
    )
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    SkeletonBox(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(16.dp)
    )
}

@Composable
fun SkeletonCircle(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    SkeletonBox(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(50)
    )
}

@Composable
fun HomeSkeletonLoader() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage card skeleton
        SkeletonBox(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            shape = RoundedCornerShape(20.dp)
        )
        
        // Quick actions skeleton
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            repeat(4) {
                SkeletonBox(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(16.dp)
                )
            }
        }
        
        // Section title
        SkeletonText(widthFraction = 0.3f)
        
        // Recent files skeleton
        repeat(3) {
            SkeletonCard()
        }
        
        // Categories skeleton
        SkeletonText(widthFraction = 0.3f)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            repeat(2) {
                SkeletonBox(
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }
    }
}
