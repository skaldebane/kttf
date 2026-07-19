package dev.skaldebane.kttf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdditionalTablesPublicTest {
    @Test
    fun exposesKernAndKerxStatePairsActionsAnchorsAndTupleCounts() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 3,
                    additionalTables =
                        mapOf(
                            Tag.parse("kern") to publicKern(),
                            Tag.parse("kerx") to publicKerx(),
                        ),
                ),
            )

        val kern = face.kerningSubtables[0]!!
        val kernState = kern.stateTable!!
        assertEquals(4, kernState.classCount)
        assertEquals(3, kernState.glyphClass(GlyphId(0)))
        assertEquals(2, kernState.entryCount)
        assertTrue(kernState.entry(0, 3)!!.hasPush)
        assertEquals(-30, kernState.action(24))
        assertNull(kernState.entry(-1, 0))

        val pair = face.kerningSubtables[1]!!
        assertEquals(2, pair.tupleCount)
        assertEquals(KerningPair(GlyphId(0), GlyphId(1), -20), pair.pair(0))
        assertNull(pair.pair(1))

        val state = face.kerningSubtables[2]!!
        assertEquals(1, state.tupleCount)
        assertEquals(3, state.extendedStateTable!!.glyphClass(GlyphId(0)))
        assertEquals(1, state.extendedStateTable!!.entry(0, 3)!!.actionIndex)
        assertEquals(-9, state.action(1))
        assertNull(state.action(-1))

        val anchors = face.kerningSubtables[3]!!
        assertEquals(1, anchors.anchorCount)
        assertEquals(KerningAnchor(2, 5), anchors.anchor(0))
        assertNull(anchors.anchor(1))
    }

    @Test
    fun exposesMorxChainsFeaturesAndEverySubtablePayload() {
        val face =
            FontFace.parse(
                minimalSfnt(
                    glyphCount = 3,
                    additionalTables = mapOf(Tag.parse("morx") to publicMorx()),
                ),
            )
        val chains = face.appleLayout.morxChains
        val chain = chains[0]!!

        assertEquals(1, chains.size)
        assertNull(chains[-1])
        assertEquals(3, chain.defaultFlags)
        assertEquals(AatMorxFeature(6, 1, 0x10, 0xFFFF_FFEF), chain.features[0])
        assertEquals(5, chain.subtables.size)

        val rearrangement = assertIs<AatMorxRearrangement>(chain.subtables[0]!!.data)
        assertEquals(3, rearrangement.stateTable.glyphClass(GlyphId(0)))
        assertTrue(rearrangement.stateTable.entry(0, 3)!!.hasMark)

        val contextual = assertIs<AatMorxContextual>(chain.subtables[1]!!.data)
        assertEquals(1, contextual.stateTable.entry(0, 3)!!.markIndex)
        assertEquals(GlyphId(2), contextual.substitution(0, GlyphId(1)))
        assertNull(contextual.substitution(-1, GlyphId(1)))

        val ligature = assertIs<AatMorxLigature>(chain.subtables[2]!!.data)
        assertEquals(1, ligature.stateTable.entry(0, 3)!!.actionIndex)
        assertEquals(0x8000_0001, ligature.action(0))
        assertEquals(4, ligature.component(0))
        assertEquals(GlyphId(2), ligature.ligature(0))

        val nonContextual = assertIs<AatMorxNonContextual>(chain.subtables[3]!!.data)
        assertEquals(GlyphId(2), nonContextual.substitution(GlyphId(1)))
        assertEquals(GlyphId(2), face.appleLayout.morxNonContextualSubstitution(GlyphId(1)))

        val insertion = assertIs<AatMorxInsertion>(chain.subtables[4]!!.data)
        val insertionEntry = insertion.stateTable.entry(0, 3)!!
        assertEquals(1, insertionEntry.currentInsertIndex)
        assertEquals(2, insertionEntry.markedInsertIndex)
        assertEquals(GlyphId(7), insertion.glyph(0))
        assertEquals(GlyphId(8), insertion.glyph(1))
        assertNull(insertion.glyph(-1))
    }
}

private fun publicKern(): ByteArray {
    val body =
        TestByteBuilder()
            .uint16(4)
            .uint16(8)
            .uint16(14)
            .uint16(18)
            .uint16(0)
            .uint16(1)
            .raw(byteArrayOf(3))
            .zeroes(5)
            .uint16(14)
            .uint16(0x8018)
            .zeroes(2)
            .int16(-30)
            .build()
    return TestByteBuilder()
        .uint32(0x0001_0000)
        .uint32(1)
        .uint32((body.size + 8).toLong())
        .int8(0)
        .int8(1)
        .uint16(0)
        .raw(body)
        .build()
}

private fun publicKerx(): ByteArray {
    val pair =
        TestByteBuilder()
            .uint32(1)
            .zeroes(12)
            .uint32(1)
            .int16(-20)
            .build()
    val state = extendedStateBody(TestByteBuilder().uint16(1).build(), TestByteBuilder().int16(-5).int16(-9).build())
    val anchor = extendedStateBody(TestByteBuilder().uint16(0).build(), TestByteBuilder().uint16(2).uint16(5).build())
    return TestByteBuilder()
        .uint16(2)
        .uint16(0)
        .uint32(3)
        .raw(kerxSubtable(0, 2, pair))
        .raw(kerxSubtable(1, 1, state))
        .raw(kerxSubtable(4, 0, anchor, 0x4000_002A))
        .build()
}

private fun kerxSubtable(
    format: Int,
    tupleCount: Int,
    stateBody: ByteArray,
    flags: Int? = null,
): ByteArray {
    val body = if (flags == null) stateBody else stateBody.copyOf().also { it.writeUInt32(16, flags.toLong()) }
    return TestByteBuilder()
        .uint32((body.size + 12).toLong())
        .zeroes(3)
        .int8(format)
        .uint32(tupleCount.toLong())
        .raw(body)
        .build()
}

private fun extendedStateBody(
    entryExtra: ByteArray,
    tail: ByteArray,
): ByteArray {
    val entryOffset = 36
    val tailOffset = entryOffset + 4 + entryExtra.size
    return TestByteBuilder()
        .uint32(4)
        .uint32(20)
        .uint32(28)
        .uint32(entryOffset.toLong())
        .uint32(tailOffset.toLong())
        .uint16(8)
        .uint16(0)
        .uint16(1)
        .uint16(3)
        .zeroes(8)
        .uint16(2)
        .uint16(0x8000)
        .raw(entryExtra)
        .raw(tail)
        .build()
}

private fun publicMorx(): ByteArray {
    val feature =
        TestByteBuilder()
            .uint16(6)
            .uint16(1)
            .uint32(0x10)
            .uint32(0xFFFF_FFEF)
            .build()
    val subtables =
        arrayOf(
            morxSubtable(0, morxState(ByteArray(0), ByteArray(0))),
            morxSubtable(1, morxState(TestByteBuilder().uint16(1).uint16(0).build(), contextualTail())),
            morxSubtable(2, morxLigatureBody()),
            morxSubtable(4, trimmedLookup(1, 2)),
            morxSubtable(5, morxState(TestByteBuilder().uint16(1).uint16(2).build(), glyphTail(7, 8))),
        )
    val chainLength = 16 + feature.size + subtables.sumOf(ByteArray::size)
    return TestByteBuilder()
        .uint16(2)
        .uint16(0)
        .uint32(1)
        .uint32(3)
        .uint32(chainLength.toLong())
        .uint32(1)
        .uint32(subtables.size.toLong())
        .raw(feature)
        .apply { subtables.forEach(::raw) }
        .build()
}

private fun morxState(
    entryExtra: ByteArray,
    tail: ByteArray,
): ByteArray {
    val tailOffset = 40 + entryExtra.size
    return TestByteBuilder()
        .uint32(4)
        .uint32(20)
        .uint32(28)
        .uint32(36)
        .uint32(tailOffset.toLong())
        .raw(trimmedLookup(0, 3))
        .zeroes(8)
        .uint16(2)
        .uint16(0x8000)
        .raw(entryExtra)
        .raw(tail)
        .build()
}

private fun morxLigatureBody(): ByteArray =
    TestByteBuilder()
        .uint32(4)
        .uint32(28)
        .uint32(36)
        .uint32(44)
        .uint32(50)
        .uint32(54)
        .uint32(56)
        .raw(trimmedLookup(0, 3))
        .zeroes(8)
        .uint16(2)
        .uint16(0x8000)
        .uint16(1)
        .uint32(0x8000_0001)
        .uint16(4)
        .uint16(2)
        .build()

private fun contextualTail(): ByteArray = TestByteBuilder().uint32(4).raw(trimmedLookup(1, 2)).build()

private fun glyphTail(
    first: Int,
    second: Int,
): ByteArray = TestByteBuilder().uint16(first).uint16(second).build()

private fun trimmedLookup(
    firstGlyph: Int,
    value: Int,
): ByteArray =
    TestByteBuilder()
        .uint16(8)
        .uint16(firstGlyph)
        .uint16(1)
        .uint16(value)
        .build()

private fun morxSubtable(
    kind: Int,
    body: ByteArray,
): ByteArray =
    TestByteBuilder()
        .uint32((12 + body.size).toLong())
        .zeroes(3)
        .int8(kind)
        .uint32(0x1234)
        .raw(body)
        .build()

private fun ByteArray.writeUInt32(
    offset: Int,
    value: Long,
) {
    this[offset] = (value ushr 24).toByte()
    this[offset + 1] = (value ushr 16).toByte()
    this[offset + 2] = (value ushr 8).toByte()
    this[offset + 3] = value.toByte()
}
