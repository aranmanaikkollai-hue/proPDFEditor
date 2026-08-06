package com.propdfeditor.startup

import android.content.Context
import androidx.startup.Initializer
import androidx.work.WorkManager
import coil.ImageLoader
import coil.util.DebugLogger
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Startup initializer for heavy libraries that need early initialization.
 * Runs after Application.onCreate but before first Activity.
 */
class PDFBoxInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        PDFBoxResourceLoader.init(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

class CoilInitializer : Initializer<ImageLoader> {
    override fun create(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .crossfade(true)
            .respectCacheHeaders(false)
            .build()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

class WorkManagerInitializer : Initializer<WorkManager> {
    override fun create(context: Context): WorkManager {
        return WorkManager.getInstance(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
