package com.propdf.pdfedit.convert

import java.io.IOException

/** Picks a file name that does not exist yet, so a conversion never overwrites a file. Pure, unit tested on the JVM. */
object UniqueName {

    /**
     * Returns [desired] if [exists] says it is free, otherwise "name (1).ext", "name (2).ext", ... The extension is the part
     * after the last dot (a leading dot does not count). Throws if the first 9999 names are taken.
     */
    fun pick(desired: String, exists: (String) -> Boolean): String {
        if (!exists(desired)) return desired
        val dot = desired.lastIndexOf('.')
        val stem = if (dot > 0) desired.substring(0, dot) else desired
        val ext = if (dot > 0) desired.substring(dot) else ""
        for (i in 1..9999) {
            val candidate = stem + " (" + i + ")" + ext
            if (!exists(candidate)) return candidate
        }
        throw IOException("Too many files named like " + desired)
    }
}
