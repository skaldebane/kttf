package dev.skaldebane.kttf.internal.parser

internal class FixedRecordArray private constructor(
    private val bytes: ByteSlice,
    internal val size: Int,
    internal val recordSize: Int,
) {
    internal operator fun get(index: Int): ByteSlice? {
        if (index !in 0 until size) return null
        return bytes.slice(index * recordSize, recordSize)
    }

    internal companion object {
        internal fun create(
            bytes: ByteSlice,
            count: Int,
            recordSize: Int,
        ): FixedRecordArray? {
            if (count < 0 || recordSize <= 0) return null
            if (count > bytes.size / recordSize) return null
            val content = bytes.slice(0, count * recordSize) ?: return null
            return FixedRecordArray(content, count, recordSize)
        }
    }
}
