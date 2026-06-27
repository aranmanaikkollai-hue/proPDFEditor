package com.propdf.core.domain.model

import android.os.Parcel
import android.os.Parcelable

//  Vault 

data class VaultConfig(
    val biometricRequired: Boolean = true,
    val autoLockTimeoutMs: Long = 300_000L,
    val encryptThumbnails: Boolean = true
) : Parcelable {
    constructor(parcel: Parcel) : this(
        parcel.readByte() != 0.toByte(),
        parcel.readLong(),
        parcel.readByte() != 0.toByte()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeByte(if (biometricRequired) 1 else 0)
        parcel.writeLong(autoLockTimeoutMs)
        parcel.writeByte(if (encryptThumbnails) 1 else 0)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VaultConfig> {
        override fun createFromParcel(parcel: Parcel): VaultConfig = VaultConfig(parcel)
        override fun newArray(size: Int): Array<VaultConfig?> = arrayOfNulls(size)
    }
}

data class VaultEntry(
    val id: String,
    val originalFileName: String,
    val encryptedFilePath: String,
    val thumbnailPath: String?,
    val encryptedAt: Long,
    val pageCount: Int,
    val fileSizeBytes: Long
)

//  Session 

sealed class SessionState {
    object Locked : SessionState()
    object Unlocked : SessionState()
    data class Expiring(val secondsRemaining: Int) : SessionState()
}

//  Backup 

data class EncryptedBackupConfig(
    val password: CharArray,
    val includeVault: Boolean = true,
    val compressionLevel: Int = 6
) : Parcelable {
    constructor(parcel: Parcel) : this(
        (parcel.createStringArray() ?: emptyArray()).joinToString("").toCharArray(),
        parcel.readByte() != 0.toByte(),
        parcel.readInt()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeStringArray(password.map { it.toString() }.toTypedArray())
        parcel.writeByte(if (includeVault) 1 else 0)
        parcel.writeInt(compressionLevel)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<EncryptedBackupConfig> {
        override fun createFromParcel(parcel: Parcel): EncryptedBackupConfig = EncryptedBackupConfig(parcel)
        override fun newArray(size: Int): Array<EncryptedBackupConfig?> = arrayOfNulls(size)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncryptedBackupConfig) return false
        return includeVault == other.includeVault &&
               compressionLevel == other.compressionLevel &&
               password.contentEquals(other.password)
    }

    override fun hashCode(): Int {
        var result = password.contentHashCode()
        result = 31 * result + includeVault.hashCode()
        result = 31 * result + compressionLevel
        return result
    }
}
