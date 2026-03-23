package com.propdf.editor

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ProPDFApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialise PDFBox — must be called before any PDFBox operation
        PDFBoxResourceLoader.init(applicationContext)

        // Restore saved theme preference
        val prefs = getSharedPreferences("propdf_prefs", MODE_PRIVATE)
        when (prefs.getInt("theme_mode", 0)) {
            1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(
                AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            )
        }
    }
}
