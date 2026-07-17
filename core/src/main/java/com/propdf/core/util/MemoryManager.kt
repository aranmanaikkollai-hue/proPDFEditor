package com.propdf.core.util

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.lang.ref.WeakReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val bitmapRefs = mutableListOf<WeakReference<android.graphics.Bitmap>>()
    
    fun registerBitmap(bitmap: android.graphics.Bitmap) {
        bitmapRefs.add(WeakReference(bitmap))
    }
    
    fun cleanup() {
        bitmapRefs.removeAll { ref ->
            val bitmap = ref.get()
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
            true
        }
        System.gc()
    }
    
    fun getAvailableMemory(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem
    }
    
    fun shouldUseLowMemoryMode(): Boolean {
        return getAvailableMemory() < 100 * 1024 * 1024 // 100MB threshold
    }
}
