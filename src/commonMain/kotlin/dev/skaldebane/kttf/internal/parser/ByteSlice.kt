package dev.skaldebane.kttf.internal.parser

internal class ByteSlice private constructor(
    internal val data: ByteArray,
    internal val start: Int,
    internal val end: Int,
) {
    internal val size: Int
        get() = end - start

    internal fun readByte(offset: Int): Byte? {
        if (!contains(offset, 1)) return null
        return data[start + offset]
    }

    internal fun readUInt8(offset: Int): Int? = readByte(offset)?.toInt()?.and(0xFF)

    internal fun readInt8(offset: Int): Int? = readByte(offset)?.toInt()

    internal fun readUInt16(offset: Int): Int? {
        if (!contains(offset, 2)) return null
        val index = start + offset
        return ((data[index].toInt() and 0xFF) shl 8) or (data[index + 1].toInt() and 0xFF)
    }

    internal fun readInt16(offset: Int): Int? {
        val value = readUInt16(offset) ?: return null
        return if (value and 0x8000 == 0) value else value - 0x10000
    }

    internal fun readUInt24(offset: Int): Int? {
        if (!contains(offset, 3)) return null
        val index = start + offset
        return ((data[index].toInt() and 0xFF) shl 16) or
            ((data[index + 1].toInt() and 0xFF) shl 8) or
            (data[index + 2].toInt() and 0xFF)
    }

    internal fun readUInt32(offset: Int): Long? {
        if (!contains(offset, 4)) return null
        val index = start + offset
        return ((data[index].toLong() and 0xFF) shl 24) or
            ((data[index + 1].toLong() and 0xFF) shl 16) or
            ((data[index + 2].toLong() and 0xFF) shl 8) or
            (data[index + 3].toLong() and 0xFF)
    }

    internal fun readInt32(offset: Int): Int? = readUInt32(offset)?.toInt()

    internal fun slice(
        offset: Int,
        length: Int,
    ): ByteSlice? {
        if (!contains(offset, length)) return null
        val absoluteStart = start + offset
        return ByteSlice(data, absoluteStart, absoluteStart + length)
    }

    internal fun sliceFrom(offset: Int): ByteSlice? {
        if (offset < 0 || offset > size) return null
        return slice(offset, size - offset)
    }

    internal fun contains(
        offset: Int,
        length: Int,
    ): Boolean {
        if (offset < 0 || length < 0 || length > size) return false
        return offset <= size - length
    }

    internal companion object {
        internal fun of(data: ByteArray): ByteSlice = ByteSlice(data, 0, data.size)
    }
}
