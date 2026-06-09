package com.propdf.core.domain.model

import android.os.Parcel
import android.os.Parcelable

// ── MergeRequest ─────────────────────────────────────────────────────────────

data class MergeRequest(
    val inputUris: List<String>,
    val outputName: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.createStringArrayList() ?: emptyList(),
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStringList(inputUris)
        parcel.writeString(outputName)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<MergeRequest> {
        override fun createFromParcel(parcel: Parcel): MergeRequest = MergeRequest(parcel)
        override fun newArray(size: Int): Array<MergeRequest?> = arrayOfNulls(size)
    }
}

// ── PageRange (replaces Pair<Int,Int> to avoid @RawValue) ────────────────────

data class PageRange(
    val start: Int,
    val end: Int
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(start)
        parcel.writeInt(end)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PageRange> {
        override fun createFromParcel(parcel: Parcel): PageRange = PageRange(parcel)
        override fun newArray(size: Int): Array<PageRange?> = arrayOfNulls(size)
    }
}

// ── SplitRequest ─────────────────────────────────────────────────────────────

data class SplitRequest(
    val inputUri: String,
    val ranges: List<PageRange>,
    val outputDir: String
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "",
        parcel.createTypedArrayList(PageRange.CREATOR) ?: emptyList(),
        parcel.readString() ?: ""
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(inputUri)
        parcel.writeTypedList(ranges)
        parcel.writeString(outputDir)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SplitRequest> {
        override fun createFromParcel(parcel: Parcel): SplitRequest = SplitRequest(parcel)
        override fun newArray(size: Int): Array<SplitRequest?> = arrayOfNulls(size)
    }
}

// ── CompressConfig ───────────────────────────────────────────────────────────

data class CompressConfig(
    val level: Int = 6,
    val targetSizeBytes: Long? = null
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readInt(),
        parcel.readValue(Long::class.java.classLoader) as? Long
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeInt(level)
        parcel.writeValue(targetSizeBytes)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CompressConfig> {
        override fun createFromParcel(parcel: Parcel): CompressConfig = CompressConfig(parcel)
        override fun newArray(size: Int): Array<CompressConfig?> = arrayOfNulls(size)
    }
}

// ── WatermarkConfig ──────────────────────────────────────────────────────────

data class WatermarkConfig(
    val text: String = "CONFIDENTIAL",
    val opacity: Float = 0.3f,
    val rotation: Float = 45f,
    val fontSize: Float = 60f
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "CONFIDENTIAL",
        parcel.readFloat(),
        parcel.readFloat(),
        parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(text)
        parcel.writeFloat(opacity)
        parcel.writeFloat(rotation)
        parcel.writeFloat(fontSize)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<WatermarkConfig> {
        override fun createFromParcel(parcel: Parcel): WatermarkConfig = WatermarkConfig(parcel)
        override fun newArray(size: Int): Array<WatermarkConfig?> = arrayOfNulls(size)
    }
}

// ── SecurityConfig ───────────────────────────────────────────────────────────

data class SecurityConfig(
    val userPassword: String? = null,
    val ownerPassword: String = "",
    val allowPrinting: Boolean = true,
    val allowCopying: Boolean = false
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.readString() ?: "",
        parcel.readByte() != 0.toByte(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(userPassword)
        parcel.writeString(ownerPassword)
        parcel.writeByte(if (allowPrinting) 1 else 0)
        parcel.writeByte(if (allowCopying) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<SecurityConfig> {
        override fun createFromParcel(parcel: Parcel): SecurityConfig = SecurityConfig(parcel)
        override fun newArray(size: Int): Array<SecurityConfig?> = arrayOfNulls(size)
    }
}

// ── HeaderFooterConfig ───────────────────────────────────────────────────────

data class HeaderFooterConfig(
    val headerText: String? = null,
    val footerText: String? = null,
    val fontSize: Float = 10f,
    val headerAlignment: String = "center",
    val footerAlignment: String = "center"
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.readString(),
        parcel.readFloat(),
        parcel.readString() ?: "center",
        parcel.readString() ?: "center"
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(headerText)
        parcel.writeString(footerText)
        parcel.writeFloat(fontSize)
        parcel.writeString(headerAlignment)
        parcel.writeString(footerAlignment)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<HeaderFooterConfig> {
        override fun createFromParcel(parcel: Parcel): HeaderFooterConfig = HeaderFooterConfig(parcel)
        override fun newArray(size: Int): Array<HeaderFooterConfig?> = arrayOfNulls(size)
    }
}

// ── PageNumberConfig ─────────────────────────────────────────────────────────

data class PageNumberConfig(
    val format: String = "Page %d of %d",
    val placement: String = "bottom",
    val alignment: String = "center",
    val fontSize: Float = 10f
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readString() ?: "Page %d of %d",
        parcel.readString() ?: "bottom",
        parcel.readString() ?: "center",
        parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(format)
        parcel.writeString(placement)
        parcel.writeString(alignment)
        parcel.writeFloat(fontSize)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PageNumberConfig> {
        override fun createFromParcel(parcel: Parcel): PageNumberConfig = PageNumberConfig(parcel)
        override fun newArray(size: Int): Array<PageNumberConfig?> = arrayOfNulls(size)
    }
}
