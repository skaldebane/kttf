package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataIntegrationTest {
    @Test
    fun exposesBinaryBackedIterableNames() {
        val source = minimalSfnt(nameData = testNameTable())
        val face = FontFace.parse(source)
        val name = face.names[0]!!

        assertEquals(1, face.names.size)
        assertFalse(face.names.isEmpty)
        assertEquals(listOf(NameIds.FAMILY), face.names.map(FontName::nameId))
        assertEquals(NamePlatformId.Windows, name.platformId)
        assertEquals(0x0409, name.languageId)
        assertTrue(name.isUnicode)
        assertEquals("kttf Test", name.decodeToString())
        assertNull(name.decodeLanguageTag())
        val raw = ByteArray(name.byteLength)
        assertContentEquals(testNameTable().copyOfRange(18, testNameTable().size), name.copyBytesInto(raw))
    }

    @Test
    fun exposesOs2ClassificationAndTypographicMetrics() {
        val face = FontFace.parse(minimalSfnt(os2Data = testOs2Table()))

        assertEquals(FontWeight(700), face.weight)
        assertEquals(FontWidth.Normal, face.width)
        assertEquals(FontStyle.Normal, face.style)
        assertTrue(face.isRegular)
        assertFalse(face.isItalic)
        assertTrue(face.isBold)
        assertFalse(face.isOblique)
        assertEquals(900, face.ascender)
        assertEquals(-250, face.descender)
        assertEquals(100, face.lineGap)
        assertEquals(1150, face.height)
        assertEquals(900, face.typographicAscender)
        assertEquals(-250, face.typographicDescender)
        assertEquals(1000, face.windowsAscender)
        assertEquals(-300, face.windowsDescender)
        assertEquals(450, face.xHeight)
        assertEquals(700, face.capitalHeight)
        assertEquals(EmbeddingPermissions.Installable, face.permissions)
        assertTrue(face.isSubsettingAllowed)
        assertTrue(face.isOutlineEmbeddingAllowed)
    }

    @Test
    fun fallsBackBetweenHheaAndOs2LikeUpstream() {
        val normal =
            FontFace.parse(
                minimalSfnt(ascender = 800, descender = -200, lineGap = 50, os2Data = testOs2Table(selectionFlags = 0)),
            )
        val hheaMissing =
            FontFace.parse(
                minimalSfnt(ascender = 0, descender = 0, lineGap = 50, os2Data = testOs2Table(selectionFlags = 0)),
            )

        assertEquals(800, normal.ascender)
        assertEquals(-200, normal.descender)
        assertEquals(50, normal.lineGap)
        assertEquals(900, hheaMissing.ascender)
        assertEquals(-250, hheaMissing.descender)
        assertEquals(100, hheaMissing.lineGap)
    }

    @Test
    fun absentOptionalMetadataUsesDocumentedDefaults() {
        val face = FontFace.parse(minimalSfnt())

        assertTrue(face.names.isEmpty)
        assertEquals(FontWeight.Normal, face.weight)
        assertEquals(FontWidth.Normal, face.width)
        assertEquals(FontStyle.Normal, face.style)
        assertTrue(face.isRegular)
        assertNull(face.permissions)
        assertFalse(face.unicodeRanges.contains('A'))
    }
}
