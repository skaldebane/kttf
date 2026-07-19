package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GsubTableTest {
    @Test
    fun parsesSingleFormatsAndRetainsSubstituteArrays() {
        val format1Bytes = join(u16(1, 6, 3), coverage1(10))
        val format1 = assertIs<SingleSubstitution.Format1>(single(format1Bytes))
        assertEquals(GlyphId(13), format1.substitute(GlyphId(10)))
        assertNull(format1.substitute(GlyphId(11)))

        val format2Bytes = join(u16(2, 10, 2, 20, 21), coverage1(10, 11))
        val format2 = assertIs<SingleSubstitution.Format2>(single(format2Bytes))
        assertEquals(GlyphId(21), format2.substitute(GlyphId(11)))
        format2Bytes[9] = 22
        assertEquals(GlyphId(22), format2.substitute(GlyphId(11)))
    }

    @Test
    fun parsesMultipleAlternateAndLigatureSubstitutions() {
        val coverage = coverage1(10)
        val multipleBytes = join(u16(1, 8, 1, 14), coverage, u16(2, 20, 21))
        val multiple = assertIs<SubstitutionSubtable.Multiple>(parse(multipleBytes, 2)).value
        assertEquals(21, multiple.sequences[0]!!.substitutes[1])

        val alternateBytes = join(u16(1, 8, 1, 14), coverage, u16(2, 30, 31))
        val alternate = assertIs<SubstitutionSubtable.Alternate>(parse(alternateBytes, 3)).value
        assertEquals(30, alternate.alternateSets[0]!!.alternates[0])

        val ligature = u16(40, 3, 11, 12)
        val ligatureSet = join(u16(1, 4), ligature)
        val setOffset = 14
        val ligatureBytes = join(u16(1, 8, 1, setOffset), coverage, ligatureSet)
        val substitution = assertIs<SubstitutionSubtable.LigatureSub>(parse(ligatureBytes, 4)).value
        val parsed = substitution.ligatureSets[0]!![0]!!
        assertEquals(GlyphId(40), parsed.glyph)
        assertEquals(12, parsed.components[1])
    }

    @Test
    fun parsesContextChainContextReverseAndExtensionKinds() {
        val context = join(u16(3, 1, 1, 12, 0, 2), coverage1(10))
        assertIs<SubstitutionSubtable.Context>(parse(context, 5))

        val chained = join(u16(3, 0, 1, 16, 0, 1, 0, 2), coverage1(10))
        assertIs<SubstitutionSubtable.ChainContext>(parse(chained, 6))

        val reverse = join(u16(1, 16, 1, 22, 1, 28, 1, 99), coverage1(10), coverage1(9), coverage1(11))
        val reverseSub = assertIs<SubstitutionSubtable.ReverseChainSingle>(parse(reverse, 8))
        assertTrue(reverseSub.isReverse)
        assertEquals(99, reverseSub.value.substitutes[0])
        assertTrue(reverseSub.value.backtrackCoverages[0]!!.contains(GlyphId(9)))
        assertTrue(reverseSub.value.lookaheadCoverages[0]!!.contains(GlyphId(11)))

        val single = join(u16(1, 6, 1), coverage1(10))
        val extension = join(u16(1, 1), u32(8), single)
        assertIs<SubstitutionSubtable.Single>(parse(extension, 7))

        val recursive = join(u16(1, 7), u32(0))
        assertNull(parse(recursive, 7))
    }

    @Test
    fun lookupTableDispatchesEveryPublicGsubKindAndRejectsUnknownFormats() {
        val single = join(u16(1, 6, 1), coverage1(10))
        val table = parseGsub(slice(layoutTable(1, single)))!!
        assertEquals(1, table.lookups.size)
        val subtable = table.lookups[0]!![0]!!
        assertTrue(subtable.coverage.contains(GlyphId(10)))
        assertFalse(subtable.isReverse)

        assertNull(parse(u16(3), 1))
        assertNull(parse(u16(2), 2))
        assertNull(parse(u16(1), 99))
    }

    private fun single(data: ByteArray): SingleSubstitution =
        assertIs<SubstitutionSubtable.Single>(parse(data, 1)).value

    private fun parse(
        data: ByteArray,
        kind: Int,
    ): SubstitutionSubtable? = SubstitutionSubtable.parse(slice(data), kind, 8)
}
