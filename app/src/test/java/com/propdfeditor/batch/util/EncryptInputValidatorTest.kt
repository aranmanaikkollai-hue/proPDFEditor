package com.propdfeditor.batch.util

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.propdfeditor.batch.util.EncryptInputValidator.Input
import com.propdfeditor.batch.util.EncryptInputValidator.Problem
import com.propdfeditor.batch.util.EncryptInputValidator.Result
import com.propdfeditor.batch.worker.EncryptWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 1 closure: batch Encrypt dialog validation rules and the config handed to EncryptWorker. */
class EncryptInputValidatorTest {

    private fun base(
        owner: String = "", ownerConfirm: String = owner,
        print: Boolean = true, copy: Boolean = true, modify: Boolean = true,
        pw: String = "user-pw", pwConfirm: String = pw
    ) = Input(pw, pwConfirm, owner, ownerConfirm, print, copy, modify)

    private fun problems(i: Input) = (EncryptInputValidator.validate(i) as Result.Invalid).problems

    private fun valid(i: Input) = EncryptInputValidator.validate(i) as Result.Valid

    // ---- user password
    @Test fun emptyPassword_isRejected() = assertEquals(setOf(Problem.PASSWORD_EMPTY), problems(base(pw = "", pwConfirm = "")))
    @Test fun mismatchedPassword_isRejected() = assertEquals(setOf(Problem.PASSWORD_MISMATCH), problems(base(pwConfirm = "other")))

    // ---- restrictive permissions require a distinct, confirmed owner password
    @Test fun restrictive_withoutOwner_isRejected() = assertEquals(setOf(Problem.OWNER_REQUIRED), problems(base(print = false)))
    @Test fun restrictive_ownerEqualToUser_isRejected() =
        assertEquals(setOf(Problem.OWNER_SAME_AS_USER), problems(base(owner = "user-pw", copy = false)))
    @Test fun restrictive_ownerNotConfirmed_isRejected() =
        assertEquals(setOf(Problem.OWNER_MISMATCH), problems(base(owner = "own-1", ownerConfirm = "own-2", modify = false)))
    @Test fun everySingleRestriction_isRestrictive() {
        for (i in listOf(base(print = false), base(copy = false), base(modify = false))) {
            assertTrue(EncryptInputValidator.isRestrictive(i))
            assertEquals(setOf(Problem.OWNER_REQUIRED), problems(i))
        }
    }
    @Test fun restrictive_withDistinctOwner_isAccepted_andOwnerIsSent() {
        val r = valid(base(owner = "own-pw", print = false, copy = false, modify = false))
        assertTrue(r.restrictive)
        val o = JsonParser.parseString(r.configJson).asJsonObject
        assertEquals("user-pw", o["password"].asString)
        assertEquals("own-pw", o["ownerPassword"].asString)
        assertFalse(o["allowPrinting"].asBoolean); assertFalse(o["allowCopying"].asBoolean); assertFalse(o["allowModifying"].asBoolean)
        assertEquals("AES_256", o["encryptionLevel"].asString)
    }

    // ---- single-password mode is kept when everything is allowed
    @Test fun allAllowed_withoutOwner_staysSinglePasswordMode() {
        val r = valid(base())
        assertFalse(r.restrictive)
        assertFalse(JsonParser.parseString(r.configJson).asJsonObject.has("ownerPassword"))
    }
    @Test fun allAllowed_optionalOwnerMustBeConfirmed() =
        assertEquals(setOf(Problem.OWNER_MISMATCH), problems(base(owner = "own", ownerConfirm = "nope")))
    @Test fun allAllowed_ownerEqualToUser_isNotSent() {
        val r = valid(base(owner = "user-pw"))
        assertFalse(JsonParser.parseString(r.configJson).asJsonObject.has("ownerPassword"))
    }
    @Test fun allAllowed_distinctOwner_isSent() =
        assertEquals("own", JsonParser.parseString(valid(base(owner = "own")).configJson).asJsonObject["ownerPassword"].asString)

    // ---- the JSON really reaches EncryptWorker.EncryptConfig (what the worker deserialises)
    @Test fun configJson_deserialisesToEncryptConfig_withOwnerAndFlags() {
        val json = valid(base(owner = "own-pw", copy = false)).configJson
        val withDir = JsonParser.parseString(json).asJsonObject.apply { addProperty("outputDirUri", "content://x/tree/y") }
        val cfg = Gson().fromJson(withDir, EncryptWorker.EncryptConfig::class.java)
        assertEquals("user-pw", cfg.password)
        assertEquals("own-pw", cfg.ownerPassword)
        assertTrue(cfg.allowPrinting); assertFalse(cfg.allowCopying); assertTrue(cfg.allowModifying)
        assertEquals("AES_256", cfg.encryptionLevel)
    }
    @Test fun singlePassword_configJson_hasNoOwner() {
        val withDir = JsonParser.parseString(valid(base()).configJson).asJsonObject.apply { addProperty("outputDirUri", "x") }
        assertNull(Gson().fromJson(withDir, EncryptWorker.EncryptConfig::class.java).ownerPassword)
    }

    // ---- secrets stay out of diagnostics
    @Test fun toString_doesNotLeakPasswords() {
        val i = base(owner = "super-secret-owner")
        assertFalse(i.toString().contains("user-pw")); assertFalse(i.toString().contains("super-secret-owner"))
        val r = valid(i)
        assertFalse(r.toString().contains("super-secret-owner"))
    }
}
