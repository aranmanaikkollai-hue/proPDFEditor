package com.propdf.nas.domain.model

/**
 * Sealed class representing NAS protocol configurations.
 */
sealed class NasConfig(
    open val id: Long,
    open val displayName: String,
    open val host: String,
    open val port: Int,
    open val username: String,
    open val password: String
) {
    data class WebDavConfig(
        override val id: Long = 0,
        override val displayName: String,
        override val host: String,
        override val port: Int = 443,
        override val username: String,
        override val password: String,
        val basePath: String = "/",
        val useHttps: Boolean = true
    ) : NasConfig(id, displayName, host, port, username, password)

    data class SmbConfig(
        override val id: Long = 0,
        override val displayName: String,
        override val host: String,
        override val port: Int = 445,
        override val username: String,
        override val password: String,
        val shareName: String,
        val domain: String = ""
    ) : NasConfig(id, displayName, host, port, username, password)
}

/**
 * Pending operation for offline queue.
 */
data class PendingNasOperation(
    val id: Long = 0,
    val configId: Long,
    val operationType: NasOperationType,
    val remotePath: String,
    val localUri: String?,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

enum class NasOperationType {
    UPLOAD, DOWNLOAD, DELETE, LIST
}
