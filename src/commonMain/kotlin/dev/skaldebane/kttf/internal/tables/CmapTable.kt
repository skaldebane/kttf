package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal enum class CmapPlatform {
    UNICODE,
    MACINTOSH,
    ISO,
    WINDOWS,
    CUSTOM,
}

internal enum class CmapFormatKind(
    internal val value: Int,
) {
    BYTE_ENCODING(0),
    HIGH_BYTE_MAPPING(2),
    SEGMENT_MAPPING(4),
    TRIMMED_MAPPING(6),
    MIXED_COVERAGE(8),
    TRIMMED_ARRAY(10),
    SEGMENTED_COVERAGE(12),
    MANY_TO_ONE(13),
    UNICODE_VARIATION_SEQUENCES(14),
}

internal sealed interface CmapVariationResult {
    class Found(
        internal val glyphId: Int,
    ) : CmapVariationResult

    data object UseDefault : CmapVariationResult
}

internal class CmapSubtable internal constructor(
    internal val platform: CmapPlatform,
    internal val encodingId: Int,
    private val format: CmapFormat,
) {
    internal val formatKind: CmapFormatKind
        get() = format.kind

    internal val isUnicode: Boolean
        get() =
            when (platform) {
                CmapPlatform.UNICODE -> {
                    true
                }

                CmapPlatform.WINDOWS -> {
                    encodingId == WINDOWS_UNICODE_BMP_ENCODING_ID ||
                        (
                            encodingId == WINDOWS_UNICODE_FULL_REPERTOIRE_ENCODING_ID &&
                                (
                                    formatKind == CmapFormatKind.SEGMENTED_COVERAGE ||
                                        formatKind == CmapFormatKind.MANY_TO_ONE
                                )
                        )
                }

                else -> {
                    false
                }
            }

    internal fun glyphId(codePoint: Long): Int? = format.glyphId(codePoint)

    internal fun glyphVariation(
        codePoint: Long,
        variationSelector: Long,
    ): CmapVariationResult? = format.glyphVariation(codePoint, variationSelector)

    internal fun forEachCodePoint(action: (Long) -> Unit): Boolean {
        var count = 0
        return format.forEachCodePoint { codePoint ->
            if (count >= MAX_CODE_POINT_TRAVERSAL) {
                false
            } else {
                count++
                action(codePoint)
                true
            }
        }
    }

    private companion object {
        private const val WINDOWS_UNICODE_BMP_ENCODING_ID: Int = 1
        private const val WINDOWS_UNICODE_FULL_REPERTOIRE_ENCODING_ID: Int = 10
        private const val MAX_CODE_POINT_TRAVERSAL: Int = 0x110000
    }
}

internal class CmapTable private constructor(
    private val data: ByteSlice,
    private val records: FixedRecordArray,
) {
    internal val size: Int
        get() = records.size

    internal operator fun get(index: Int): CmapSubtable? {
        val record = records[index] ?: return null
        val platform = record.readUInt16(0)?.toPlatform() ?: return null
        val encodingId = record.readUInt16(2) ?: return null
        val offset = record.readUInt32(4)?.toIndexOrNull() ?: return null
        val subtableData = data.sliceFrom(offset) ?: return null
        val format = CmapFormat.parse(subtableData) ?: return null
        return CmapSubtable(platform, encodingId, format)
    }

    internal fun glyphId(codePoint: Long): Int? {
        var index = 0
        while (index < size) {
            val subtable = get(index) ?: return null
            if (subtable.isUnicode) {
                val glyphId = subtable.glyphId(codePoint)
                if (glyphId != null) return glyphId
            }
            index++
        }
        return null
    }

    internal fun glyphVariation(
        codePoint: Long,
        variationSelector: Long,
    ): Int? {
        var index = 0
        while (index < size) {
            val subtable = get(index) ?: return null
            if (subtable.formatKind == CmapFormatKind.UNICODE_VARIATION_SEQUENCES) {
                return when (val result = subtable.glyphVariation(codePoint, variationSelector)) {
                    is CmapVariationResult.Found -> result.glyphId
                    CmapVariationResult.UseDefault -> glyphId(codePoint)
                    null -> null
                }
            }
            index++
        }
        return null
    }

    internal companion object {
        private const val ENCODING_RECORD_SIZE: Int = 8

        internal fun parse(data: ByteSlice): CmapTable? {
            val count = data.readUInt16(2) ?: return null
            val recordsData = data.slice(4, count * ENCODING_RECORD_SIZE) ?: return null
            val records = FixedRecordArray.create(recordsData, count, ENCODING_RECORD_SIZE) ?: return null
            return CmapTable(data, records)
        }

        private fun Int.toPlatform(): CmapPlatform? =
            when (this) {
                0 -> CmapPlatform.UNICODE
                1 -> CmapPlatform.MACINTOSH
                2 -> CmapPlatform.ISO
                3 -> CmapPlatform.WINDOWS
                4 -> CmapPlatform.CUSTOM
                else -> null
            }

        private fun Long.toIndexOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null
    }
}

internal sealed interface CmapFormat {
    val kind: CmapFormatKind

    fun glyphId(codePoint: Long): Int?

    fun glyphVariation(
        codePoint: Long,
        variationSelector: Long,
    ): CmapVariationResult? = null

    fun forEachCodePoint(action: (Long) -> Boolean): Boolean

    companion object {
        fun parse(data: ByteSlice): CmapFormat? =
            when (data.readUInt16(0)) {
                0 -> Format0.parse(data)
                2 -> Format2.parse(data)
                4 -> Format4.parse(data)
                6 -> Format6.parse(data)
                8 -> UnsupportedFormat8
                10 -> Format10.parse(data)
                12 -> Format12.parse(data)
                13 -> Format13.parse(data)
                14 -> Format14.parse(data)
                else -> null
            }
    }
}

private class Format0 private constructor(
    private val glyphIds: ByteSlice,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.BYTE_ENCODING

    override fun glyphId(codePoint: Long): Int? {
        if (codePoint !in 0..255) return null
        return glyphIds.readUInt8(codePoint.toInt())?.takeUnless { it == 0 }
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean {
        var index = 0
        while (index < 256) {
            if (glyphIds.readUInt8(index) != 0 && !action(index.toLong())) return false
            index++
        }
        return true
    }

    companion object {
        fun parse(data: ByteSlice): Format0? {
            val glyphIds = data.slice(6, 256) ?: return null
            return Format0(glyphIds)
        }
    }
}

private class Format2 private constructor(
    private val data: ByteSlice,
    private val subHeaderKeys: FixedRecordArray,
    private val subHeadersOffset: Int,
    private val subHeaders: FixedRecordArray,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.HIGH_BYTE_MAPPING

    override fun glyphId(codePoint: Long): Int? {
        if (codePoint !in 0..0xFFFF) return null
        val value = codePoint.toInt()
        val highByte = value ushr 8
        val lowByte = value and 0xFF
        val subHeaderIndex = if (value < 0xFF) 0 else subHeaderKeys.uint16(highByte)?.div(8) ?: return null
        val subHeader = subHeaders[subHeaderIndex] ?: return null
        val firstCode = subHeader.readUInt16(0) ?: return null
        val entryCount = subHeader.readUInt16(2) ?: return null
        val rangeEnd = firstCode + entryCount
        if (rangeEnd > 0xFFFF || lowByte < firstCode || lowByte >= rangeEnd) return null

        val idDelta = subHeader.readInt16(4) ?: return null
        val idRangeOffset = subHeader.readUInt16(6) ?: return null
        val offset =
            subHeadersOffset.toLong() +
                SUBHEADER_SIZE.toLong() * (subHeaderIndex + 1L) - 2L +
                idRangeOffset.toLong() +
                (lowByte - firstCode).toLong() * 2L
        if (offset !in 0..Int.MAX_VALUE.toLong()) return null
        val glyph = data.readUInt16(offset.toInt()) ?: return null
        if (glyph == 0) return null
        val resolved = (glyph + idDelta) % 65536
        return resolved.takeIf { it in 0..0xFFFF }
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean {
        var firstByte = 0
        while (firstByte < 256) {
            val subHeaderIndex = subHeaderKeys.uint16(firstByte)?.div(8) ?: return false
            val subHeader = subHeaders[subHeaderIndex] ?: return false
            val firstCode = subHeader.readUInt16(0) ?: return false
            val entryCount = subHeader.readUInt16(2) ?: return false
            if (subHeaderIndex == 0) {
                val rangeEnd = firstCode + entryCount
                if (rangeEnd > 0xFFFF) return false
                if (firstByte in firstCode until rangeEnd && !action(firstByte.toLong())) return false
            } else {
                val base = firstCode + (firstByte shl 8)
                if (base > 0xFFFF) return false
                var offset = 0
                while (offset < entryCount) {
                    val codePoint = base + offset
                    if (codePoint > 0xFFFF) return false
                    if (!action(codePoint.toLong())) return false
                    offset++
                }
            }
            firstByte++
        }
        return true
    }

    companion object {
        private const val SUBHEADER_SIZE: Int = 8

        fun parse(data: ByteSlice): Format2? {
            val keysData = data.slice(6, 512) ?: return null
            val keys = FixedRecordArray.create(keysData, 256, 2) ?: return null
            var maximumKey = 0
            var index = 0
            while (index < keys.size) {
                maximumKey = maxOf(maximumKey, keys.uint16(index) ?: return null)
                index++
            }
            val subHeaderCount = maximumKey / 8 + 1
            val offset = 518
            val headersData = data.slice(offset, subHeaderCount * SUBHEADER_SIZE) ?: return null
            val headers = FixedRecordArray.create(headersData, subHeaderCount, SUBHEADER_SIZE) ?: return null
            return Format2(data, keys, offset, headers)
        }
    }
}

private class Format4 private constructor(
    private val data: ByteSlice,
    private val startCodes: FixedRecordArray,
    private val endCodes: FixedRecordArray,
    private val idDeltas: FixedRecordArray,
    private val idRangeOffsets: FixedRecordArray,
    private val idRangeOffsetsPosition: Int,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.SEGMENT_MAPPING

    override fun glyphId(codePoint: Long): Int? {
        if (codePoint !in 0..0xFFFF) return null
        val value = codePoint.toInt()
        var start = 0
        var end = startCodes.size
        while (end > start) {
            val index = (start + end) / 2
            val endValue = endCodes.uint16(index) ?: return null
            if (endValue >= value) {
                val startValue = startCodes.uint16(index) ?: return null
                if (startValue > value) {
                    end = index
                } else {
                    val rangeOffset = idRangeOffsets.uint16(index) ?: return null
                    val delta = idDeltas.int16(index) ?: return null
                    if (rangeOffset == 0) return (value + (delta and 0xFFFF)) and 0xFFFF
                    if (rangeOffset == 0xFFFF) return null

                    val characterDelta = (value - startValue).toLong() * 2
                    if (characterDelta > 0xFFFF) return null
                    var position = (idRangeOffsetsPosition + index * 2) and 0xFFFF
                    position = (position + characterDelta.toInt()) and 0xFFFF
                    position = (position + rangeOffset) and 0xFFFF
                    val glyph = data.readUInt16(position) ?: return null
                    if (glyph == 0) return null
                    val resolved = (glyph.toShort().toInt() + delta).toShort().toInt()
                    return resolved.takeIf { it >= 0 }
                }
            } else {
                start = index + 1
            }
        }
        return null
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean {
        var index = 0
        while (index < startCodes.size) {
            val start = startCodes.uint16(index) ?: return false
            val end = endCodes.uint16(index) ?: return false
            if (start == 0xFFFF && end == 0xFFFF) return true
            var codePoint = start
            while (codePoint <= end) {
                if (!action(codePoint.toLong())) return false
                codePoint++
            }
            index++
        }
        return true
    }

    companion object {
        fun parse(data: ByteSlice): Format4? {
            val segmentCountX2 = data.readUInt16(6) ?: return null
            if (segmentCountX2 < 2) return null
            val segmentCount = segmentCountX2 / 2
            var offset = 14
            val endCodes = data.records(offset, segmentCount, 2) ?: return null
            offset += segmentCount * 2 + 2
            val startCodes = data.records(offset, segmentCount, 2) ?: return null
            offset += segmentCount * 2
            val idDeltas = data.records(offset, segmentCount, 2) ?: return null
            offset += segmentCount * 2
            val rangeOffsetsPosition = offset
            val idRangeOffsets = data.records(offset, segmentCount, 2) ?: return null
            return Format4(data, startCodes, endCodes, idDeltas, idRangeOffsets, rangeOffsetsPosition)
        }
    }
}

private class Format6 private constructor(
    private val firstCodePoint: Int,
    private val glyphs: FixedRecordArray,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.TRIMMED_MAPPING

    override fun glyphId(codePoint: Long): Int? {
        val index = codePoint - firstCodePoint
        if (index !in 0 until glyphs.size.toLong()) return null
        return glyphs.uint16(index.toInt())
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean {
        var index = 0
        while (index < glyphs.size && firstCodePoint + index <= 0xFFFF) {
            if (!action((firstCodePoint + index).toLong())) return false
            index++
        }
        return true
    }

    companion object {
        fun parse(data: ByteSlice): Format6? {
            val first = data.readUInt16(6) ?: return null
            val count = data.readUInt16(8) ?: return null
            val glyphs = data.records(10, count, 2) ?: return null
            return Format6(first, glyphs)
        }
    }
}

private data object UnsupportedFormat8 : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.MIXED_COVERAGE

    override fun glyphId(codePoint: Long): Int? = null

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean = true
}

private class Format10 private constructor(
    private val firstCodePoint: Long,
    private val glyphs: FixedRecordArray,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.TRIMMED_ARRAY

    override fun glyphId(codePoint: Long): Int? {
        if (codePoint !in 0..UINT32_MAX) return null
        val index = codePoint - firstCodePoint
        if (index !in 0 until glyphs.size.toLong()) return null
        return glyphs.uint16(index.toInt())
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean {
        var index = 0L
        while (index < glyphs.size) {
            val codePoint = firstCodePoint + index
            if (codePoint > UINT32_MAX) return true
            if (!action(codePoint)) return false
            index++
        }
        return true
    }

    companion object {
        fun parse(data: ByteSlice): Format10? {
            val first = data.readUInt32(12) ?: return null
            val count = data.readUInt32(16)?.toIndexOrNull() ?: return null
            val glyphs = data.records(20, count, 2) ?: return null
            return Format10(first, glyphs)
        }
    }
}

private data class SequentialMapGroup(
    val start: Long,
    val end: Long,
    val glyph: Long,
)

private class Format12 private constructor(
    private val groups: FixedRecordArray,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.SEGMENTED_COVERAGE

    override fun glyphId(codePoint: Long): Int? {
        if (codePoint !in 0..UINT32_MAX) return null
        val group = groups.binarySearchGroup(codePoint) ?: return null
        val sum = group.glyph + codePoint
        if (sum > UINT32_MAX) return null
        return (sum - group.start).toGlyphIdOrNull()
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean = groups.forEachGroup(action)

    companion object {
        fun parse(data: ByteSlice): Format12? {
            val count = data.readUInt32(12)?.toIndexOrNull() ?: return null
            val groups = data.records(16, count, 12) ?: return null
            return Format12(groups)
        }
    }
}

private class Format13 private constructor(
    private val groups: FixedRecordArray,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.MANY_TO_ONE

    override fun glyphId(codePoint: Long): Int? {
        if (codePoint !in 0..UINT32_MAX) return null
        var index = 0
        while (index < groups.size) {
            val group = groups.group(index) ?: return null
            if (codePoint in group.start..group.end) return group.glyph.toGlyphIdOrNull()
            index++
        }
        return null
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean = groups.forEachGroup(action)

    companion object {
        fun parse(data: ByteSlice): Format13? {
            val count = data.readUInt32(12)?.toIndexOrNull() ?: return null
            val groups = data.records(16, count, 12) ?: return null
            return Format13(groups)
        }
    }
}

private class Format14 private constructor(
    private val data: ByteSlice,
    private val records: FixedRecordArray,
) : CmapFormat {
    override val kind: CmapFormatKind = CmapFormatKind.UNICODE_VARIATION_SEQUENCES

    override fun glyphId(codePoint: Long): Int? = null

    override fun glyphVariation(
        codePoint: Long,
        variationSelector: Long,
    ): CmapVariationResult? {
        if (codePoint !in 0..UINT24_MAX || variationSelector !in 0..UINT24_MAX) return null
        val record = records.binarySearch(variationSelector) { it.readUInt24(0)?.toLong() } ?: return null
        val defaultOffset = record.readUInt32(3) ?: return null
        if (defaultOffset != 0L) {
            val rangesData = data.sliceFrom(defaultOffset.toIndexOrNull() ?: return null) ?: return null
            val count = rangesData.readUInt32(0)?.toIndexOrNull() ?: return null
            val ranges = rangesData.records(4, count, 4) ?: return null
            var index = 0
            while (index < ranges.size) {
                val range = ranges[index] ?: return null
                val start = range.readUInt24(0)?.toLong() ?: return null
                val additional = range.readUInt8(3) ?: return null
                if (codePoint in start..start + additional) return CmapVariationResult.UseDefault
                index++
            }
        }

        val nonDefaultOffset = record.readUInt32(7) ?: return null
        if (nonDefaultOffset != 0L) {
            val mappingsData = data.sliceFrom(nonDefaultOffset.toIndexOrNull() ?: return null) ?: return null
            val count = mappingsData.readUInt32(0)?.toIndexOrNull() ?: return null
            val mappings = mappingsData.records(4, count, 5) ?: return null
            val mapping = mappings.binarySearch(codePoint) { it.readUInt24(0)?.toLong() } ?: return null
            val glyph = mapping.readUInt16(3) ?: return null
            return CmapVariationResult.Found(glyph)
        }
        return null
    }

    override fun forEachCodePoint(action: (Long) -> Boolean): Boolean = true

    companion object {
        fun parse(data: ByteSlice): Format14? {
            val count = data.readUInt32(6)?.toIndexOrNull() ?: return null
            val records = data.records(10, count, 11) ?: return null
            return Format14(data, records)
        }
    }
}

private const val UINT24_MAX: Long = 0xFF_FFFFL
private const val UINT32_MAX: Long = 0xFFFF_FFFFL

private fun ByteSlice.records(
    offset: Int,
    count: Int,
    recordSize: Int,
): FixedRecordArray? {
    if (count < 0 || recordSize <= 0 || count > Int.MAX_VALUE / recordSize) return null
    val bytes = slice(offset, count * recordSize) ?: return null
    return FixedRecordArray.create(bytes, count, recordSize)
}

private fun FixedRecordArray.uint16(index: Int): Int? = get(index)?.readUInt16(0)

private fun FixedRecordArray.int16(index: Int): Int? = get(index)?.readInt16(0)

private fun FixedRecordArray.group(index: Int): SequentialMapGroup? {
    val record = get(index) ?: return null
    return SequentialMapGroup(
        start = record.readUInt32(0) ?: return null,
        end = record.readUInt32(4) ?: return null,
        glyph = record.readUInt32(8) ?: return null,
    )
}

private fun FixedRecordArray.forEachGroup(action: (Long) -> Boolean): Boolean {
    var index = 0
    while (index < size) {
        val group = group(index) ?: return false
        var codePoint = group.start
        while (codePoint <= group.end) {
            if (!action(codePoint)) return false
            if (codePoint == UINT32_MAX) break
            codePoint++
        }
        index++
    }
    return true
}

private fun FixedRecordArray.binarySearchGroup(codePoint: Long): SequentialMapGroup? {
    var remaining = size
    if (remaining == 0) return null
    var base = 0
    while (remaining > 1) {
        val half = remaining / 2
        val middle = base + half
        val group = group(middle) ?: return null
        if (group.start <= codePoint) base = middle
        remaining -= half
    }
    val group = group(base) ?: return null
    return group.takeIf { codePoint in it.start..it.end }
}

private fun FixedRecordArray.binarySearch(
    target: Long,
    key: (ByteSlice) -> Long?,
): ByteSlice? {
    var remaining = size
    if (remaining == 0) return null
    var base = 0
    while (remaining > 1) {
        val half = remaining / 2
        val middle = base + half
        val record = get(middle) ?: return null
        val value = key(record) ?: return null
        if (value <= target) base = middle
        remaining -= half
    }
    val record = get(base) ?: return null
    return record.takeIf { key(it) == target }
}

private fun Long.toIndexOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null

private fun Long.toGlyphIdOrNull(): Int? = if (this in 0..0xFFFF) toInt() else null
