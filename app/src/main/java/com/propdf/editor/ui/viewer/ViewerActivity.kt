package com.propdf.editor.ui.viewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This Activity is a placeholder for the Compose PDF viewer.
        // The actual Compose UI is in PdfViewerScreen.kt.
        // TODO: Replace with setContent { PdfViewerScreen(...) } when ready.
    }
}
