package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal enum class RasterImageFormat {
    PNG,
    BITMAP_MONO,
    BITMAP_MONO_PACKED,
    BITMAP_GRAY_2,
    BITMAP_GRAY_2_PACKED,
    BITMAP_GRAY_4,
    BITMAP_GRAY_4_PACKED,
    BITMAP_GRAY_8,
    BITMAP_PREMUL_BGRA_32,
}

internal class RasterImageData internal constructor(
    private val bytes: ByteSlice,
) {
    internal val size: Int
        get() = bytes.size

    internal operator fun get(index: Int): Byte? = bytes.readByte(index)

    internal fun copyInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): Boolean {
        if (destinationOffset < 0 || bytes.size > destination.size - destinationOffset) return false
        bytes.data.copyInto(destination, destinationOffset, bytes.start, bytes.end)
        return true
    }
}

internal data class RasterGlyphImage(
    internal val x: Int,
    internal val y: Int,
    internal val width: Int,
    internal val height: Int,
    internal val pixelsPerEm: Int,
    internal val format: RasterImageFormat,
    internal val data: RasterImageData,
)
