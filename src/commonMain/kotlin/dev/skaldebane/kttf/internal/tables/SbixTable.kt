package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class SbixStrike internal constructor(
    private val data: ByteSlice,
    private val offsets: ByteSlice,
    internal val pixelsPerEm: Int,
    internal val pixelsPerInch: Int,
) {
    internal val size: Int
        get() = offsets.size / 4 - 1

    internal fun get(glyphId: Int): RasterGlyphImage? = get(glyphId, 0)

    private fun get(
        glyphId: Int,
        depth: Int,
    ): RasterGlyphImage? {
        if (depth == MAX_DUPLICATE_DEPTH || glyphId !in 0 until size) return null
        val start = offsets.readUInt32(glyphId * 4)?.toIndexOrNull() ?: return null
        val end = offsets.readUInt32((glyphId + 1) * 4)?.toIndexOrNull() ?: return null
        if (start == end || end < start || end - start < GLYPH_HEADER_SIZE) return null
        val glyphData = data.slice(start, end - start) ?: return null
        val x = glyphData.readInt16(0) ?: return null
        val y = glyphData.readInt16(2) ?: return null
        val imageType = glyphData.readUInt32(4) ?: return null
        val imageBytes = glyphData.sliceFrom(GLYPH_HEADER_SIZE) ?: return null

        if (imageType == DUPE_TAG) {
            val duplicateGlyph = imageBytes.readUInt16(0) ?: return null
            return get(duplicateGlyph, depth + 1)
        }
        if (imageType != PNG_TAG) return null

        val width = imageBytes.readUInt32(16)?.toUShortOrNull() ?: return null
        val height = imageBytes.readUInt32(20)?.toUShortOrNull() ?: return null
        return RasterGlyphImage(
            x = x,
            y = y,
            width = width,
            height = height,
            pixelsPerEm = pixelsPerEm,
            format = RasterImageFormat.PNG,
            data = RasterImageData(imageBytes),
        )
    }

    private companion object {
        private const val GLYPH_HEADER_SIZE: Int = 8
        private const val MAX_DUPLICATE_DEPTH: Int = 10
        private const val PNG_TAG: Long = 0x706E_6720L
        private const val DUPE_TAG: Long = 0x6475_7065L
    }
}

internal class SbixStrikes internal constructor(
    private val data: ByteSlice,
    private val offsets: ByteSlice,
    private val glyphOffsetCount: Int,
) {
    internal val size: Int
        get() = offsets.size / 4

    internal operator fun get(index: Int): SbixStrike? {
        if (index !in 0 until size) return null
        val offset = offsets.readUInt32(index * 4)?.toIndexOrNull() ?: return null
        val strikeData = data.sliceFrom(offset) ?: return null
        val pixelsPerEm = strikeData.readUInt16(0) ?: return null
        val pixelsPerInch = strikeData.readUInt16(2) ?: return null
        val byteCount = glyphOffsetCount * 4
        val glyphOffsets = strikeData.slice(4, byteCount) ?: return null
        return SbixStrike(strikeData, glyphOffsets, pixelsPerEm, pixelsPerInch)
    }
}

internal class SbixTable private constructor(
    internal val strikes: SbixStrikes,
) {
    internal fun bestStrike(pixelsPerEm: Int): SbixStrike? {
        if (pixelsPerEm !in 0..0xFFFF) return null
        var selectedIndex = 0
        var selectedPpem = 0
        var index = 0
        while (index < strikes.size) {
            val strike = strikes[index] ?: break
            if (
                (pixelsPerEm <= strike.pixelsPerEm && strike.pixelsPerEm < selectedPpem) ||
                (pixelsPerEm > selectedPpem && strike.pixelsPerEm > selectedPpem)
            ) {
                selectedIndex = index
                selectedPpem = strike.pixelsPerEm
            }
            index++
        }
        return strikes[selectedIndex]
    }

    internal companion object {
        internal fun parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): SbixTable? {
            if (numberOfGlyphs !in 1 until 0xFFFF) return null
            if (data.readUInt16(0) != 1) return null
            data.readUInt16(2) ?: return null
            val strikeCount = data.readUInt32(4) ?: return null
            if (strikeCount == 0L || strikeCount > Int.MAX_VALUE / 4L) return null
            val offsets = data.slice(8, (strikeCount * 4L).toInt()) ?: return null
            return SbixTable(SbixStrikes(data, offsets, numberOfGlyphs + 1))
        }
    }
}

private fun Long.toIndexOrNull(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

private fun Long.toUShortOrNull(): Int? = if (this in 0..0xFFFFL) toInt() else null
