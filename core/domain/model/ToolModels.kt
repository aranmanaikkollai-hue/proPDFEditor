package com.propdf.core.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MergeRequest(
    val inputUris: List<String>,
    val outputName: String
) : Parcelable

@Parcelize
data class SplitRequest(
    val inputUri: String,
    val ranges: List<IntRange>,
    val outputDir: String
) : Parcelable

@Parcelize
data class CompressConfig(
    val level: Int = 6,
    val targetSizeBytes: Long? = null
) : Parcelable

@Parcelize
data class WatermarkConfig(
    val text: String = "CONFIDENTIAL",
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val fontSize: Float = 60f
) : Parcelable

@Parcelize
data class SecurityConfig(
    val userPassword: String? = null,
    val ownerPassword: String = "",
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = false
) : Parcelable

@Parcelize
data class HeaderFooterConfig(
    val headerText: String? = null,
    val footerText: String? = null,
    val fontSize: Float = 10f,
    val headerAlignment: String = "center",
    val footerAlignment: String = "center"
) : Parcelable

@Parcelize
data class PageNumberConfig(
    val format: String = "Page %d of %d",
    val placement: String = "bottom",
    val alignment: String = "center",
    val fontSize: Float = 10f
) : Parcelable
