package dev.skaldebane.kttf.internal.parser

internal class Stream private constructor(
    private val bytes: ByteSlice,
    internal var offset: Int,
) {
    internal val atEnd: Boolean
        get() = offset >= bytes.size

    internal fun readUInt8(): Int? = read(1, bytes::readUInt8)

    internal fun readInt8(): Int? = read(1, bytes::readInt8)

    internal fun readUInt16(): Int? = read(2, bytes::readUInt16)

    internal fun readInt16(): Int? = read(2, bytes::readInt16)

    internal fun readUInt24(): Int? = read(3, bytes::readUInt24)

    internal fun readUInt32(): Long? = read(4, bytes::readUInt32)

    internal fun readInt32(): Int? = read(4, bytes::readInt32)

    internal fun readBytes(length: Int): ByteSlice? {
        val result = bytes.slice(offset, length) ?: return null
        offset += length
        return result
    }

    internal fun advance(length: Int): Boolean {
        if (!bytes.contains(offset, length)) return false
        offset += length
        return true
    }

    internal fun tail(): ByteSlice? = bytes.sliceFrom(offset)

    private inline fun <T> read(
        width: Int,
        operation: (Int) -> T?,
    ): T? {
        val result = operation(offset) ?: return null
        offset += width
        return result
    }

    internal companion object {
        internal fun atStart(bytes: ByteSlice): Stream = Stream(bytes, 0)

        internal fun at(
            bytes: ByteSlice,
            offset: Int,
        ): Stream? {
            if (!bytes.contains(offset, 0)) return null
            return Stream(bytes, offset)
        }
    }
}
