package com.propdf.core.domain.model

import android.graphics.Bitmap
import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Represents a single page in a PDF document for editing operations.
 */
@Parcelize
data class PdfPage(
    val pageNumber: Int,
    val thumbnailUri: Uri? = null,
    val width: Float = 0f,
    val height: Float = 0f,
    val rotation: Int = 0
) : Parcelable

/**
 * Configuration for page manipulation operations.
 */
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

/**
 * Configuration for splitting PDFs.
 */
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

/**
 * Configuration for merging PDFs.
 */
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

/**
 * Configuration for adding page numbers.
 */
@Parcelize
data class PageNumberConfig(
    val startNumber: Int = 1,
    val position: PageNumberPosition = PageNumberPosition.BOTTOM_CENTER,
    val format: String = "{n}",
    val fontSize: Float = 12f,
    val color: Int = 0xFF000000.toInt(),
    val startPage: Int = 1,
    val endPage: Int = -1
) : Parcelable

enum class PageNumberPosition {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT
}

/**
 * Configuration for headers and footers.
 */
@Parcelize
data class HeaderFooterConfig(
    val headerText: String = "",
    val footerText: String = "",
    val headerFontSize: Float = 10f,
    val footerFontSize: Float = 10f,
    val color: Int = 0xFF666666.toInt(),
    val marginTop: Float = 36f,
    val marginBottom: Float = 36f
) : Parcelable

/**
 * Configuration for watermarks.
 */
@Parcelize
data class WatermarkConfig(
    val text: String = "",
    val imageUri: Uri? = null,
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val fontSize: Float = 48f,
    val color: Int = 0xFF000000.toInt(),
    val position: WatermarkPosition = WatermarkPosition.CENTER,
    val pages: List<Int> = emptyList()
) : Parcelable

enum class WatermarkPosition {
    CENTER, TILE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT
}

/**
 * Configuration for background operations.
 */
@Parcelize
data class BackgroundConfig(
    val color: Int = 0xFFFFFFFF.toInt(),
    val imageUri: Uri? = null,
    val opacity: Float = 1.0f,
    val pages: List<Int> = emptyList()
) : Parcelable

/**
 * Configuration for PDF compression/optimization.
 */
@Parcelize
data class CompressConfig(
    val imageQuality: Int = 80,
    val compressImages: Boolean = true,
    val removeMetadata: Boolean = false,
    val flattenForms: Boolean = false,
    val removeUnusedObjects: Boolean = true,
    val targetDpi: Int = 150
) : Parcelable

/**
 * Configuration for image insertion.
 */
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

/**
 * Configuration for page cropping.
 */
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

/**
 * Configuration for page resizing.
 */
@Parcelize
data class ResizeConfig(
    val targetWidth: Float,
    val targetHeight: Float,
    val unit: MeasurementUnit = MeasurementUnit.POINT,
    val scaleContent: Boolean = true,
    val keepAspectRatio: Boolean = false
) : Parcelable

/**
 * Result of a PDF operation.
 */
@Parcelize
data class PdfOperationResult(
    val success: Boolean,
    val outputUri: Uri? = null,
    val pagesAffected: Int = 0,
    val bytesSaved: Long = 0,
    val errorMessage: String? = null
) : Parcelable

/**
 * Progress tracking for long-running PDF operations.
 */
@Parcelize
data class OperationProgress(
    val operationId: String,
    val currentStep: Int,
    val totalSteps: Int,
    val message: String
) : Parcelable

/**
 * UI state for page editor.
 */
data class PageEditorUiState(
    val isLoading: Boolean = false,
    val pages: List<PageItem> = emptyList(),
    val pageCount: Int = 0,
    val error: String? = null,
    val operationInProgress: Boolean = false,
    val operationProgress: OperationProgress? = null
)

/**
 * Individual page item for UI display.
 */
data class PageItem(
    val pageNumber: Int,
    val thumbnail: Bitmap? = null,
    val isSelected: Boolean = false,
    val isDragging: Boolean = false
)

/**
 * Operation result sealed class for UI layer.
 */
sealed class OperationResult {
    data class Success(val uri: Uri, val message: String = "") : OperationResult()
    data class Error(val message: String?) : OperationResult()
    data class Progress(val progress: OperationProgress) : OperationResult()
}
