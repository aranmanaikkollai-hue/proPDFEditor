package com.propdf.viewer.render

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class RenderScheduler {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingRenders = ConcurrentHashMap.newKeySet<View>()

    fun schedule(view: View) {
        pendingRenders.add(view)
        scope.launch {
            delay(16L) // 1 frame
            if (pendingRenders.remove(view)) {
                view.postInvalidateOnAnimation()
            }
        }
    }

    fun scheduleImmediate(view: View) {
        pendingRenders.remove(view)
        view.postInvalidateOnAnimation()
    }

    fun dispose() {
        scope.cancel()
        pendingRenders.clear()
    }
}
