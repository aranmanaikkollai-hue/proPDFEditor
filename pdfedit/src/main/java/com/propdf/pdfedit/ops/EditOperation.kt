package com.propdf.pdfedit.ops

import com.propdf.pdfedit.geometry.PointPds
import com.propdf.pdfedit.geometry.RectPds
import java.io.InputStream

/** Opens a font program (TrueType) when needed. A new stream must be returned on every call. */
fun interface FontSource {
    fun open(): InputStream
}

/** Supplies the encoded bytes (JPEG or PNG) of an image to add. */
fun interface ImageSource {
    fun readBytes(): ByteArray
}

/** Which font new text uses. */
sealed interface FontChoice {
    /** The open-licence font bundled with the app (Liberation Sans Regular). */
    object BundledRegular : FontChoice

    /** The open-licence font bundled with the app (Liberation Sans Bold). */
    object BundledBold : FontChoice

    /** A font file found on the device. Plain TrueType/OpenType files only, not .ttc collections. */
    class DeviceFont(val label: String, val source: FontSource) : FontChoice
}

/**
 * Identifies one drawing of an image that already exists in the PDF: the [ordinal]-th image the page's content
 * stream draws, in drawing order. It stays valid because edits are always replayed on the untouched base file.
 */
data class ImageRef(val pageIndex: Int, val ordinal: Int, val xObjectName: String)

/** One user edit. Operations are plain data; they are replayed on the original file to produce the output. */
sealed interface EditOperation {
    val id: String
    val pageIndex: Int
}

/** New text. [origin] is the left end of the first baseline in Page Display Space. Lines split at newline. */
class AddText(
    override val id: String,
    override val pageIndex: Int,
    val text: String,
    val origin: PointPds,
    val fontSizePt: Double,
    val font: FontChoice = FontChoice.BundledRegular,
    val colorRgb: Int = 0x000000
) : EditOperation

/** A new image drawn into [bounds] (Page Display Space). */
class AddImage(
    override val id: String,
    override val pageIndex: Int,
    val bounds: RectPds,
    val image: ImageSource
) : EditOperation

/** Moves or resizes an image already in the PDF so it fills [newBounds]. Any rotation it had becomes upright. */
class TransformImage(
    override val id: String,
    override val pageIndex: Int,
    val target: ImageRef,
    val newBounds: RectPds
) : EditOperation

/** Removes one drawing of an image already in the PDF. */
class DeleteImage(
    override val id: String,
    override val pageIndex: Int,
    val target: ImageRef
) : EditOperation
