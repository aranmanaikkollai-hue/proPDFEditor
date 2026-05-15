package com.propdf.viewer.gesture

import android.animation.ValueAnimator
import android.view.animation.Interpolator
import android.view.animation.PathInterpolator
import androidx.core.animation.doOnEnd

/**
 * Smooth scroller with custom ease-out-cubic interpolator for premium feel.
 * Eliminates scroll jitter and provides fluid page transitions.
 * Used for programmatic page jumps and zoom animations.
 */
class SmoothScroller(
    private val durationMs: Long = 300,
    private val onUpdate: (Float, Float) -> Unit,
    private val onComplete: (() -> Unit)? = null
) {
    private var animator: ValueAnimator? = null

    // Custom interpolator: ease-out-cubic for premium deceleration feel
    private val interpolator: Interpolator = PathInterpolator(0.33f, 1f, 0.68f, 1f)

    fun scrollTo(
        fromX: Float, fromY: Float,
        toX: Float, toY: Float
    ) {
        cancel()

        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = durationMs
            this.interpolator = this@SmoothScroller.interpolator

            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val currentX = fromX + (toX - fromX) * fraction
                val currentY = fromY + (toY - fromY) * fraction
                onUpdate(currentX, currentY)
            }

            doOnEnd {
                onComplete?.invoke()
            }

            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    fun isRunning(): Boolean = animator?.isRunning == true
}
