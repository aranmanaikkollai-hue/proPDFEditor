package com.propdf.core.domain.model

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

// ==================== EXISTING MODELS (PRESERVED) ====================

@Parcelize
data class MergeRequest(
    val inputUris: List<Uri>,
    val outputName: String
) : Parcelable

@Parcelize
data class SplitRequest(
    val inputUri: String,
    val ranges: @RawValue List<IntRange>,
    val outputDir: String
) : Parcelable

@Parcelize
data class SecurityConfig(
    val userPassword: String? = null,
    val ownerPassword: String = "",
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = false
) : Parcelable

// ==================== MERGED & ENHANCED MODELS ====================

/**
 * Configuration for PDF compression/optimization.
 * Merged from both PdfPageEditModels.kt and ToolModels.kt
 */
@Parcelize
data class CompressConfig(
    val level: Int = 6,
    val targetSizeBytes: Long? = null,
    val imageQuality: Int = 80,
    val compressImages: Boolean = true,
    val removeMetadata: Boolean = false,
    val flattenForms: Boolean = false,
    val removeUnusedObjects: Boolean = true,
    val targetDpi: Int = 150
) : Parcelable

/**
 * Configuration for watermarks.
 * Merged from both PdfPageEditModels.kt and ToolModels.kt
 */
@Parcelize
data class WatermarkConfig(
    val text: String = "CONFIDENTIAL",
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val fontSize: Float = 60f,
    val imageUri: Uri? = null,
    val color: Int = 0xFF000000.toInt(),
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    val pages: List<Int> = emptyList()
) : Parcelable

enum class WatermarkPosition {
    CENTER, TILE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

/**
 * Configuration for headers and footers.
 * Merged from both PdfPageEditModels.kt and ToolModels.kt
 */
@Parcelize
data class HeaderFooterConfig(
    val headerText: String? = null,
    val footerText: String? = null,
    val fontSize: Float = 10f,
    val headerAlignment: String = "center",
    val footerAlignment: String = "center",
    val headerFontSize: Float = 10f,
    val footerFontSize: Float = 10f,
    val color: Int = 0xFF666666.toInt(),
    val marginTop: Float = 36f,
    val marginBottom: Float = 36f
) : Parcelable

/**
 * Configuration for adding page numbers.
 * Merged from both PdfPageEditModels.kt and ToolModels.kt
 */
@Parcelize
data class PageNumberConfig(
    val format: String = "Page %d of %d",
    val placement: String = "bottom",
    val alignment: String = "center",
    val fontSize: Float = 10f,
    val startNumber: Int = 1,
    val position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
    val startPage: Int = 1,
    val endPage: Int = -1,
    val color: Int = 0xFF000000.toInt()
) : Parcelable

enum class PageNumberPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

// ==================== NEW MODELS (from PdfPageEditModels.kt) ====================

@Parcelize
data class PdfPage(
    val pageNumber: Int,
    val thumbnailUri: Uri? = null,
    val width: Float = 0f,
    val height: Float = 0f,
    val rotation: Int = 0
) : Parcelable

@Parcelize
data class PageManipulationConfig(
    val sourcePages: List<Int>,
    val targetPosition: Int = -1,
    val operation: PageOperation
) : Parcelable

enum class PageOperation {
    DELETE,
    DUPLICATE,
    MOVE,
    EXTRACT,
    ROTATE,
    CROP,
    RESIZE,
    MIRROR
}

@Parcelize
data class SplitConfig(
    val splitType: SplitType,
    val splitValue: String = "",
    val outputPrefix: String = "split"
) : Parcelable

enum class SplitType {
    BY_SIZE_MB,
    BY_BOOKMARK,
    EVERY_N_PAGES,
    BY_PAGE_RANGE
}

@Parcelize
data class MergeConfig(
    val sourceUris: List<Uri>,
    val outputFileName: String,
    val bookmarkMode: BookmarkMergeMode = BookmarkMergeMode.PRESERVE_ALL
) : Parcelable

enum class BookmarkMergeMode {
    PRESERVE_ALL,
    FLATTEN,
    RENAME_WITH_PREFIX
}

@Parcelize
data class BackgroundConfig(
    val color: Int = 0xFFFFFFFF.toInt(),
    val imageUri: Uri? = null,
    val opacity: Float = 1.0f,
    val pages: List<Int> = emptyList()
) : Parcelable

@Parcelize
data class ImageInsertionConfig(
    val imageUri: Uri,
    val pageWidth: Float = 595f,
    val pageHeight: Float = 842f,
    val fitMode: ImageFitMode = ImageFitMode.FIT_CENTER,
    val margin: Float = 36f
) : Parcelable

enum class ImageFitMode {
    FILL, FIT_CENTER, FIT_WIDTH, FIT_HEIGHT, ORIGINAL
}

@Parcelize
data class CropConfig(
    val leftMargin: Float = 0f,
    val rightMargin: Float = 0f,
    val topMargin: Float = 0f,
    val bottomMargin: Float = 0f,
    val unit: MeasurementUnit = MeasurementUnit.POINT
) : Parcelable

enum class MeasurementUnit {
    POINT, MM, INCH, CM
}

@Parcelize
data class ResizeConfig(
    val targetWidth: Float,
    val targetHeight: Float,
    val unit: MeasurementUnit = MeasurementUnit.POINT,
    val scaleContent: Boolean = true,
    val keepAspectRatio: Boolean = false
) : Parcelable

@Parcelize
data class PdfOperationResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val pagesAffected: Int = 0,
    val bytesSaved: Long = 0,
    val errorMessage: String? = null
) : Parcelable

@Parcelize
data class OperationProgress(
    val operationId: String,
    val currentStep: Int,
    val totalSteps: Int,
    val message: String
) : Parcelable

// ==================== UI STATE MODELS (NON-PARCELABLE) ====================

data class PageEditorUiState(
    val isLoading: Boolean = false,
    val pages: List<PageItem> = emptyList(),
    val pageCount: Int = 0,
    val error: String? = null,
    val operationInProgress: Boolean = false,
    val operationProgress: OperationProgress? = null
)

data class PageItem(
    val pageNumber: Int,
    val thumbnail: Bitmap? = null,
    val isSelected: Boolean = false,
    val isDragging: Boolean = false
)

sealed class OperationResult {
    data class Success(val uri: Uri, val message: String = "") : OperationResult()
    data class Error(val message: String?) : OperationResult()
    data class Progress(val progress: OperationProgress) : OperationResult()
}
