package com.propdf.editor.ui.viewer

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import com.propdf.editor.R
import com.propdfeditor.ui.signature.ApplySignatureActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ViewerActivity : AppCompatActivity() {

    private var documentUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentUri = intent.getParcelableExtra(EXTRA_DOCUMENT_URI) ?: intent.data
        // This Activity is a placeholder for the Compose PDF viewer.
        // The actual Compose UI is in PdfViewerScreen.kt.
        // TODO: Replace with setContent { PdfViewerScreen(...) } when ready.
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_pdf_viewer, menu)
        return true
    }

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

    companion object {
        const val EXTRA_DOCUMENT_URI = "document_uri"
    }
}
