package com.propdfeditor.core.pdf.signature

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * VISUAL SIGNATURE: stamps a signature image (drawn / typed / imported) onto a page.
 *
 * This is NOT a digital signature. It adds no certificate, no CMS data, no ByteRange and no signature
 * dictionary; anyone can copy the image, and nothing proves who placed it or whether the file changed afterwards.
 * Cryptographic signing lives separately in [PdfSignatureEngine.applyDigitalSignature].
 *
 * Built on PDFBox Android 2.0.27.0 (no iText). Rules:
 *  - the source is staged into the app cache first (works for content:// and file:// alike);
 *  - encrypted and already-signed/certified PDFs are refused, never silently altered;
 *  - the result is written to a sibling ".part" file, re-opened and checked, and only then moved into place;
 *  - the caller's rectangle is in DISPLAY space (points, top-left origin), see [VisualSignatureGeometry].
 */
class PdfBoxVisualSignatureEngine(private val context: Context) {

    suspend fun stamp(
        inputUri: Uri,
        outputFile: File,
        signatureBitmap: Bitmap,
        pageNumber: Int,
        rect: RectF,
        opacity: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        if (signatureBitmap.isRecycled || signatureBitmap.width <= 0 || signatureBitmap.height <= 0) {
            throw SignatureEngineException.InvalidImage()
        }

        val staged = File.createTempFile("vsig_in_", ".pdf", context.cacheDir)
        val parent = outputFile.absoluteFile.parentFile ?: context.cacheDir
        val partial = File(parent, outputFile.name + ".part")
        var doc: PDDocument? = null
        var work: Bitmap? = null
        try {
            stage(inputUri, staged)
            doc = openDocument(staged)
            if (doc.isEncrypted) throw SignatureEngineException.EncryptedDocument()
            if (isSignedOrCertified(doc)) throw SignatureEngineException.SignedDocument()
            val pageCount = doc.numberOfPages
            if (pageNumber < 1 || pageNumber > pageCount) throw SignatureEngineException.InvalidPage()

            val page = doc.getPage(pageNumber - 1)
            val box = page.cropBox
            val placement = try {
                VisualSignatureGeometry.place(
                    box.lowerLeftX, box.lowerLeftY, box.upperRightX, box.upperRightY, page.rotation,
                    rect.left, rect.top, rect.right, rect.bottom,
                    signatureBitmap.width, signatureBitmap.height
                )
            } catch (e: IllegalArgumentException) {
                throw SignatureEngineException.InvalidPlacement(e.message ?: "Signature area is not valid")
            }

            val source = if (signatureBitmap.config == Bitmap.Config.ARGB_8888) {
                signatureBitmap
            } else {
                signatureBitmap.copy(Bitmap.Config.ARGB_8888, false)?.also { work = it }
                    ?: throw SignatureEngineException.InvalidImage()
            }
            val image = LosslessFactory.createFromImage(doc, source)
            val alpha = opacity.coerceIn(0f, 1f)

            PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                if (alpha < 1f) {
                    val gs = PDExtendedGraphicsState().apply {
                        setNonStrokingAlphaConstant(alpha)
                        setStrokingAlphaConstant(alpha)
                    }
                    cs.setGraphicsStateParameters(gs)
                }
                cs.drawImage(
                    image,
                    Matrix(placement.a, placement.b, placement.c, placement.d, placement.e, placement.f)
                )
            }

            doc.save(partial)
            doc.close()
            doc = null

            verifyStamped(partial, pageCount)
            publish(partial, outputFile)
        } finally {
            try { doc?.close() } catch (_: Exception) { }
            try { work?.recycle() } catch (_: Exception) { }
            staged.delete()
            if (partial.exists()) partial.delete()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun memory(): MemoryUsageSetting =
        MemoryUsageSetting.setupTempFileOnly().setTempDir(context.cacheDir)

    private fun stage(uri: Uri, target: File) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw SignatureEngineException.UnreadableDocument(e)
        } ?: throw SignatureEngineException.UnreadableDocument()
        input.use { src -> FileOutputStream(target).use { dst -> src.copyTo(dst) } }
        if (target.length() == 0L) throw SignatureEngineException.UnreadableDocument()
    }

    private fun openDocument(file: File): PDDocument = try {
        PDDocument.load(file, "", memory())
    } catch (e: InvalidPasswordException) {
        throw SignatureEngineException.EncryptedDocument(e)
    } catch (e: IOException) {
        throw SignatureEngineException.UnreadableDocument(e)
    }

    /** Signature dictionaries reachable from the AcroForm, or a /Perms /DocMDP certification entry. */
    internal fun isSignedOrCertified(doc: PDDocument): Boolean {
        if (doc.signatureDictionaries.isNotEmpty()) return true
        val perms = doc.documentCatalog.cosObject.getCOSDictionary(COSName.PERMS)
        return perms != null && perms.containsKey(COSName.getPDFName("DocMDP"))
    }

    /** The stamped copy must reopen, keep its page count, and must not have become (or look) signed. */
    private fun verifyStamped(file: File, expectedPages: Int) {
        val check = try {
            openDocument(file)
        } catch (e: SignatureEngineException) {
            throw SignatureEngineException.OutputVerificationFailed("output cannot be reopened")
        }
        try {
            if (check.numberOfPages != expectedPages) {
                throw SignatureEngineException.OutputVerificationFailed("page count changed")
            }
            if (isSignedOrCertified(check)) {
                throw SignatureEngineException.OutputVerificationFailed("output unexpectedly contains a signature")
            }
        } finally {
            try { check.close() } catch (_: Exception) { }
        }
    }

    private fun publish(partial: File, target: File) {
        if (target.exists() && !target.delete()) {
            throw SignatureEngineException.SigningFailed("The signed file could not be saved.")
        }
        if (!partial.renameTo(target)) {
            partial.inputStream().use { src -> FileOutputStream(target).use { dst -> src.copyTo(dst) } }
        }
    }
}
