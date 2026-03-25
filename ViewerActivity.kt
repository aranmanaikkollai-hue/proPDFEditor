package com.propdf.editor.ui.viewer


import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.propdf.editor.R
import com.propdf.editor.utils.FileHelper
import java.io.File


class ViewerActivity : AppCompatActivity() {


    private lateinit var recyclerView: RecyclerView
    private var pdfRenderer: PdfRenderer? = null
    private var fileDescriptor: ParcelFileDescriptor? = null


    companion object {
        private const val EXTRA_URI = "pdf_uri"


        fun start(context: Context, uri: Uri) {
            val intent = Intent(context, ViewerActivity::class.java)
            intent.putExtra(EXTRA_URI, uri)
            context.startActivity(intent)
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer_simple)


        recyclerView = findViewById(R.id.rv_pages)
        recyclerView.layoutManager = LinearLayoutManager(this)


        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        uri?.let { openPdf(it) }
    }


    private fun openPdf(uri: Uri) {
        val file: File = FileHelper.uriToFile(this, uri) ?: return


        fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pdfRenderer = PdfRenderer(fileDescriptor!!)


        recyclerView.adapter = PdfPageAdapter(pdfRenderer!!)
    }


    override fun onDestroy() {
        super.onDestroy()
        pdfRenderer?.close()
        fileDescriptor?.close()
    }
}