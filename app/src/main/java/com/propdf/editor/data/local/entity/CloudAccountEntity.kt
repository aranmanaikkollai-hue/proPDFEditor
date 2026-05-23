package com.propdf.editor.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cloud_accounts",
    indices = [Index(value = ["provider", "accountId"], unique = true)]
)
data class CloudAccountEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val provider: String, // GOOGLE_DRIVE, ONEDRIVE, DROPBOX
    val accountId: String,
    val accountEmail: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiry: Long = 0,
    val isActive: Boolean = true,
    val connectedAt: Long = System.currentTimeMillis(),
    val lastSync: Long? = null
)

enum class CloudProvider {
    GOOGLE_DRIVE, ONEDRIVE, DROPBOX
}
