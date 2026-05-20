package com.propdf.viewer.render

import android.view.Choreographer
import android.view.View
import androidx.core.view.ViewCompat

/**
 * Batches annotation redraw requests to align with display frames.
 * Prevents redundant invalidates during rapid touch events (e.g. freehand drawing).
 * Optimized for low-end devices with 16 ms frame budget.
 */
class RenderScheduler {

    private var framePending = false
    private var targetView: View? = null

    private val frameCallback = Choreographer.FrameCallback {
        framePending = false
        targetView?.let { view ->
            ViewCompat.postInvalidateOnAnimation(view)
        }
        targetView = null
    }

    /**
     * Schedule an invalidate on the next Choreographer frame.
     * Multiple calls within the same frame are coalesced into a single invalidate.
     */
    fun schedule(view: View) {
        if (framePending) {
            targetView = view
            return
        }
        framePending = true
        targetView = view
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** Immediate invalidate for operations requiring instant visual feedback. */
    fun scheduleImmediate(view: View) {
        ViewCompat.postInvalidateOnAnimation(view)
    }

    fun dispose() {
        if (framePending) {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
            framePending = false
        }
        targetView = null
    }
}
