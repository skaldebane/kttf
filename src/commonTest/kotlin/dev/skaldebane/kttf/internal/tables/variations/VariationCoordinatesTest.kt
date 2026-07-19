package dev.skaldebane.kttf.internal.tables.variations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariationCoordinatesTest {
    @Test
    fun clampsRawAndFloatingPointCoordinatesLikePinnedUpstream() {
        assertEquals(-16384, NormalizedCoordinate.fromF2Dot14(-32768).rawValue)
        assertEquals(-16384, NormalizedCoordinate.fromF2Dot14(-16384).rawValue)
        assertEquals(0, NormalizedCoordinate.ZERO.rawValue)
        assertEquals(16384, NormalizedCoordinate.fromF2Dot14(32767).rawValue)

        assertEquals(-16384, NormalizedCoordinate.fromFloat(Float.NEGATIVE_INFINITY).rawValue)
        assertEquals(-8192, NormalizedCoordinate.fromFloat(-0.5f).rawValue)
        assertEquals(1638, NormalizedCoordinate.fromFloat(0.1f).rawValue)
        assertEquals(16384, NormalizedCoordinate.fromFloat(Float.POSITIVE_INFINITY).rawValue)
        assertEquals(0.5f, NormalizedCoordinate.fromF2Dot14(8192).toFloat())
    }

    @Test
    fun storesCoordinatesCompactlyWithBoundedAccess() {
        val coordinates =
            VariationCoordinates.copyOf(
                listOf(
                    NormalizedCoordinate.fromF2Dot14(-1),
                    NormalizedCoordinate.ZERO,
                ),
            )

        assertEquals(2, coordinates.size)
        assertEquals(-1, coordinates[0]?.rawValue)
        assertNull(coordinates[-1])
        assertNull(coordinates[2])
        assertTrue(coordinates.hasNonDefaultValues())
        assertTrue(coordinates.set(1, NormalizedCoordinate.fromF2Dot14(100)))
        assertEquals(100, coordinates[1]?.rawValue)
        assertFalse(coordinates.set(2, NormalizedCoordinate.ZERO))
        assertNull(VariationCoordinates.zeros(-1))
        assertFalse(VariationCoordinates.zeros(2)!!.hasNonDefaultValues())
    }
}
