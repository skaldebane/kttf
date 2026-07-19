package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.parser.ByteSlice

/** A bounded, non-copying view of an OpenType table. */
public class FontTable internal constructor(
    public val tag: Tag,
    private val bytes: ByteSlice,
) {
    /** Number of bytes in this table. */
    public val size: Int
        get() = bytes.size

    /** Returns the byte at [index]. */
    public operator fun get(index: Int): Byte =
        bytes.readByte(index) ?: throw IndexOutOfBoundsException("Table byte index $index is outside 0 until $size")

    /**
     * Copies bytes from this table into [destination].
     *
     * This is an explicitly materializing operation; ordinary table lookup and
     * indexed access retain and read the original font bytes.
     */
    public fun copyInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
        startIndex: Int = 0,
        endIndex: Int = size,
    ): ByteArray {
        if (startIndex < 0 || endIndex < startIndex || endIndex > size) {
            throw IndexOutOfBoundsException("Table range [$startIndex, $endIndex) is outside 0 until $size")
        }

        val length = endIndex - startIndex
        if (destinationOffset < 0 || destinationOffset > destination.size - length) {
            throw IndexOutOfBoundsException("Destination cannot hold $length bytes at offset $destinationOffset")
        }

        bytes.data.copyInto(
            destination = destination,
            destinationOffset = destinationOffset,
            startIndex = bytes.start + startIndex,
            endIndex = bytes.start + endIndex,
        )
        return destination
    }
}
