package com.propdf.editor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ProPDFApp : Application() {
    override fun onCreate() {
        super.onCreate()
    }
}
