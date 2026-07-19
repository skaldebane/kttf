package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal const val AAT_START_OF_TEXT_STATE: Int = 0
internal const val AAT_END_OF_TEXT_CLASS: Int = 0
internal const val AAT_OUT_OF_BOUNDS_CLASS: Int = 1
internal const val AAT_DELETED_GLYPH_CLASS: Int = 2

internal class AatUInt16View internal constructor(
    private val data: ByteSlice,
    internal val size: Int = data.size / 2,
) {
    internal operator fun get(index: Int): Int? {
        if (index !in 0 until size) return null
        return data.readUInt16(index * 2)
    }

    internal companion object {
        internal fun sized(
            data: ByteSlice,
            count: Int,
        ): AatUInt16View? {
            if (count < 0 || count > data.size / 2) return null
            return AatUInt16View(data.slice(0, count * 2)!!, count)
        }
    }
}

internal class AatInt16View internal constructor(
    private val data: ByteSlice,
    internal val size: Int = data.size / 2,
) {
    internal operator fun get(index: Int): Int? {
        if (index !in 0 until size) return null
        return data.readInt16(index * 2)
    }

    internal companion object {
        internal fun sized(
            data: ByteSlice,
            count: Int,
        ): AatInt16View? {
            if (count < 0 || count > data.size / 2) return null
            return AatInt16View(data.slice(0, count * 2)!!, count)
        }
    }
}

internal class AatUInt32View internal constructor(
    private val data: ByteSlice,
) {
    internal val size: Int
        get() = data.size / 4

    internal operator fun get(index: Int): Long? {
        if (index !in 0 until size) return null
        return data.readUInt32(index * 4)
    }
}

internal class AatGlyphView internal constructor(
    private val data: ByteSlice,
) {
    internal val size: Int
        get() = data.size / 2

    internal operator fun get(index: Int): Int? {
        if (index !in 0 until size) return null
        return data.readUInt16(index * 2)
    }
}

internal data class AatStateEntry<T>(
    internal val newState: Int,
    internal val flags: Int,
    internal val extra: T,
) {
    internal val hasOffset: Boolean
        get() = flags and 0x3FFF != 0

    internal val valueOffset: Int
        get() = flags and 0x3FFF

    internal val hasReset: Boolean
        get() = flags and 0x2000 != 0

    internal val hasAdvance: Boolean
        get() = flags and 0x4000 == 0

    internal val hasPush: Boolean
        get() = flags and 0x8000 != 0

    internal val hasMark: Boolean
        get() = hasPush
}

internal class AatExtendedStateTable<T> private constructor(
    internal val numberOfClasses: Int,
    private val lookup: AatLookup,
    private val stateArray: ByteSlice,
    private val entryTable: ByteSlice,
    private val extraSize: Int,
    private val parseExtra: (ByteSlice) -> T?,
) {
    internal val numberOfStates: Int
        get() =
            if (numberOfClasses == 0) {
                0
            } else {
                (stateArray.size.toLong() / (numberOfClasses.toLong() * 2)).toInt()
            }

    internal val numberOfEntries: Int
        get() = entryTable.size / (4 + extraSize)

    internal fun glyphClass(glyphId: Int): Int? {
        if (glyphId == 0xFFFF) return AAT_DELETED_GLYPH_CLASS
        return lookup.value(glyphId)
    }

    internal fun entry(
        state: Int,
        glyphClass: Int,
    ): AatStateEntry<T>? {
        if (state < 0 || glyphClass < 0) return null
        val resolvedClass = if (glyphClass >= numberOfClasses) AAT_OUT_OF_BOUNDS_CLASS else glyphClass
        val stateIndex = state.toLong() * numberOfClasses + resolvedClass
        if (stateIndex > Int.MAX_VALUE / 2) return null
        val entryIndex = stateArray.readUInt16(stateIndex.toInt() * 2) ?: return null
        val entrySize = 4 + extraSize
        val offset = entryIndex.toLong() * entrySize
        if (offset > Int.MAX_VALUE) return null
        val bytes = entryTable.slice(offset.toInt(), entrySize) ?: return null
        val extra = parseExtra(bytes.slice(4, extraSize)!!) ?: return null
        return AatStateEntry(
            newState = bytes.readUInt16(0) ?: return null,
            flags = bytes.readUInt16(2) ?: return null,
            extra = extra,
        )
    }

    internal companion object {
        internal const val HEADER_SIZE: Int = 16

        internal fun <T> parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
            extraSize: Int,
            parseExtra: (ByteSlice) -> T?,
        ): AatExtendedStateTable<T>? {
            if (numberOfGlyphs !in 1..0xFFFF || extraSize < 0) return null
            val numberOfClasses = data.readUInt32(0)?.toIntOrNull() ?: return null
            val lookupOffset = data.readUInt32(4)?.toIntOrNull() ?: return null
            val stateOffset = data.readUInt32(8)?.toIntOrNull() ?: return null
            val entryOffset = data.readUInt32(12)?.toIntOrNull() ?: return null
            val lookupData = data.sliceFrom(lookupOffset) ?: return null
            return AatExtendedStateTable(
                numberOfClasses = numberOfClasses,
                lookup = AatLookup.parse(numberOfGlyphs, lookupData) ?: return null,
                stateArray = data.sliceFrom(stateOffset) ?: return null,
                entryTable = data.sliceFrom(entryOffset) ?: return null,
                extraSize = extraSize,
                parseExtra = parseExtra,
            )
        }
    }
}

internal class AatLookup private constructor(
    private val format: Format,
) {
    internal fun value(glyphId: Int): Int? {
        if (glyphId !in 0..0xFFFF) return null
        return format.value(glyphId)
    }

    private sealed interface Format {
        fun value(glyphId: Int): Int?
    }

    private class Format0(
        private val values: AatUInt16View,
    ) : Format {
        override fun value(glyphId: Int): Int? = values[glyphId]
    }

    private class Format2(
        private val records: LookupBinarySearch,
    ) : Format {
        override fun value(glyphId: Int): Int? = records.segment(glyphId)?.value
    }

    private class Format4(
        private val records: LookupBinarySearch,
        private val data: ByteSlice,
    ) : Format {
        override fun value(glyphId: Int): Int? {
            val segment = records.segment(glyphId) ?: return null
            val index = glyphId - segment.firstGlyph
            return data.readUInt16(segment.value + index * 2)
        }
    }

    private class Format6(
        private val records: LookupBinarySearch,
    ) : Format {
        override fun value(glyphId: Int): Int? = records.single(glyphId)?.value
    }

    private class Format8(
        private val firstGlyph: Int,
        private val values: AatUInt16View,
    ) : Format {
        override fun value(glyphId: Int): Int? = values[glyphId - firstGlyph]
    }

    private class Format10(
        private val valueSize: Int,
        private val firstGlyph: Int,
        private val glyphCount: Int,
        private val data: ByteSlice,
    ) : Format {
        override fun value(glyphId: Int): Int? {
            val index = glyphId - firstGlyph
            if (index !in 0 until glyphCount) return null
            return when (valueSize) {
                1 -> data.readUInt8(index)
                2 -> data.readUInt16(index * 2)
                4 -> data.readUInt32(index * 4)?.toInt()?.and(0xFFFF)
                else -> null
            }
        }
    }

    internal companion object {
        internal fun parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): AatLookup? {
            if (numberOfGlyphs !in 1..0xFFFF) return null
            val format = data.readUInt16(0) ?: return null
            val body = data.sliceFrom(2) ?: return null
            val parsed =
                when (format) {
                    0 -> {
                        Format0(AatUInt16View.sized(body, numberOfGlyphs) ?: return null)
                    }

                    2 -> {
                        Format2(LookupBinarySearch.parse(body, 6) ?: return null)
                    }

                    4 -> {
                        Format4(LookupBinarySearch.parse(body, 6) ?: return null, data)
                    }

                    6 -> {
                        Format6(LookupBinarySearch.parse(body, 4) ?: return null)
                    }

                    8 -> {
                        val firstGlyph = body.readUInt16(0) ?: return null
                        val count = body.readUInt16(2) ?: return null
                        val values = AatUInt16View.sized(body.sliceFrom(4) ?: return null, count) ?: return null
                        Format8(firstGlyph, values)
                    }

                    10 -> {
                        val valueSize = body.readUInt16(0) ?: return null
                        val firstGlyph = body.readUInt16(2) ?: return null
                        val count = body.readUInt16(4) ?: return null
                        Format10(valueSize, firstGlyph, count, body.sliceFrom(6) ?: return null)
                    }

                    else -> {
                        return null
                    }
                }
            return AatLookup(parsed)
        }
    }
}

private data class LookupRecord(
    val lastGlyph: Int,
    val firstGlyph: Int,
    val value: Int,
)

private class LookupBinarySearch private constructor(
    private val data: ByteSlice,
    private val recordSize: Int,
    private val size: Int,
) {
    fun segment(glyphId: Int): LookupRecord? = search(glyphId, false)

    fun single(glyphId: Int): LookupRecord? = search(glyphId, true)

    private fun search(
        glyphId: Int,
        single: Boolean,
    ): LookupRecord? {
        var low = 0
        var high = size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val record = record(middle) ?: return null
            val comparison =
                when {
                    glyphId < record.firstGlyph -> -1
                    !single && glyphId <= record.lastGlyph -> 0
                    single && glyphId == record.firstGlyph -> 0
                    else -> 1
                }
            when {
                comparison < 0 -> high = middle - 1
                comparison > 0 -> low = middle + 1
                else -> return record
            }
        }
        return null
    }

    private fun record(index: Int): LookupRecord? {
        if (index !in 0 until size) return null
        val offset = index * recordSize
        return if (recordSize == 6) {
            LookupRecord(
                lastGlyph = data.readUInt16(offset) ?: return null,
                firstGlyph = data.readUInt16(offset + 2) ?: return null,
                value = data.readUInt16(offset + 4) ?: return null,
            )
        } else {
            val glyph = data.readUInt16(offset) ?: return null
            LookupRecord(glyph, glyph, data.readUInt16(offset + 2) ?: return null)
        }
    }

    private fun lastIsTermination(count: Int): Boolean {
        val record = record(count - 1) ?: return false
        return if (recordSize == 6) {
            record.lastGlyph == 0xFFFF && record.firstGlyph == 0xFFFF
        } else {
            record.firstGlyph == 0xFFFF
        }
    }

    companion object {
        fun parse(
            data: ByteSlice,
            expectedRecordSize: Int,
        ): LookupBinarySearch? {
            val recordSize = data.readUInt16(0) ?: return null
            val count = data.readUInt16(2) ?: return null
            if (recordSize != expectedRecordSize || count == 0) return null
            val records = data.slice(10, count * recordSize) ?: return null
            val all = LookupBinarySearch(records, recordSize, count)
            val size = if (all.lastIsTermination(count)) count - 1 else count
            if (size == 0) return null
            return LookupBinarySearch(records, recordSize, size)
        }
    }
}

internal fun Long.toIntOrNull(): Int? = if (this <= Int.MAX_VALUE) toInt() else null
