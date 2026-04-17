package com.propdf.editor.ui.viewer



import android.content.Context

import android.content.Intent

import android.graphics.Bitmap

import android.graphics.Color

import android.graphics.pdf.PdfRenderer // FIX: Corrected import

import android.net.Uri

import android.os.Bundle

import android.os.ParcelFileDescriptor

import android.view.Gravity

import android.widget.*

import androidx.appcompat.app.AppCompatActivity

import androidx.lifecycle.lifecycleScope

import kotlinx.coroutines.*

import java.io.File

import java.io.FileOutputStream



class ViewerActivity : AppCompatActivity() {



    private var pdfRenderer: PdfRenderer? = null

    private lateinit var pageContainer: LinearLayout



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        val uri = Uri.parse(intent.getStringExtra("extra_pdf_uri"))

        

        pageContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        setContentView(ScrollView(this).apply { addView(pageContainer) })



        loadPdf(uri)

    }



    private fun loadPdf(uri: Uri) {

        lifecycleScope.launch {

            val file = withContext(Dispatchers.IO) {

                val cacheFile = File(cacheDir, "temp.pdf")

                contentResolver.openInputStream(uri)?.use { input ->

                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }

                }

                cacheFile

            }

            openRenderer(file)

            renderPages()

        }

    }



    private fun openRenderer(file: File) {

        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)

        pdfRenderer = PdfRenderer(pfd) // Now resolves correctly

    }



    private fun renderPages() {

        val renderer = pdfRenderer ?: return

        for (i in 0 until renderer.pageCount) {

            val page = renderer.openPage(i)

            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()

            

            pageContainer.addView(ImageView(this).apply {

                setImageBitmap(bitmap)

                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, 20) }

            })

        }

    }



    override fun onDestroy() {

        super.onDestroy()

        pdfRenderer?.close()

    }



    companion object {

        fun start(context: Context, uri: Uri) {

            val intent = Intent(context, ViewerActivity::class.java).apply {

                putExtra("extra_pdf_uri", uri.toString())

            }

            context.startActivity(intent)

        }

    }

}

