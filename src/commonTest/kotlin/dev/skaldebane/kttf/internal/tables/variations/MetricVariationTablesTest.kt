package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MetricVariationTablesTest {
    @Test
    fun hvarUsesImplicitAdvancesAndExplicitBearingMaps() {
        val store = variationStore()
        val map = deltaSetMap(0x0001, 0x0000)
        val storeOffset = 20
        val mapOffset = storeOffset + store.size
        val bytes =
            VariationBytes()
                .u32(0x0001_0000)
                .u32(storeOffset.toLong())
                .u32(0)
                .u32(mapOffset.toLong())
                .u32(mapOffset.toLong())
                .bytes(store)
                .bytes(map)
                .build()
        val table = assertNotNull(HvarTable.parse(ByteSlice.of(bytes)))

        assertEquals(10.0f, table.advanceWidthDelta(0, coordinates(8192)))
        assertEquals(20.0f, table.advanceWidthDelta(1, coordinates(8192)))
        assertEquals(20.0f, table.leftSideBearingDelta(0, coordinates(8192)))
        assertEquals(10.0f, table.rightSideBearingDelta(100, coordinates(8192)))
        assertNull(table.advanceWidthDelta(-1, coordinates(8192)))
    }

    @Test
    fun vvarExposesEveryVerticalMetricDelta() {
        val store = variationStore()
        val map = deltaSetMap(0x0001)
        val storeOffset = 24
        val mapOffset = storeOffset + store.size
        val bytes =
            VariationBytes()
                .u32(0x0001_0000)
                .u32(storeOffset.toLong())
                .u32(mapOffset.toLong())
                .u32(mapOffset.toLong())
                .u32(mapOffset.toLong())
                .u32(mapOffset.toLong())
                .bytes(store)
                .bytes(map)
                .build()
        val table = assertNotNull(VvarTable.parse(ByteSlice.of(bytes)))

        assertEquals(20.0f, table.advanceHeightDelta(3, coordinates(8192)))
        assertEquals(20.0f, table.topSideBearingDelta(3, coordinates(8192)))
        assertEquals(20.0f, table.bottomSideBearingDelta(3, coordinates(8192)))
        assertEquals(20.0f, table.verticalOriginDelta(3, coordinates(8192)))
    }

    @Test
    fun mvarFindsSortedMetricRecordsByTag() {
        val store = variationStore()
        val bytes =
            VariationBytes()
                .u32(0x0001_0000)
                .u16(0)
                .u16(8)
                .u16(2)
                .u16(28)
                .u32(Tag.parse("hasc").value.toLong())
                .u16(0)
                .u16(0)
                .u32(Tag.parse("hcla").value.toLong())
                .u16(0)
                .u16(1)
                .bytes(store)
                .build()
        val table = assertNotNull(MvarTable.parse(ByteSlice.of(bytes)))

        assertEquals(10.0f, table.metricDelta(Tag.parse("hasc"), coordinates(8192)))
        assertEquals(20.0f, table.metricDelta(Tag.parse("hcla"), coordinates(8192)))
        assertNull(table.metricDelta(Tag.parse("xhgt"), coordinates(8192)))
    }

    @Test
    fun mvarSearchesTagsInUnsignedOpenTypeOrder() {
        val store = variationStore()
        val bytes =
            VariationBytes()
                .u32(0x0001_0000)
                .u16(0)
                .u16(8)
                .u16(2)
                .u16(28)
                .u32(0x7FFF_FFFFL)
                .u16(0)
                .u16(0)
                .u32(0x8000_0000L)
                .u16(0)
                .u16(1)
                .bytes(store)
                .build()
        val table = assertNotNull(MvarTable.parse(ByteSlice.of(bytes)))

        assertEquals(10.0f, table.metricDelta(Tag.fromInt(0x7FFF_FFFF), coordinates(8192)))
        assertEquals(20.0f, table.metricDelta(Tag.fromInt(Int.MIN_VALUE), coordinates(8192)))
    }

    @Test
    fun metricTablesValidateHeadersOffsetsAndOptionalMappings() {
        val store = variationStore()
        val hvar =
            VariationBytes()
                .u32(0x0001_0000)
                .u32(20)
                .u32(0)
                .u32(0)
                .u32(0)
                .bytes(store)
                .build()
        assertNotNull(HvarTable.parse(ByteSlice.of(hvar)))
        assertNull(HvarTable.parse(ByteSlice.of(hvar.copyOf(19))))
        val hvarWithBadOptionalMap =
            assertNotNull(HvarTable.parse(ByteSlice.of(hvar.copyOf().also { putUInt32(it, 8, 0xFFFF_FFFFL) })))
        assertNull(hvarWithBadOptionalMap.advanceWidthDelta(0, coordinates(8192)))
        assertNull(hvarWithBadOptionalMap.leftSideBearingDelta(0, coordinates(8192)))

        val vvar =
            VariationBytes()
                .u32(0x0001_0000)
                .u32(24)
                .u32(0)
                .u32(0)
                .u32(0)
                .u32(0)
                .bytes(store)
                .build()
        assertNotNull(VvarTable.parse(ByteSlice.of(vvar)))
        assertNull(VvarTable.parse(ByteSlice.of(vvar.copyOf(23))))
        val vvarWithBadOptionalMap =
            assertNotNull(VvarTable.parse(ByteSlice.of(vvar.copyOf().also { putUInt32(it, 20, 0xFFFF_FFFFL) })))
        assertNull(vvarWithBadOptionalMap.verticalOriginDelta(0, coordinates(8192)))

        val mvarWithoutRecords =
            VariationBytes()
                .u32(0x0001_0000)
                .u16(0)
                .u16(8)
                .u16(0)
                .u16(12)
                .bytes(store)
                .build()
        assertNull(MvarTable.parse(ByteSlice.of(mvarWithoutRecords)))
        assertNull(MvarTable.parse(ByteSlice.of(mvarWithoutRecords.copyOf().also { putUInt16(it, 6, 7) })))
        assertNull(
            MvarTable.parse(
                ByteSlice.of(mvarWithoutRecords.copyOf(19).also { putUInt16(it, 8, 1) }),
            ),
        )
    }
}
