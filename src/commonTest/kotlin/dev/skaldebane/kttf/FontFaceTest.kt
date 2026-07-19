package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FontFaceTest {
    @Test
    fun rejectsEmptyFont() {
        assertNull(FontFace.parseOrNull(ByteArray(0)))
        assertFailsWith<FontParsingException> { FontFace.parse(ByteArray(0)) }
    }

    @Test
    fun rejectsFontWithZeroTables() {
        val data =
            TestByteBuilder()
                .uint32(0x00010000)
                .uint16(0)
                .uint16(0)
                .uint16(0)
                .uint16(0)
                .build()

        assertNull(FontFace.parseOrNull(data))
    }

    @Test
    fun rejectsTruncatedMaximumTableCount() {
        val data =
            TestByteBuilder()
                .uint32(0x00010000)
                .uint16(0xFFFF)
                .uint16(0)
                .uint16(0)
                .uint16(0)
                .build()

        assertNull(FontFace.parseOrNull(data))
    }

    @Test
    fun readsCollectionCountShallowly() {
        val emptyCollection =
            TestByteBuilder()
                .uint32(0x74746366)
                .uint32(0)
                .uint32(0)
                .build()
        val enormousCollection =
            TestByteBuilder()
                .uint32(0x74746366)
                .uint32(0)
                .uint32(0xFFFF_FFFFL)
                .build()

        assertEquals(0L, fontsInCollection(emptyCollection))
        assertNull(FontFace.parseOrNull(emptyCollection))
        assertEquals(0xFFFF_FFFFL, fontsInCollection(enormousCollection))
        assertNull(FontFace.parseOrNull(enormousCollection))
    }

    @Test
    fun rejectsInvalidFaceIndices() {
        val collection =
            TestByteBuilder()
                .uint32(0x74746366)
                .uint32(0)
                .uint32(1)
                .uint32(12)
                .build()
        val regular = minimalSfnt()

        assertEquals(1L, fontsInCollection(collection))
        assertNull(FontFace.parseOrNull(collection, Int.MAX_VALUE))
        assertNull(FontFace.parseOrNull(regular, 1))
        assertNull(FontFace.parseOrNull(regular, -1))
    }

    @Test
    fun parsesMandatoryTablesAndHorizontalMetrics() {
        val face = FontFace.parse(minimalSfnt())

        assertEquals(0, face.faceIndex)
        assertEquals(1, face.faceCount)
        assertEquals(1000, face.unitsPerEm)
        assertEquals(FontBounds(-50, -200, 1200, 900), face.bounds)
        assertEquals(800, face.hheaAscender)
        assertEquals(-200, face.hheaDescender)
        assertEquals(100, face.hheaLineGap)
        assertEquals(3, face.glyphCount)
        assertEquals(500, face.glyphHorizontalAdvance(GlyphId(0)))
        assertEquals(600, face.glyphHorizontalAdvance(GlyphId(1)))
        assertEquals(600, face.glyphHorizontalAdvance(GlyphId(2)))
        assertEquals(30, face.glyphHorizontalSideBearing(GlyphId(2)))
        assertNull(face.glyphHorizontalAdvance(GlyphId(3)))
    }

    @Test
    fun selectsFacesFromCollection() {
        val collection = minimalCollection()
        val first = FontFace.parse(collection, 0)
        val second = FontFace.parse(collection, 1)

        assertEquals(2, first.faceCount)
        assertEquals(0, first.faceIndex)
        assertEquals(1000, first.unitsPerEm)
        assertEquals(2, second.faceCount)
        assertEquals(1, second.faceIndex)
        assertEquals(2048, second.unitsPerEm)
        assertEquals(1600, second.hheaAscender)
    }

    @Test
    fun returnsBoundedTableViewAndCopiesRanges() {
        val face = FontFace.parse(minimalSfnt())
        val maxp = assertNotNull(face.table(Tag.parse("maxp")))

        assertEquals(6, maxp.size)
        assertEquals(0, maxp[0].toInt())
        assertEquals(1, maxp[1].toInt())
        assertNull(face.table(Tag.parse("name")))

        val destination = ByteArray(4)
        maxp.copyInto(destination, destinationOffset = 1, startIndex = 1, endIndex = 4)
        assertContentEquals(byteArrayOf(0, 1, 0, 0), destination)
        assertFailsWith<IndexOutOfBoundsException> {
            maxp.copyInto(ByteArray(2), destinationOffset = 0, startIndex = 0, endIndex = 3)
        }
    }

    @Test
    fun parsesRequiredTablesFromUnsortedDirectory() {
        val face = FontFace.parse(minimalSfnt(sortTables = false))

        assertEquals(1000, face.unitsPerEm)
        assertEquals(3, face.glyphCount)
        assertEquals(600, face.glyphHorizontalAdvance(GlyphId(2)))
    }

    @Test
    fun missingOrMalformedHorizontalMetricsDoNotRejectFace() {
        val fonts =
            listOf(
                minimalSfnt(hmtxData = null),
                minimalSfnt(numberOfMetrics = 1, hmtxData = byteArrayOf(0, 1)),
                minimalSfnt(numberOfMetrics = 0),
            )

        for (data in fonts) {
            val face = FontFace.parse(data)
            assertEquals(1000, face.unitsPerEm)
            assertNull(face.glyphHorizontalAdvance(GlyphId(0)))
            assertNull(face.glyphHorizontalSideBearing(GlyphId(0)))
        }
    }

    @Test
    fun malformedOptionalTableRangeIsUnavailable() {
        val source = minimalSfnt()
        val hmtxRecord = 12 + 2 * 16
        source.fill(0xFF.toByte(), fromIndex = hmtxRecord + 12, toIndex = hmtxRecord + 16)

        val face = FontFace.parse(source)

        assertNull(face.table(Tag.parse("hmtx")))
        assertNull(face.glyphHorizontalAdvance(GlyphId(0)))
    }

    @Test
    fun everyTruncatedPrefixIsHandledSafely() {
        val valid = minimalSfnt()

        for (size in valid.indices) {
            FontFace.parseOrNull(valid.copyOf(size))
        }
        assertNotNull(FontFace.parseOrNull(valid))
    }

    @Test
    fun mapsBmpAndSupplementaryUnicodeCodePoints() {
        val cmap =
            testCmap(
                Triple(3, 1, testFormat4()),
                Triple(3, 10, testFormat12()),
            )
        val face = FontFace.parse(minimalSfnt(cmapData = cmap))

        assertEquals(GlyphId(1), face.glyphId(0x41))
        assertEquals(GlyphId(1), face.glyphId('A'))
        assertEquals(GlyphId(2), face.glyphId(0x10000))
        assertNull(face.glyphId(0x42))
        assertNull(face.glyphId(-1))
        assertNull(face.glyphId(0x110000))
        assertNull(face.glyphId(0xD800))
        assertNull(face.glyphId('\uD800'))
    }

    @Test
    fun exposesIndexedAndIterableCmapSubtables() {
        val cmap = testCmap(Triple(3, 1, testFormat4()), Triple(3, 10, testFormat12()))
        val subtables = assertNotNull(FontFace.parse(minimalSfnt(cmapData = cmap)).cmap).subtables

        assertEquals(2, subtables.size)
        assertEquals(CmapPlatformId.Windows, subtables[0]?.platformId)
        assertEquals(CmapFormat.SegmentMapping, subtables[0]?.format)
        assertTrue(subtables[0]?.isUnicode == true)
        assertEquals(
            listOf(CmapFormat.SegmentMapping, CmapFormat.SegmentedCoverage),
            subtables.map(CmapSubtable::format),
        )

        val codePoints = mutableListOf<Long>()
        subtables[0]!!.forEachCodePoint(codePoints::add)
        assertEquals(listOf(0x41L), codePoints)
    }

    @Test
    fun highLevelLookupSkipsNonUnicodeSubtablesInRecordOrder() {
        val cmap =
            testCmap(
                Triple(1, 0, testFormat4(glyphId = 2)),
                Triple(3, 1, testFormat4(glyphId = 1)),
            )
        val face = FontFace.parse(minimalSfnt(cmapData = cmap))
        val subtables = requireNotNull(face.cmap).subtables

        assertTrue(subtables[0]?.isUnicode == false)
        assertEquals(GlyphId(2), subtables[0]?.glyphId(0x41))
        assertEquals(GlyphId(1), face.glyphId(0x41))
    }

    @Test
    fun malformedSubtableTerminatesHighLevelLookupAndIterationLikeUpstream() {
        val malformed = byteArrayOf(0, 99)
        val cmap = testCmap(Triple(0, 0, malformed), Triple(3, 1, testFormat4()))
        val face = FontFace.parse(minimalSfnt(cmapData = cmap))

        assertNull(face.cmap?.subtables?.get(0))
        assertEquals(
            CmapFormat.SegmentMapping,
            face.cmap
                ?.subtables
                ?.get(1)
                ?.format,
        )
        assertTrue(
            face.cmap
                ?.subtables
                ?.toList()
                .isNullOrEmpty(),
        )
        assertNull(face.glyphId(0x41))
    }

    @Test
    fun resolvesVariationMappingsAndDefaultFallback() {
        val cmap = testCmap(Triple(3, 1, testFormat4()), Triple(0, 5, testFormat14()))
        val face = FontFace.parse(minimalSfnt(cmapData = cmap))

        assertEquals(GlyphId(1), face.glyphVariationId(0x41, 0xFE0F))
        assertEquals(GlyphId(2), face.glyphVariationId(0x42, 0xFE0F))
        assertNull(face.glyphVariationId(0x43, 0xFE0F))
        assertNull(face.glyphVariationId(0x41, 0xD800))
        assertEquals(
            CmapVariationResult.UseDefault,
            face.cmap
                ?.subtables
                ?.get(1)
                ?.glyphVariation(0x41, 0xFE0F),
        )
    }

    @Test
    fun malformedCmapDoesNotRejectFace() {
        val face = FontFace.parse(minimalSfnt(cmapData = byteArrayOf(0, 0, 0, 1)))

        assertNull(face.cmap)
        assertNull(face.glyphId(0x41))
    }

    @Test
    fun cmapTraversalPropagatesCallbackFailure() {
        val cmap = testCmap(Triple(3, 1, testFormat4()))
        val subtable = FontFace.parse(minimalSfnt(cmapData = cmap)).cmap!!.subtables[0]!!

        assertFailsWith<ExpectedCallbackFailure> {
            subtable.forEachCodePoint { throw ExpectedCallbackFailure() }
        }
    }

    @Test
    fun everyCmapFontPrefixIsHandledSafely() {
        val valid = minimalSfnt(cmapData = testCmap(Triple(3, 1, testFormat4())))

        for (size in valid.indices) {
            FontFace.parseOrNull(valid.copyOf(size))
        }
        assertNotNull(FontFace.parseOrNull(valid))
    }

    private class ExpectedCallbackFailure : RuntimeException()
}
