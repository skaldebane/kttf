package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream

internal sealed interface CffCharset {
    fun sidToGid(sid: Int): Int?

    fun gidToSid(gid: Int): Int?

    fun seacCodeToGid(code: Int): Int?
}

internal fun parseCffCharset(
    data: ByteSlice,
    charsetOffset: Int?,
    glyphCount: Int,
): CffCharset? {
    if (glyphCount !in 1..UShort.MAX_VALUE.toInt()) return null

    return when (charsetOffset) {
        null, ISO_ADOBE_CHARSET_ID -> IsoAdobeCharset
        EXPERT_CHARSET_ID -> ExpertCharset(EXPERT_SIDS)
        EXPERT_SUBSET_CHARSET_ID -> ExpertCharset(EXPERT_SUBSET_SIDS)
        else -> parseCustomCharset(data, charsetOffset, glyphCount)
    }
}

private data object IsoAdobeCharset : CffCharset {
    override fun sidToGid(sid: Int): Int? = if (sid == 0) 0 else null

    override fun gidToSid(gid: Int): Int? = gid.takeIf { it in 0..ISO_ADOBE_LAST_GID }

    override fun seacCodeToGid(code: Int): Int? =
        cffStandardEncodingCodeToSid(code)?.takeIf { code <= ISO_ADOBE_LAST_GID }
}

private class ExpertCharset(
    private val sids: IntArray,
) : CffCharset {
    override fun sidToGid(sid: Int): Int? = if (sid == 0) 0 else null

    override fun gidToSid(gid: Int): Int? = if (gid in sids.indices) sids[gid] else null

    override fun seacCodeToGid(code: Int): Int? = null
}

private class Format0Charset(
    private val sids: ByteSlice,
) : CffCharset {
    override fun sidToGid(sid: Int): Int? {
        if (sid == 0) return 0
        if (sid !in 1..UShort.MAX_VALUE.toInt()) return null

        var index = 0
        while (index < sids.size / SID_SIZE) {
            if (sids.readUInt16(index * SID_SIZE) == sid) return index + 1
            index++
        }
        return null
    }

    override fun gidToSid(gid: Int): Int? {
        if (gid == 0) return 0
        if (gid !in 1..UShort.MAX_VALUE.toInt()) return null
        return sids.readUInt16((gid - 1) * SID_SIZE)
    }

    override fun seacCodeToGid(code: Int): Int? = cffStandardEncodingCodeToSid(code)?.let(::sidToGid)
}

private class RangeCharset(
    private val ranges: ByteSlice,
    private val leftSize: Int,
) : CffCharset {
    private val rangeSize: Int = SID_SIZE + leftSize

    override fun sidToGid(sid: Int): Int? {
        if (sid == 0) return 0
        if (sid !in 1..UShort.MAX_VALUE.toInt()) return null

        var gid = 1
        var offset = 0
        while (offset < ranges.size) {
            val first = ranges.readUInt16(offset) ?: return null
            val left = readLeft(offset + SID_SIZE) ?: return null
            if (sid >= first && sid <= first + left) return gid + sid - first
            gid += left + 1
            offset += rangeSize
        }
        return null
    }

    override fun gidToSid(gid: Int): Int? {
        if (gid == 0) return 0
        if (gid !in 1..UShort.MAX_VALUE.toInt()) return null

        var sidOffset = gid - 1
        var offset = 0
        while (offset < ranges.size) {
            val first = ranges.readUInt16(offset) ?: return null
            val left = readLeft(offset + SID_SIZE) ?: return null
            if (sidOffset <= left) {
                val sid = first + sidOffset
                return sid.takeIf { it <= UShort.MAX_VALUE.toInt() }
            }
            sidOffset -= left + 1
            offset += rangeSize
        }
        return null
    }

    override fun seacCodeToGid(code: Int): Int? = cffStandardEncodingCodeToSid(code)?.let(::sidToGid)

    private fun readLeft(offset: Int): Int? =
        when (leftSize) {
            1 -> ranges.readUInt8(offset)
            2 -> ranges.readUInt16(offset)
            else -> null
        }
}

private fun parseCustomCharset(
    data: ByteSlice,
    offset: Int,
    glyphCount: Int,
): CffCharset? {
    val stream = Stream.at(data, offset) ?: return null
    return when (stream.readUInt8() ?: return null) {
        0 -> {
            val byteCount = (glyphCount - 1) * SID_SIZE
            Format0Charset(stream.readBytes(byteCount) ?: return null)
        }

        1 -> {
            parseRangeCharset(data, stream, glyphCount - 1, 1)
        }

        2 -> {
            parseRangeCharset(data, stream, glyphCount - 1, 2)
        }

        else -> {
            null
        }
    }
}

private fun parseRangeCharset(
    data: ByteSlice,
    stream: Stream,
    glyphsRemaining: Int,
    leftSize: Int,
): CffCharset? {
    val start = stream.offset
    var remaining = glyphsRemaining
    while (remaining > 0) {
        stream.readUInt16() ?: return null
        val left =
            when (leftSize) {
                1 -> stream.readUInt8()
                2 -> stream.readUInt16()
                else -> null
            } ?: return null
        val rangeLength = left + 1
        if (rangeLength > remaining) return null
        remaining -= rangeLength
    }

    val ranges = data.slice(start, stream.offset - start) ?: return null
    return RangeCharset(ranges, leftSize)
}

private const val ISO_ADOBE_CHARSET_ID: Int = 0
private const val EXPERT_CHARSET_ID: Int = 1
private const val EXPERT_SUBSET_CHARSET_ID: Int = 2
private const val ISO_ADOBE_LAST_GID: Int = 228
private const val SID_SIZE: Int = 2

// Derived from ttf-parser's CFF Expert charset tables at commit
// 6e75b3c539ef6bfbacb020aaa3226414ddea7ce5. Copyright 2018 Yevhenii Reizner
// and the ttf-parser contributors. Used and modified under Apache-2.0.
@Suppress("ktlint:standard:argument-list-wrapping")
private val EXPERT_SIDS: IntArray =
    intArrayOf(
        0, 1, 229, 230, 231, 232, 233, 234, 235, 236, 237, 238, 13, 14, 15, 99,
        239, 240, 241, 242, 243, 244, 245, 246, 247, 248, 27, 28, 249, 250, 251, 252,
        253, 254, 255, 256, 257, 258, 259, 260, 261, 262, 263, 264, 265, 266, 109, 110,
        267, 268, 269, 270, 271, 272, 273, 274, 275, 276, 277, 278, 279, 280, 281, 282,
        283, 284, 285, 286, 287, 288, 289, 290, 291, 292, 293, 294, 295, 296, 297, 298,
        299, 300, 301, 302, 303, 304, 305, 306, 307, 308, 309, 310, 311, 312, 313, 314,
        315, 316, 317, 318, 158, 155, 163, 319, 320, 321, 322, 323, 324, 325, 326, 150,
        164, 169, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339, 340,
        341, 342, 343, 344, 345, 346, 347, 348, 349, 350, 351, 352, 353, 354, 355, 356,
        357, 358, 359, 360, 361, 362, 363, 364, 365, 366, 367, 368, 369, 370, 371, 372,
        373, 374, 375, 376, 377, 378,
    )

@Suppress("ktlint:standard:argument-list-wrapping")
private val EXPERT_SUBSET_SIDS: IntArray =
    intArrayOf(
        0, 1, 231, 232, 235, 236, 237, 238, 13, 14, 15, 99, 239, 240, 241, 242,
        243, 244, 245, 246, 247, 248, 27, 28, 249, 250, 251, 253, 254, 255, 256, 257,
        258, 259, 260, 261, 262, 263, 264, 265, 266, 109, 110, 267, 268, 269, 270, 272,
        300, 301, 302, 305, 314, 315, 158, 155, 163, 320, 321, 322, 323, 324, 325, 326,
        150, 164, 169, 327, 328, 329, 330, 331, 332, 333, 334, 335, 336, 337, 338, 339,
        340, 341, 342, 343, 344, 345, 346,
    )
