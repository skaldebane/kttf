package dev.skaldebane.kttf.internal.tables.variations

import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ItemVariationStoreTest {
    @Test
    fun evaluatesRegionsAndShortDeltaRowsLikePinnedUpstream() {
        val store = assertNotNull(ItemVariationStore.parse(ByteSlice.of(variationStore())))

        assertEquals(intArrayOf(0, 1).toList(), store.regionIndices(0)?.toList())
        assertNull(store.regionIndices(1))
        assertEquals(10.0f, store.delta(0, 0, coordinates(8192)))
        assertEquals(20.0f, store.delta(0, 1, coordinates(8192)))
        assertEquals(5.0f, store.delta(0, 0, coordinates(-4096)))
        assertEquals(0.0f, store.delta(0, 0, coordinates(0)))
        assertNull(store.delta(0, 2, coordinates(8192)))
    }

    @Test
    fun supportsLongWordsAndPinnedRegionEdgeRules() {
        val store = ItemVariationStore.parse(ByteSlice.of(variationStore(listOf(100_000 to 300), longWords = true)))!!

        assertEquals(150.0f, store.delta(0, 0, coordinates(8192)))
        assertEquals(1.0f, store.regions.evaluate(0, VariationCoordinates.zeros(0)!!))
        assertEquals(0.0f, store.regions.evaluate(0, coordinates(0, 0)))

        val malformedRegion = variationStore().also { putUInt16(it, 12 + 4, 1000) }
        val malformedStore = ItemVariationStore.parse(ByteSlice.of(malformedRegion))!!
        assertEquals(1.0f, malformedStore.regions.evaluate(0, coordinates(0)))
    }

    @Test
    fun mapsBothDeltaSetIndexFormatsAndClampsToLastEntry() {
        val format0 = DeltaSetIndexMap.parse(ByteSlice.of(deltaSetMap(0x0025, 0x0037)))!!
        assertEquals(DeltaSetIndex(2, 5), format0.map(0))
        assertEquals(DeltaSetIndex(3, 7), format0.map(1))
        assertEquals(DeltaSetIndex(3, 7), format0.map(100))
        assertNull(format0.map(-1))

        val format1 =
            VariationBytes()
                .u8(1)
                .u8(0)
                .u32(1)
                .u8(0x21)
                .build()
        assertEquals(DeltaSetIndex(16, 1), DeltaSetIndexMap.parse(ByteSlice.of(format1))?.map(0))
        assertNull(DeltaSetIndexMap.parse(ByteSlice.of(byteArrayOf(0, 0, 0, 0)))?.map(0))
    }

    @Test
    fun rejectsTruncatedStoresMapsAndRowsWithoutEscapingBounds() {
        val valid = variationStore()
        assertNull(ItemVariationStore.parse(ByteSlice.of(valid.copyOf(7))))
        assertNull(ItemVariationStore.parse(ByteSlice.of(valid.copyOf(valid.size - 1)))?.delta(0, 1, coordinates(8192)))

        val badRegionOffset = valid.copyOf().also { putUInt32(it, 2, 0xFFFF_FFFFL) }
        val badDataOffset = valid.copyOf().also { putUInt32(it, 8, 0xFFFF_FFFFL) }
        assertNull(ItemVariationStore.parse(ByteSlice.of(badRegionOffset)))
        val storeWithBadDataOffset = assertNotNull(ItemVariationStore.parse(ByteSlice.of(badDataOffset)))
        assertNull(storeWithBadDataOffset.regionIndices(0))
        assertNull(storeWithBadDataOffset.delta(0, 0, coordinates(8192)))

        val partialMap = assertNotNull(DeltaSetIndexMap.parse(ByteSlice.of(deltaSetMap(1, 2).copyOf(6))))
        assertEquals(DeltaSetIndex(0, 1), partialMap.map(0))
        assertNull(partialMap.map(1))
        assertNull(partialMap.map(100))
    }

    @Test
    fun decodesFourByteEntriesAndRejectsIndicesThatDoNotFitUpstreamU16Results() {
        val valid =
            VariationBytes()
                .u8(0)
                .u8(0x3F)
                .u16(1)
                .u32(0x0002_FFFFL)
                .build()
        assertEquals(DeltaSetIndex(2, 0xFFFF), DeltaSetIndexMap.parse(ByteSlice.of(valid))?.map(0))

        val outerOverflow =
            VariationBytes()
                .u8(0)
                .u8(0x30)
                .u16(1)
                .u32(0x0002_0000L)
                .build()
        assertNull(DeltaSetIndexMap.parse(ByteSlice.of(outerOverflow))?.map(0))
    }

    @Test
    fun rejectsWordCountsBeyondTheRegionIndexArray() {
        val bytes = variationStore().also { putUInt16(it, 30, 3) }
        val store = assertNotNull(ItemVariationStore.parse(ByteSlice.of(bytes)))

        assertNull(store.delta(0, 0, coordinates(8192)))
    }
}
