package com.propdf.viewer.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Small on-device settings for the viewer. Nothing here leaves the device. */
@Singleton
class ViewerPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    var colorMode: ReaderColorMode
        get() = ReaderColorMode.fromName(prefs.getString(KEY_COLOR_MODE, null))
        set(value) {
            prefs.edit().putString(KEY_COLOR_MODE, value.name).apply()
        }

    private companion object {
        const val FILE_NAME = "viewer_prefs"
        const val KEY_COLOR_MODE = "color_mode"
    }
}
