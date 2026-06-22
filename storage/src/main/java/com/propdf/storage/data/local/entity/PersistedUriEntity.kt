package com.propdf.storage.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "persisted_uris")
data class PersistedUriEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "uri_string")
    val uriString: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "provider_authority")
    val providerAuthority: String?,

    @ColumnInfo(name = "is_removable_storage")
    val isRemovableStorage: Boolean = false,

    @ColumnInfo(name = "added_at")
    val addedAt: Long = System.currentTimeMillis()
)
