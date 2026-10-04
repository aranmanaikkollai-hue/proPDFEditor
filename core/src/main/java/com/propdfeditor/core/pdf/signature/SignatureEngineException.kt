package com.propdfeditor.core.pdf.signature

/**
 * Typed failures of the signature engines. Every message is written for the person using the app:
 * no file paths, no library detail, never a password or key material.
 */
sealed class SignatureEngineException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class SignedDocument :
        SignatureEngineException(
            "This PDF is already digitally signed. Adding a visible signature image would break its existing " +
                "signature, so nothing was changed."
        )

    class CertifiedDocument :
        SignatureEngineException(
            "This PDF is certified and does not allow further signatures, so nothing was changed."
        )

    class EncryptedDocument(cause: Throwable? = null) :
        SignatureEngineException("This PDF is password protected. Remove the password before signing it.", cause)

    class UnreadableDocument(cause: Throwable? = null) :
        SignatureEngineException("This PDF could not be read.", cause)

    class InvalidPage :
        SignatureEngineException("The selected page does not exist in this document.")

    class InvalidPlacement(detail: String) :
        SignatureEngineException(detail)

    class InvalidImage :
        SignatureEngineException("The signature image could not be used.")

    /** Wrong keystore password, damaged file, or no usable signing key in it. */
    class KeystoreProblem(message: String, cause: Throwable? = null) : SignatureEngineException(message, cause)

    /** The signing certificate is expired or not yet valid. */
    class CertificateProblem(message: String) : SignatureEngineException(message)

    class Unsupported(message: String) : SignatureEngineException(message)

    /** Signing failed, or the freshly written file did not pass the post-write check, so it was discarded. */
    class SigningFailed(message: String = "The document could not be signed.", cause: Throwable? = null) :
        SignatureEngineException(message, cause)

    /** Output could not be verified after writing, so it was discarded. [detail] is for logs only. */
    class OutputVerificationFailed(val detail: String) :
        SignatureEngineException("The signed file could not be verified, so it was not saved.")
}
