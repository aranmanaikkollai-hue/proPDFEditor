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
// In onCreateOptionsMenu:
override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
    return true
}

// In onOptionsItemSelected:
override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
        R.id.action_sign_document -> {
            documentUri?.let { uri ->
                startActivity(ApplySignatureActivity.createIntent(this, uri))
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }
}
