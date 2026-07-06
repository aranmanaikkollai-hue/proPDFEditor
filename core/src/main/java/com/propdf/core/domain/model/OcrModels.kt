package com.propdf.core.domain.model

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

/**
 * OCR Language supported by ML Kit offline models.
 * Each maps to a specific ML Kit TextRecognizerOptions.
 */
enum class OcrLanguage(
    val code: String,
    val displayName: String,
    val mlKitTag: String,
    val script: Script
) {
    // Latin script languages
    ENGLISH("en", "English", "Latin", Script.LATIN),
    FRENCH("fr", "French", "Latin", Script.LATIN),
    GERMAN("de", "German", "Latin", Script.LATIN),
    SPANISH("es", "Spanish", "Latin", Script.LATIN),
    PORTUGUESE("pt", "Portuguese", "Latin", Script.LATIN),
    ITALIAN("it", "Italian", "Latin", Script.LATIN),
    DUTCH("nl", "Dutch", "Latin", Script.LATIN),
    POLISH("pl", "Polish", "Latin", Script.LATIN),
    TURKISH("tr", "Turkish", "Latin", Script.LATIN),
    INDONESIAN("id", "Indonesian", "Latin", Script.LATIN),
    VIETNAMESE("vi", "Vietnamese", "Latin", Script.LATIN),

    // Devanagari script (Hindi, Marathi, Sanskrit, Nepali)
    HINDI("hi", "Hindi", "Devanagari", Script.DEVANAGARI),
    MARATHI("mr", "Marathi", "Devanagari", Script.DEVANAGARI),
    SANSKRIT("sa", "Sanskrit", "Devanagari", Script.DEVANAGARI),
    NEPALI("ne", "Nepali", "Devanagari", Script.DEVANAGARI),

    // Tamil — NOTE: ML Kit v2 does NOT have a dedicated Tamil model.
    // Tamil is partially supported by the Latin recognizer for transliterated text,
    // and by the Devanagari model for some characters.
    // For full Tamil, we use the Latin recognizer with auto-language as fallback.
    TAMIL("ta", "Tamil", "Latin", Script.TAMIL),

    // East Asian
    JAPANESE("ja", "Japanese", "Japanese", Script.JAPANESE),
    KOREAN("ko", "Korean", "Korean", Script.KOREAN),
    CHINESE_SIMPLIFIED("zh-Hans", "Chinese (Simplified)", "Chinese", Script.CHINESE),
    CHINESE_TRADITIONAL("zh-Hant", "Chinese (Traditional)", "Chinese", Script.CHINESE),

    // Auto-detect
    AUTO("auto", "Auto Detect", "Auto", Script.AUTO);

    companion object {
        fun fromCode(code: String): OcrLanguage = values().find { it.code == code } ?: AUTO

        /** Languages available for offline use (bundled with APK). */
        fun offlineLanguages(): List<OcrLanguage> = listOf(
            ENGLISH, FRENCH, GERMAN, SPANISH, PORTUGUESE, ITALIAN,
            DUTCH, POLISH, TURKISH, INDONESIAN, VIETNAMESE,
            HINDI, MARATHI, SANSKRIT, NEPALI,
            TAMIL,
            JAPANESE, KOREAN,
            CHINESE_SIMPLIFIED, CHINESE_TRADITIONAL
        )

        /** Indian region languages supported offline. */
        fun indianLanguages(): List<OcrLanguage> = listOf(
            HINDI, MARATHI, SANSKRIT, NEPALI, TAMIL, ENGLISH
        )
    }
}

enum class Script {
    LATIN, DEVANAGARI, TAMIL, JAPANESE, KOREAN, CHINESE, AUTO
}

/**
 * OCR preprocessing options applied before recognition.
 */
@Parcelize
data class OcrPreprocessConfig(
    val enableDeskew: Boolean = true,
    val enableDenoise: Boolean = true,
    val enablePerspectiveCorrection: Boolean = true,
    val enableContrastEnhance: Boolean = true,
    val targetDpi: Int = 300,
    val binarizeThreshold: Int = 128
) : Parcelable

/**
 * OCR recognition configuration.
 */
@Parcelize
data class OcrConfig(
    val languages: List<OcrLanguage> = listOf(OcrLanguage.AUTO),
    val preprocessConfig: OcrPreprocessConfig = OcrPreprocessConfig(),
    val enableHandwriting: Boolean = false,
    val enableTableDetection: Boolean = false,
    val cropRect: @RawValue android.graphics.Rect? = null,
    val confidenceThreshold: Float = 0.5f
) : Parcelable

/**
 * A single recognized text block with bounding box and confidence.
 */
@Parcelize
data class OcrTextBlock(
    val text: String,
    val boundingBox: @RawValue RectF,
    val confidence: Float,
    val language: String,
    val lines: List<OcrTextLine>
) : Parcelable

/**
 * A line of recognized text.
 */
@Parcelize
data class OcrTextLine(
    val text: String,
    val boundingBox: @RawValue RectF,
    val confidence: Float,
    val elements: List<OcrTextElement>
) : Parcelable

/**
 * A single word/element of recognized text.
 */
@Parcelize
data class OcrTextElement(
    val text: String,
    val boundingBox: @RawValue RectF,
    val confidence: Float,
    val recognizedLanguage: String
) : Parcelable

/**
 * Complete OCR result for a single image/page.
 */
@Parcelize
data class OcrPageResult(
    val pageIndex: Int,
    val fullText: String,
    val blocks: List<OcrTextBlock>,
    val imageWidth: Int,
    val imageHeight: Int,
    val processingTimeMs: Long,
    val detectedLanguages: List<String>
) : Parcelable

/**
 * Batch OCR request for multiple images.
 */
@Parcelize
data class OcrBatchRequest(
    val imageUris: List<String>,
    val config: OcrConfig,
    val outputFormat: OcrOutputFormat = OcrOutputFormat.TEXT
) : Parcelable

/**
 * OCR output formats for export.
 */
enum class OcrOutputFormat {
    TEXT, PDF, TXT, DOCX
}

/**
 * OCR job status for WorkManager tracking.
 */
enum class OcrJobStatus {
    PENDING, DOWNLOADING_MODELS, PREPROCESSING, RECOGNIZING,
    CORRECTING, EXPORTING, COMPLETED, FAILED, CANCELLED
}

/**
 * OCR job entity for Room persistence.
 */
@Parcelize
data class OcrJob(
    val id: String,
    val request: OcrBatchRequest,
    val status: OcrJobStatus,
    val progress: Int = 0,
    val totalPages: Int = 0,
    val completedPages: Int = 0,
    val resultUri: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
) : Parcelable

/**
 * Detected table structure from Table OCR.
 */
@Parcelize
data class OcrTable(
    val rows: Int,
    val cols: Int,
    val cells: List<OcrTableCell>
) : Parcelable

@Parcelize
data class OcrTableCell(
    val row: Int,
    val col: Int,
    val text: String,
    val boundingBox: @RawValue RectF,
    val confidence: Float
) : Parcelable

/**
 * Handwriting detection result.
 */
@Parcelize
data class HandwritingResult(
    val hasHandwriting: Boolean,
    val confidence: Float,
    val regions: List<@RawValue RectF>
) : Parcelable
