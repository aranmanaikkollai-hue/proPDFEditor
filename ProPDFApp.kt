package com.propdf.editor

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.multidex.MultiDex
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

/**
 * ProPDFApp - Application class
 * Initializes all PDF libraries and global configurations.
 * Supports Android 4.1+ (API 16)
 */
@HiltAndroidApp
class ProPDFApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize MultiDex for API < 21
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            MultiDex.install(this)
        }

        // Initialize PDFBox (Apache 2.0)
        PDFBoxResourceLoader.init(applicationContext)

        // Apply saved theme preference
        applyTheme()
    }

    private fun applyTheme() {
        val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        when (prefs.getInt("theme_mode", 0)) {
            0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }
}
