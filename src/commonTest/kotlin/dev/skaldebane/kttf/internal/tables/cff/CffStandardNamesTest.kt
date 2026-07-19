package dev.skaldebane.kttf.internal.tables.cff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CffStandardNamesTest {
    @Test
    fun containsAllStandardCffSidsWithBoundedLookup() {
        assertEquals(391, CffStandardNames.count)
        assertEquals(".notdef", CffStandardNames.name(0))
        assertEquals("Semibold", CffStandardNames.name(390))
        assertNull(CffStandardNames.name(-1))
        assertNull(CffStandardNames.name(391))
        assertNull(CffStandardNames.name(Int.MIN_VALUE))
        assertNull(CffStandardNames.name(Int.MAX_VALUE))
    }

    @Test
    fun mapsRepresentativeSidsAcrossTheCffStandardTable() {
        val expected =
            mapOf(
                1 to "space",
                34 to "A",
                96 to "exclamdown",
                228 to "zcaron",
                229 to "exclamsmall",
                266 to "ff",
                378 to "Ydieresissmall",
                379 to "001.000",
                383 to "Black",
                389 to "Roman",
            )

        for ((sid, name) in expected) {
            assertEquals(name, CffStandardNames.name(sid), "SID $sid")
            assertEquals(sid, CffStandardNames.sid(name), name)
        }
    }

    @Test
    fun resolvesExactStandardNamesBeforeAnyCallerFallback() {
        val fallbackSid = CffStandardNames.count

        assertEquals(34, CffStandardNames.sid("A") ?: fallbackSid)
        assertEquals(66, CffStandardNames.sid("a") ?: fallbackSid)
        assertEquals(379, CffStandardNames.sid("001.000") ?: fallbackSid)
    }

    @Test
    fun returnsNullForUnknownOrNonExactNames() {
        assertNull(CffStandardNames.sid(""))
        assertNull(CffStandardNames.sid("missing"))
        assertNull(CffStandardNames.sid("semibold"))
        assertNull(CffStandardNames.sid("A "))
        assertNull(CffStandardNames.sid(".null"))
        assertNull(CffStandardNames.sid("nonmarkingreturn"))
    }
}
