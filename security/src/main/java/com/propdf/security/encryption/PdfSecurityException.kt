package com.propdf.security.encryption

/**
 * Typed failures of the PDFBox password-security engine. Every message is written for the person
 * using the app, contains no file paths or library detail, and never contains a password.
 */
sealed class PdfSecurityException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The PDF needs a password and none was supplied. */
    class PasswordRequired(cause: Throwable? = null) :
        PdfSecurityException("This PDF is password protected. Enter its password to continue.", cause)

    /** A password was supplied but it does not open the PDF. */
    class WrongPassword(cause: Throwable? = null) :
        PdfSecurityException("The password is incorrect.", cause)

    /** The PDF opened, but only with user-level rights; the operation needs the owner password. */
    class OwnerPasswordRequired :
        PdfSecurityException("The owner password is required to remove this PDF's protection or change its permissions.")

    class AlreadyEncrypted(cause: Throwable? = null) :
        PdfSecurityException("This PDF is already password protected. Remove the password first.", cause)

    class NotEncrypted :
        PdfSecurityException("This PDF is not password protected.")

    class SignedDocument :
        PdfSecurityException("This PDF is digitally signed. Changing its security would break the signature, so nothing was changed.")

    /** The password has characters the chosen encryption level cannot store faithfully (RC4 / AES-128 are Latin-1 only). */
    class UnsupportedPassword :
        PdfSecurityException(
            "This password contains characters that the selected encryption level cannot store. " +
                "Use AES-256 or a password with Latin characters only."
        )

    class BlankOwnerPassword :
        PdfSecurityException("An owner password is required to set permissions.")

    /** The freshly written file did not pass the reopen check, so it was discarded. [detail] is for logs only. */
    class VerificationFailed(val detail: String) :
        PdfSecurityException("The protected file could not be verified, so it was not saved.")

    // ---- Secure redaction (PdfBoxRedactionEngine) ----

    /** No areas were marked, so there is nothing to redact. */
    class RedactionNoRegions :
        PdfSecurityException("Mark at least one area to redact before saving.")

    /** The source PDF is password protected; redaction refuses it rather than write an unprotected copy. */
    class RedactionProtectedSource :
        PdfSecurityException("This PDF is password protected. Remove the password first, then redact it.")

    /** Redaction rebuilds pages, which would break a digital signature. */
    class RedactionSignedDocument :
        PdfSecurityException("This PDF is digitally signed. Redacting it would break the signature, so nothing was changed.")

    /** A marked area is outside the document or has no usable size. */
    class RedactionInvalidRegion :
        PdfSecurityException("One of the marked areas is not valid. Adjust the marked areas and try again.")

    /** The redacted file did not pass the post-write checks, so it was discarded. [detail] is for logs only. */
    class RedactionVerificationFailed(val detail: String) :
        PdfSecurityException("The redacted file could not be verified, so it was not saved.")
}

/** User-facing text for a security failure, or null when [this] is not one of ours. */
fun Throwable.securityUserMessage(): String? = (this as? PdfSecurityException)?.message
