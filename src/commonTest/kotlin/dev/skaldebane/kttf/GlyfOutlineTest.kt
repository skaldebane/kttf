package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GlyfOutlineTest {
    @Test
    fun outlinesSimpleTrueTypeGlyph() {
        val glyph = testTriangleGlyph()
        val face = outlineFace(glyph, intArrayOf(0, glyph.size, glyph.size, glyph.size))
        val sink = RecordingOutlineSink()

        assertEquals(FontBounds(0, 0, 100, 100), face.outlineGlyph(GlyphId(0), sink))
        assertEquals(
            listOf("M 0.0 0.0", "L 100.0 0.0", "L 0.0 100.0", "L 0.0 0.0", "Z"),
            sink.commands,
        )
        assertEquals(FontBounds(0, 0, 100, 100), face.glyphBoundingBox(GlyphId(0)))
        assertNull(face.outlineGlyph(GlyphId(1), sink))
    }

    @Test
    fun outlinesTranslatedCompositeGlyph() {
        val simple = testTriangleGlyph()
        val composite = compositeGlyph(componentGlyphId = 0, translateX = 50, translateY = 20)
        val data = simple + composite
        val face = outlineFace(data, intArrayOf(0, simple.size, data.size, data.size))
        val sink = RecordingOutlineSink()

        assertEquals(FontBounds(50, 20, 150, 120), face.outlineGlyph(GlyphId(1), sink))
        assertEquals("M 50.0 20.0", sink.commands.first())
    }

    @Test
    fun boundsUseTransformedQuadraticControlPoints() {
        val glyph = quadraticGlyph()
        val face = outlineFace(glyph, intArrayOf(0, glyph.size, glyph.size, glyph.size))
        val sink = RecordingOutlineSink()

        assertEquals(FontBounds(0, 0, 100, 100), face.outlineGlyph(GlyphId(0), sink))
        assertEquals(listOf("M 0.0 0.0", "Q 100.0 100.0 0.0 0.0", "Z"), sink.commands)
    }

    @Test
    fun rejectsCompositeCyclesAtDepthLimit() {
        val cycle = compositeGlyph(componentGlyphId = 0, translateX = 0, translateY = 0)
        val face = outlineFace(cycle, intArrayOf(0, cycle.size, cycle.size, cycle.size))

        assertNull(face.outlineGlyph(GlyphId(0), RecordingOutlineSink()))
    }

    @Test
    fun propagatesSinkExceptions() {
        val glyph = testTriangleGlyph()
        val face = outlineFace(glyph, intArrayOf(0, glyph.size, glyph.size, glyph.size))

        assertFailsWith<ExpectedSinkFailure> {
            face.outlineGlyph(
                GlyphId(0),
                object : OutlineSink by RecordingOutlineSink() {
                    override fun moveTo(
                        x: Float,
                        y: Float,
                    ): Unit = throw ExpectedSinkFailure()
                },
            )
        }
    }

    @Test
    fun everyOutlineFontPrefixTerminatesSafely() {
        val glyph = testTriangleGlyph()
        val valid = outlineFont(glyph, intArrayOf(0, glyph.size, glyph.size, glyph.size))

        for (size in valid.indices) {
            FontFace.parseOrNull(valid.copyOf(size))?.outlineGlyph(GlyphId(0), RecordingOutlineSink())
        }
    }

    private fun outlineFace(
        glyf: ByteArray,
        offsets: IntArray,
    ): FontFace = FontFace.parse(outlineFont(glyf, offsets))

    private fun outlineFont(
        glyf: ByteArray,
        offsets: IntArray,
    ): ByteArray {
        require(offsets.all { it % 2 == 0 })
        val loca = testShortLoca(*offsets)
        return minimalSfnt(glyphCount = 3, locaData = loca, glyfData = glyf)
    }

    private fun quadraticGlyph(): ByteArray =
        TestByteBuilder()
            .int16(1)
            .int16(0)
            .int16(0)
            .int16(100)
            .int16(100)
            .uint16(1)
            .uint16(0)
            .int8(0x31)
            .int8(0x36)
            .int8(100)
            .int8(100)
            .build()

    private fun compositeGlyph(
        componentGlyphId: Int,
        translateX: Int,
        translateY: Int,
    ): ByteArray =
        TestByteBuilder()
            .int16(-1)
            .int16(0)
            .int16(0)
            .int16(0)
            .int16(0)
            .uint16(0x0003)
            .uint16(componentGlyphId)
            .int16(translateX)
            .int16(translateY)
            .build()

    private class RecordingOutlineSink : OutlineSink {
        val commands = mutableListOf<String>()

        override fun moveTo(
            x: Float,
            y: Float,
        ) {
            commands += "M ${x.testCoordinate()} ${y.testCoordinate()}"
        }

        override fun lineTo(
            x: Float,
            y: Float,
        ) {
            commands += "L ${x.testCoordinate()} ${y.testCoordinate()}"
        }

        override fun quadTo(
            controlX: Float,
            controlY: Float,
            x: Float,
            y: Float,
        ) {
            commands +=
                "Q ${controlX.testCoordinate()} ${controlY.testCoordinate()} " +
                "${x.testCoordinate()} ${y.testCoordinate()}"
        }

        override fun curveTo(
            control1X: Float,
            control1Y: Float,
            control2X: Float,
            control2Y: Float,
            x: Float,
            y: Float,
        ) {
            commands +=
                "C ${control1X.testCoordinate()} ${control1Y.testCoordinate()} " +
                "${control2X.testCoordinate()} ${control2Y.testCoordinate()} " +
                "${x.testCoordinate()} ${y.testCoordinate()}"
        }

        override fun close() {
            commands += "Z"
        }
    }

    private class ExpectedSinkFailure : RuntimeException()
}
