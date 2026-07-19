package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.layout.coverage1
import dev.skaldebane.kttf.internal.tables.layout.i16
import dev.skaldebane.kttf.internal.tables.layout.join
import dev.skaldebane.kttf.internal.tables.layout.layoutTable
import dev.skaldebane.kttf.internal.tables.layout.u16
import dev.skaldebane.kttf.internal.tables.variations.variationStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NewFamiliesIntegrationTest {
    @Test
    fun derivesVariableFaceAndIntegratesMetricsAndCff2() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 1,
                    numberOfMetrics = 1,
                    os2Data = testOs2Table(),
                    additionalTables =
                        mapOf(
                            Tag.parse("fvar") to fvar(),
                            Tag.parse("HVAR") to hvar(),
                            Tag.parse("MVAR") to mvar(),
                            Tag.parse("CFF2") to cff2(),
                        ),
                ),
            )

        assertTrue(face.isVariable)
        assertEquals(Tag.parse("wght"), face.variationAxes[0]?.tag)
        assertEquals(500, face.glyphHorizontalAdvance(GlyphId(0)))
        assertEquals(FontBounds(10, 0, 30, 30), face.glyphBoundingBox(GlyphId(0)))

        val varied = face.withVariations(mapOf(Tag.parse("wght") to 900f))
        assertFalse(face.hasNonDefaultVariations)
        assertTrue(varied.hasNonDefaultVariations)
        assertEquals(listOf(1f), varied.variationCoordinates)
        assertEquals(520, varied.glyphHorizontalAdvance(GlyphId(0)))
        assertEquals(920, varied.ascender)

        val glyph = testTriangleGlyph()
        val glyfFace =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 1,
                    numberOfMetrics = 1,
                    locaData = testShortLoca(0, glyph.size),
                    glyfData = glyph,
                    additionalTables = mapOf(Tag.parse("fvar") to fvar(), Tag.parse("gvar") to gvar()),
                ),
            )
        assertEquals(
            FontBounds(10, 0, 110, 100),
            glyfFace.withVariations(mapOf(Tag.parse("wght") to 900f)).glyphBoundingBox(GlyphId(0)),
        )
        assertEquals(
            510,
            glyfFace.withVariations(mapOf(Tag.parse("wght") to 900f)).glyphHorizontalAdvance(GlyphId(0)),
        )
    }

    @Test
    fun exposesColorSvgAndBitmapFamilies() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 2,
                    additionalTables =
                        mapOf(
                            Tag.parse("CPAL") to cpal(),
                            Tag.parse("COLR") to colr(),
                            Tag.parse("SVG ") to svg(),
                            Tag.parse("sbix") to sbix(),
                        ),
                ),
            )
        val painter = RecordingColorPainter()

        assertEquals(1, face.colorPaletteCount)
        assertTrue(face.isColorGlyph(GlyphId(0)))
        assertTrue(face.paintColorGlyph(GlyphId(0), 0, FontColor(0, 0, 0, 255), painter))
        assertEquals(listOf("glyph:1", "color:30,20,10,40"), painter.events)

        val svg = assertNotNull(face.svgGlyph(GlyphId(1)))
        val svgBytes = ByteArray(svg.size)
        svg.copyInto(svgBytes)
        assertContentEquals("<svg/>".encodeToByteArray(), svgBytes)

        val raster = assertNotNull(face.rasterGlyph(GlyphId(0), 16))
        assertEquals(GlyphRasterFormat.Png, raster.format)
        assertEquals(2, raster.width)
        assertEquals(3, raster.height)
    }

    @Test
    fun exposesOpenTypeLayoutAndMath() {
        val gsubSingle = join(u16(1, 6, 3), coverage1(0))
        val gposSingle = join(u16(1, 8, 0x0001), i16(7), coverage1(0))
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 2,
                    additionalTables =
                        mapOf(
                            Tag.parse("GDEF") to gdef(),
                            Tag.parse("GSUB") to layoutTable(1, gsubSingle),
                            Tag.parse("GPOS") to layoutTable(1, gposSingle),
                            Tag.parse("MATH") to math(),
                        ),
                ),
            )

        assertEquals(GlyphDefinitionClass.Base, face.glyphDefinitions?.glyphClass(GlyphId(0)))
        assertEquals(GlyphDefinitionClass.Mark, face.glyphDefinitions?.glyphClass(GlyphId(1)))
        assertEquals(GlyphId(3), face.glyphSubstitutions?.substitute(0, GlyphId(0)))
        assertEquals(7, face.glyphPositioning?.adjustment(0, GlyphId(0))?.xPlacement)
        assertEquals(Tag.parse("latn"), face.glyphSubstitutions?.script(0)?.tag)
        assertEquals(80, face.mathLayout?.scriptPercentScaleDown)
        assertEquals(12, face.mathLayout?.constant(0))
    }

    @Test
    fun exposesKerningVorgStatAndAat() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 2,
                    additionalTables =
                        mapOf(
                            Tag.parse("kern") to kern(),
                            Tag.parse("VORG") to vorg(),
                            Tag.parse("STAT") to stat(),
                            Tag.parse("ankr") to ankr(),
                            Tag.parse("feat") to feat(),
                        ),
                ),
            )

        assertEquals(-20, face.kerningSubtables[0]?.kerning(GlyphId(0), GlyphId(1)))
        assertEquals(900, face.glyphVerticalOrigin(GlyphId(0)))
        assertEquals(850, face.glyphVerticalOrigin(GlyphId(1)))
        assertEquals(Tag.parse("wght"), face.styleAttributes?.axis(0)?.tag)
        assertEquals(listOf(FontPoint(10, -20)), face.appleLayout.anchorPoints(GlyphId(0)))
        assertEquals(1, face.appleLayout.featureCount)
        assertEquals(
            3,
            face.appleLayout
                .feature(0)
                ?.settings
                ?.get(0)
                ?.value,
        )
    }

    private class RecordingColorPainter : ColorGlyphPainter {
        val events = mutableListOf<String>()

        override fun outlineGlyph(glyphId: GlyphId) {
            events += "glyph:${glyphId.value}"
        }

        override fun paint(paint: ColorPaint) {
            if (paint is ColorPaint.Solid) {
                val color = paint.color
                events += "color:${color.red},${color.green},${color.blue},${color.alpha}"
            }
        }

        override fun pushClip(): Unit = Unit

        override fun pushClipBox(clipBox: ColorClipBox): Unit = Unit

        override fun popClip(): Unit = Unit

        override fun pushLayer(mode: ColorCompositeMode): Unit = Unit

        override fun popLayer(): Unit = Unit

        override fun pushTransform(transform: FontTransform): Unit = Unit

        override fun popTransform(): Unit = Unit
    }
}

private fun fvar(): ByteArray =
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

private fun hvar(): ByteArray {
    val store = variationStore()
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint32(20)
        .uint32(0)
        .uint32(0)
        .uint32(0)
        .raw(store)
        .build()
}

private fun mvar(): ByteArray {
    val store = variationStore()
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(0)
        .uint16(8)
        .uint16(1)
        .uint16(20)
        .raw("hasc".encodeToByteArray())
        .uint16(0)
        .uint16(0)
        .raw(store)
        .build()
}

private fun gvar(): ByteArray {
    val serialized =
        TestByteBuilder()
            .int8(13)
            .int8(10)
            .int8(10)
            .int8(10)
            .int8(10)
            .int8(10)
            .zeroes(9)
            .build()
    val glyphData =
        TestByteBuilder()
            .uint16(1)
            .uint16(10)
            .uint16(serialized.size)
            .uint16(0x8000)
            .int16(16384)
            .raw(serialized)
            .build()
    val padded = if (glyphData.size % 2 == 0) glyphData else glyphData + byteArrayOf(0)
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(1)
        .uint16(0)
        .uint32(24)
        .uint16(1)
        .uint16(0)
        .uint32(24)
        .uint16(0)
        .uint16(padded.size / 2)
        .raw(padded)
        .build()
}

private fun cff2(): ByteArray {
    val charString = byteArrayOf(149.toByte(), 22, 159.toByte(), 169.toByte(), 5)
    val rawStore = variationStore()
    val store = TestByteBuilder().uint16(rawStore.size).raw(rawStore).build()
    val globalIndex = TestByteBuilder().uint32(0).build()
    val charStrings =
        TestByteBuilder()
            .uint32(1)
            .int8(4)
            .uint32(1)
            .uint32((charString.size + 1).toLong())
            .raw(charString)
            .build()
    val charStringsOffset = 5 + 12 + globalIndex.size
    val variationStoreOffset = charStringsOffset + charStrings.size
    val top =
        TestByteBuilder()
            .int8(29)
            .uint32(charStringsOffset.toLong())
            .int8(17)
            .int8(29)
            .uint32(variationStoreOffset.toLong())
            .int8(24)
            .build()
    return TestByteBuilder()
        .int8(2)
        .int8(0)
        .int8(5)
        .uint16(top.size)
        .raw(top)
        .raw(globalIndex)
        .raw(charStrings)
        .raw(store)
        .build()
}

private fun cpal(): ByteArray =
    TestByteBuilder()
        .uint16(0)
        .uint16(1)
        .uint16(1)
        .uint16(1)
        .uint32(14)
        .uint16(0)
        .raw(byteArrayOf(10, 20, 30, 40))
        .build()

private fun colr(): ByteArray =
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

private fun svg(): ByteArray {
    val document = "<svg/>".encodeToByteArray()
    return TestByteBuilder()
        .uint16(0)
        .uint32(6)
        .uint16(1)
        .uint16(1)
        .uint16(1)
        .uint32(14)
        .uint32(document.size.toLong())
        .raw(document)
        .build()
}

private fun sbix(): ByteArray {
    val png = ByteArray(24)
    png[19] = 2
    png[23] = 3
    val strike =
        TestByteBuilder()
            .uint16(16)
            .uint16(72)
            .uint32(16)
            .uint32(48)
            .uint32(48)
            .int16(0)
            .int16(0)
            .raw("png ".encodeToByteArray())
            .raw(png)
            .build()
    return TestByteBuilder()
        .uint16(1)
        .uint16(0)
        .uint32(1)
        .uint32(12)
        .raw(strike)
        .build()
}

private fun gdef(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(12)
        .uint16(0)
        .uint16(0)
        .uint16(0)
        .uint16(1)
        .uint16(0)
        .uint16(2)
        .uint16(1)
        .uint16(3)
        .build()

private fun math(): ByteArray {
    val constants = ByteArray(214)
    constants[1] = 80
    constants[3] = 60
    constants[9] = 12
    return TestByteBuilder()
        .uint16(1)
        .uint16(0)
        .uint16(10)
        .uint16(0)
        .uint16(0)
        .raw(constants)
        .build()
}

private fun kern(): ByteArray =
    TestByteBuilder()
        .uint16(0)
        .uint16(1)
        .uint16(0)
        .uint16(20)
        .int8(0)
        .int8(1)
        .uint16(1)
        .uint16(0)
        .uint16(0)
        .uint16(0)
        .uint16(0)
        .uint16(1)
        .int16(-20)
        .build()

private fun vorg(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0000)
        .int16(900)
        .uint16(1)
        .uint16(1)
        .int16(850)
        .build()

private fun stat(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0001)
        .uint16(8)
        .uint16(1)
        .uint32(20)
        .uint16(0)
        .uint32(28)
        .uint16(257)
        .raw("wght".encodeToByteArray())
        .uint16(256)
        .uint16(0)
        .build()

private fun ankr(): ByteArray =
    TestByteBuilder()
        .uint16(0)
        .uint16(0)
        .uint32(12)
        .uint32(22)
        .uint16(8)
        .uint16(0)
        .uint16(2)
        .uint16(0)
        .uint16(0)
        .uint32(1)
        .int16(10)
        .int16(-20)
        .build()

private fun feat(): ByteArray =
    TestByteBuilder()
        .uint32(0x0001_0000)
        .uint16(1)
        .zeroes(6)
        .uint16(1)
        .uint16(1)
        .uint32(24)
        .int8(0x80)
        .int8(0)
        .uint16(300)
        .uint16(3)
        .uint16(301)
        .build()
