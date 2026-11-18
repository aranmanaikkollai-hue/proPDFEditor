package com.propdf.pdfedit.engine

import com.propdf.pdfedit.geometry.RectPds
import com.propdf.pdfedit.ops.ImageRef

enum class EditWarning {
    /** The file has a digital signature; rewriting the file makes it invalid. */
    SIGNATURE_WILL_BE_INVALID,

    /** The file is a certified document (DocMDP); changes may be flagged by viewers. */
    CERTIFIED_DOCUMENT,

    /** Some characters could not be drawn by the chosen font and were replaced or removed. */
    CHARACTERS_REPLACED,

    /** An image operation pointed at an image drawing that is not in the file (the base file changed). */
    STALE_IMAGE_REFERENCE,

    /** An image could not be moved because its transformation matrix cannot be inverted. */
    IMAGE_NOT_TRANSFORMABLE,

    /** An operation had an empty or invalid rectangle and was skipped. */
    EMPTY_BOUNDS,

    /** An image could not be decoded and was skipped. */
    IMAGE_UNREADABLE,

    /** A device font could not be loaded; the bundled font was used instead. */
    FONT_UNAVAILABLE
}

class EditReport(
    val warnings: Set<EditWarning>,
    /** Operation id to the characters that were replaced or removed in it. */
    val replacedCharacters: Map<String, List<String>>,
    /** Ids of operations that were skipped, with the reason as a warning. */
    val skippedOperations: Map<String, EditWarning>,
    val pageCount: Int
)

class ImageInfo(val ref: ImageRef, val bounds: RectPds)

class PageInfo(
    val pageIndex: Int,
    val widthPts: Double,
    val heightPts: Double,
    val rotation: Int,
    /** Images drawn directly by the page content, in drawing order. These can be moved or deleted. */
    val images: List<ImageInfo>,
    /** Inline images (BI/EI) are not supported for moving or deleting. */
    val inlineImages: Int,
    /** Drawings of form XObjects; images inside them are not reachable by this engine. */
    val formDrawings: Int
)

class DocumentInfo(
    val pageCount: Int,
    val signed: Boolean,
    val certified: Boolean,
    val pages: List<PageInfo>
)

class EditRefusedException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason { ENCRYPTED, UNREADABLE, PAGE_OUT_OF_RANGE, SAME_AS_SOURCE }
}

/** Thrown when the written file did not pass its own checks; the output file is deleted first. */
class EditVerificationException(val problems: List<String>) : Exception(problems.joinToString("; "))
