package com.propdf.editor.data.repository

import android.graphics.Bitmap
import com.propdf.core.domain.model.*
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.toAppResult
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.utils.PdfMerger
import java.io.File
import javax.inject.Inject

class PdfOperationsRepositoryImpl @Inject constructor() : PdfOperationsRepository {

    override suspend fun merge(request: MergeRequest, outputFile: File): AppResult<File> = runCatching {
        val writer = PdfWriter(outputFile)
        val dest = PdfDocument(writer)
        val merger = PdfMerger(dest)

        // Safely convert Uri to File and filter out non-existent files
        request.inputUris.mapNotNull { it.path?.let { p -> File(p) } }.filter { it.exists() }.forEach { f ->
            val src = PdfDocument(PdfReader(f))
            merger.merge(src, 1, src.numberOfPages)
            src.close()
        }

        dest.close()
        outputFile
    }.toAppResult()

    override suspend fun split(request: SplitRequest): AppResult<List<File>> = runCatching {
        val srcFile = File(request.inputUri.path ?: "")
        val src = PdfDocument(PdfReader(srcFile))
        val outputFiles = mutableListOf<File>()
        
        // Use the parent directory of the source file, or a temp dir if unavailable
        val outputDir = srcFile.parentFile ?: File(System.getProperty("java.io.tmpdir"))

        for (i in 1..src.numberOfPages) {
            val out = File(outputDir, "split_$i.pdf")
            val writer = PdfWriter(out)
            val dest = PdfDocument(writer)
            val merger = PdfMerger(dest)
            merger.merge(src, i, i)
            dest.close()
            outputFiles.add(out)
        }
        src.close()
        outputFiles
    }.toAppResult()

    override suspend fun deletePages(inputFile: File, outputFile: File, pages: List<Int>): AppResult<File> = runCatching {
        val src = PdfDocument(PdfReader(inputFile))
        val writer = PdfWriter(outputFile)
        val dest = PdfDocument(writer)

        for (i in 1..src.numberOfPages) {
            if (i !in pages) {
                dest.addPage(src.getPage(i).copyTo(dest))
            }
        }

        src.close()
        dest.close()
        outputFile
    }.toAppResult()

    override suspend fun addPageNumbers(inputFile: File, outputFile: File, config: PageNumberConfig): AppResult<File> = runCatching {
        val src = PdfDocument(PdfReader(inputFile))
        val writer = PdfWriter(outputFile)
        val dest = PdfDocument(writer)
        for (i in 1..src.numberOfPages) {
            dest.addPage(src.getPage(i).copyTo(dest))
        }
        src.close()
        dest.close()
        outputFile
    }.toAppResult()

    override suspend fun addHeaderFooter(inputFile: File, outputFile: File, config: HeaderFooterConfig): AppResult<File> = runCatching {
        val src = PdfDocument(PdfReader(inputFile))
        val writer = PdfWriter(outputFile)
        val dest = PdfDocument(writer)
        for (i in 1..src.numberOfPages) {
            dest.addPage(src.getPage(i).copyTo(dest))
        }
        src.close()
        dest.close()
        outputFile
    }.toAppResult()

    override suspend fun addWatermark(inputFile: File, outputFile: File, config: WatermarkConfig): AppResult<File> = runCatching {
        val src = PdfDocument(PdfReader(inputFile))
        val writer = PdfWriter(outputFile)
        val dest = PdfDocument(writer)
        for (i in 1..src.numberOfPages) {
            dest.addPage(src.getPage(i).copyTo(dest))
        }
        src.close()
        dest.close()
        outputFile
    }.toAppResult()

    override suspend fun compress(inputFile: File, outputFile: File, config: CompressConfig): AppResult<File> = runCatching {
        val src = PdfDocument(PdfReader(inputFile))
        val writer = PdfWriter(outputFile)
        val dest = PdfDocument(writer)
        for (i in 1..src.numberOfPages) {
            dest.addPage(src.getPage(i).copyTo(dest))
        }
        src.close()
        dest.close()
        outputFile
    }.toAppResult()
}
