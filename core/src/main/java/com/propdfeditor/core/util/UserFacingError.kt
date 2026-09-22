package com.propdfeditor.core.util

import com.propdf.core.domain.result.AppException
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Maps a caught [Throwable] to a short, safe, non-technical message suitable for
 * displaying directly to the user (a Snackbar, error state, or dialog).
 *
 * This exists because the app has dozens of call sites that currently do
 * `e.message ?: "X failed"`, which surfaces whatever the underlying library
 * (iText, PDFBox, Room, the OS) happened to put in its exception message --
 * things like "Rebuild failed: trailer not found" or a raw stack-trace class
 * name. That's confusing at best and a support/security-hygiene problem at
 * worst.
 *
 * This is intentionally a single, centralized place for this mapping instead
 * of duplicating string-matching logic at every call site. Callers that need
 * the real diagnostic detail for logging should keep logging [Throwable] as
 * before -- this function is only for the string shown to the user.
 *
 * This does not change control flow, retry behavior, or which exceptions are
 * caught -- it is a pure String -> String mapping applied at the point a
 * message is about to be shown to the user.
 *
 * :core's own AppException/toAppException() (domain.result package) already exists as a
 * typed error hierarchy, but its subtypes (FileNotFound, InvalidPdf, SecurityError, etc.)
 * still fall back to `message ?: "<default>"` when wrapping a real exception -- so any
 * caller reading `AppResult.Error.exception.message` directly still gets the raw
 * underlying message whenever one was present, the same leak this function exists to
 * close. Special-casing AppException here (ignoring its wrapped .message on purpose)
 * lets both error-reporting paths in the app funnel through one safe mapping instead of
 * needing two.
 */
fun Throwable.toSafeUserMessage(fallback: String = "Something went wrong. Please try again."): String {
    if (this is AppException) {
        return when (this) {
            is AppException.FileNotFound -> "The selected file is no longer available."
            is AppException.FileTooLarge -> "This file is too large for this operation."
            is AppException.UnsupportedUri -> "This file location isn't supported. Try selecting it again."
            is AppException.SecurityError -> "Permission was denied for this file. Try selecting it again."
            is AppException.OutOfMemory -> "This document is too large to process on this device."
            is AppException.InvalidPdf -> "This PDF couldn't be processed. It may be corrupted or in an unsupported format."
            is AppException.IOError -> "This document couldn't be saved. Please try again."
            is AppException.RenderingError -> "This page couldn't be displayed."
            is AppException.AnnotationError -> "This annotation couldn't be saved."
            is com.propdf.core.domain.result.PdfProcessingError.InvalidPage -> "That page doesn't exist in this document."
            is com.propdf.core.domain.result.PdfProcessingError.CorruptedFile -> "This PDF couldn't be processed. It may be corrupted or in an unsupported format."
            is com.propdf.core.domain.result.PdfProcessingError.ProcessingFailed -> "This operation could not be completed."
            is AppException.Unknown -> fallback
            else -> fallback
        }
    }

    val className = this::class.java.name
    val lowerMessage = message?.lowercase().orEmpty()

    return when {
        // iText throws this specific type when a PDF requires a password it
        // wasn't given, or the supplied password/permissions are wrong.
        className.endsWith("BadPasswordException") ||
            lowerMessage.contains("password") ->
            "This PDF is password protected."

        this is FileNotFoundException ||
            lowerMessage.contains("no such file") ||
            lowerMessage.contains("enoent") ->
            "The selected file is no longer available."

        this is SecurityException ||
            lowerMessage.contains("permission denial") ||
            lowerMessage.contains("permission is required") ->
            "Permission was denied for this file. Try selecting it again."

        lowerMessage.contains("not enough space") ||
            lowerMessage.contains("no space left") ->
            "Not enough storage space to complete this action."

        // Corrupt/invalid PDF structure -- iText/PDFBox surface this in many
        // different exception classes and messages, so match on the class
        // name pattern shared by iText's own hierarchy plus common phrasing
        // rather than enumerating every concrete type.
        className.contains("itextpdf") ||
            lowerMessage.contains("pdf header") ||
            lowerMessage.contains("trailer") ||
            lowerMessage.contains("xref") ||
            lowerMessage.contains("invalid pdf") ||
            lowerMessage.contains("corrupt") ->
            "This PDF couldn't be processed. It may be corrupted or in an unsupported format."

        this is IOException ->
            "This document couldn't be saved. Please try again."

        else -> fallback
    }
}
