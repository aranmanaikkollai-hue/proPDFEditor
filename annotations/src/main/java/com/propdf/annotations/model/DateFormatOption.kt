package com.propdf.annotations.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Centralized date-format catalog for Date Stamp annotations.
 *
 * Previously StampAnnotation.getDisplayText() hardcoded `SimpleDateFormat("MMM dd, yyyy")`
 * as the only option -- there was no way to pick a different format, and if any other
 * screen needed a date pattern it would have had to duplicate the literal. This is the
 * single source of truth for both the preset list shown in the stamp picker and the
 * actual formatting logic, so patterns only ever live in one place.
 */
data class DateFormatOption(
    val label: String,
    val pattern: String
) {
    companion object {
        val PRESETS = listOf(
            DateFormatOption("MMM dd, yyyy", "MMM dd, yyyy"),   // Aug 22, 2026
            DateFormatOption("dd MMM yyyy", "dd MMM yyyy"),     // 22 Aug 2026
            DateFormatOption("dd/MM/yyyy", "dd/MM/yyyy"),
            DateFormatOption("MM/dd/yyyy", "MM/dd/yyyy"),
            DateFormatOption("yyyy-MM-dd", "yyyy-MM-dd"),
            DateFormatOption("dd-MM-yyyy", "dd-MM-yyyy"),
            DateFormatOption("yyyy/MM/dd", "yyyy/MM/dd")
        )

        const val DEFAULT_PATTERN = "MMM dd, yyyy"

        /**
         * Formats [date] with [pattern], falling back to [DEFAULT_PATTERN] if the pattern
         * is blank or invalid (e.g. a malformed custom pattern) so a bad pattern can never
         * crash rendering or leave a stamp with no text.
         */
        fun format(pattern: String, date: Date = Date(), locale: Locale = Locale.getDefault()): String {
            val safePattern = pattern.ifBlank { DEFAULT_PATTERN }
            return try {
                SimpleDateFormat(safePattern, locale).format(date)
            } catch (e: IllegalArgumentException) {
                SimpleDateFormat(DEFAULT_PATTERN, locale).format(date)
            }
        }

        /** True if [pattern] is a syntactically valid SimpleDateFormat pattern. */
        fun isValidPattern(pattern: String): Boolean {
            if (pattern.isBlank()) return false
            return try {
                SimpleDateFormat(pattern, Locale.getDefault())
                true
            } catch (e: IllegalArgumentException) {
                false
            }
        }
    }
}
