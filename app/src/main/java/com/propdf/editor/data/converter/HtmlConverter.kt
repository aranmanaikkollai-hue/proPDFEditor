package com.propdf.editor.data.converter

import android.content.Context
import android.net.Uri
import android.os.Build
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.propdf.editor.domain.model.ConversionResult
import com.propdf.editor.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class HtmlConverter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TIMEOUT_MS = 30000L
    }
    
    suspend fun toPdf(
        context: Context,
        sourceUri: Uri,
        outputDir: File,
        fileName: String,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = withContext(Dispatchers.Main) {
        try {
            onProgress(10)
            
            val htmlContent = readHtmlContent(context, sourceUri)
                ?: return@withContext ConversionResult(false, null, fileName, "Cannot read HTML")
            
            onProgress(30)
            
            val outputFile = File(outputDir, "$fileName.pdf")
            
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                convertWithWebView(context, htmlContent, outputFile, onProgress)
            }
            
            result ?: ConversionResult(false, null, fileName, "Conversion timeout")
            
        } catch (e: Exception) {
            ConversionResult(false, null, fileName, e.message ?: "HTML to PDF failed")
        }
    }
    
    private suspend fun convertWithWebView(
        context: Context,
        htmlContent: String,
        outputFile: File,
        onProgress: suspend (Int) -> Unit
    ): ConversionResult = suspendCancellableCoroutine { continuation ->
        val webView = WebView(context).apply {
            settings.javaScriptEnabled = false
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
        }
        
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return false
            }
            
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                
                view?.postDelayed({
                    try {
                        val printManager = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        
                        val adapter = view.createPrintDocumentAdapter("${outputFile.name}_adapter")
                        
                        val attributes = PrintAttributes.Builder()
                            .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                            .setMinMargins(PrintAttributes.Margins.DEFAULT)
                            .build()
                        
                        val printAdapter = object : PrintDocumentAdapter() {
                            private var wrappedAdapter: PrintDocumentAdapter? = null
                            
                            override fun onStart() {
                                wrappedAdapter = adapter
                                wrappedAdapter?.onStart()
                            }
                            
                            override fun onLayout(
                                oldAttributes: PrintAttributes?,
                                newAttributes: PrintAttributes?,
                                cancellationSignal: android.os.CancellationSignal?,
                                callback: LayoutResultCallback?,
                                extras: android.os.Bundle?
                            ) {
                                wrappedAdapter?.onLayout(oldAttributes, newAttributes, cancellationSignal, callback, extras)
                            }
                            
                            override fun onWrite(
                                pages: Array<out PageRange>?,
                                destination: ParcelFileDescriptor?,
                                cancellationSignal: android.os.CancellationSignal?,
                                callback: WriteResultCallback?
                            ) {
                                val fileDescriptor = ParcelFileDescriptor.open(
                                    outputFile,
                                    ParcelFileDescriptor.MODE_READ_WRITE
                                )
                                wrappedAdapter?.onWrite(pages, fileDescriptor, cancellationSignal, object : WriteResultCallback() {
                                    override fun onWriteFinished(pages: PageRange?) {
                                        fileDescriptor.close()
                                        callback?.onWriteFinished(pages)
                                        
                                        val uri = FileUtils.getUriForFile(context, outputFile)
                                        continuation.resume(
                                            ConversionResult(
                                                true,
                                                uri,
                                                outputFile.nameWithoutExtension,
                                                "Converted HTML to PDF",
                                                totalBytes = outputFile.length()
                                            )
                                        )
                                        webView.destroy()
                                    }
                                    
                                    override fun onWriteFailed(error: CharSequence?) {
                                        fileDescriptor.close()
                                        callback?.onWriteFailed(error)
                                        continuation.resume(
                                            ConversionResult(false, null, outputFile.nameWithoutExtension, error?.toString() ?: "Write failed")
                                        )
                                        webView.destroy()
                                    }
                                    
                                    override fun onWriteCancelled() {
                                        fileDescriptor.close()
                                        callback?.onWriteCancelled()
                                        continuation.resume(
                                            ConversionResult(false, null, outputFile.nameWithoutExtension, "Cancelled")
                                        )
                                        webView.destroy()
                                    }
                                })
                            }
                            
                            override fun onFinish() {
                                wrappedAdapter?.onFinish()
                            }
                        }
                        
                        printManager.print("HTML to PDF", printAdapter, attributes)
                        onProgress(80)
                        
                    } catch (e: Exception) {
                        continuation.resume(
                            ConversionResult(false, null, outputFile.nameWithoutExtension, e.message ?: "Print failed")
                        )
                        webView.destroy()
                    }
                }, 1000)
            }
        }
        
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        onProgress(50)
        
        continuation.invokeOnCancellation {
            webView.stopLoading()
            webView.destroy()
        }
    }
    
    private fun readHtmlContent(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader().use { it.readText() }
            }
        } catch (e: Exception) {
            null
        }
    }
}
