package com.propdf.viewer.gesture

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator

class SmoothScroller(
    private val durationMs: Long = 300,
    private val onUpdate: (Float, Float) -> Unit,
    private val onComplete: () -> Unit
) {

    private var animator: ValueAnimator? = null

    fun start(fromX: Float = 0f, fromY: Float = 0f, toX: Float = 0f, toY: Float = 0f) {
        cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                val currentX = fromX + (toX - fromX) * fraction
                val currentY = fromY + (toY - fromY) * fraction
                onUpdate(currentX, currentY)
            }
            addListener(object : android.animation.Animator.AnimatorListener {
                override fun onAnimationStart(animation: android.animation.Animator) {}
                override fun onAnimationEnd(animation: android.animation.Animator) { onComplete() }
                override fun onAnimationCancel(animation: android.animation.Animator) {}
                override fun onAnimationRepeat(animation: android.animation.Animator) {}
            })
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }
}
