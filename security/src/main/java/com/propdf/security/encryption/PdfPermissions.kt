package com.propdf.security.encryption

/**
 * PDF user-access permission bits, independent of any PDF library.
 *
 * The numeric values are IDENTICAL to the iText 7 `EncryptionConstants.ALLOW_*` values that the
 * app used before the PDFBox migration, so permission masks that are persisted (for example
 * `SecureDocumentEntity.permissions`) or built by the existing UI keep their meaning. They map
 * onto the bits of the PDF `/P` entry (ISO 32000-1, table 22):
 *
 *  - bit 3  (value 4)    print (low quality unless bit 12 is also set)
 *  - bit 4  (value 8)    modify contents
 *  - bit 5  (value 16)   copy / extract text and graphics
 *  - bit 6  (value 32)   add or modify annotations
 *  - bit 9  (value 256)  fill in form fields
 *  - bit 10 (value 512)  extract for accessibility (screen readers)
 *  - bit 11 (value 1024) assemble (insert, rotate, delete pages)
 *  - bit 12 (value 2048) print high quality
 *
 * A mask lists what is ALLOWED. A bit that is not in the mask is denied. [ALLOW_PRINTING]
 * therefore means "print at full quality" (bits 3 + 12) and [ALLOW_DEGRADED_PRINTING] means
 * "print at low quality only" (bit 3).
 */
object PdfPermissions {
    const val ALLOW_DEGRADED_PRINTING = 4
    const val ALLOW_PRINTING = 4 + 2048
    const val ALLOW_MODIFY_CONTENTS = 8
    const val ALLOW_COPY = 16
    const val ALLOW_MODIFY_ANNOTATIONS = 32
    const val ALLOW_FILL_IN = 256
    const val ALLOW_SCREENREADERS = 512
    const val ALLOW_ASSEMBLY = 1024

    /** Every permission bit the app knows about. */
    const val ALL = ALLOW_PRINTING or ALLOW_MODIFY_CONTENTS or ALLOW_COPY or ALLOW_MODIFY_ANNOTATIONS or
        ALLOW_FILL_IN or ALLOW_SCREENREADERS or ALLOW_ASSEMBLY

    /** Reserved /P bits that must be 1 for revision 3+ (0xFFFFF0C0). */
    private const val RESERVED_R3 = -3904

    /** Reserved /P bits that must be 1 for revision 2 (0xFFFFFFC0). */
    private const val RESERVED_R2 = -64

    /**
     * Builds the signed 32-bit /P value for an allow-mask. Bits outside the known permission bits
     * are dropped, reserved bits are set, and bits 0-1 stay zero as the specification requires.
     */
    fun toPValue(allowMask: Int, revision2: Boolean = false): Int =
        (allowMask and ALL) or (if (revision2) RESERVED_R2 else RESERVED_R3)

    /** True when every bit of [flag] is allowed by the /P value [pValue]. */
    fun isAllowed(pValue: Int, flag: Int): Boolean = (pValue and flag) == flag

    /** Reduces a /P value (or any mask) to just the permission bits the app models. */
    fun allowedBits(pValue: Int): Int = pValue and ALL
}
