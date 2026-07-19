package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CffOutlineTest {
    @Test
    fun outlinesCffGlyphThroughFontFace() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 1,
                    numberOfMetrics = 1,
                    cffData = testCffTable(testCffTriangleCharString()),
                ),
            )
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
    fun exposesCffNamesWithPerQueryPostFallback() {
        val cff =
            testCffTable(
                byteArrayOf(14),
                byteArrayOf(14),
                charset = byteArrayOf(0, 0, 34),
            )
        val cffOnly = FontFace.parse(minimalSfnt(glyphCount = 2, numberOfMetrics = 1, cffData = cff))

        assertEquals(".notdef", cffOnly.glyphName(GlyphId(0)))
        assertEquals("A", cffOnly.glyphName(GlyphId(1)))
        assertEquals(GlyphId(1), cffOnly.glyphIdByName("A"))
        assertEquals(GlyphId(1), cffOnly.glyphIdFromCffCode(65))
        assertEquals(GlyphId(0), cffOnly.glyphIdFromCffCode(8))
        assertNull(cffOnly.glyphIdFromCffCode(-1))
        assertNull(cffOnly.glyphIdFromCffCode(256))

        val withPost =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 2,
                    numberOfMetrics = 1,
                    cffData = cff,
                    postData = testPostTable(),
                ),
            )
        assertEquals("custom", withPost.glyphName(GlyphId(1)))
        assertEquals(GlyphId(1), withPost.glyphIdByName("custom"))
        assertEquals(GlyphId(1), withPost.glyphIdByName("A"))
    }

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
}
