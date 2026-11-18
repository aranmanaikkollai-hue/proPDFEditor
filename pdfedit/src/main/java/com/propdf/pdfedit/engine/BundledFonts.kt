package com.propdf.pdfedit.engine

import android.content.Context
import com.propdf.pdfedit.ops.FontSource

/** The open-licence fonts shipped in this module's assets (Liberation Sans 2.1.5, SIL Open Font License 1.1). */
object BundledFonts {
    const val REGULAR_ASSET = "fonts/LiberationSans-Regular.ttf"
    const val BOLD_ASSET = "fonts/LiberationSans-Bold.ttf"

    fun regular(context: Context) = FontSource { context.assets.open(REGULAR_ASSET) }

    fun bold(context: Context) = FontSource { context.assets.open(BOLD_ASSET) }
}
