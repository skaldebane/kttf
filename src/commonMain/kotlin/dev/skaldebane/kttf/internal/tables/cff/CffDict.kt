package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream
import kotlin.jvm.JvmInline
import kotlin.math.pow

private const val TWO_BYTE_OPERATOR_MARK: Int = 12
private const val REAL_BUFFER_SIZE: Int = 64
private const val END_OF_REAL_FLAG: Int = 0xF
private const val FIXED_16_DOT_16_MARK: Int = 255

@JvmInline
internal value class CffOperator(
    internal val value: Int,
)

internal enum class CffDictionaryParseStatus {
    OPERATOR,
    END,
    MALFORMED,
}

@JvmInline
internal value class CffDictionaryParseResult private constructor(
    private val encodedValue: Int,
) {
    internal val status: CffDictionaryParseStatus
        get() =
            when (encodedValue) {
                END_VALUE -> CffDictionaryParseStatus.END
                MALFORMED_VALUE -> CffDictionaryParseStatus.MALFORMED
                else -> CffDictionaryParseStatus.OPERATOR
            }

    internal val operator: CffOperator?
        get() = if (encodedValue >= 0) CffOperator(encodedValue) else null

    internal companion object {
        internal val End: CffDictionaryParseResult = CffDictionaryParseResult(END_VALUE)
        internal val Malformed: CffDictionaryParseResult = CffDictionaryParseResult(MALFORMED_VALUE)

        internal fun operator(value: CffOperator): CffDictionaryParseResult = CffDictionaryParseResult(value.value)

        private const val END_VALUE: Int = -1
        private const val MALFORMED_VALUE: Int = -2
    }
}

internal enum class CffOperandParseResult {
    SUCCESS,
    MALFORMED,
    OVERFLOW,
    ARITY_MISMATCH,
}

internal class CffDictionaryParser(
    private val data: ByteSlice,
    private val operands: DoubleArray,
) {
    private var offset: Int = 0
    private var operandsOffset: Int = 0

    internal var operandCount: Int = 0
        private set

    internal fun parseNext(): CffOperator? = parseNextResult().operator

    internal fun parseNextResult(): CffDictionaryParseResult {
        val stream = Stream.at(data, offset) ?: return CffDictionaryParseResult.Malformed
        operandsOffset = offset
        if (stream.atEnd) return CffDictionaryParseResult.End

        while (!stream.atEnd) {
            val firstByte = stream.readUInt8() ?: return CffDictionaryParseResult.Malformed
            if (isCffDictOneByteOperator(firstByte)) {
                val operator =
                    if (firstByte == TWO_BYTE_OPERATOR_MARK) {
                        1200 + (stream.readUInt8() ?: return CffDictionaryParseResult.Malformed)
                    } else {
                        firstByte
                    }
                offset = stream.offset
                return CffDictionaryParseResult.operator(CffOperator(operator))
            }

            if (!skipCffNumber(firstByte, stream)) return CffDictionaryParseResult.Malformed
        }

        return CffDictionaryParseResult.Malformed
    }

    internal fun parseOperands(): Boolean = parseOperandsResult() == CffOperandParseResult.SUCCESS

    internal fun parseOperands(expectedCount: Int): Boolean =
        parseOperandsResult(expectedCount) == CffOperandParseResult.SUCCESS

    internal fun parseOperandsResult(): CffOperandParseResult = parseOperandsResultInternal(null)

    internal fun parseOperandsResult(expectedCount: Int): CffOperandParseResult {
        require(expectedCount >= 0) { "expectedCount must not be negative" }
        return parseOperandsResultInternal(expectedCount)
    }

    private fun parseOperandsResultInternal(expectedCount: Int?): CffOperandParseResult {
        val stream = Stream.at(data, operandsOffset) ?: return CffOperandParseResult.MALFORMED
        operandCount = 0
        var overflowed = false

        while (!stream.atEnd) {
            val firstByte = stream.readUInt8() ?: return CffOperandParseResult.MALFORMED
            if (isCffDictOneByteOperator(firstByte)) {
                return when {
                    overflowed -> CffOperandParseResult.OVERFLOW
                    expectedCount != null && operandCount != expectedCount -> CffOperandParseResult.ARITY_MISMATCH
                    else -> CffOperandParseResult.SUCCESS
                }
            }

            val operand = parseCffNumber(firstByte, stream) ?: return CffOperandParseResult.MALFORMED
            if (operandCount < operands.size) {
                operands[operandCount] = operand
            } else {
                overflowed = true
            }
            operandCount++
        }

        return CffOperandParseResult.MALFORMED
    }

    internal fun operand(index: Int): Double? =
        if (index in 0 until operandCount && index < operands.size) operands[index] else null

    internal fun parseNumber(): Double? {
        if (!parseOperands(1)) return null
        return operand(0)
    }

    internal fun parseOffset(): Int? {
        if (!parseOperands(1)) return null
        return operands[0].toNonNegativeIntExactOrNull()
    }

    internal fun parseRange(): IntRange? {
        if (!parseOperands(2)) return null
        val length = operands[0].toNonNegativeIntExactOrNull() ?: return null
        val start = operands[1].toNonNegativeIntExactOrNull() ?: return null
        if (length > Int.MAX_VALUE - start) return null
        return start until (start + length)
    }
}

// Adobe Technical Note #5176, Appendix H CFF DICT Encoding.
internal fun isCffDictOneByteOperator(byte: Int): Boolean =
    when (byte) {
        in 0..27 -> true
        in 28..30 -> false
        31 -> true
        in 32..254 -> false
        255 -> true
        else -> false
    }

// Adobe Technical Note #5177, Table 3 Operand Encoding. Fixed 16.16 is a
// Type 2 charstring operand and must not be enabled while scanning a DICT.
internal fun parseCffNumber(
    firstByte: Int,
    stream: Stream,
    includeFixed16Dot16: Boolean = false,
): Double? =
    when (firstByte) {
        28 -> {
            stream.readInt16()?.toDouble()
        }

        29 -> {
            stream.readInt32()?.toDouble()
        }

        30 -> {
            parseCffReal(stream)
        }

        in 32..246 -> {
            (firstByte - 139).toDouble()
        }

        in 247..250 -> {
            val secondByte = stream.readUInt8() ?: return null
            ((firstByte - 247) * 256 + secondByte + 108).toDouble()
        }

        in 251..254 -> {
            val secondByte = stream.readUInt8() ?: return null
            (-(firstByte - 251) * 256 - secondByte - 108).toDouble()
        }

        FIXED_16_DOT_16_MARK -> {
            if (!includeFixed16Dot16) return null
            val fixed = stream.readInt32() ?: return null
            fixed.toDouble() / 65536.0
        }

        else -> {
            null
        }
    }

private fun parseCffReal(stream: Stream): Double? {
    val chars = CharArray(REAL_BUFFER_SIZE)
    var length = 0

    while (true) {
        val byte = stream.readUInt8() ?: return null
        val firstNibble = byte ushr 4
        val secondNibble = byte and 0xF

        if (firstNibble == END_OF_REAL_FLAG) break
        length = appendRealNibble(firstNibble, chars, length) ?: return null

        if (secondNibble == END_OF_REAL_FLAG) break
        length = appendRealNibble(secondNibble, chars, length) ?: return null
    }

    return parseRealCharacters(chars, length)
}

private fun parseRealCharacters(
    chars: CharArray,
    length: Int,
): Double? {
    if (length == 0) return null
    var index = 0
    var negative = false
    if (chars[index] == '-') {
        negative = true
        index++
    }

    var value = 0.0
    var digitCount = 0
    var fractionalDigits = 0
    var hasDecimalPoint = false
    while (index < length && chars[index] != 'E') {
        val character = chars[index++]
        if (character == '.') {
            if (hasDecimalPoint) return null
            hasDecimalPoint = true
            continue
        }
        if (character !in '0'..'9') return null
        value = value * 10.0 + (character.code - '0'.code)
        digitCount++
        if (hasDecimalPoint) fractionalDigits++
    }
    if (digitCount == 0) return null

    var exponent = 0
    if (index < length) {
        index++
        var negativeExponent = false
        if (index < length && chars[index] == '-') {
            negativeExponent = true
            index++
        }
        val exponentStart = index
        while (index < length) {
            val character = chars[index++]
            if (character !in '0'..'9') return null
            if (exponent < MAX_ABSOLUTE_DECIMAL_EXPONENT) {
                exponent = (exponent * 10 + character.code - '0'.code).coerceAtMost(MAX_ABSOLUTE_DECIMAL_EXPONENT)
            }
        }
        if (index == exponentStart) return null
        if (negativeExponent) exponent = -exponent
    }

    val decimalExponent = exponent - fractionalDigits
    val result =
        if (decimalExponent < 0) {
            value / 10.0.pow(-decimalExponent)
        } else {
            value * 10.0.pow(decimalExponent)
        }
    val signedResult = if (negative) -result else result
    return signedResult.takeIf { it.isFinite() }
}

// Adobe Technical Note #5176, Table 5 Nibble Definitions.
private fun appendRealNibble(
    nibble: Int,
    chars: CharArray,
    start: Int,
): Int? {
    if (start == REAL_BUFFER_SIZE) return null
    var index = start

    when (nibble) {
        in 0..9 -> {
            chars[index] = ('0'.code + nibble).toChar()
        }

        10 -> {
            chars[index] = '.'
        }

        11 -> {
            chars[index] = 'E'
        }

        12 -> {
            if (index + 1 == REAL_BUFFER_SIZE) return null
            chars[index++] = 'E'
            chars[index] = '-'
        }

        13 -> {
            return null
        }

        14 -> {
            chars[index] = '-'
        }

        else -> {
            return null
        }
    }

    return index + 1
}

private const val MAX_ABSOLUTE_DECIMAL_EXPONENT: Int = 10_000

internal fun skipCffNumber(
    firstByte: Int,
    stream: Stream,
    includeFixed16Dot16: Boolean = false,
): Boolean =
    when (firstByte) {
        28 -> {
            stream.advance(2)
        }

        29 -> {
            stream.advance(4)
        }

        30 -> {
            while (!stream.atEnd) {
                val byte = stream.readUInt8() ?: return false
                if (byte ushr 4 == END_OF_REAL_FLAG || byte and 0xF == END_OF_REAL_FLAG) return true
            }
            false
        }

        in 32..246 -> {
            true
        }

        in 247..254 -> {
            stream.advance(1)
        }

        FIXED_16_DOT_16_MARK -> {
            includeFixed16Dot16 && stream.advance(4)
        }

        else -> {
            false
        }
    }
