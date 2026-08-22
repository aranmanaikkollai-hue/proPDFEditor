package com.propdfeditor.startup

import android.content.Context
import androidx.startup.Initializer

/**
 * REMOVED: ProPDFApplication already implements ImageLoaderFactory.
 * Having a separate CoilInitializer creates a duplicate ImageLoader.
 * This class is kept as a no-op to avoid manifest merge conflicts,
 * but should be removed from the manifest in the next release.
 */
class CoilInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // No-op: ImageLoader is created on-demand by ProPDFApplication.newImageLoader()
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
