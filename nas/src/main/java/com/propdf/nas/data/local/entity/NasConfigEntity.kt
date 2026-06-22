package com.propdf.nas.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "nas_configs")
data class NasConfigEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "type")
    val type: String, // "WEBDAV" or "SMB"

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "host")
    val host: String,

    @ColumnInfo(name = "port")
    val port: Int,

    @ColumnInfo(name = "username")
    val username: String,

    @ColumnInfo(name = "password")
    val password: String,

    // WebDAV specific
    @ColumnInfo(name = "base_path")
    val basePath: String?,

    @ColumnInfo(name = "use_https")
    val useHttps: Boolean?,

    // SMB specific
    @ColumnInfo(name = "share_name")
    val shareName: String?,

    @ColumnInfo(name = "domain")
    val domain: String?
)
