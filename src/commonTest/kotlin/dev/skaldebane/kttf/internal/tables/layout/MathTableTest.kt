package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MathTableTest {
    @Test
    fun exposesEveryConstantRecordAndStaysBinaryBacked() {
        val constants = constants()
        val tableBytes = join(u16(1, 0, 10, 0, 0), constants)
        val parsed = MathTable.parse(slice(tableBytes))!!.constants!!

        assertEquals(80, parsed.scriptPercentScaleDown)
        assertEquals(60, parsed.scriptScriptPercentScaleDown)
        assertEquals(1000, parsed.delimitedSubFormulaMinHeight)
        assertEquals(1200, parsed.displayOperatorMinHeight)
        for (index in 0..50) assertEquals(index + 1, parsed.value(index).value)
        assertEquals(51, parsed.radicalKernAfterDegree.value)
        assertEquals(65, parsed.radicalDegreeBottomRaisePercent)
        assertNull(parsed.axisHeight.device)

        put16(tableBytes, 10 + 12, 333)
        assertEquals(333, parsed.axisHeight.value)
    }

    @Test
    fun glyphInfoMapsValuesExtendedShapesAndFourCornerKerns() {
        val glyphInfo = glyphInfo()
        val parsed = MathGlyphInfo.parse(slice(glyphInfo))!!
        assertEquals(22, parsed.italicCorrections!!.get(GlyphId(10))!!.value)
        assertTrue(parsed.extendedShapes!!.contains(GlyphId(11)))
        assertNull(parsed.italicCorrections.get(GlyphId(11)))

        val kernInfo = parsed.kernInfos!!.get(GlyphId(10))!!
        assertEquals(1, kernInfo.topRight!!.count)
        assertEquals(30, kernInfo.topRight.height(0)!!.value)
        assertEquals(40, kernInfo.topRight.kern(0)!!.value)
        assertEquals(50, kernInfo.topRight.kern(1)!!.value)
        assertNull(kernInfo.topLeft)
    }

    @Test
    fun variantsExposeVerticalAndHorizontalConstructionsAssembliesAndParts() {
        val parsed = MathVariants.parse(slice(variants()))!!
        assertEquals(12, parsed.minConnectorOverlap)

        val vertical = parsed.verticalConstructions.get(GlyphId(10))!!
        assertEquals(GlyphId(30), vertical.variants[0]!!.glyph)
        assertEquals(500, vertical.variants[0]!!.advanceMeasurement)
        assertEquals(7, vertical.assembly!!.italicsCorrection.value)
        val part = vertical.assembly.parts[0]!!
        assertEquals(GlyphId(40), part.glyph)
        assertEquals(100, part.startConnectorLength)
        assertEquals(400, part.fullAdvance)
        assertTrue(part.isExtender)

        val horizontal = parsed.horizontalConstructions.get(GlyphId(11))!!
        assertEquals(GlyphId(31), horizontal.variants[0]!!.glyph)
        assertFalse(horizontal.assembly!!.parts[0]!!.isExtender)
        assertNull(parsed.verticalConstructions.get(GlyphId(11)))
    }

    @Test
    fun topLevelTableKeepsOptionalSectionsIndependentAndRejectsWrongMajorVersion() {
        val constants = constants()
        val glyphInfo = glyphInfo()
        val variants = variants()
        val glyphOffset = 10 + constants.size
        val variantsOffset = glyphOffset + glyphInfo.size
        val table =
            MathTable.parse(
                slice(
                    join(
                        u16(1, 7, 10, glyphOffset, variantsOffset),
                        constants,
                        glyphInfo,
                        variants,
                    ),
                ),
            )!!
        assertEquals(80, table.constants!!.scriptPercentScaleDown)
        assertEquals(
            22,
            table.glyphInfo!!
                .italicCorrections!!
                .get(GlyphId(10))!!
                .value,
        )
        assertEquals(12, table.variants!!.minConnectorOverlap)

        val empty = MathTable.parse(slice(u16(1, 0, 0, 0, 0)))!!
        assertNull(empty.constants)
        assertNull(empty.glyphInfo)
        assertNull(empty.variants)
        assertNull(MathTable.parse(slice(u16(2, 0, 0, 0, 0))))
        assertNull(MathTable.parse(slice(ByteArray(9))))
    }

    private fun constants(): ByteArray {
        val data = ByteArray(216)
        put16(data, 0, 80)
        put16(data, 2, 60)
        put16(data, 4, 1000)
        put16(data, 6, 1200)
        for (index in 0..50) put16(data, 8 + index * 4, index + 1)
        put16(data, 212, 65)
        return data
    }

    private fun glyphInfo(): ByteArray {
        val italics = join(u16(8, 1, 22, 0), coverage1(10))
        val extended = coverage1(11)
        val kern =
            join(
                u16(12, 1, 18, 0, 0, 0),
                coverage1(10),
                u16(1, 30, 0, 40, 0, 50, 0),
            )
        return join(u16(8, 0, 8 + italics.size, 8 + italics.size + extended.size), italics, extended, kern)
    }

    private fun variants(): ByteArray {
        val verticalCoverage = coverage1(10)
        val horizontalCoverage = coverage1(11)
        val vertical = construction(30, 40, 1)
        val horizontal = construction(31, 41, 0)
        val verticalOffset = 14 + verticalCoverage.size + horizontalCoverage.size
        val horizontalOffset = verticalOffset + vertical.size
        return join(
            u16(12, 14, 14 + verticalCoverage.size, 1, 1, verticalOffset, horizontalOffset),
            verticalCoverage,
            horizontalCoverage,
            vertical,
            horizontal,
        )
    }

    private fun construction(
        variantGlyph: Int,
        partGlyph: Int,
        flags: Int,
    ): ByteArray =
        join(
            u16(8, 1, variantGlyph, 500),
            u16(7, 0, 1, partGlyph, 100, 200, 400, flags),
        )

    private fun put16(
        data: ByteArray,
        offset: Int,
        value: Int,
    ) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }
}
