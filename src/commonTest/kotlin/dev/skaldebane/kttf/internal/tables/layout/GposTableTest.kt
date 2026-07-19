package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GposTableTest {
    @Test
    fun parsesSingleAdjustmentFormatsAndValueDevices() {
        val format1Bytes = join(u16(1, 14, 0x000F), i16(1, 2, 3, 4), coverage1(10))
        val format1 = assertIs<SingleAdjustment.Format1>(single(format1Bytes))
        assertEquals(ValueRecord(1, 2, 3, 4), format1.value)

        val format2Bytes = join(u16(2, 12, 0x0001, 2), i16(7, 8), coverage1(10, 11))
        val format2 = assertIs<SingleAdjustment.Format2>(single(format2Bytes))
        assertEquals(8, format2.values[1]!!.xPlacement)

        val withDevice = join(u16(1, 10, 0x0011), i16(5), u16(16), coverage1(10), u16(2, 3, 0x8000))
        val value = assertIs<SingleAdjustment.Format1>(single(withDevice)).value
        val variation = assertIs<Device.Variation>(value.xPlacementDevice)
        assertEquals(2, variation.outerIndex)
        assertEquals(3, variation.innerIndex)
    }

    @Test
    fun parsesPairSetAndClassMatrixFormats() {
        val set = join(u16(1, 20), i16(3, 4))
        val format1Bytes = join(u16(1, 20, 0x0001, 0x0002, 1, 12), set, coverage1(10))
        val format1 = assertIs<PairAdjustment.Format1>(pair(format1Bytes))
        val values = format1.sets[0]!!.get(GlyphId(20))!!
        assertEquals(3, values.first.xPlacement)
        assertEquals(4, values.second.yPlacement)

        val classes1 = class1(10, 0)
        val classes2 = class1(20, 0)
        val format2Bytes =
            join(
                u16(2, 20, 0x0001, 0x0001, 26, 34, 1, 1),
                i16(5, 6),
                coverage1(10),
                classes1,
                classes2,
            )
        val format2 = assertIs<PairAdjustment.Format2>(pair(format2Bytes))
        assertEquals(0, format2.firstClasses.classOf(GlyphId(10)))
        assertEquals(
            6,
            format2.matrix
                .get(0, 0)!!
                .second.xPlacement,
        )
        assertNull(format2.matrix.get(1, 0))
    }

    @Test
    fun parsesCursiveAnchorsIncludingContourAndDeviceFormats() {
        val entry = u16(1, 100, 200)
        val exit = u16(2, 300, 400, 9)
        val cursiveBytes = join(u16(1, 24, 1, 10, 16), entry, exit, coverage1(10))
        val cursive = assertIs<PositioningSubtable.Cursive>(parse(cursiveBytes, 3)).value
        assertEquals(100, cursive.anchors.entry(0)!!.x)
        assertEquals(9, cursive.anchors.exit(0)!!.contourPoint)

        val anchor3 = join(u16(3, 1, 2, 10, 16), u16(4, 5, 0x8000), u16(6, 7, 0x8000))
        val parsed = Anchor.parse(slice(anchor3))!!
        assertEquals(4, assertIs<Device.Variation>(parsed.xDevice).outerIndex)
        assertEquals(7, assertIs<Device.Variation>(parsed.yDevice).innerIndex)
    }

    @Test
    fun parsesEveryMarkAttachmentLookup() {
        val markToBase = assertIs<PositioningSubtable.MarkToBase>(parse(markToBase(), 4)).value
        assertEquals(2, markToBase.marks[0]!!.first)
        assertEquals(70, markToBase.marks[0]!!.second.x)
        assertEquals(80, markToBase.anchors[0, 0]!!.x)

        val markToLigature =
            assertIs<PositioningSubtable.MarkToLigature>(parse(markToLigature(), 5)).value
        assertEquals(90, markToLigature.ligatures[0]!![0, 0]!!.x)

        val markToMark = assertIs<PositioningSubtable.MarkToMark>(parse(markToMark(), 6)).value
        assertEquals(100, markToMark.mark2Anchors[0, 0]!!.x)
    }

    @Test
    fun dispatchesContextChainAndBoundedExtensions() {
        val context = join(u16(3, 1, 1, 12, 0, 2), coverage1(10))
        assertIs<PositioningSubtable.Context>(parse(context, 7))
        val chained = join(u16(3, 0, 1, 16, 0, 1, 0, 2), coverage1(10))
        assertIs<PositioningSubtable.ChainContext>(parse(chained, 8))

        val single = join(u16(1, 8, 0x0001), i16(5), coverage1(10))
        val extension = join(u16(1, 1), u32(8), single)
        assertIs<PositioningSubtable.Single>(parse(extension, 9))
        assertNull(parse(join(u16(1, 9), u32(0)), 9))

        val table = parseGpos(slice(layoutTable(1, single)))!!
        assertTrue(table.lookups[0]!![0]!!.coverage.contains(GlyphId(10)))
        assertNull(parse(u16(7), 1))
        assertNull(parse(u16(1), 42))
    }

    private fun markToBase(): ByteArray {
        val markCoverage = coverage1(10)
        val baseCoverage = coverage1(20)
        val markArray = join(u16(1, 2, 6), u16(1, 70, 71))
        val baseArray = join(u16(1, 4), u16(1, 80, 81))
        return join(
            u16(1, 12, 18, 1, 24, 36),
            markCoverage,
            baseCoverage,
            markArray,
            baseArray,
        )
    }

    private fun markToLigature(): ByteArray {
        val markCoverage = coverage1(10)
        val ligatureCoverage = coverage1(20)
        val markArray = join(u16(1, 0, 6), u16(1, 70, 71))
        val attachment = join(u16(1, 4), u16(1, 90, 91))
        val ligatureArray = join(u16(1, 4), attachment)
        return join(
            u16(1, 12, 18, 1, 24, 36),
            markCoverage,
            ligatureCoverage,
            markArray,
            ligatureArray,
        )
    }

    private fun markToMark(): ByteArray {
        val mark1Coverage = coverage1(10)
        val mark2Coverage = coverage1(20)
        val markArray = join(u16(1, 0, 6), u16(1, 70, 71))
        val mark2Array = join(u16(1, 4), u16(1, 100, 101))
        return join(
            u16(1, 12, 18, 1, 24, 36),
            mark1Coverage,
            mark2Coverage,
            markArray,
            mark2Array,
        )
    }

    private fun single(data: ByteArray): SingleAdjustment = assertIs<PositioningSubtable.Single>(parse(data, 1)).value

    private fun pair(data: ByteArray): PairAdjustment =
        assertIs<PositioningSubtable.PairAdjustmentSubtable>(parse(data, 2)).value

    private fun parse(
        data: ByteArray,
        kind: Int,
    ): PositioningSubtable? = PositioningSubtable.parse(slice(data), kind, 8)
}
