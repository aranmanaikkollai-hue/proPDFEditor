package com.propdfeditor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdfeditor.core.database.converter.DateConverter
import java.util.Date

@Entity(
    tableName = "signature_history",
    foreignKeys = [
        ForeignKey(
            entity = SignatureEntity::class,
            parentColumns = ["id"],
            childColumns = ["signature_id"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [Index("signature_id"), Index("document_path"), Index("signed_at")]
)
@TypeConverters(DateConverter::class)
data class SignatureHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "signature_id")
    val signatureId: Long?,

    @ColumnInfo(name = "document_path")
    val documentPath: String,

    @ColumnInfo(name = "document_name")
    val documentName: String,

    @ColumnInfo(name = "signed_at")
    val signedAt: Date = Date(),

    @ColumnInfo(name = "page_number")
    val pageNumber: Int,

    @ColumnInfo(name = "signature_type")
    val signatureType: SignatureEntity.SignatureType,

    @ColumnInfo(name = "signature_name")
    val signatureName: String,

    @ColumnInfo(name = "is_verified")
    val isVerified: Boolean = false,

    @ColumnInfo(name = "verification_status")
    val verificationStatus: VerificationStatus = VerificationStatus.PENDING,

    @ColumnInfo(name = "certificate_alias")
    val certificateAlias: String? = null,

    @ColumnInfo(name = "timestamp_authority")
    val timestampAuthority: String? = null,

    @ColumnInfo(name = "timestamp_token")
    val timestampToken: ByteArray? = null,

    @ColumnInfo(name = "hash_algorithm")
    val hashAlgorithm: String = "SHA-256",

    @ColumnInfo(name = "signature_rect_left")
    val signatureRectLeft: Float = 0f,

    @ColumnInfo(name = "signature_rect_top")
    val signatureRectTop: Float = 0f,

    @ColumnInfo(name = "signature_rect_right")
    val signatureRectRight: Float = 0f,

    @ColumnInfo(name = "signature_rect_bottom")
    val signatureRectBottom: Float = 0f,

    @ColumnInfo(name = "output_path")
    val outputPath: String? = null,

    @ColumnInfo(name = "file_size_before")
    val fileSizeBefore: Long = 0L,

    @ColumnInfo(name = "file_size_after")
    val fileSizeAfter: Long = 0L,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null
) {
    enum class VerificationStatus {
        PENDING,
        VALID,
        INVALID,
        CORRUPTED,
        EXPIRED_CERTIFICATE,
        REVOKED_CERTIFICATE,
        UNKNOWN
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SignatureHistoryEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
