package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream

internal sealed interface CffEncoding {
    fun codeToGid(
        charset: CffCharset,
        code: Int,
    ): Int?
}

internal fun parseCffEncoding(
    data: ByteSlice,
    encodingOffset: Int?,
): CffEncoding? =
    when (encodingOffset) {
        null, STANDARD_ENCODING_ID -> StandardEncoding
        EXPERT_ENCODING_ID -> ExpertEncoding
        else -> parseCustomEncoding(data, encodingOffset)
    }

internal fun cffStandardEncodingCodeToSid(code: Int): Int? = STANDARD_ENCODING.getOrNull(code)

private data object StandardEncoding : CffEncoding {
    override fun codeToGid(
        charset: CffCharset,
        code: Int,
    ): Int? = cffStandardEncodingCodeToSid(code)?.let(charset::sidToGid)
}

private data object ExpertEncoding : CffEncoding {
    override fun codeToGid(
        charset: CffCharset,
        code: Int,
    ): Int? = cffStandardEncodingCodeToSid(code)?.let(charset::sidToGid)
}

private class CustomEncoding(
    private val format: Int,
    private val records: ByteSlice,
    private val supplemental: ByteSlice?,
) : CffEncoding {
    override fun codeToGid(
        charset: CffCharset,
        code: Int,
    ): Int? {
        if (code !in 0..UByte.MAX_VALUE.toInt()) return null

        val supplements = supplemental
        if (supplements != null) {
            var offset = 0
            while (offset < supplements.size) {
                if (supplements.readUInt8(offset) == code) {
                    val sid = supplements.readUInt16(offset + 1) ?: return null
                    return charset.sidToGid(sid)
                }
                offset += SUPPLEMENT_SIZE
            }
        }

        return when (format) {
            0 -> format0CodeToGid(code)
            1 -> format1CodeToGid(code)
            else -> null
        }
    }

    private fun format0CodeToGid(code: Int): Int? {
        var index = 0
        while (index < records.size) {
            if (records.readUInt8(index) == code) return index + 1
            index++
        }
        return null
    }

    private fun format1CodeToGid(code: Int): Int? {
        var gid = 1
        var offset = 0
        while (offset < records.size) {
            val first = records.readUInt8(offset) ?: return null
            val left = records.readUInt8(offset + 1) ?: return null
            val end = (first + left).coerceAtMost(UByte.MAX_VALUE.toInt())
            if (code in first..end) return gid + code - first

            gid += left + 1
            offset += FORMAT_1_RANGE_SIZE
        }
        return null
    }
}

private fun parseCustomEncoding(
    data: ByteSlice,
    offset: Int,
): CffEncoding? {
    val stream = Stream.at(data, offset) ?: return null
    val rawFormat = stream.readUInt8() ?: return null
    val hasSupplemental = rawFormat and SUPPLEMENTAL_FLAG != 0
    val format = rawFormat and SUPPLEMENTAL_FLAG.inv()
    val count = stream.readUInt8() ?: return null
    val recordSize =
        when (format) {
            0 -> FORMAT_0_RECORD_SIZE
            1 -> FORMAT_1_RANGE_SIZE
            else -> return null
        }
    val records = stream.readBytes(count * recordSize) ?: return null
    val supplemental =
        if (hasSupplemental) {
            val supplementCount = stream.readUInt8() ?: return null
            stream.readBytes(supplementCount * SUPPLEMENT_SIZE) ?: return null
        } else {
            null
        }

    return CustomEncoding(format, records, supplemental)
}

private const val STANDARD_ENCODING_ID: Int = 0
private const val EXPERT_ENCODING_ID: Int = 1
private const val SUPPLEMENTAL_FLAG: Int = 0x80
private const val FORMAT_0_RECORD_SIZE: Int = 1
private const val FORMAT_1_RANGE_SIZE: Int = 2
private const val SUPPLEMENT_SIZE: Int = 3

// Derived from ttf-parser's CFF Standard Encoding table at commit
// 6e75b3c539ef6bfbacb020aaa3226414ddea7ce5. Copyright 2018 Yevhenii Reizner
// and the ttf-parser contributors. Used and modified under Apache-2.0.
@Suppress("ktlint:standard:argument-list-wrapping")
private val STANDARD_ENCODING: IntArray =
    intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48,
        49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64,
        65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
        81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
        0, 111, 112, 113, 114, 0, 115, 116, 117, 118, 119, 120, 121, 122, 0, 123,
        0, 124, 125, 126, 127, 128, 129, 130, 131, 0, 132, 133, 0, 134, 135, 136,
        137, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 138, 0, 139, 0, 0, 0, 0, 140, 141, 142, 143, 0, 0, 0, 0,
        0, 144, 0, 0, 0, 145, 0, 0, 146, 147, 148, 149, 0, 0, 0, 0,
    )
