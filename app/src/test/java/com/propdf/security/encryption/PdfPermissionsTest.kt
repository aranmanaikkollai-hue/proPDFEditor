package com.propdf.security.encryption

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPermissionsTest {

    @Test fun bitValuesMatchThePdfSpecificationAndTheOldIText7Constants() {
        assertEquals(4, PdfPermissions.ALLOW_DEGRADED_PRINTING)          // bit 3
        assertEquals(8, PdfPermissions.ALLOW_MODIFY_CONTENTS)            // bit 4
        assertEquals(16, PdfPermissions.ALLOW_COPY)                      // bit 5
        assertEquals(32, PdfPermissions.ALLOW_MODIFY_ANNOTATIONS)        // bit 6
        assertEquals(256, PdfPermissions.ALLOW_FILL_IN)                  // bit 9
        assertEquals(512, PdfPermissions.ALLOW_SCREENREADERS)            // bit 10
        assertEquals(1024, PdfPermissions.ALLOW_ASSEMBLY)                // bit 11
        assertEquals(2052, PdfPermissions.ALLOW_PRINTING)                // bits 3 + 12
    }

    @Test fun pValueSetsReservedBitsAndKeepsBitsZeroAndOneClear() {
        val p = PdfPermissions.toPValue(PdfPermissions.ALLOW_COPY)
        assertEquals(0xFFFFF0C0.toInt() or 16, p)
        assertEquals(0, p and 3)
    }

    @Test fun revision2PValueSetsTheHigherReservedBitsToo() {
        val p = PdfPermissions.toPValue(0, revision2 = true)
        assertEquals(0xFFFFFFC0.toInt(), p)
    }

    @Test fun unknownBitsInAMaskAreDropped() {
        val p = PdfPermissions.toPValue(PdfPermissions.ALLOW_COPY or 0x40000000 or 1 or 2)
        assertEquals(0xFFFFF0C0.toInt() or 16, p)
    }

    @Test fun emptyMaskDeniesEveryModelledPermission() {
        val p = PdfPermissions.toPValue(0)
        assertEquals(0, PdfPermissions.allowedBits(p))
        assertFalse(PdfPermissions.isAllowed(p, PdfPermissions.ALLOW_PRINTING))
    }

    @Test fun fullMaskAllowsEverything() {
        val p = PdfPermissions.toPValue(PdfPermissions.ALL)
        for (flag in listOf(
            PdfPermissions.ALLOW_PRINTING, PdfPermissions.ALLOW_DEGRADED_PRINTING, PdfPermissions.ALLOW_MODIFY_CONTENTS,
            PdfPermissions.ALLOW_COPY, PdfPermissions.ALLOW_MODIFY_ANNOTATIONS, PdfPermissions.ALLOW_FILL_IN,
            PdfPermissions.ALLOW_SCREENREADERS, PdfPermissions.ALLOW_ASSEMBLY
        )) assertTrue(PdfPermissions.isAllowed(p, flag))
    }

    @Test fun fullPrintingNeedsBothBitsDegradedNeedsOnlyBitThree() {
        val degraded = PdfPermissions.toPValue(PdfPermissions.ALLOW_DEGRADED_PRINTING)
        assertTrue(PdfPermissions.isAllowed(degraded, PdfPermissions.ALLOW_DEGRADED_PRINTING))
        assertFalse(PdfPermissions.isAllowed(degraded, PdfPermissions.ALLOW_PRINTING))
        val full = PdfPermissions.toPValue(PdfPermissions.ALLOW_PRINTING)
        assertTrue(PdfPermissions.isAllowed(full, PdfPermissions.ALLOW_PRINTING))
        assertTrue(PdfPermissions.isAllowed(full, PdfPermissions.ALLOW_DEGRADED_PRINTING))
    }

    @Test fun legacyUiNeverOffersScreenReaderExtraction() {
        // PermissionsFragment/EncryptionFragment have no accessibility checkbox, so a mask they build never contains bit 10.
        val uiMask = PdfPermissions.ALLOW_PRINTING or PdfPermissions.ALLOW_MODIFY_CONTENTS or PdfPermissions.ALLOW_COPY or
            PdfPermissions.ALLOW_MODIFY_ANNOTATIONS or PdfPermissions.ALLOW_FILL_IN or PdfPermissions.ALLOW_ASSEMBLY or
            PdfPermissions.ALLOW_DEGRADED_PRINTING
        assertFalse(PdfPermissions.isAllowed(PdfPermissions.toPValue(uiMask), PdfPermissions.ALLOW_SCREENREADERS))
    }
}
