package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MorxTableTest {
    @Test
    fun exposesChainsFeaturesCoverageAndNonContextualLookup() {
        val lookup =
            TestByteBuilder()
                .uint16(8)
                .uint16(2)
                .uint16(1)
                .uint16(9)
                .build()
        val subtable = morxSubtable(kind = 4, coverage = 0xF0, featureFlags = 0x1234, body = lookup)
        val feature =
            TestByteBuilder()
                .uint16(6)
                .uint16(1)
                .uint32(0x10)
                .uint32(0xFFFF_FFEF)
                .build()
        val chain = morxChain(defaultFlags = 3, features = arrayOf(feature), subtables = arrayOf(subtable))
        val parsed = parse(morx(chain)).chains.single()

        assertEquals(3, parsed.defaultFlags)
        assertEquals(MorxFeature(6, 1, 0x10, 0xFFFF_FFEF), parsed.features[0])
        val parsedSubtable = parsed.subtables.single()
        assertEquals(0x1234, parsedSubtable.featureFlags)
        assertTrue(parsedSubtable.coverage.isLogical)
        assertTrue(parsedSubtable.coverage.isAllDirections)
        assertTrue(parsedSubtable.coverage.isBackwards)
        assertTrue(parsedSubtable.coverage.isVertical)
        val kind = assertIs<MorxSubtableKind.NonContextual>(parsedSubtable.kind)
        assertEquals(9, kind.lookup.value(2))
        assertNull(kind.lookup.value(1))
    }

    @Test
    fun insertionSubtableProvidesStateEntryAndZeroCopyGlyphArray() {
        val body =
            TestByteBuilder()
                .uint32(4)
                .uint32(20)
                .uint32(28)
                .uint32(36)
                .uint32(44)
                .uint16(8)
                .uint16(0)
                .uint16(1)
                .uint16(3)
                .zeroes(8)
                .uint16(2)
                .uint16(0x8000)
                .uint16(1)
                .uint16(2)
                .uint16(7)
                .uint16(8)
                .build()
        val bytes = morx(morxChain(subtables = arrayOf(morxSubtable(5, body = body))))
        val source = ByteArray(bytes.size + 2)
        bytes.copyInto(source, 1)
        val table = MorxTable.parse(1, ByteSlice.of(source).slice(1, bytes.size)!!)!!
        val kind =
            assertIs<MorxSubtableKind.Insertion>(
                table.chains
                    .single()
                    .subtables
                    .single()
                    .kind,
            )

        assertEquals(3, kind.table.state.glyphClass(0))
        val entry = kind.table.state.entry(0, 3)!!
        assertEquals(2, entry.newState)
        assertEquals(MorxInsertionEntryData(1, 2), entry.extra)
        assertTrue(entry.hasMark)
        assertEquals(7, kind.table.glyphs[0])
        source[1 + bytes.size - 1] = 9
        assertEquals(9, kind.table.glyphs[1])
    }

    @Test
    fun malformedChainAndSubtableLengthsStopIterationWithinBounds() {
        val valid = morx(morxChain(subtables = emptyArray()))
        assertEquals(1, parse(valid).chains.count())

        val truncated = valid.copyOf(valid.size - 1)
        assertEquals(0, parse(truncated).chains.count())
        val noChains = TestByteBuilder().zeroes(4).uint32(0).build()
        assertFalse(parse(noChains).chains.iterator().hasNext())
        assertNull(MorxTable.parse(0, ByteSlice.of(valid)))
    }

    private fun parse(data: ByteArray): MorxTable = requireNotNull(MorxTable.parse(3, ByteSlice.of(data)))
}

private fun morx(vararg chains: ByteArray): ByteArray =
    TestByteBuilder()
        .uint16(2)
        .uint16(0)
        .uint32(chains.size.toLong())
        .apply { chains.forEach(::raw) }
        .build()

private fun morxChain(
    defaultFlags: Int = 0,
    features: Array<ByteArray> = emptyArray(),
    subtables: Array<ByteArray>,
): ByteArray {
    val length = 16 + features.sumOf { it.size } + subtables.sumOf { it.size }
    return TestByteBuilder()
        .uint32(defaultFlags.toLong())
        .uint32(length.toLong())
        .uint32(features.size.toLong())
        .uint32(subtables.size.toLong())
        .apply { features.forEach(::raw) }
        .apply { subtables.forEach(::raw) }
        .build()
}

private fun morxSubtable(
    kind: Int,
    coverage: Int = 0,
    featureFlags: Int = 0,
    body: ByteArray,
): ByteArray =
    TestByteBuilder()
        .uint32((12 + body.size).toLong())
        .int8(coverage)
        .uint16(0)
        .int8(kind)
        .uint32(featureFlags.toLong())
        .raw(body)
        .build()
