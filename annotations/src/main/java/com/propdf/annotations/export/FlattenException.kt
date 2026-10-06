package com.propdf.annotations.export

/**
 * Typed failures for annotation flatten / burn. Messages are written for the person using the app: no paths,
 * no library detail, never a password.
 */
sealed class FlattenException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** A flatten/burn rewrites the whole file, which would break (or silently drop) an existing signature. */
    class SignedDocument :
        FlattenException(
            "This PDF is digitally signed. Flattening would invalidate its signature, so nothing was changed."
        )

    class EncryptedDocument(cause: Throwable? = null) :
        FlattenException("This PDF is password protected. Remove the password before flattening.", cause)

    class UnreadableDocument(cause: Throwable? = null) :
        FlattenException("This PDF could not be read.", cause)

    class InvalidPage(pageIndex: Int) :
        FlattenException("An annotation refers to page ${pageIndex + 1}, which does not exist in this document.")

    /** The file that was just written failed the post-write check and was discarded. [detail] is for logs only. */
    class OutputVerificationFailed(val detail: String) :
        FlattenException("The flattened file could not be verified, so it was not saved.")

    /**
     * A text annotation contains characters the built-in PDF fonts cannot encode (for example Tamil, Arabic or
     * CJK). Flatten refuses instead of dropping or garbling the text. Burn (image) export can render them.
     */
    class UnsupportedCharacters :
        FlattenException(
            "A text annotation contains characters that cannot be written into the PDF text yet " +
                "(for example Tamil, Arabic or Chinese/Japanese/Korean). Nothing was changed. " +
                "Use the image (burn) export instead."
        )

    class WriteFailed(cause: Throwable? = null) :
        FlattenException("The flattened file could not be written.", cause)
}
