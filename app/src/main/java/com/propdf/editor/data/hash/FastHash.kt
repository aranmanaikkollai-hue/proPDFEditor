package com.propdf.editor.data.hash

object FastHash {
    private const val FNV_64_INIT = 0xcbf29ce484222325L
    private const val FNV_64_PRIME = 0x100000001b3L

    fun hash(data: ByteArray, seed: Long = FNV_64_INIT): String {
        var hash = seed
        for (byte in data) {
            hash = hash xor (byte.toLong() and 0xFF)
            hash *= FNV_64_PRIME
        }
        return hash.toULong().toString(16)
    }

    fun hashWithSizePrefix(data: ByteArray, fileSize: Long): String {
        val sizeBytes = ByteArray(8).apply {
            var v = fileSize
            for (i in 7 downTo 0) {
                this[i] = (v and 0xFF).toByte()
                v = v shr 8
            }
        }
        return hash(sizeBytes + data)
    }
}
