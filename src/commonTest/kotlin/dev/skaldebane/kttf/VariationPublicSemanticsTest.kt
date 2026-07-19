package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.variations.deltaSetMap
import dev.skaldebane.kttf.internal.tables.variations.variationStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VariationPublicSemanticsTest {
    @Test
    fun mvarTruncatesFractionalDeltasAndFallsBackOnI16Overflow() {
        val os2 =
            testOs2Table().also {
                it.writeTestInt16(26, 20_000)
                it.writeTestInt16(28, 10)
            }
        val face =
            FontFace
                .parse(
                    minimalSfnt(
                        os2Data = os2,
                        additionalTables =
                            mapOf(
                                Tag.parse("fvar") to semanticsFvar(),
                                Tag.parse("MVAR") to semanticsMvar(),
                            ),
                    ),
                ).withVariations(mapOf(Tag.parse("wght") to 650f))

        assertEquals(FontLineMetrics(10, 20_000), face.strikeoutMetrics)
    }

    @Test
    fun hvarUsesU16AndI16OutputDomains() {
        val face =
            FontFace
                .parse(
                    minimalSfnt(
                        glyphCount = 1,
                        numberOfMetrics = 1,
                        hmtxData = TestByteBuilder().uint16(0xFFFF).int16(0x7FFF).build(),
                        additionalTables =
                            mapOf(
                                Tag.parse("fvar") to semanticsFvar(),
                                Tag.parse("HVAR") to semanticsHvar(),
                            ),
                    ),
                ).withVariations(mapOf(Tag.parse("wght") to 650f))

        assertNull(face.glyphHorizontalAdvance(GlyphId(0)))
        assertNull(face.glyphHorizontalSideBearing(GlyphId(0)))
    }

    @Test
    fun vvarUsesU16AndI16OutputDomains() {
        val face =
            FontFace
                .parse(
                    minimalSfnt(
                        glyphCount = 1,
                        vheaData = testVheaTable(),
                        vmtxData = TestByteBuilder().uint16(0xFFFF).int16(0x7FFF).build(),
                        additionalTables =
                            mapOf(
                                Tag.parse("fvar") to semanticsFvar(),
                                Tag.parse("VVAR") to semanticsVvar(),
                                Tag.parse("VORG") to semanticsVorg(),
                            ),
                    ),
                ).withVariations(mapOf(Tag.parse("wght") to 650f))

        assertNull(face.glyphVerticalAdvance(GlyphId(0)))
        assertNull(face.glyphVerticalSideBearing(GlyphId(0)))
        assertNull(face.glyphVerticalOrigin(GlyphId(0)))
    }

    @Test
    fun gvarPhantomPointsDriveAdvanceFallbackAndArePublic() {
        val glyph = ByteArray(10)
        val face =
            FontFace
                .parse(
                    minimalSfnt(
                        glyphCount = 1,
                        numberOfMetrics = 1,
                        hmtxData = TestByteBuilder().uint16(500).int16(0).build(),
                        locaData = testShortLoca(0, glyph.size),
                        glyfData = glyph,
                        vheaData = testVheaTable(),
                        vmtxData = TestByteBuilder().uint16(0xFFFF).int16(0).build(),
                        additionalTables =
                            mapOf(
                                Tag.parse("fvar") to semanticsFvar(),
                                Tag.parse("gvar") to phantomGvar(),
                            ),
                    ),
                ).withVariations(mapOf(Tag.parse("wght") to 650f))

        assertEquals(FontPointF(0.5f, 0f), face.glyphPhantomPoints(GlyphId(0))?.right)
        assertEquals(FontPointF(0f, 2f), face.glyphPhantomPoints(GlyphId(0))?.bottom)
        assertEquals(501, face.glyphHorizontalAdvance(GlyphId(0)))
        assertNull(face.glyphVerticalAdvance(GlyphId(0)))
    }

    @Test
    fun parsedGvarWithoutGlyfBlocksFallbackToCffOutline() {
        val cff = testCffTable(byteArrayOf(149.toByte(), 22, 159.toByte(), 169.toByte(), 5, 14))
        val plain = FontFace.parse(minimalSfnt(glyphCount = 1, cffData = cff))
        val mixed =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 1,
                    cffData = cff,
                    additionalTables =
                        mapOf(
                            Tag.parse("fvar") to semanticsFvar(),
                            Tag.parse("gvar") to phantomGvar(),
                        ),
                ),
            )

        assertTrue(plain.glyphBoundingBox(GlyphId(0)) != null)
        assertNull(mixed.glyphBoundingBox(GlyphId(0)))
    }

    @Test
    fun exposesSimpleColrGlyphQuery() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 2,
                    additionalTables =
                        mapOf(
                            Tag.parse("CPAL") to semanticsCpal(),
                            Tag.parse("COLR") to semanticsColrV0(),
                        ),
                ),
            )

        assertTrue(face.isSimpleColorGlyph(GlyphId(0)))
        assertFalse(face.isSimpleColorGlyph(GlyphId(1)))
        assertNull(face.colorGlyphClipBox(GlyphId(0)))
    }

    @Test
    fun evaluatesPublicColrClipBoxAtCurrentCoordinates() {
        val face =
            FontFace
                .parse(
                    minimalSfnt(
                        glyphCount = 1,
                        additionalTables =
                            mapOf(
                                Tag.parse("fvar") to semanticsFvar(),
                                Tag.parse("CPAL") to semanticsCpal(),
                                Tag.parse("COLR") to semanticsVariableColr(),
                            ),
                    ),
                ).withVariations(mapOf(Tag.parse("wght") to 650f))

        assertEquals(ColorClipBox(1.5f, 3f, 31.5f, 42f), face.colorGlyphClipBox(GlyphId(0)))
        assertFalse(face.isSimpleColorGlyph(GlyphId(0)))
    }
}

private fun semanticsFvar(): ByteArray =
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

private fun semanticsMvar(): ByteArray {
    val store = variationStore(listOf(0 to 1, 0 to 32_767), longWords = true)
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(0)
        .uint16(8)
        .uint16(2)
        .uint16(28)
        .raw("stro".encodeToByteArray())
        .uint16(0)
        .uint16(0)
        .raw("strs".encodeToByteArray())
        .uint16(0)
        .uint16(1)
        .raw(store)
        .build()
}

private fun semanticsHvar(): ByteArray {
    val store = variationStore(listOf(0 to 4))
    val map = deltaSetMap(0)
    val mapOffset = 20 + store.size
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint32(20)
        .uint32(0)
        .uint32(mapOffset.toLong())
        .uint32(0)
        .raw(store)
        .raw(map)
        .build()
}

private fun semanticsVvar(): ByteArray {
    val store = variationStore(listOf(0 to 4))
    val map = deltaSetMap(0)
    val mapOffset = 24 + store.size
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint32(24)
        .uint32(0)
        .uint32(mapOffset.toLong())
        .uint32(0)
        .uint32(mapOffset.toLong())
        .raw(store)
        .raw(map)
        .build()
}

private fun semanticsVorg(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0000)
        .int16(0x7FFF)
        .uint16(0)
        .build()

private fun phantomGvar(): ByteArray {
    val serialized = byteArrayOf(3, 0, 1, 0, 0, 3, 0, 0, 0, 4)
    val glyphData =
        TestByteBuilder()
            .uint16(1)
            .uint16(10)
            .uint16(serialized.size)
            .uint16(0x8000)
            .int16(16_384)
            .raw(serialized)
            .build()
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(1)
        .uint16(0)
        .uint32(24)
        .uint16(1)
        .uint16(0)
        .uint32(24)
        .uint16(0)
        .uint16(glyphData.size / 2)
        .raw(glyphData)
        .build()
}

private fun semanticsCpal(): ByteArray =
    TestByteBuilder()
        .uint16(0)
        .uint16(1)
        .uint16(1)
        .uint16(1)
        .uint32(14)
        .uint16(0)
        .raw(byteArrayOf(0, 0, 0, 0xFF.toByte()))
        .build()

private fun semanticsColrV0(): ByteArray =
    TestByteBuilder()
        .uint16(0)
        .uint16(1)
        .uint32(14)
        .uint32(20)
        .uint16(1)
        .uint16(0)
        .uint16(0)
        .uint16(1)
        .uint16(1)
        .uint16(0)
        .build()

private fun semanticsVariableColr(): ByteArray {
    val paint =
        TestByteBuilder()
            .int8(2)
            .uint16(0)
            .int16(16_384)
            .build()
    val clip =
        TestByteBuilder()
            .int8(1)
            .uint32(1)
            .uint16(0)
            .uint16(0)
            .raw(byteArrayOf(0, 0, 12))
            .int8(2)
            .int16(1)
            .int16(2)
            .int16(30)
            .int16(40)
            .uint32(500)
            .build()
    val mapEntries = IntArray(504)
    mapEntries[500] = 1
    mapEntries[501] = 2
    mapEntries[502] = 3
    mapEntries[503] = 4
    val map = deltaSetMap(*mapEntries)
    val store = variationStore(listOf(0 to 0, 0 to 1, 0 to 2, 0 to 3, 0 to 4))
    val baseOffset = 34
    val clipOffset = baseOffset + 10 + paint.size
    val mapOffset = clipOffset + clip.size
    val storeOffset = mapOffset + map.size
    return TestByteBuilder()
        .uint16(1)
        .uint16(0)
        .uint32(0)
        .uint32(0)
        .uint16(0)
        .uint32(baseOffset.toLong())
        .uint32(0)
        .uint32(clipOffset.toLong())
        .uint32(mapOffset.toLong())
        .uint32(storeOffset.toLong())
        .uint32(1)
        .uint16(0)
        .uint32(10)
        .raw(paint)
        .raw(clip)
        .raw(map)
        .raw(store)
        .build()
}

private fun ByteArray.writeTestInt16(
    offset: Int,
    value: Int,
) {
    this[offset] = (value ushr 8).toByte()
    this[offset + 1] = value.toByte()
}
