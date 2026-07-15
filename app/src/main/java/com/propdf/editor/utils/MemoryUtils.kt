package com.propdf.editor.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug

object MemoryUtils {

    fun hasAvailableMemory(requiredBytes: Long): Boolean {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val availableMemory = maxMemory - usedMemory
        return availableMemory > requiredBytes
    }

    fun getAvailableMemory(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem
    }

    fun getMemoryInfo(context: Context): String {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory() / (1024 * 1024)
        val totalMemory = runtime.totalMemory() / (1024 * 1024)
        val freeMemory = runtime.freeMemory() / (1024 * 1024)
        val usedMemory = totalMemory - freeMemory
        
        return "Max: ${maxMemory}MB, Used: ${usedMemory}MB, Free: ${freeMemory}MB"
    }

    fun shouldUseLowMemoryMode(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.lowMemory || memoryInfo.availMem < 100 * 1024 * 1024 // Less than 100MB
    }

    fun calculateBitmapMemory(width: Int, height: Int, config: android.graphics.Bitmap.Config = android.graphics.Bitmap.Config.ARGB_8888): Long {
        val bytesPerPixel = when (config) {
            android.graphics.Bitmap.Config.ARGB_8888 -> 4
            android.graphics.Bitmap.Config.RGB_565 -> 2
            android.graphics.Bitmap.Config.ARGB_4444 -> 2
            android.graphics.Bitmap.Config.ALPHA_8 -> 1
            else -> 4
        }
        return width.toLong() * height * bytesPerPixel
    }
}
