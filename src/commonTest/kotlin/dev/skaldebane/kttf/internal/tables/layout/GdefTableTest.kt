package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.internal.tables.variations.NormalizedCoordinate
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GdefTableTest {
    @Test
    fun exposesGlyphClassesMarkAttachmentClassesMarkSetsAndVariationStore() {
        val glyphClasses = class1(4, 1, 2, 3, 4, 9)
        val markClasses = class2(6, 7, 11)
        val markCoverage = coverage1(6, 8)
        val markSets = join(u16(1, 1), u32(8), markCoverage)
        val variationStore = variationStore()
        val glyphOffset = 18
        val markClassOffset = glyphOffset + glyphClasses.size
        val markSetsOffset = markClassOffset + markClasses.size
        val variationOffset = markSetsOffset + markSets.size
        val table =
            GdefTable.parse(
                slice(
                    join(
                        u32(0x0001_0003),
                        u16(glyphOffset, 0, 0, markClassOffset, markSetsOffset),
                        u32(variationOffset.toLong()),
                        glyphClasses,
                        markClasses,
                        markSets,
                        variationStore,
                    ),
                ),
            )!!

        assertTrue(table.hasGlyphClasses)
        assertEquals(GdefGlyphClass.Base, table.glyphClass(GlyphId(4)))
        assertEquals(GdefGlyphClass.Ligature, table.glyphClass(GlyphId(5)))
        assertEquals(GdefGlyphClass.Mark, table.glyphClass(GlyphId(6)))
        assertEquals(GdefGlyphClass.Component, table.glyphClass(GlyphId(7)))
        assertNull(table.glyphClass(GlyphId(8)))
        assertEquals(11, table.markAttachmentClass(GlyphId(7)))
        assertEquals(0, table.markAttachmentClass(GlyphId(8)))
        assertTrue(table.isMarkGlyph(GlyphId(6), 0))
        assertTrue(table.isMarkGlyph(GlyphId(8)))
        assertFalse(table.isMarkGlyph(GlyphId(7)))

        val coordinates = VariationCoordinates.copyOf(listOf(NormalizedCoordinate.fromFloat(1f)))
        assertEquals(20f, table.variationDelta(0, 0, coordinates))
        assertNull(table.variationDelta(1, 0, coordinates))
    }

    @Test
    fun supportsPinnedVersionsAndRejectsInvalidOrTruncatedHeaders() {
        val version10 = join(u32(0x0001_0000), u16(0, 0, 0, 0))
        val version12 = join(u32(0x0001_0002), u16(0, 0, 0, 0, 0))
        assertFalse(GdefTable.parse(slice(version10))!!.hasGlyphClasses)
        assertFalse(GdefTable.parse(slice(version12))!!.isMarkGlyph(GlyphId(1)))
        assertNull(GdefTable.parse(slice(join(u32(0x0001_0001), u16(0, 0, 0, 0)))))
        assertNull(GdefTable.parse(slice(version12.copyOf(11))))
    }

    @Test
    fun preservesValidGdefWhenOptionalMarkGlyphSetsIsMalformed() {
        val glyphClasses = class1(4, 1)
        val headerSize = 14
        val malformedMarkSets = u16(2, 1)
        val table =
            GdefTable.parse(
                slice(
                    join(
                        u32(0x0001_0002),
                        u16(headerSize, 0, 0, 0, headerSize + glyphClasses.size),
                        glyphClasses,
                        malformedMarkSets,
                    ),
                ),
            )

        assertEquals(GdefGlyphClass.Base, table?.glyphClass(GlyphId(4)))
        assertFalse(table!!.isMarkGlyph(GlyphId(4)))
    }

    private fun variationStore(): ByteArray =
        join(
            u16(1),
            u32(12),
            u16(1),
            u32(22),
            u16(1, 1),
            i16(-16384, 16384, 16384),
            u16(1, 1, 1, 0),
            i16(20),
        )
}
