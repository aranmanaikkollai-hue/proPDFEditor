package com.propdf.security.keystore

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Secure memory container for sensitive data (passwords, keys).
 * Unlike [String], the backing char array can be explicitly zeroed.
 */
class SecureMemory private constructor(internal val data: CharArray) {
    internal val cleared = AtomicBoolean(false)

    companion object {
        fun fromString(source: String): SecureMemory {
            return SecureMemory(source.toCharArray())
        }

        fun fromCharArray(source: CharArray): SecureMemory {
            return SecureMemory(source.copyOf())
        }

        fun fromByteArray(source: ByteArray): SecureMemory {
            val chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(source)).array()
            return SecureMemory(chars)
        }
    }

    internal inline fun <R> use(block: (CharArray) -> R): R {
        check(!cleared.get()) { "SecureMemory has already been cleared" }
        return try {
            block(data)
        } finally {
            clear()
        }
    }

    fun toCharArray(): CharArray {
        check(!cleared.get()) { "SecureMemory has already been cleared" }
        return data.copyOf()
    }

    fun clear() {
        if (cleared.compareAndSet(false, true)) {
            data.fill('\u0000')
        }
    }

    fun isCleared(): Boolean = cleared.get()

    @Suppress("unused")
    protected fun finalize() {
        clear()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecureMemory) return false
        if (cleared.get() || other.cleared.get()) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        return if (cleared.get()) 0 else data.contentHashCode()
    }

    override fun toString(): String = "SecureMemory(cleared=${cleared.get()})"
}
