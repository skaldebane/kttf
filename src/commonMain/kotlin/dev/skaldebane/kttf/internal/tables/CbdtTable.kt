package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream

internal class CbdtTable private constructor(
    private val locations: CblcTable,
    private val data: ByteSlice,
) {
    internal fun get(
        glyphId: Int,
        pixelsPerEm: Int,
    ): RasterGlyphImage? {
        val location = locations.get(glyphId, pixelsPerEm) ?: return null
        val stream = Stream.at(data, location.offset) ?: return null
        val metrics = readMetrics(stream, location) ?: return null
        val imageData = readImageData(stream, metrics, location.dataFormat) ?: return null
        return RasterGlyphImage(
            x = metrics.x,
            y = metrics.y - metrics.height,
            width = metrics.width,
            height = metrics.height,
            pixelsPerEm = location.pixelsPerEm,
            format = imageData.first,
            data = RasterImageData(imageData.second),
        )
    }

    private fun readMetrics(
        stream: Stream,
        location: BitmapLocation,
    ): BitmapMetrics? =
        when (location.metricsFormat) {
            BitmapMetricsFormat.SMALL -> {
                val metrics = stream.readBitmapMetrics() ?: return null
                if (!stream.advance(1)) return null
                metrics
            }

            BitmapMetricsFormat.BIG -> {
                val metrics = stream.readBitmapMetrics() ?: return null
                if (!stream.advance(4)) return null
                metrics
            }

            BitmapMetricsFormat.SHARED -> {
                location.metrics
            }
        }

    private fun readImageData(
        stream: Stream,
        metrics: BitmapMetrics,
        format: BitmapDataFormat,
    ): Pair<RasterImageFormat, ByteSlice>? =
        when (format) {
            is BitmapDataFormat.ByteAligned -> {
                val rowLength = (metrics.width.toLong() * format.bitDepth + 7L) / 8L
                val length = rowLength * metrics.height
                val imageFormat = byteAlignedFormat(format.bitDepth) ?: return null
                imageFormat to (stream.readBytes(length.toLengthOrNull() ?: return null) ?: return null)
            }

            is BitmapDataFormat.BitAligned -> {
                val length =
                    (metrics.width.toLong() * metrics.height.toLong() * format.bitDepth.toLong() + 7L) / 8L
                val imageFormat = bitAlignedFormat(format.bitDepth) ?: return null
                imageFormat to (stream.readBytes(length.toLengthOrNull() ?: return null) ?: return null)
            }

            BitmapDataFormat.Png -> {
                val length = stream.readUInt32()?.toLengthOrNull() ?: return null
                RasterImageFormat.PNG to (stream.readBytes(length) ?: return null)
            }
        }

    internal companion object {
        internal fun parse(
            locations: CblcTable,
            data: ByteSlice,
        ): CbdtTable = CbdtTable(locations, data)
    }
}

private fun Stream.readBitmapMetrics(): BitmapMetrics? =
    BitmapMetrics(
        height = readUInt8() ?: return null,
        width = readUInt8() ?: return null,
        x = readInt8() ?: return null,
        y = readInt8() ?: return null,
    )

private fun byteAlignedFormat(bitDepth: Int): RasterImageFormat? =
    when (bitDepth) {
        1 -> RasterImageFormat.BITMAP_MONO
        2 -> RasterImageFormat.BITMAP_GRAY_2
        4 -> RasterImageFormat.BITMAP_GRAY_4
        8 -> RasterImageFormat.BITMAP_GRAY_8
        32 -> RasterImageFormat.BITMAP_PREMUL_BGRA_32
        else -> null
    }

private fun bitAlignedFormat(bitDepth: Int): RasterImageFormat? =
    when (bitDepth) {
        1 -> RasterImageFormat.BITMAP_MONO_PACKED
        2 -> RasterImageFormat.BITMAP_GRAY_2_PACKED
        4 -> RasterImageFormat.BITMAP_GRAY_4_PACKED
        8 -> RasterImageFormat.BITMAP_GRAY_8
        32 -> RasterImageFormat.BITMAP_PREMUL_BGRA_32
        else -> null
    }

private fun Long.toLengthOrNull(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null
