package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream

internal class CffIndex private constructor(
    private val data: ByteSlice,
    private val offsets: CffIndexOffsets,
) {
    internal val size: Int
        get() = (offsets.size - 1).coerceAtLeast(0)

    internal operator fun get(index: Int): ByteSlice? {
        if (index !in 0 until size) return null

        val start = offsets[index] ?: return null
        val end = offsets[index + 1] ?: return null
        if (start > end || end > data.size.toLong()) return null

        return data.slice(start.toInt(), (end - start).toInt())
    }

    internal companion object {
        internal fun parseCff1(stream: Stream): CffIndex? {
            val count = stream.readUInt16()?.toLong() ?: return null
            return parse(count, stream)
        }

        internal fun parseCff2(stream: Stream): CffIndex? {
            val count = stream.readUInt32() ?: return null
            return parse(count, stream)
        }

        internal fun skipCff1(stream: Stream): Boolean {
            val count = stream.readUInt16()?.toLong() ?: return false
            return skip(count, stream)
        }

        internal fun skipCff2(stream: Stream): Boolean {
            val count = stream.readUInt32() ?: return false
            return skip(count, stream)
        }

        private fun parse(
            count: Long,
            stream: Stream,
        ): CffIndex? {
            if (count == 0L || count == UINT32_MAX) return empty()

            val offsets = readOffsets(count, stream) ?: return null
            val lastOffset = offsets.last() ?: return empty()
            if (lastOffset > Int.MAX_VALUE) return null
            val data = stream.readBytes(lastOffset.toInt()) ?: return null
            return CffIndex(data, offsets)
        }

        private fun skip(
            count: Long,
            stream: Stream,
        ): Boolean {
            if (count == 0L || count == UINT32_MAX) return true

            val offsets = readOffsets(count, stream) ?: return false
            val lastOffset = offsets.last() ?: return true
            if (lastOffset > Int.MAX_VALUE) return false
            return stream.advance(lastOffset.toInt())
        }

        private fun readOffsets(
            count: Long,
            stream: Stream,
        ): CffIndexOffsets? {
            val offsetSize = CffOffsetSize.parse(stream.readUInt8() ?: return null) ?: return null
            val entryCount = count + 1
            val byteCount = entryCount * offsetSize.width
            if (entryCount > UINT32_MAX || byteCount > UINT32_MAX || byteCount > Int.MAX_VALUE) return null

            val bytes = stream.readBytes(byteCount.toInt()) ?: return null
            return CffIndexOffsets(bytes, offsetSize)
        }

        private fun empty(): CffIndex = CffIndex(ByteSlice.of(EMPTY_BYTES), CffIndexOffsets.empty())

        private const val UINT32_MAX: Long = 0xFFFF_FFFFL
        private val EMPTY_BYTES: ByteArray = ByteArray(0)
    }
}

private class CffIndexOffsets(
    private val data: ByteSlice,
    private val offsetSize: CffOffsetSize,
) {
    internal val size: Int
        get() = data.size / offsetSize.width

    internal operator fun get(index: Int): Long? {
        if (index !in 0 until size) return null

        val offset = index * offsetSize.width
        val value =
            when (offsetSize) {
                CffOffsetSize.SIZE_1 -> data.readUInt8(offset)?.toLong()
                CffOffsetSize.SIZE_2 -> data.readUInt16(offset)?.toLong()
                CffOffsetSize.SIZE_3 -> data.readUInt24(offset)?.toLong()
                CffOffsetSize.SIZE_4 -> data.readUInt32(offset)
            } ?: return null

        return if (value == 0L) null else value - 1
    }

    internal fun last(): Long? = if (size == 0) null else get(size - 1)

    internal companion object {
        internal fun empty(): CffIndexOffsets = CffIndexOffsets(ByteSlice.of(ByteArray(0)), CffOffsetSize.SIZE_1)
    }
}

private enum class CffOffsetSize(
    internal val width: Int,
) {
    SIZE_1(1),
    SIZE_2(2),
    SIZE_3(3),
    SIZE_4(4),
    ;

    internal companion object {
        internal fun parse(value: Int): CffOffsetSize? = entries.firstOrNull { it.width == value }
    }
}
