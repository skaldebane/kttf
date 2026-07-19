package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CffOperandStackTest {
    @Test
    fun exposesCffOperandLimits() {
        assertEquals(48, CffOperandStack.CFF1_CAPACITY)
        assertEquals(513, CffOperandStack.CFF2_CAPACITY)
    }

    @Test
    fun pushesIndexesAndPopsInStackOrder() {
        val stack = CffOperandStack(3)

        assertTrue(stack.isEmpty)
        assertTrue(stack.push(1.25f))
        assertTrue(stack.push(-2.5f))
        assertEquals(2, stack.size)
        assertEquals(1.25f, stack[0])
        assertEquals(-2.5f, stack[1])
        assertEquals(-2.5f, stack.pop())
        assertEquals(1.25f, stack.pop())
        assertNull(stack.pop())
        assertTrue(stack.isEmpty)
    }

    @Test
    fun overflowAndInvalidIndexesDoNotMutateTheStack() {
        val stack = CffOperandStack(2)

        assertTrue(stack.push(10.0f))
        assertTrue(stack.push(20.0f))
        assertFalse(stack.push(30.0f))
        assertEquals(2, stack.size)
        assertEquals(10.0f, stack[0])
        assertEquals(20.0f, stack[1])
        assertNull(stack[-1])
        assertNull(stack[2])
        assertNull(stack[Int.MAX_VALUE])
    }

    @Test
    fun clearMakesAllValuesInaccessibleAndStackReusable() {
        val stack = CffOperandStack(1)
        assertTrue(stack.push(4.0f))

        stack.clear()

        assertEquals(0, stack.size)
        assertTrue(stack.isEmpty)
        assertNull(stack[0])
        assertNull(stack.pop())
        assertTrue(stack.push(5.0f))
        assertEquals(5.0f, stack[0])
    }

    @Test
    fun decodesSingleByteType2Integers() {
        assertDecoded(-107.0f, 32)
        assertDecoded(-1.0f, 138)
        assertDecoded(0.0f, 139)
        assertDecoded(1.0f, 140)
        assertDecoded(107.0f, 246)
    }

    @Test
    fun decodesTwoByteType2IntegersAtTheirBoundaries() {
        assertDecoded(108.0f, 247, 0)
        assertDecoded(1131.0f, 250, 255)
        assertDecoded(-108.0f, 251, 0)
        assertDecoded(-1131.0f, 254, 255)
    }

    @Test
    fun decodesShortIntegersAndSignedFixedNumbers() {
        assertDecoded(32767.0f, 28, 0x7F, 0xFF)
        assertDecoded(-32768.0f, 28, 0x80, 0x00)
        assertDecoded(1.5f, 255, 0x00, 0x01, 0x80, 0x00)
        assertDecoded(-1.5f, 255, 0xFF, 0xFE, 0x80, 0x00)
    }

    @Test
    fun rejectsOperatorsAndTruncatedNumbersWithoutConsumingPayload() {
        assertRejected(0, 0xAA)
        assertRejected(27, 0xAA)
        assertRejected(29, 0xAA)
        assertRejected(31, 0xAA)
        assertRejected(256, 0xAA)
        assertTruncated(247)
        assertTruncated(28, 0x01)
        assertTruncated(255, 0x00, 0x01, 0x00)
    }

    private fun assertDecoded(
        expected: Float,
        firstByte: Int,
        vararg payload: Int,
    ) {
        val stream = streamOf(*payload)

        assertEquals(expected, decodeType2Number(firstByte, stream))
        assertEquals(payload.size, stream.offset)
    }

    private fun assertRejected(
        firstByte: Int,
        vararg payload: Int,
    ) {
        val stream = streamOf(*payload)

        assertNull(decodeType2Number(firstByte, stream))
        assertEquals(0, stream.offset)
    }

    private fun assertTruncated(
        firstByte: Int,
        vararg payload: Int,
    ) {
        val stream = streamOf(*payload)

        assertNull(decodeType2Number(firstByte, stream))
        assertEquals(0, stream.offset)
    }

    private fun streamOf(vararg values: Int): Stream =
        Stream.atStart(ByteSlice.of(ByteArray(values.size) { values[it].toByte() }))
}
