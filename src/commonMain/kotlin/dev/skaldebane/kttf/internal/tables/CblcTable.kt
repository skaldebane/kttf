package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal enum class BitmapMetricsFormat {
    SMALL,
    BIG,
    SHARED,
}

internal sealed interface BitmapDataFormat {
    data class ByteAligned(
        internal val bitDepth: Int,
    ) : BitmapDataFormat

    data class BitAligned(
        internal val bitDepth: Int,
    ) : BitmapDataFormat

    data object Png : BitmapDataFormat
}

internal data class BitmapMetrics(
    internal val x: Int = 0,
    internal val y: Int = 0,
    internal val width: Int = 0,
    internal val height: Int = 0,
)

internal data class BitmapLocation(
    internal val metricsFormat: BitmapMetricsFormat,
    internal val dataFormat: BitmapDataFormat,
    internal val offset: Int,
    internal val metrics: BitmapMetrics,
    internal val pixelsPerEm: Int,
)

internal class CblcTable private constructor(
    private val data: ByteSlice,
) {
    internal fun get(
        glyphId: Int,
        pixelsPerEm: Int,
    ): BitmapLocation? {
        if (glyphId !in 0..0xFFFF || pixelsPerEm !in 0..0xFFFF) return null
        val sizeTable = selectBitmapSizeTable(glyphId, pixelsPerEm) ?: return null
        val subtable = selectIndexSubtable(sizeTable, glyphId) ?: return null
        return readLocation(sizeTable, subtable, glyphId)
    }

    private fun selectBitmapSizeTable(
        glyphId: Int,
        pixelsPerEm: Int,
    ): BitmapSizeTable? {
        val count = data.readUInt32(4) ?: return null
        val availableRecords = (data.size - SIZE_TABLES_OFFSET).coerceAtLeast(0) / BITMAP_SIZE_TABLE_SIZE
        if (count > availableRecords.toLong()) return null

        var selectedIndex = -1
        var selectedPpem = 0
        var selectedBitDepth = 0
        var index = 0
        while (index < count.toInt()) {
            val offset = SIZE_TABLES_OFFSET + index * BITMAP_SIZE_TABLE_SIZE
            val startGlyphId = data.readUInt16(offset + 40) ?: return null
            val endGlyphId = data.readUInt16(offset + 42) ?: return null
            val ppem = data.readUInt8(offset + 44) ?: return null
            val bitDepth = data.readUInt8(offset + 46) ?: return null
            if (
                glyphId in startGlyphId..endGlyphId &&
                (
                    (pixelsPerEm <= ppem && ppem < selectedPpem) ||
                        (pixelsPerEm > selectedPpem && ppem > selectedPpem)
                )
            ) {
                selectedIndex = index
                selectedPpem = ppem
                selectedBitDepth = bitDepth
            }
            index++
        }

        if (selectedIndex < 0) return null
        val selectedOffset = SIZE_TABLES_OFFSET + selectedIndex * BITMAP_SIZE_TABLE_SIZE
        val arrayOffset = data.readUInt32(selectedOffset)?.toIndexOrNull() ?: return null
        val subtableCount = data.readUInt32(selectedOffset + 8) ?: return null
        return BitmapSizeTable(arrayOffset, subtableCount, selectedPpem, selectedBitDepth)
    }

    private fun selectIndexSubtable(
        sizeTable: BitmapSizeTable,
        glyphId: Int,
    ): IndexSubtable? {
        var index = 0L
        while (index < sizeTable.subtableCount) {
            val recordOffset = sizeTable.arrayOffset.toLong() + index * INDEX_SUBTABLE_RECORD_SIZE
            if (recordOffset > Int.MAX_VALUE) return null
            val offset = recordOffset.toInt()
            val startGlyphId = data.readUInt16(offset) ?: return null
            val endGlyphId = data.readUInt16(offset + 2) ?: return null
            val relativeOffset = data.readUInt32(offset + 4) ?: return null
            if (glyphId in startGlyphId..endGlyphId) {
                val absoluteOffset = sizeTable.arrayOffset.toLong() + relativeOffset
                return IndexSubtable(startGlyphId, absoluteOffset.toIndexOrNull() ?: return null)
            }
            index++
        }
        return null
    }

    private fun readLocation(
        sizeTable: BitmapSizeTable,
        subtable: IndexSubtable,
        glyphId: Int,
    ): BitmapLocation? {
        val indexFormat = data.readUInt16(subtable.offset) ?: return null
        val imageFormat = data.readUInt16(subtable.offset + 2) ?: return null
        val baseImageOffset = data.readUInt32(subtable.offset + 4) ?: return null
        val format = bitmapFormat(imageFormat, sizeTable.bitDepth) ?: return null
        val glyphDifference = glyphId - subtable.startGlyphId
        var metrics = BitmapMetrics()

        val relativeOffset =
            when (indexFormat) {
                1 -> {
                    val entryOffset = subtable.offset.toLong() + 8L + glyphDifference.toLong() * 4L
                    data.readUInt32(entryOffset.toIndexOrNull() ?: return null) ?: return null
                }

                2 -> {
                    val imageSize = data.readUInt32(subtable.offset + 8) ?: return null
                    metrics = data.readSharedMetrics(subtable.offset + 12) ?: return null
                    imageSize.safeProduct(glyphDifference.toLong()) ?: return null
                }

                3 -> {
                    val entryOffset = subtable.offset.toLong() + 8L + glyphDifference.toLong() * 2L
                    data.readUInt16(entryOffset.toIndexOrNull() ?: return null)?.toLong() ?: return null
                }

                4 -> {
                    sparseOffset(subtable.offset, glyphId) ?: return null
                }

                5 -> {
                    val imageSize = data.readUInt32(subtable.offset + 8) ?: return null
                    metrics = data.readBigMetrics(subtable.offset + 12) ?: return null
                    val countOffset = subtable.offset + 20
                    val glyphCount = data.readUInt32(countOffset) ?: return null
                    val glyphArrayOffset = countOffset + 4
                    val index = binarySearchGlyphs(glyphArrayOffset, glyphCount, glyphId) ?: return null
                    imageSize.safeProduct(index) ?: return null
                }

                else -> {
                    return null
                }
            }

        val imageOffset = (baseImageOffset + relativeOffset).toIndexOrNull() ?: return null
        return BitmapLocation(
            metricsFormat = format.metrics,
            dataFormat = format.data,
            offset = imageOffset,
            metrics = metrics,
            pixelsPerEm = sizeTable.pixelsPerEm,
        )
    }

    private fun sparseOffset(
        subtableOffset: Int,
        glyphId: Int,
    ): Long? {
        val glyphCount = data.readUInt32(subtableOffset + 8) ?: return null
        if (glyphCount == UINT32_MAX) return null
        val pairCount = glyphCount + 1L
        val pairsOffset = subtableOffset.toLong() + 12L
        val byteCount = pairCount * 4L
        if (pairsOffset > Int.MAX_VALUE || byteCount > Int.MAX_VALUE) return null
        val pairs = data.slice(pairsOffset.toInt(), byteCount.toInt()) ?: return null
        var index = 0L
        while (index < pairCount) {
            val offset = (index * 4L).toInt()
            if (pairs.readUInt16(offset) == glyphId) return pairs.readUInt16(offset + 2)?.toLong()
            index++
        }
        return null
    }

    private fun binarySearchGlyphs(
        arrayOffset: Int,
        count: Long,
        glyphId: Int,
    ): Long? {
        if (count > Int.MAX_VALUE / 2L) return null
        data.slice(arrayOffset, (count * 2L).toInt()) ?: return null
        if (count == 0L) return null
        var size = count
        var base = 0L
        while (size > 1L) {
            val half = size / 2L
            val middle = base + half
            val value = data.readUInt16(arrayOffset + (middle * 2L).toInt()) ?: return null
            if (value <= glyphId) base = middle
            size -= half
        }
        return base.takeIf { data.readUInt16(arrayOffset + (base * 2L).toInt()) == glyphId }
    }

    internal companion object {
        internal fun parse(data: ByteSlice): CblcTable = CblcTable(data)

        private const val SIZE_TABLES_OFFSET: Int = 8
        private const val BITMAP_SIZE_TABLE_SIZE: Int = 48
        private const val INDEX_SUBTABLE_RECORD_SIZE: Long = 8L
        private const val UINT32_MAX: Long = 0xFFFF_FFFFL
    }
}

private data class BitmapSizeTable(
    val arrayOffset: Int,
    val subtableCount: Long,
    val pixelsPerEm: Int,
    val bitDepth: Int,
)

private data class IndexSubtable(
    val startGlyphId: Int,
    val offset: Int,
)

private data class BitmapFormat(
    val metrics: BitmapMetricsFormat,
    val data: BitmapDataFormat,
)

private fun bitmapFormat(
    imageFormat: Int,
    bitDepth: Int,
): BitmapFormat? =
    when (imageFormat) {
        1 -> BitmapFormat(BitmapMetricsFormat.SMALL, BitmapDataFormat.ByteAligned(bitDepth))
        2 -> BitmapFormat(BitmapMetricsFormat.SMALL, BitmapDataFormat.BitAligned(bitDepth))
        5 -> BitmapFormat(BitmapMetricsFormat.SHARED, BitmapDataFormat.BitAligned(bitDepth))
        6 -> BitmapFormat(BitmapMetricsFormat.BIG, BitmapDataFormat.ByteAligned(bitDepth))
        7 -> BitmapFormat(BitmapMetricsFormat.BIG, BitmapDataFormat.BitAligned(bitDepth))
        17 -> BitmapFormat(BitmapMetricsFormat.SMALL, BitmapDataFormat.Png)
        18 -> BitmapFormat(BitmapMetricsFormat.BIG, BitmapDataFormat.Png)
        19 -> BitmapFormat(BitmapMetricsFormat.SHARED, BitmapDataFormat.Png)
        else -> null
    }

private fun ByteSlice.readSharedMetrics(offset: Int): BitmapMetrics? =
    BitmapMetrics(
        height = readUInt8(offset) ?: return null,
        width = readUInt8(offset + 1) ?: return null,
        x = readInt8(offset + 2) ?: return null,
        y = readInt8(offset + 3) ?: return null,
    )

private fun ByteSlice.readBigMetrics(offset: Int): BitmapMetrics? {
    val metrics = readSharedMetrics(offset) ?: return null
    if (!contains(offset, 8)) return null
    return metrics
}

private fun Long.safeProduct(value: Long): Long? {
    if (value < 0 || (value != 0L && this > Long.MAX_VALUE / value)) return null
    return this * value
}

private fun Long.toIndexOrNull(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null
