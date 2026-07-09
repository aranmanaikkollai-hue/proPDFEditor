package com.propdf.scanner.model

import android.graphics.PointF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

@Parcelize
data class DocumentEdge(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) : Parcelable {
    companion object {
        fun fromNormalized(width: Float, height: Float, points: List<PointF>): DocumentEdge {
            return DocumentEdge(
                topLeft = PointF(points[0].x * width, points[0].y * height),
                topRight = PointF(points[1].x * width, points[1].y * height),
                bottomRight = PointF(points[2].x * width, points[2].y * height),
                bottomLeft = PointF(points[3].x * width, points[3].y * height)
            )
        }
    }

    fun toNormalized(width: Float, height: Float): List<PointF> {
        return listOf(
            PointF(topLeft.x / width, topLeft.y / height),
            PointF(topRight.x / width, topRight.y / height),
            PointF(bottomRight.x / width, bottomRight.y / height),
            PointF(bottomLeft.x / width, bottomLeft.y / height)
        )
    }

    fun isValid(): Boolean {
        return topLeft.x >= 0 && topLeft.y >= 0 &&
               topRight.x >= 0 && topRight.y >= 0 &&
               bottomRight.x >= 0 && bottomRight.y >= 0 &&
               bottomLeft.x >= 0 && bottomLeft.y >= 0
    }
}

enum class ScanMode {
    AUTO, DOCUMENT, BOOK, ID_CARD, PASSPORT, RECEIPT, WHITEBOARD, BUSINESS_CARD, BATCH
}

enum class ColorFilter {
    ORIGINAL, MAGIC_COLOR, GRAYSCALE, BLACK_WHITE, SHADOW_REMOVAL, NOISE_REMOVAL, GLARE_REMOVAL
}

@Parcelize
data class ScannedPage(
    val id: String = UUID.randomUUID().toString(),
    val originalImagePath: String,
    val processedImagePath: String? = null,
    val documentEdge: DocumentEdge? = null,
    val colorFilter: ColorFilter = ColorFilter.ORIGINAL,
    val rotation: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val pageNumber: Int = 0
) : Parcelable

@Parcelize
data class ScannedDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val pages: List<ScannedPage>,
    val scanMode: ScanMode = ScanMode.AUTO,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) : Parcelable

sealed class ScannerUiState {
    object Idle : ScannerUiState()
    object Preview : ScannerUiState()
    object DetectingEdges : ScannerUiState()
    object Capturing : ScannerUiState()
    data class Processing(val progress: Int, val total: Int) : ScannerUiState()
    data class EdgeDetected(val edge: DocumentEdge) : ScannerUiState()
    data class Error(val message: String) : ScannerUiState()
}

sealed class BatchScanState {
    object Idle : BatchScanState()
    data class Scanning(val currentPage: Int, val totalPages: Int) : BatchScanState()
    data class Processing(val progress: Int) : BatchScanState()
    data class Complete(val document: ScannedDocument) : BatchScanState()
    data class Error(val message: String) : BatchScanState()
}

enum class ExportFormat { PDF, JPEG, PNG, TIFF }

data class ExportConfig(
    val format: ExportFormat = ExportFormat.PDF,
    val quality: Int = 95,
    val pageSize: PageSize = PageSize.A4,
    val colorMode: ColorFilter = ColorFilter.ORIGINAL,
    val compressImages: Boolean = true
)

enum class PageSize { A4, LETTER, LEGAL, AUTO }
