// security/src/main/java/com/propdf/security/util/SecurityConstants.kt
package com.propdf.security.util

object SecurityConstants {
    
    // Encryption
    const val AES_KEY_SIZE = 256
    const val AES_BLOCK_SIZE = 128
    const val GCM_IV_LENGTH = 12
    const val GCM_TAG_LENGTH = 128
    
    // Secure Delete
    const val SECURE_DELETE_PASSES = 7 // DoD 5220.22-M standard
    const val SECURE_DELETE_BUFFER_SIZE = 8192
    
    // PDF Permissions
    const val PERMISSION_ALL = -1
    const val PERMISSION_NONE = 0
    
    // Operation Timeouts
    const val OPERATION_TIMEOUT_MS = 300000L // 5 minutes
    
    // Memory
    const val MAX_MEMORY_BUFFER = 10 * 1024 * 1024L // 10MB
}
