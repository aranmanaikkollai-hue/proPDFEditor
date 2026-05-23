package com.propdf.editor.domain.model

data class CloudAccount(
    val id: Long = 0,
    val provider: CloudProvider,
    val accountId: String,
    val accountEmail: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenExpiry: Long = 0,
    val isActive: Boolean = true,
    val connectedAt: Long = System.currentTimeMillis(),
    val lastSync: Long? = null
)
