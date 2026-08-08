package com.propdfeditor.startup

import android.content.Context
import android.util.Log
import androidx.startup.Initializer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class PDFBoxInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        try {
            PDFBoxResourceLoader.init(context.applicationContext)
            Log.d("Startup", "PDFBoxInitializer complete")
        } catch (e: Throwable) {
            Log.e("Startup", "PDFBox initialization failed — continuing", e)
        }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
