package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostVerticalIntegrationTest {
    @Test
    fun exposesPostMetadataAndGlyphNames() {
        val face = FontFace.parse(minimalSfnt(postData = testPostTable()))

        assertEquals(1.5f, face.italicAngle)
        assertTrue(face.isItalic)
        assertTrue(face.isMonospaced)
        assertEquals(FontLineMetrics(-100, 20), face.underlineMetrics)
        assertEquals(".notdef", face.glyphName(GlyphId(0)))
        assertEquals("custom", face.glyphName(GlyphId(1)))
        assertEquals(".null", face.glyphName(GlyphId(2)))
        assertEquals(GlyphId(1), face.glyphIdByName("custom"))
        assertNull(face.glyphIdByName("missing"))
    }

    @Test
    fun exposesVerticalHeaderAndMetrics() {
        val face = FontFace.parse(minimalSfnt(vheaData = testVheaTable(), vmtxData = testVmtxTable()))

        assertEquals(900, face.vheaAscender)
        assertEquals(-300, face.vheaDescender)
        assertEquals(50, face.vheaLineGap)
        assertEquals(1000, face.glyphVerticalAdvance(GlyphId(0)))
        assertEquals(1100, face.glyphVerticalAdvance(GlyphId(1)))
        assertEquals(1100, face.glyphVerticalAdvance(GlyphId(2)))
        assertEquals(30, face.glyphVerticalSideBearing(GlyphId(2)))
    }

    @Test
    fun absentPostAndVerticalTablesUseDefaults() {
        val face = FontFace.parse(minimalSfnt())

        assertEquals(0f, face.italicAngle)
        assertFalse(face.isItalic)
        assertFalse(face.isMonospaced)
        assertNull(face.underlineMetrics)
        assertNull(face.vheaAscender)
        assertNull(face.glyphVerticalAdvance(GlyphId(0)))
    }
}
