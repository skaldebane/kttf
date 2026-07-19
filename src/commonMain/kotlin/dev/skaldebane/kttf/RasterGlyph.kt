package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.RasterGlyphImage
import dev.skaldebane.kttf.internal.tables.RasterImageData
import dev.skaldebane.kttf.internal.tables.RasterImageFormat

/** Encoding of an image returned by [FontFace.rasterGlyph]. */
public enum class GlyphRasterFormat {
    Png,
    Monochrome,
    PackedMonochrome,
    Gray2,
    PackedGray2,
    Gray4,
    PackedGray4,
    Gray8,
    PremultipliedBgra32,
}

/** A bounded, zero-copy view of encoded raster data. */
public class GlyphRasterData internal constructor(
    private val data: RasterImageData,
) {
    public val size: Int
        get() = data.size

    public operator fun get(index: Int): Byte =
        data[index] ?: throw IndexOutOfBoundsException("index: $index, size: $size")

    public fun copyInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): ByteArray {
        if (!data.copyInto(destination, destinationOffset)) throw IndexOutOfBoundsException("Raster data does not fit")
        return destination
    }
}

/** Encoded bitmap strike image and its font-space placement. */
public data class GlyphRasterImage(
    public val x: Int,
    public val y: Int,
    public val width: Int,
    public val height: Int,
    public val pixelsPerEm: Int,
    public val format: GlyphRasterFormat,
    public val data: GlyphRasterData,
)

internal fun RasterGlyphImage.toPublic(): GlyphRasterImage =
    GlyphRasterImage(x, y, width, height, pixelsPerEm, format.toPublic(), GlyphRasterData(data))

private fun RasterImageFormat.toPublic(): GlyphRasterFormat =
    when (this) {
        RasterImageFormat.PNG -> GlyphRasterFormat.Png
        RasterImageFormat.BITMAP_MONO -> GlyphRasterFormat.Monochrome
        RasterImageFormat.BITMAP_MONO_PACKED -> GlyphRasterFormat.PackedMonochrome
        RasterImageFormat.BITMAP_GRAY_2 -> GlyphRasterFormat.Gray2
        RasterImageFormat.BITMAP_GRAY_2_PACKED -> GlyphRasterFormat.PackedGray2
        RasterImageFormat.BITMAP_GRAY_4 -> GlyphRasterFormat.Gray4
        RasterImageFormat.BITMAP_GRAY_4_PACKED -> GlyphRasterFormat.PackedGray4
        RasterImageFormat.BITMAP_GRAY_8 -> GlyphRasterFormat.Gray8
        RasterImageFormat.BITMAP_PREMUL_BGRA_32 -> GlyphRasterFormat.PremultipliedBgra32
    }
