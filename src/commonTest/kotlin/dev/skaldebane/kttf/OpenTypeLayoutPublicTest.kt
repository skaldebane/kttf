package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.layout.MathTable
import dev.skaldebane.kttf.internal.tables.layout.coverage1
import dev.skaldebane.kttf.internal.tables.layout.i16
import dev.skaldebane.kttf.internal.tables.layout.join
import dev.skaldebane.kttf.internal.tables.layout.layoutTable
import dev.skaldebane.kttf.internal.tables.layout.parseGpos
import dev.skaldebane.kttf.internal.tables.layout.parseGsub
import dev.skaldebane.kttf.internal.tables.layout.slice
import dev.skaldebane.kttf.internal.tables.layout.u16
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenTypeLayoutPublicTest {
    @Test
    fun exposesBoundedGsubLigaturesLookupFlagsAndCoverage() {
        val ligature = u16(40, 3, 11, 12)
        val ligatureSet = join(u16(1, 4), ligature)
        val subtable = join(u16(1, 8, 1, 14), coverage1(10), ligatureSet)
        val table = parseGsub(slice(layoutTable(4, subtable, 0x031B)))!!
        val substitutions = GlyphSubstitutions(table, VariationCoordinates.zeros(0)!!)
        val lookup = substitutions.lookup(0)!!
        val view = substitutions.subtable(0, 0)!!

        assertEquals(GlyphSubstitutionKind.Ligature, view.kind)
        assertTrue(GlyphId(10) in view.coverage)
        assertEquals(listOf(GlyphId(11), GlyphId(12)), view.ligatures(GlyphId(10))!![0].components)
        assertTrue(lookup.flags.rightToLeft)
        assertTrue(lookup.flags.ignoreBaseGlyphs)
        assertTrue(lookup.flags.ignoreMarks)
        assertEquals(3, lookup.flags.markAttachmentType)
        assertEquals(3, lookup.flags.markFilteringSet)
        assertNull(substitutions.lookup(-1))
        assertNull(substitutions.subtable(0, 1))
    }

    @Test
    fun exposesGposAnchorsAndVariationIndices() {
        val entry = join(u16(3, 100, 200, 10, 0), u16(4, 5, 0x8000))
        val cursive = join(u16(1, 10 + entry.size, 1, 10, 0), entry, coverage1(10))
        val table = parseGpos(slice(layoutTable(3, cursive)))!!
        val view = GlyphPositioning(table, VariationCoordinates.zeros(0)!!).subtable(0, 0)!!
        val anchor = assertNotNull(view.entryAnchor(GlyphId(10)))

        assertEquals(GlyphPositioningKind.Cursive, view.kind)
        assertEquals(100, anchor.x)
        assertTrue(anchor.xDevice!!.isVariationIndex)
        assertEquals(4, anchor.xDevice.outerVariationIndex)
        assertEquals(5, anchor.xDevice.innerVariationIndex)
        assertNull(anchor.xDevice.delta(12, 1000))
        assertNull(view.entryAnchor(GlyphId(11)))
    }

    @Test
    fun exposesMathValueDevicesAndFourCornerKerns() {
        val constants = ByteArray(216)
        put16(constants, 8, 7)
        put16(constants, 10, 216)
        val device = u16(2, 3, 0x8000)
        val kernTable = join(u16(1), i16(30), u16(0), i16(40), u16(0), i16(50), u16(0))
        val kernInfo = join(u16(12, 1, 18, 0, 0, 0), coverage1(10), kernTable)
        val glyphInfo = join(u16(0, 0, 0, 8), kernInfo)
        val mathBytes = join(u16(1, 0, 10, 10 + constants.size + device.size, 0), constants, device, glyphInfo)
        val math = MathLayout(MathTable.parse(slice(mathBytes))!!)

        val constant = math.constantValue(0)!!
        assertEquals(7, constant.value)
        assertEquals(2, constant.device!!.outerVariationIndex)
        assertEquals(3, constant.device.innerVariationIndex)
        val kern = math.kernInfo(GlyphId(10))!!.topRight!!
        assertEquals(30, kern.correctionHeights.single().value)
        assertEquals(listOf(40, 50), kern.kernValues.map { it.value })
        assertNull(math.kernInfo(GlyphId(11)))
        assertFalse(constant.device.startSize != null)
    }

    private fun put16(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }
}
