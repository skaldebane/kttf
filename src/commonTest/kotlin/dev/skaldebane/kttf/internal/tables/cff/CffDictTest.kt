package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CffDictTest {
    @Test
    fun parsesUpstreamDictNumbers() {
        assertEquals(1000.0, decodeNumber(0xFA, 0x7C))
        assertEquals(-1000.0, decodeNumber(0xFE, 0x7C))
        assertEquals(10000.0, decodeNumber(0x1C, 0x27, 0x10))
        assertEquals(-10000.0, decodeNumber(0x1C, 0xD8, 0xF0))
        assertEquals(100000.0, decodeNumber(0x1D, 0x00, 0x01, 0x86, 0xA0))
        assertEquals(-100000.0, decodeNumber(0x1D, 0xFF, 0xFE, 0x79, 0x60))
    }

    @Test
    fun parsesCompactIntegerBoundaries() {
        assertEquals(-107.0, decodeNumber(32))
        assertEquals(0.0, decodeNumber(139))
        assertEquals(107.0, decodeNumber(246))
        assertEquals(108.0, decodeNumber(247, 0))
        assertEquals(1131.0, decodeNumber(250, 255))
        assertEquals(-108.0, decodeNumber(251, 0))
        assertEquals(-1131.0, decodeNumber(254, 255))
    }

    @Test
    fun parsesBcdReals() {
        assertEquals(123.45, decodeNumber(30, 0x12, 0x3A, 0x45, 0xFF))
        assertEquals(-0.001, decodeNumber(30, 0xE0, 0xA0, 0x01, 0xFF))
        assertEquals(0.0012, decodeNumber(30, 0x1A, 0x2C, 0x3F))
    }

    @Test
    fun boundsBcdRealTextAt64Characters() {
        val sixtyFourDigits = IntArray(32) { 0x11 } + 0xF0
        val sixtyFiveDigits = IntArray(32) { 0x11 } + intArrayOf(0x1F)

        assertTrue(decodeNumber(30, *sixtyFourDigits)!!.isFinite())
        assertNull(decodeNumber(30, *sixtyFiveDigits))

        val exponentMinusAtBoundary = IntArray(31) { 0x11 } + intArrayOf(0x1C, 0xFF)
        assertNull(decodeNumber(30, *exponentMinusAtBoundary))
    }

    @Test
    fun rejectsMalformedBcdReals() {
        assertNull(decodeNumber(30))
        assertNull(decodeNumber(30, 0xF0))
        assertNull(decodeNumber(30, 0xDF))
        assertNull(decodeNumber(30, 0xEF))
        assertNull(decodeNumber(30, 0x1A, 0x2A, 0x3F))
    }

    @Test
    fun fixed16Dot16RequiresExplicitOptIn() {
        val positive = stream(0x00, 0x01, 0x80, 0x00)
        val negative = stream(0xFF, 0xFE, 0x80, 0x00)

        assertNull(parseCffNumber(255, positive))
        assertEquals(1.5, parseCffNumber(255, stream(0x00, 0x01, 0x80, 0x00), includeFixed16Dot16 = true))
        assertEquals(-1.5, parseCffNumber(255, negative, includeFixed16Dot16 = true))
        assertNull(parseCffNumber(255, stream(0, 1, 2), includeFixed16Dot16 = true))
    }

    @Test
    fun scansOperatorsAndParsesOnlySelectedOperands() {
        val parser = parser(139, 140, 12, 3, 141, 17)

        assertEquals(1203, parser.parseNext()?.value)
        assertTrue(parser.parseOperands())
        assertEquals(2, parser.operandCount)
        assertEquals(0.0, parser.operand(0))
        assertEquals(1.0, parser.operand(1))
        assertNull(parser.operand(2))

        assertEquals(17, parser.parseNext()?.value)
        assertEquals(2.0, parser.parseNumber())
        assertNull(parser.parseNext())
    }

    @Test
    fun reportsOperandOverflowAndRetainsTheExactCount() {
        val parser = parser(139, 140, 141, 0, operandCapacity = 2)

        assertEquals(0, parser.parseNext()?.value)
        assertEquals(CffOperandParseResult.OVERFLOW, parser.parseOperandsResult())
        assertEquals(3, parser.operandCount)
        assertEquals(0.0, parser.operand(0))
        assertEquals(1.0, parser.operand(1))
        assertNull(parser.operand(2))

        val emptyParser = parser(139, 0, operandCapacity = 0)
        assertEquals(0, emptyParser.parseNext()?.value)
        assertEquals(CffOperandParseResult.OVERFLOW, emptyParser.parseOperandsResult())
        assertEquals(1, emptyParser.operandCount)
    }

    @Test
    fun supportsExactOperandArity() {
        val exact = parser(139, 140, 0)
        assertEquals(0, exact.parseNext()?.value)
        assertTrue(exact.parseOperands(2))

        val tooFew = parser(139, 0)
        assertEquals(0, tooFew.parseNext()?.value)
        assertEquals(CffOperandParseResult.ARITY_MISMATCH, tooFew.parseOperandsResult(2))
        assertEquals(1, tooFew.operandCount)

        val tooMany = parser(139, 140, 0)
        assertEquals(0, tooMany.parseNext()?.value)
        assertFalse(tooMany.parseOperands(1))
        assertEquals(2, tooMany.operandCount)
    }

    @Test
    fun parsesOffsetAndLengthStartRangeHelpers() {
        val offset = parser(29, 0, 0, 0, 42, 15)
        assertEquals(15, offset.parseNext()?.value)
        assertEquals(42, offset.parseOffset())

        val range = parser(144, 149, 18)
        assertEquals(18, range.parseNext()?.value)
        assertEquals(10 until 15, range.parseRange())

        val multiple = parser(139, 140, 0)
        assertEquals(0, multiple.parseNext()?.value)
        assertNull(multiple.parseOffset())

        val negative = parser(138, 0)
        assertEquals(0, negative.parseNext()?.value)
        assertNull(negative.parseOffset())
        assertNull(negative.parseRange())
    }

    @Test
    fun numericHelpersRejectFractionsNonFiniteValuesAndOverflow() {
        val wrongArityNumber = parser(139, 140, 20)
        assertEquals(20, wrongArityNumber.parseNext()?.value)
        assertNull(wrongArityNumber.parseNumber())

        val fractionalOffset = parser(30, 0x1A, 0x5F, 15)
        assertEquals(15, fractionalOffset.parseNext()?.value)
        assertNull(fractionalOffset.parseOffset())

        val overflowingRange = parser(29, 0x7F, 0xFF, 0xFF, 0xFF, 140, 18)
        assertEquals(18, overflowingRange.parseNext()?.value)
        assertNull(overflowingRange.parseRange())

        assertNull(decodeNumber(30, 0x1B, 0x99, 0x99, 0xF0))
        assertNull(Double.NaN.toIntExactOrNull())
        assertNull(Double.POSITIVE_INFINITY.toIntExactOrNull())
        assertNull(1.5.toIntExactOrNull())
        assertNull((Int.MAX_VALUE.toDouble() + 1.0).toIntExactOrNull())
        assertEquals(Int.MIN_VALUE, Int.MIN_VALUE.toDouble().toIntExactOrNull())
        assertNull(Int.MAX_VALUE.toFloat().toIntExactOrNull())
        assertEquals(Int.MIN_VALUE, Int.MIN_VALUE.toFloat().toIntExactOrNull())
        assertNull(1e100.toFiniteFloatOrNull())
        assertNull(Double.NEGATIVE_INFINITY.toFiniteFloatOrNull())
        assertEquals(1.5f, 1.5.toFiniteFloatOrNull())
    }

    @Test
    fun distinguishesCleanEndFromMalformedDictionaryData() {
        val parser = parser(139, 0)
        val operatorResult = parser.parseNextResult()
        assertEquals(CffDictionaryParseStatus.OPERATOR, operatorResult.status)
        assertEquals(0, operatorResult.operator?.value)
        assertEquals(CffDictionaryParseResult.End, parser.parseNextResult())

        assertEquals(CffDictionaryParseResult.End, parser().parseNextResult())
        val malformed = parser(139).parseNextResult()
        assertEquals(CffDictionaryParseStatus.MALFORMED, malformed.status)
        assertEquals(null, malformed.operator)
        assertEquals(CffDictionaryParseResult.Malformed, parser(28, 1).parseNextResult())
        assertEquals(CffDictionaryParseResult.Malformed, parser(12).parseNextResult())
    }

    @Test
    fun recognizesReservedAndEscapedOperators() {
        assertTrue(isCffDictOneByteOperator(0))
        assertTrue(isCffDictOneByteOperator(27))
        assertFalse(isCffDictOneByteOperator(28))
        assertFalse(isCffDictOneByteOperator(30))
        assertTrue(isCffDictOneByteOperator(31))
        assertFalse(isCffDictOneByteOperator(32))
        assertFalse(isCffDictOneByteOperator(254))
        assertTrue(isCffDictOneByteOperator(255))

        assertEquals(31, parser(31).parseNext()?.value)
        assertEquals(255, parser(255).parseNext()?.value)
    }

    @Test
    fun rejectsTruncatedNumbersAndEscapedOperators() {
        assertNull(parser(28, 1).parseNext())
        assertNull(parser(29, 0, 0, 0).parseNext())
        assertNull(parser(247).parseNext())
        assertNull(parser(254).parseNext())
        assertNull(parser(12).parseNext())

        assertFalse(parser(28, 1).parseOperands())
        assertFalse(parser(29, 0, 0, 0).parseOperands())
        assertFalse(parser(247).parseOperands())
    }

    @Test
    fun unterminatedRealCannotBeSkipped() {
        assertFalse(skipCffNumber(30, stream(0x12, 0x34)))
        assertTrue(skipCffNumber(30, stream(0x12, 0x3F)))
    }

    @Test
    fun scannerCanSkipMalformedOrOversizedRealButOperandDecodeRejectsIt() {
        val reservedNibble = parser(30, 0xDF, 0)
        assertEquals(0, reservedNibble.parseNext()?.value)
        assertFalse(reservedNibble.parseOperands())

        val oversizedReal = IntArray(33) { 0x11 } + intArrayOf(0xFF, 0)
        val oversized = parser(30, *oversizedReal)
        assertEquals(0, oversized.parseNext()?.value)
        assertFalse(oversized.parseOperands())
    }

    private fun decodeNumber(
        firstByte: Int,
        vararg remaining: Int,
    ): Double? = parseCffNumber(firstByte, stream(*remaining))

    private fun parser(
        vararg data: Int,
        operandCapacity: Int = 48,
    ): CffDictionaryParser = CffDictionaryParser(slice(*data), DoubleArray(operandCapacity))

    private fun stream(vararg data: Int): Stream = Stream.atStart(slice(*data))

    private fun slice(vararg data: Int): ByteSlice = ByteSlice.of(ByteArray(data.size) { data[it].toByte() })
}
