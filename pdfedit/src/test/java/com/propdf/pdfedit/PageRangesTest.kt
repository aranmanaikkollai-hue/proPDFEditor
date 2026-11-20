package com.propdf.pdfedit

import com.propdf.pdfedit.convert.PageRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageRangesTest {
    private fun ok(spec: String, count: Int): List<Int> {
        val r = PageRanges.parse(spec, count)
        assertTrue("expected Ok for '" + spec + "'", r is PageRanges.Result.Ok)
        return (r as PageRanges.Result.Ok).indices
    }

    private fun bad(spec: String, count: Int) {
        val r = PageRanges.parse(spec, count)
        assertTrue("expected an error for '" + spec + "'", r is PageRanges.Result.Error)
    }

    @Test fun emptyMeansAllPages() = assertEquals(listOf(0, 1, 2, 3, 4), ok("", 5))
    @Test fun blankMeansAllPages() = assertEquals(listOf(0, 1, 2), ok("   ", 3))
    @Test fun listsAndRanges() = assertEquals(listOf(0, 1, 2, 4), ok("1-3,5", 5))
    @Test fun openEndedRanges() {
        assertEquals(listOf(2, 3, 4), ok("3-", 5))
        assertEquals(listOf(0, 1), ok("-2", 5))
    }
    @Test fun whitespaceIsIgnored() = assertEquals(listOf(1, 3, 4), ok(" 2 , 4-5 ", 5))
    @Test fun overlapsAndOrderAreNormalized() = assertEquals(listOf(0, 1, 2, 3), ok("3,1-3,2-4", 5))
    @Test fun lastPageIsAllowed() = assertEquals(listOf(4), ok("5", 5))
    @Test fun singlePageDocument() = assertEquals(listOf(0), ok("1-", 1))

    @Test fun outOfRangeIsAnError() {
        bad("6", 5); bad("0", 5); bad("1-6", 5); bad("-6", 5)
    }
    @Test fun backwardsRangeIsAnError() = bad("3-2", 5)
    @Test fun garbageIsAnError() {
        bad("a", 5); bad("1,,2", 5); bad("1-2-3", 5); bad("-", 5); bad("1;2", 5); bad("+1", 5); bad("1.5", 5)
    }
    @Test fun hugeNumbersAreAnErrorNotAnOverflow() {
        bad("99999999999", 5); bad("1-99999999999", 5)
    }
    @Test fun documentWithoutPagesIsAnError() = bad("1", 0)
}
