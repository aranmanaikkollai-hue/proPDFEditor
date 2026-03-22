package com.propdf.editor.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.propdf.editor.R
import com.propdf.editor.ui.viewer.ViewerActivity
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val pdfPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { openPdfInViewer(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Handle PDF opened from another app
        if (intent?.action == Intent.ACTION_VIEW) {
            intent.data?.let { openPdfInViewer(it) }
        }

        // FAB to open PDF picker
        findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(
            R.id.fab_open_pdf
        )?.setOnClickListener {
            pdfPickerLauncher.launch("application/pdf")
        }
    }

    private fun openPdfInViewer(uri: Uri) {
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra(ViewerActivity.EXTRA_PDF_URI, uri.toString())
        )
    }
}
