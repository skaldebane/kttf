package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.tables.variations.NormalizedCoordinate
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayoutCommonTest {
    @Test
    fun coverageAndClassDefinitionsExposeBothFormatsWithoutCopying() {
        val coverageBytes = coverage1(3, 7, 9)
        val coverage = assertIs<Coverage.Format1>(Coverage.parse(slice(coverageBytes)))
        assertEquals(1, coverage.indexOf(GlyphId(7)))
        coverageBytes[7] = 8
        assertFalse(coverage.contains(GlyphId(7)))
        assertEquals(1, coverage.indexOf(GlyphId(8)))

        val ranges = assertIs<Coverage.Format2>(Coverage.parse(slice(coverage2(10, 12, 4))))
        assertEquals(6, ranges.indexOf(GlyphId(12)))
        assertNull(ranges.indexOf(GlyphId(13)))

        assertEquals(6, ClassDefinition.parse(slice(class1(20, 5, 6)))!!.classOf(GlyphId(21)))
        assertEquals(7, ClassDefinition.parse(slice(class2(30, 35, 7)))!!.classOf(GlyphId(33)))
        assertEquals(0, ClassDefinition.Empty.classOf(GlyphId(1)))
    }

    @Test
    fun deviceViewsDecodePackedHintingAndVariationIndices() {
        val hinting = assertIs<Device.Hinting>(Device.parse(slice(join(u16(10, 17, 1), u16(0x7777)))))
        assertEquals(100, hinting.delta(10, 1000))
        assertEquals(-100, hinting.delta(11, 1100))
        assertEquals(100, hinting.xDelta(1000, 10 to 11))
        assertEquals(-90, hinting.yDelta(1000, 10 to 11))
        assertNull(hinting.delta(9, 1000))

        val variation = assertIs<Device.Variation>(Device.parse(slice(u16(4, 9, 0x8000))))
        assertEquals(4, variation.outerIndex)
        assertEquals(9, variation.innerIndex)
        assertNull(Device.parse(slice(u16(0, 0, 7))))
    }

    @Test
    fun layoutListsExposeScriptsLanguagesFeaturesLookupsAndFlags() {
        val single = join(u16(1, 6, 2), coverage1(5))
        val table = parseGsub(slice(layoutTable(1, single, lookupFlags = 0x031F)))!!
        val script = table.scripts.find(Tag.parse("latn"))!!
        assertEquals(Tag.parse("latn"), script.tag)
        assertEquals(Tag.parse("dflt"), script.defaultLanguage!!.tag)
        assertNull(script.defaultLanguage.requiredFeature)
        assertEquals(0, script.defaultLanguage.featureIndices[0])
        assertEquals(0, script.languages.size)

        val feature = table.features.find(Tag.parse("test"))!!
        assertEquals(0, feature.lookupIndices[0])
        val lookup = table.lookups[0]!!
        assertTrue(lookup.flags.rightToLeft)
        assertTrue(lookup.flags.ignoreBaseGlyphs)
        assertTrue(lookup.flags.ignoreLigatures)
        assertTrue(lookup.flags.ignoreMarks)
        assertTrue(lookup.flags.useMarkFilteringSet)
        assertEquals(3, lookup.flags.markAttachmentType)
        assertEquals(3, lookup.markFilteringSet)
        assertIs<SubstitutionSubtable.Single>(lookup[0])
    }

    @Test
    fun contextAndChainedContextRepresentAllThreeFormats() {
        val coverage = coverage1(10)
        val rule = u16(2, 1, 11, 0, 4)
        val set = join(u16(1, 4), rule)
        val context1 = join(u16(1, 6 + 2, 1, 8), coverage, set)
        assertIs<ContextLookup.Format1>(ContextLookup.parse(slice(context1)))

        val context2Header = u16(2, 10, 16, 1, 22)
        val context2 = join(context2Header, coverage, class1(10, 1), set)
        assertIs<ContextLookup.Format2>(ContextLookup.parse(slice(context2)))

        val context3 = join(u16(3, 1, 1, 12, 0, 2), coverage)
        val parsedContext3 = assertIs<ContextLookup.Format3>(ContextLookup.parse(slice(context3)))
        assertEquals(0, parsedContext3.coverages.size)

        val chainedRule = u16(1, 9, 1, 1, 8, 1, 0, 3)
        val chainedSet = join(u16(1, 4), chainedRule)
        val chain1 = join(u16(1, 8, 1, 14), coverage, chainedSet)
        assertIs<ChainedContextLookup.Format1>(ChainedContextLookup.parse(slice(chain1)))

        val chain2 = join(u16(2, 14, 0, 20, 0, 1, 26), coverage, class1(10, 1), chainedSet)
        assertIs<ChainedContextLookup.Format2>(ChainedContextLookup.parse(slice(chain2)))

        val chain3 = join(u16(3, 0, 1, 16, 0, 1, 0, 2), coverage)
        val parsedChain3 = assertIs<ChainedContextLookup.Format3>(ChainedContextLookup.parse(slice(chain3)))
        assertEquals(0, parsedChain3.inputCoverages.size)
    }

    @Test
    fun featureVariationsSelectConditionsAndSubstituteFeatures() {
        val base = layoutTable(1, join(u16(1, 6, 2), coverage1(5)))
        val body = base.copyOfRange(10, base.size)
        val variations = featureVariations()
        val variationsOffset = 14 + body.size
        val tableBytes =
            join(
                u16(1, 1, 14, 14 + 20, 14 + 34),
                u32(variationsOffset.toLong()),
                body,
                variations,
            )
        val table = parseGsub(slice(tableBytes))!!
        val coordinates = VariationCoordinates.copyOf(listOf(NormalizedCoordinate.fromFloat(0.5f)))

        assertEquals(0, table.scripts.index(Tag.parse("latn")))
        assertEquals(0, table.features.index(Tag.parse("test")))
        assertTrue(
            table.lookups[0]!!
                .flags.ignoreFlags
                .not(),
        )
        assertEquals(0, table.variations!!.findIndex(coordinates))
        val substitute = table.variations.findSubstitute(0, 0)!!
        assertEquals(Tag.parse("DFLT"), substitute.tag)
        assertEquals(3, substitute.lookupIndices[0])
        assertEquals(4, substitute.lookupIndices[1])

        coordinates[0] = NormalizedCoordinate.fromFloat(-0.5f)
        assertNull(table.variations.findIndex(coordinates))
        assertNull(table.variations.findSubstitute(1, 0))
    }

    @Test
    fun layoutVersionOneRequiresVariationOffsetButIgnoresInvalidOptionalData() {
        val base = layoutTable(1, join(u16(1, 6, 2), coverage1(5)))
        val body = base.copyOfRange(10, base.size)
        val parsed =
            parseGsub(
                slice(
                    join(
                        u16(1, 1, 14, 34, 48),
                        u32(0xFFFF_FFFFL),
                        body,
                        featureVariations(),
                    ),
                ),
            )!!
        assertNull(parsed.variations)
    }

    private fun featureVariations(): ByteArray {
        val conditionSet = join(u16(1), u32(6), u16(1, 0), i16(4096, 12288))
        val substitution = join(u16(1, 0, 1, 0), u32(12), u16(0, 2, 3, 4))
        return join(
            u16(1, 0),
            u32(1, 16, (16 + conditionSet.size).toLong()),
            conditionSet,
            substitution,
        )
    }
}
