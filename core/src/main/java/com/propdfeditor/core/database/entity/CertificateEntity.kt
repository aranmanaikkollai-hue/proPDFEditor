package com.propdfeditor.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.propdfeditor.core.database.converter.DateConverter
import java.util.Date

@Entity(tableName = "certificates")
@TypeConverters(DateConverter::class)
data class CertificateEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "alias")
    val alias: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "keystore_path")
    val keystorePath: String,

    @ColumnInfo(name = "keystore_type")
    val keystoreType: String = "PKCS12",

    @ColumnInfo(name = "certificate_data")
    val certificateData: ByteArray?,

    @ColumnInfo(name = "subject_dn")
    val subjectDn: String?,

    @ColumnInfo(name = "issuer_dn")
    val issuerDn: String?,

    @ColumnInfo(name = "serial_number")
    val serialNumber: String?,

    @ColumnInfo(name = "valid_from")
    val validFrom: Date?,

    @ColumnInfo(name = "valid_until")
    val validUntil: Date?,

    @ColumnInfo(name = "algorithm")
    val algorithm: String?,

    @ColumnInfo(name = "key_size")
    val keySize: Int?,

    @ColumnInfo(name = "is_self_signed")
    val isSelfSigned: Boolean = false,

    @ColumnInfo(name = "is_encrypted")
    val isEncrypted: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date(),

    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Date? = null,

    @ColumnInfo(name = "use_count")
    val useCount: Int = 0,

    @ColumnInfo(name = "is_default")
    val isDefault: Boolean = false,

    @ColumnInfo(name = "password_hint")
    val passwordHint: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CertificateEntity
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
