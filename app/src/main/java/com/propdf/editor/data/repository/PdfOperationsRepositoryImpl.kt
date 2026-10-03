package com.propdf.editor.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.propdf.core.domain.dispatcher.DispatcherProvider
import com.propdf.core.domain.logger.AppLogger
import com.propdf.core.domain.model.AnnotationStroke
import com.propdf.core.domain.model.AnnotationText
import com.propdf.core.domain.model.BackgroundConfig
import com.propdf.core.domain.model.CompressConfig
import com.propdf.core.domain.model.CropConfig
import com.propdf.core.domain.model.HeaderFooterConfig
import com.propdf.core.domain.model.ImageInsertionConfig
import com.propdf.core.domain.model.MeasurementUnit
import com.propdf.core.domain.model.MergeConfig
import com.propdf.core.domain.model.MergeRequest
import com.propdf.core.domain.model.PageNumberConfig
import com.propdf.core.domain.model.ResizeConfig
import com.propdf.core.domain.model.SecurityConfig
import com.propdf.core.domain.model.SplitRequest
import com.propdf.core.domain.model.WatermarkConfig
import com.propdf.core.domain.repository.PdfOperationsRepository
import com.propdf.core.domain.result.AppException
import com.propdf.core.domain.result.AppResult
import com.propdf.core.domain.result.PdfProcessingError
import com.propdf.core.domain.result.toAppException
import com.propdf.security.encryption.PdfBoxPasswordEngine
import com.propdf.security.encryption.PdfPermissions
import com.propdf.security.encryption.PdfSecurityException
import com.propdfeditor.batch.util.PdfOperationException
import com.propdfeditor.core.util.toSafeUserMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Marks an [AppException.Unknown] whose message was written by this app and is safe to show. */
internal const val USER_SAFE_PREFIX = "[user-safe] "

/** Maps a failure to an [AppResult.Error]; app-authored PdfOperationException text stays user-visible. */
internal fun Throwable.toOpError(): AppResult.Error =
    if (this is PdfOperationException || this is PdfSecurityException) {
        AppResult.Error(AppException.Unknown(USER_SAFE_PREFIX + (message ?: "The operation could not be completed.")))
    } else {
        AppResult.Error(toAppException())
    }

/** User-facing text for any exception produced by this repository. */
fun AppException.pageEditorMessage(): String {
    val m = message
    return if (this is AppException.Unknown && m != null && m.startsWith(USER_SAFE_PREFIX)) {
        m.removePrefix(USER_SAFE_PREFIX)
    } else {
        toSafeUserMessage()
    }
}

/**
 * Page Editor / PDF operations repository.
 *
 * Engine: PDFBox Android 2.0.27.0 through [PdfBoxPageEngine] (no iText in this file).
 * iText code that is NOT part of the Page Editor path (compress, annotation export) lives in [LegacyITextPdfOperations] and is delegated to unchanged.
 *
 * SAF: every Uri may be file:// or content://. content:// sources are copied into a private
 * cache file (ContentResolver) and that copy is always deleted afterwards. Results are written
 * to new cache files and returned as file:// Uris; originals are never modified.
 */
@Singleton
class PdfOperationsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: AppLogger
) : PdfOperationsRepository {

    private val engine by lazy { PdfBoxPageEngine(context) }
    private val legacy by lazy { LegacyITextPdfOperations(context, dispatchers, logger) }

    // ===================== plumbing =====================

    /** Runs [block] on the IO dispatcher. Cancellation is re-thrown, never converted to an error. */
    private suspend fun <T> io(block: suspend () -> T): AppResult<T> = withContext(dispatchers.io) {
        try {
            AppResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e.toOpError()
        }
    }

    private class Local(val file: File, val temporary: Boolean)

    private fun resolve(uri: Uri): Local {
        val scheme = uri.scheme
        if (scheme == null || scheme == "file") {
            val path = uri.path ?: throw AppException.UnsupportedUri("Missing file path")
            val f = File(path)
            if (f.exists() && f.canRead()) return Local(f, false)
            throw AppException.FileNotFound("Source file is no longer available")
        }
        val tmp = File(context.cacheDir, "pdf_op_src_${System.nanoTime()}.pdf")
        try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: throw AppException.FileNotFound("Source file is no longer available")
            stream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            if (tmp.length() == 0L) throw AppException.FileNotFound("Source file is empty")
            return Local(tmp, true)
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
    }

    private suspend fun <T> withLocal(uri: Uri, block: suspend (File) -> T): T {
        val local = resolve(uri)
        try {
            return block(local.file)
        } finally {
            if (local.temporary) local.file.delete()
        }
    }

    private suspend fun <T> withLocals(uris: List<Uri>, block: suspend (List<File>) -> T): T {
        val locals = ArrayList<Local>()
        try {
            uris.forEach { locals.add(resolve(it)) }
            return block(locals.map { it.file })
        } finally {
            locals.forEach { if (it.temporary) it.file.delete() }
        }
    }

    private fun safeName(prefix: String): String {
        val cleaned = prefix.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_').take(60)
        return cleaned.ifEmpty { "output" }
    }

    private fun createOutputFile(prefix: String, extension: String = "pdf"): File =
        File(context.cacheDir, "${safeName(prefix)}_${System.nanoTime()}.$extension")

    private fun toPoints(value: Float, unit: MeasurementUnit): Float = when (unit) {
        MeasurementUnit.POINT -> value
        MeasurementUnit.INCH -> value * 72f
        MeasurementUnit.CM -> value * 28.3465f
        MeasurementUnit.MM -> value * 2.83465f
    }

    private fun AppResult<File>.toUriResult(): AppResult<Uri> = when (this) {
        is AppResult.Success -> AppResult.Success(Uri.fromFile(data))
        is AppResult.Error -> this
        is AppResult.Loading -> AppResult.Loading(progress)
    }

    private fun parseSource(value: String): Uri =
        if (value.contains("://")) Uri.parse(value) else Uri.fromFile(File(value))

    /** Runs a Uri-based single-output operation: resolve source, write a fresh cache file, return its Uri. */
    private suspend fun uriOp(source: Uri, prefix: String, op: suspend (File, File) -> File): AppResult<Uri> =
        io { withLocal(source) { input -> Uri.fromFile(op(input, createOutputFile(prefix))) } }

    // ===================== merge / split =====================

    override suspend fun merge(request: MergeRequest, outputFile: File): AppResult<File> = io {
        withLocals(request.inputUris) { files -> engine.merge(files, outputFile) }
    }

    override suspend fun split(request: SplitRequest): AppResult<List<File>> = io {
        val dir = File(request.outputDir).also { it.mkdirs() }
        val stamp = System.currentTimeMillis()
        withLocal(parseSource(request.inputUri)) { input ->
            engine.splitRanges(input, request.ranges) { i -> File(dir, "part${i + 1}_$stamp.pdf") }
        }
    }

    override suspend fun mergePdfs(config: MergeConfig): AppResult<Uri> = io {
        val output = createOutputFile(config.outputFileName.ifBlank { "merged" })
        withLocals(config.sourceUris) { files -> Uri.fromFile(engine.merge(files, output)) }
    }

    override suspend fun splitBySize(sourceUri: Uri, maxSizeMb: Int, outputPrefix: String): AppResult<List<Uri>> = io {
        withLocal(sourceUri) { input ->
            val count = engine.pageCount(input)
            val maxBytes = maxSizeMb.coerceAtLeast(1) * 1024L * 1024L
            val ranges = PageOrderPlanner.bySize(count, input.length(), maxBytes)
            engine.splitRanges(input, ranges) { i -> createOutputFile("${outputPrefix}_part${i + 1}") }
                .map { Uri.fromFile(it) }
        }
    }

    override suspend fun splitByBookmark(sourceUri: Uri, outputPrefix: String): AppResult<List<Uri>> = io {
        withLocal(sourceUri) { input ->
            val count = engine.pageCount(input)
            // Existing behaviour: chunk count follows the number of top-level bookmarks.
            val ranges = PageOrderPlanner.evenChunks(count, engine.topLevelBookmarkCount(input))
            engine.splitRanges(input, ranges) { i -> createOutputFile("${outputPrefix}_part${i + 1}") }
                .map { Uri.fromFile(it) }
        }
    }

    override suspend fun splitEveryNPages(sourceUri: Uri, n: Int, outputPrefix: String): AppResult<List<Uri>> = io {
        withLocal(sourceUri) { input ->
            val ranges = PageOrderPlanner.everyN(engine.pageCount(input), n)
            engine.splitRanges(input, ranges) { i -> createOutputFile("${outputPrefix}_part${i + 1}") }
                .map { Uri.fromFile(it) }
        }
    }

    // ===================== page assembly (File-based) =====================

    override suspend fun deletePages(inputFile: File, outputFile: File, pages: List<Int>): AppResult<File> = io {
        val order = PageOrderPlanner.deleteOrder(engine.pageCount(inputFile), pages)
        if (order.isEmpty()) throw PdfOperationException("A document must keep at least one page.")
        engine.assemble(inputFile, outputFile, order)
    }

    override suspend fun rotatePages(inputFile: File, outputFile: File, rotations: Map<Int, Float>): AppResult<File> = io {
        engine.rotate(inputFile, outputFile, rotations.mapValues { it.value.toInt() })
    }

    override suspend fun imagesToPdf(imageFiles: List<File>, outputFile: File): AppResult<File> = io {
        val usable = imageFiles.filter { it.exists() && it.length() > 0 }.map { Uri.fromFile(it) }
        engine.imagesToPdf(usable, outputFile, null)
    }

    override suspend fun insertImageOnPage(inputFile: File, outputFile: File, config: ImageInsertionConfig): AppResult<File> = io {
        engine.insertImageOnLastPage(inputFile, outputFile, config)
    }

    override suspend fun reshapePageSize(inputFile: File, outputFile: File, widthPt: Float, heightPt: Float): AppResult<File> = io {
        engine.resize(inputFile, outputFile, emptyList(), widthPt, heightPt, keepAspect = true, scaleContent = true)
    }

    override suspend fun addWatermark(inputFile: File, outputFile: File, config: WatermarkConfig): AppResult<File> = io {
        engine.watermark(inputFile, outputFile, config)
    }

    override suspend fun addPageNumbers(inputFile: File, outputFile: File, config: PageNumberConfig): AppResult<File> = io {
        engine.pageNumbers(inputFile, outputFile, config)
    }

    override suspend fun addHeaderFooter(inputFile: File, outputFile: File, config: HeaderFooterConfig): AppResult<File> = io {
        engine.headerFooter(inputFile, outputFile, config)
    }

    // ===================== NOT Page Editor: compress / annotation export still iText, delegated unchanged =====================

    override suspend fun compress(inputFile: File, outputFile: File, config: CompressConfig): AppResult<File> =
        legacy.compress(inputFile, outputFile, config)

    // Password security: PDFBox via :security's PdfBoxPasswordEngine (no iText, no bypass).
    private val passwordEngine by lazy { PdfBoxPasswordEngine(context) }

    override suspend fun encrypt(inputFile: File, outputFile: File, config: SecurityConfig): AppResult<File> = io {
        var allow = PdfPermissions.ALLOW_SCREENREADERS
        if (config.allowPrinting) allow = allow or PdfPermissions.ALLOW_PRINTING
        if (config.allowCopying) allow = allow or PdfPermissions.ALLOW_COPY
        passwordEngine.encrypt(
            inputFile, outputFile,
            PdfBoxPasswordEngine.EncryptionRequest(
                userPassword = config.userPassword,
                ownerPassword = config.ownerPassword,
                permissions = allow,
                algorithm = PdfBoxPasswordEngine.Algorithm.AES_256
            )
        )
    }

    override suspend fun decrypt(inputFile: File, outputFile: File, password: String): AppResult<File> = io {
        passwordEngine.decrypt(inputFile, outputFile, password)
    }

    override suspend fun saveAnnotations(
        inputFile: File,
        outputFile: File,
        pageAnnotations: Map<Int, Pair<List<AnnotationStroke>, Float>>,
        pageTextAnnotations: Map<Int, Pair<List<AnnotationText>, Float>>
    ): AppResult<File> = legacy.saveAnnotations(inputFile, outputFile, pageAnnotations, pageTextAnnotations)

    override suspend fun compressPdf(sourceUri: Uri, config: CompressConfig): AppResult<Uri> =
        compressVia(sourceUri, "compressed", config)

    override suspend fun optimizePdf(sourceUri: Uri, aggressive: Boolean): AppResult<Uri> =
        compressVia(
            sourceUri, "optimized",
            CompressConfig(level = if (aggressive) 9 else 6, targetDpi = if (aggressive) 100 else 150)
        )

    private suspend fun compressVia(sourceUri: Uri, prefix: String, config: CompressConfig): AppResult<Uri> {
        val local = try {
            resolve(sourceUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            return e.toOpError()
        }
        try {
            return legacy.compress(local.file, createOutputFile(prefix), config).toUriResult()
        } finally {
            if (local.temporary) local.file.delete()
        }
    }

    // ===================== render / read =====================

    override suspend fun extractPagesAsImages(inputFile: File, pages: List<Int>?): AppResult<List<Bitmap>> = io {
        val result = ArrayList<Bitmap>()
        val pfd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                val list = pages ?: (1..renderer.pageCount).toList()
                for (n in list) {
                    if (n !in 1..renderer.pageCount) continue
                    renderer.openPage(n - 1).use { page ->
                        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        result.add(bmp)
                    }
                }
            }
            result as List<Bitmap>
        } catch (t: Throwable) {
            result.forEach { it.recycle() }
            throw t
        } finally {
            pfd.close()
        }
    }

    override suspend fun renderPageToBitmap(inputFile: File, pageNum: Int, width: Int?): AppResult<Bitmap> = io {
        val pfd = ParcelFileDescriptor.open(inputFile, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { renderer ->
                val index = pageNum - 1
                if (index !in 0 until renderer.pageCount) {
                    throw PdfProcessingError.InvalidPage("Page $pageNum not in range 1..${renderer.pageCount}")
                }
                renderer.openPage(index).use { page ->
                    val targetWidth = width ?: page.width
                    val scale = targetWidth.toFloat() / page.width
                    val targetHeight = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        } finally {
            pfd.close()
        }
    }

    override suspend fun getPageCount(sourceUri: Uri): AppResult<Int> = io {
        withLocal(sourceUri) { engine.pageCount(it) }
    }

    override suspend fun getPageThumbnails(sourceUri: Uri, pages: List<Int>): AppResult<List<Bitmap>> = io {
        withLocal(sourceUri) { input ->
            val pfd = ParcelFileDescriptor.open(input, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                PdfRenderer(pfd).use { renderer ->
                    pages.mapNotNull { pageNum ->
                        val index = pageNum - 1
                        if (index !in 0 until renderer.pageCount) return@mapNotNull null
                        renderer.openPage(index).use { page ->
                            val width = 200
                            val ratio = width.toFloat() / page.width
                            val height = (page.height * ratio).toInt().coerceAtLeast(1)
                            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            } finally {
                pfd.close()
            }
        }
    }

    // ===================== Uri-based Page Editor operations =====================

    override suspend fun insertPdfPages(sourceUri: Uri, insertUri: Uri, position: Int, sourcePages: List<Int>): AppResult<Uri> = io {
        withLocals(listOf(sourceUri, insertUri)) { files ->
            Uri.fromFile(engine.insertPdf(files[0], files[1], position, sourcePages, createOutputFile("inserted_pdf")))
        }
    }

    override suspend fun deletePages(sourceUri: Uri, pages: List<Int>): AppResult<Uri> =
        uriOp(sourceUri, "deleted") { input, output ->
            val order = PageOrderPlanner.deleteOrder(engine.pageCount(input), pages)
            if (order.isEmpty()) throw PdfOperationException("A document must keep at least one page.")
            engine.assemble(input, output, order)
        }

    override suspend fun duplicatePages(sourceUri: Uri, pages: List<Int>): AppResult<Uri> =
        uriOp(sourceUri, "duplicated") { input, output ->
            engine.assemble(input, output, PageOrderPlanner.duplicateOrder(engine.pageCount(input), pages))
        }

    override suspend fun movePages(sourceUri: Uri, pages: List<Int>, target: Int): AppResult<Uri> =
        uriOp(sourceUri, "moved") { input, output ->
            engine.assemble(input, output, PageOrderPlanner.moveOrder(engine.pageCount(input), pages, target))
        }

    override suspend fun extractPages(sourceUri: Uri, pages: List<Int>, outputName: String): AppResult<Uri> =
        uriOp(sourceUri, outputName.ifBlank { "extracted" }) { input, output ->
            val order = PageOrderPlanner.extractOrder(engine.pageCount(input), pages)
            if (order.isEmpty()) throw PdfOperationException("None of the selected pages exist in this document.")
            engine.assemble(input, output, order)
        }

    override suspend fun rotatePages(sourceUri: Uri, pages: List<Int>, degrees: Int): AppResult<Uri> =
        uriOp(sourceUri, "rotated") { input, output ->
            engine.rotate(input, output, pages.associateWith { degrees })
        }

    override suspend fun cropPages(sourceUri: Uri, pages: List<Int>, config: CropConfig): AppResult<Uri> =
        uriOp(sourceUri, "cropped") { input, output ->
            engine.crop(
                input, output, pages,
                toPoints(config.leftMargin, config.unit), toPoints(config.topMargin, config.unit),
                toPoints(config.rightMargin, config.unit), toPoints(config.bottomMargin, config.unit)
            )
        }

    override suspend fun resizePages(sourceUri: Uri, pages: List<Int>, config: ResizeConfig): AppResult<Uri> =
        uriOp(sourceUri, "resized") { input, output ->
            engine.resize(
                input, output, pages,
                toPoints(config.targetWidth, config.unit), toPoints(config.targetHeight, config.unit),
                config.keepAspectRatio, config.scaleContent
            )
        }

    override suspend fun mirrorPages(sourceUri: Uri, pages: List<Int>, horizontal: Boolean): AppResult<Uri> =
        uriOp(sourceUri, "mirrored") { input, output -> engine.mirror(input, output, pages, horizontal) }

    override suspend fun insertBlankPage(sourceUri: Uri, position: Int, width: Float, height: Float): AppResult<Uri> =
        uriOp(sourceUri, "inserted") { input, output -> engine.insertBlank(input, output, position, width, height) }

    override suspend fun addPageNumbers(sourceUri: Uri, config: PageNumberConfig): AppResult<Uri> =
        uriOp(sourceUri, "numbered") { input, output -> engine.pageNumbers(input, output, config) }

    override suspend fun addHeaderFooter(sourceUri: Uri, config: HeaderFooterConfig): AppResult<Uri> =
        uriOp(sourceUri, "headerfooter") { input, output -> engine.headerFooter(input, output, config) }

    override suspend fun addWatermark(sourceUri: Uri, config: WatermarkConfig): AppResult<Uri> =
        uriOp(sourceUri, "watermarked") { input, output -> engine.watermark(input, output, config) }

    override suspend fun addBackground(sourceUri: Uri, config: BackgroundConfig): AppResult<Uri> =
        uriOp(sourceUri, "background") { input, output -> engine.background(input, output, config) }

    override suspend fun insertImagePage(sourceUri: Uri, position: Int, config: ImageInsertionConfig): AppResult<Uri> =
        uriOp(sourceUri, "with_image") { input, output -> engine.insertImagePage(input, output, position, config) }

    override suspend fun combineImagesToPdf(images: List<Uri>, outputName: String, config: ImageInsertionConfig): AppResult<Uri> = io {
        Uri.fromFile(engine.imagesToPdf(images, createOutputFile(outputName.ifBlank { "combined" }), config))
    }
}
