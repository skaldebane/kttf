package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.variations.deltaSetMap
import dev.skaldebane.kttf.internal.tables.variations.variationStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PublicIntegrationGapsTest {
    @Test
    fun resolvesWindowsAndClassicMacintoshLanguages() {
        val english = FontFace.parse(minimalSfnt(nameData = testNameTable())).names[0]!!
        val french = FontFace.parse(minimalSfnt(nameData = nameTable(3, 1, 0x040C))).names[0]!!
        val macintosh = FontFace.parse(minimalSfnt(nameData = nameTable(1, 0, 0))).names[0]!!
        val unknown = FontFace.parse(minimalSfnt(nameData = nameTable(1, 1, 1))).names[0]!!

        assertEquals(FontLanguage.EnglishUnitedStates, english.language)
        assertEquals(FontLanguage("French", "France"), french.language)
        assertEquals(FontLanguage.EnglishUnitedStates, macintosh.language)
        assertEquals(FontLanguage.Unknown, unknown.language)
    }

    @Test
    fun appliesMvarToScriptStrikeoutAndVerticalMetrics() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    os2Data = metricsOs2(),
                    vheaData = testVheaTable(),
                    vmtxData = testVmtxTable(),
                    additionalTables =
                        mapOf(
                            Tag.parse("fvar") to testFvar(),
                            Tag.parse("MVAR") to metricsMvar(),
                            Tag.parse("VORG") to testVorg(),
                            Tag.parse("VVAR") to testVvar(),
                        ),
                ),
            )

        assertEquals(FontLineMetrics(30, 20), face.strikeoutMetrics)
        assertEquals(FontScriptMetrics(100, 200, 3, 4), face.subscriptMetrics)
        assertEquals(FontScriptMetrics(110, 210, 5, 6), face.superscriptMetrics)
        assertEquals(1200, face.verticalHeight)
        assertEquals(900, face.glyphVerticalOrigin(GlyphId(0)))

        val varied = face.withVariations(mapOf(Tag.parse("wght") to 900f))
        assertEquals(FontLineMetrics(50, 40), varied.strikeoutMetrics)
        assertEquals(FontScriptMetrics(120, 220, 23, 24), varied.subscriptMetrics)
        assertEquals(FontScriptMetrics(130, 230, 25, 26), varied.superscriptMetrics)
        assertEquals(1200, varied.verticalHeight)
        assertEquals(940, varied.glyphVerticalOrigin(GlyphId(0)))
        assertEquals(1040, varied.glyphVerticalAdvance(GlyphId(0)))
    }

    @Test
    fun enumeratesDirectoryAndSuccessfullyParsedTables() {
        val source = minimalSfnt(nameData = testNameTable(), os2Data = testOs2Table())
        val face = FontFace.parse(source)
        val maxpRecord = face.tableRecords.first { it.tag == Tag.parse("maxp") }

        assertEquals(face.tableRecords.size, face.tableRecords.toList().size)
        assertEquals(0L, maxpRecord.checksum)
        assertEquals(6L, maxpRecord.length)
        assertContentEquals(
            source.copyOfRange(maxpRecord.offset.toInt(), (maxpRecord.offset + maxpRecord.length).toInt()),
            face.table(maxpRecord.tag)!!.copy(),
        )
        assertTrue(Tag.parse("head") in face.parsedTables)
        assertTrue(Tag.parse("name") in face.parsedTables)
        assertTrue(Tag.parse("OS/2") in face.parsedTables)
        assertFalse(Tag.parse("post") in face.parsedTables)
        assertEquals(ParsedTableKind.Names, face.parsedTables[Tag.parse("name")]?.kind)
        assertEquals(
            "name",
            face.parsedTables[Tag.parse("name")]
                ?.table
                ?.tag
                .toString(),
        )
    }

    @Test
    fun parsesIndependentRetainedTableArrays() {
        val sourceFace = FontFace.parse(minimalSfnt())
        val tags = listOf("head", "hhea", "maxp", "hmtx").map(Tag::parse)
        val arrays = tags.associateWith { sourceFace.table(it)!!.copy() }.toMutableMap()
        val hmtx = arrays.getValue(Tag.parse("hmtx"))
        val face = FontFace.fromTables(arrays)
        arrays.clear()

        assertTrue(face.tableRecords.toList().isEmpty())
        assertEquals(500, face.glyphHorizontalAdvance(GlyphId(0)))
        hmtx[0] = 0x02
        hmtx[1] = 0xBC.toByte()
        assertEquals(700, face.glyphHorizontalAdvance(GlyphId(0)))
        assertNotNull(face.parsedTables[Tag.parse("hmtx")])
    }

    @Test
    fun reportsDistinctFaceParsingErrors() {
        assertEquals(
            FontParsingError.UnknownMagic,
            assertFailsWith<FontParsingException> { FontFace.parse(byteArrayOf(0, 0, 0, 0)) }.error,
        )
        assertEquals(
            FontParsingError.MalformedFont,
            assertFailsWith<FontParsingException> {
                FontFace.parse(TestByteBuilder().uint32(0x0001_0000).build())
            }.error,
        )
        assertEquals(
            FontParsingError.FaceIndexOutOfBounds,
            assertFailsWith<FontParsingException> { FontFace.parse(minimalSfnt(), 1) }.error,
        )
        assertEquals(
            FontParsingError.MissingOrMalformedHead,
            assertFailsWith<FontParsingException> { FontFace.fromTables(emptyMap()) }.error,
        )
    }

    @Test
    fun exposesCffWidthAndMatrixAndGeneralUtilities() {
        val charString = byteArrayOf(247.toByte(), 92, 139.toByte(), 139.toByte(), 21, 14)
        val face = FontFace.parse(minimalSfnt(glyphCount = 1, cffData = testCffTable(charString)))
        val tag = Tag.fromBytes(byteArrayOf(0x6E, 0x61, 0x6D, 0x65))

        assertEquals(200, face.glyphCffWidth(GlyphId(0)))
        assertNull(face.glyphCid(GlyphId(0)))
        assertEquals(FontTransform(0.001f, 0f, 0f, 0.001f, 0f, 0f), face.cffMatrix)
        assertEquals("name", tag.toString())
        assertEquals(0x6E616D65L, tag.packedValue)
        assertContentEquals(byteArrayOf(0x6E, 0x61, 0x6D, 0x65), tag.toByteArray())
        assertEquals("A   ", Tag.fromBytesLossy(byteArrayOf(0x41)).toString())
        assertTrue(Tag.fromBytesLossy(ByteArray(0)).isNull)
        assertEquals(1250, face.bounds.width)
        assertEquals(1100, face.bounds.height)
        assertTrue(face.bounds.contains(0, 0))
    }
}

private fun FontTable.copy(): ByteArray = copyInto(ByteArray(size))

private fun nameTable(
    platform: Int,
    encoding: Int,
    language: Int,
): ByteArray =
    TestByteBuilder()
        .uint16(0)
        .uint16(1)
        .uint16(18)
        .uint16(platform)
        .uint16(encoding)
        .uint16(language)
        .uint16(NameIds.FAMILY)
        .uint16(0)
        .uint16(0)
        .build()

private fun metricsOs2(): ByteArray =
    testOs2Table().also {
        it.writeInt16(10, 100)
        it.writeInt16(12, 200)
        it.writeInt16(14, 3)
        it.writeInt16(16, 4)
        it.writeInt16(18, 110)
        it.writeInt16(20, 210)
        it.writeInt16(22, 5)
        it.writeInt16(24, 6)
        it.writeInt16(26, 20)
        it.writeInt16(28, 30)
    }

private fun testFvar(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(16)
        .uint16(2)
        .uint16(1)
        .uint16(20)
        .uint16(0)
        .uint16(8)
        .raw("wght".encodeToByteArray())
        .uint32(100L shl 16)
        .uint32(400L shl 16)
        .uint32(900L shl 16)
        .uint16(0)
        .uint16(256)
        .build()

private fun metricsMvar(): ByteArray {
    val tags = listOf("stro", "strs", "sbxs", "sbys", "sbxo", "sbyo", "spxs", "spys", "spxo", "spyo").sorted()
    val store = variationStore()
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(0)
        .uint16(8)
        .uint16(tags.size)
        .uint16(12 + tags.size * 8)
        .apply {
            tags.forEach { raw(it.encodeToByteArray()).uint16(0).uint16(0) }
        }.raw(store)
        .build()
}

private fun testVorg(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0000)
        .int16(900)
        .uint16(0)
        .build()

private fun testVvar(): ByteArray {
    val store = variationStore()
    val map = deltaSetMap(0x0001)
    val mapOffset = 24 + store.size
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint32(24)
        .uint32(mapOffset.toLong())
        .uint32(mapOffset.toLong())
        .uint32(mapOffset.toLong())
        .uint32(mapOffset.toLong())
        .raw(store)
        .raw(map)
        .build()
}

private fun ByteArray.writeInt16(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}
