package com.propdfeditor.batch.util

import com.google.gson.JsonObject

/**
 * Pure validation + config building for the batch Encrypt dialog (no Android classes, JVM-testable).
 *
 * Rules (approved Phase 1 closure decision):
 *  - the user password is required and must match its confirmation;
 *  - "restrictive" means at least one of print / copy / modify is NOT allowed;
 *  - restrictive => a distinct owner password is REQUIRED, must match its confirmation and must differ from the
 *    user password (the user password is never silently reused as the owner password);
 *  - all permissions allowed => the owner password is optional (single-password mode); if one is given it must
 *    match its confirmation, and it is only sent when it differs from the user password.
 *
 * Passwords are never logged here. NOTE: the batch job's config JSON is stored by BatchJobRepository (Room), so the
 * passwords in it are persisted until the job record is removed; see PHASE_2_BLOCKERS_AND_RISKS.md.
 */
object EncryptInputValidator {

    enum class Problem { PASSWORD_EMPTY, PASSWORD_MISMATCH, OWNER_REQUIRED, OWNER_MISMATCH, OWNER_SAME_AS_USER }

    data class Input(
        val password: String,
        val confirmPassword: String,
        val ownerPassword: String = "",
        val ownerConfirm: String = "",
        val allowPrinting: Boolean = true,
        val allowCopying: Boolean = true,
        val allowModifying: Boolean = true
    ) {
        // Keep secrets out of accidental toString()/logging output.
        override fun toString(): String =
            "Input(allowPrinting=$allowPrinting, allowCopying=$allowCopying, allowModifying=$allowModifying, passwords=<redacted>)"
    }

    sealed class Result {
        /** [configJson] is the EncryptConfig JSON WITHOUT outputDirUri (the view model injects it). */
        data class Valid(val configJson: String, val restrictive: Boolean) : Result() {
            override fun toString(): String = "Valid(restrictive=$restrictive, configJson=<redacted>)"
        }
        data class Invalid(val problems: Set<Problem>) : Result()
    }

    fun isRestrictive(input: Input): Boolean = !(input.allowPrinting && input.allowCopying && input.allowModifying)

    fun validate(input: Input): Result {
        val problems = linkedSetOf<Problem>()
        if (input.password.isEmpty()) problems += Problem.PASSWORD_EMPTY
        else if (input.password != input.confirmPassword) problems += Problem.PASSWORD_MISMATCH

        val restrictive = isRestrictive(input)
        val ownerGiven = input.ownerPassword.isNotEmpty()
        if (restrictive) {
            if (!ownerGiven) problems += Problem.OWNER_REQUIRED
            else if (input.ownerPassword != input.ownerConfirm) problems += Problem.OWNER_MISMATCH
            else if (input.ownerPassword == input.password) problems += Problem.OWNER_SAME_AS_USER
        } else if (ownerGiven || input.ownerConfirm.isNotEmpty()) {
            if (input.ownerPassword != input.ownerConfirm) problems += Problem.OWNER_MISMATCH
        }
        if (problems.isNotEmpty()) return Result.Invalid(problems)

        val json = JsonObject().apply {
            addProperty("password", input.password)
            addProperty("encryptionLevel", "AES_256")
            addProperty("allowPrinting", input.allowPrinting)
            addProperty("allowCopying", input.allowCopying)
            addProperty("allowModifying", input.allowModifying)
            if (ownerGiven && input.ownerPassword != input.password) addProperty("ownerPassword", input.ownerPassword)
        }
        return Result.Valid(json.toString(), restrictive)
    }
}
