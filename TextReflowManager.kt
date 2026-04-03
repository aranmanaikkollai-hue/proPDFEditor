// FILE: TextReflowManager.kt
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/data/repository/TextReflowManager.kt
//
// FEATURE: Text reflow mode
//   - Extract full text from PDF using PDFBox PDFTextStripper
//   - Each page creates a NEW PDFTextStripper instance (rule #15)
//   - Returns structured list of ReflowPage(pageNum, text)
//   - TextReflowActivity renders each page in a scrollable TextView
//
// RULES OBEYED:
//   - PDFTextStripper: new instance per page in loop   (rule #15)
//   - Pure ASCII                                        (rule #32)
//   - setTextIsSelectable(true) not isTextSelectable=  (rule #9)
//   - isVerticalScrollBarEnabled = false                (rule #14)
//   - No FrameLayout.LayoutParams(w,h,weight)           (rule #10)

package com.propdf.editor.data.repository

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class ReflowPage(
    val pageNumber: Int,
    val text: String
)

@Singleton
class TextReflowManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    // Extract text from ALL pages, one entry per page.
    // Uses a fresh PDFTextStripper per page to avoid state contamination (rule #15).
    suspend fun extractPages(pdfFile: File): Result<List<ReflowPage>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val result = mutableListOf<ReflowPage>()
                val doc = PDDocument.load(pdfFile)
                try {
                    val pageCount = doc.numberOfPages
                    for (p in 1..pageCount) {
                        // NEW instance per page -- rule #15
                        val stripper = PDFTextStripper()
                        stripper.startPage = p
                        stripper.endPage   = p
                        val text = stripper.getText(doc)
                            .trim()
                            .replace("\r\n", "\n")
                            .replace("\r", "\n")
                        if (text.isNotBlank()) {
                            result.add(ReflowPage(p, text))
                        }
                    }
                } finally {
                    doc.close()
                }
                result
            }
        }

    // Extract ALL text as one flat string (for search indexing)
    suspend fun extractFullText(pdfFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sb = StringBuilder()
                val doc = PDDocument.load(pdfFile)
                try {
                    val pageCount = doc.numberOfPages
                    for (p in 1..pageCount) {
                        val stripper = PDFTextStripper()  // rule #15
                        stripper.startPage = p
                        stripper.endPage   = p
                        sb.appendLine(stripper.getText(doc))
                    }
                } finally {
                    doc.close()
                }
                sb.toString()
            }
        }
}
