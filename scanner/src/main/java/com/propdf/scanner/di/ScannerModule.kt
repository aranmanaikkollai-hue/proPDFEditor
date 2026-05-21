package com.propdf.scanner.di

import android.content.Context
import com.propdf.scanner.engine.DocumentScannerEngine
import com.propdf.scanner.engine.ocr.MlKitOcrEngine
import com.propdf.scanner.engine.pdf.SearchablePdfGenerator

/**
 * Manual dependency provider for scanner module.
 * Hilt is not available in scanner module, so we use a simple service locator pattern.
 */
object ScannerModule {

    @Volatile
    private var scannerEngine: DocumentScannerEngine? = null

    @Volatile
    private var ocrEngine: MlKitOcrEngine? = null

    @Volatile
    private var pdfGenerator: SearchablePdfGenerator? = null

    fun provideDocumentScannerEngine(context: Context): DocumentScannerEngine {
        return scannerEngine ?: synchronized(this) {
            scannerEngine ?: DocumentScannerEngine(context.applicationContext).also {
                scannerEngine = it
            }
        }
    }

    fun provideMlKitOcrEngine(context: Context): MlKitOcrEngine {
        return ocrEngine ?: synchronized(this) {
            ocrEngine ?: MlKitOcrEngine(context.applicationContext).also {
                ocrEngine = it
            }
        }
    }

    fun provideSearchablePdfGenerator(context: Context): SearchablePdfGenerator {
        return pdfGenerator ?: synchronized(this) {
            pdfGenerator ?: SearchablePdfGenerator(context.applicationContext).also {
                pdfGenerator = it
            }
        }
    }

    fun release() {
        ocrEngine?.close()
        ocrEngine = null
        scannerEngine = null
        pdfGenerator = null
    }
}
