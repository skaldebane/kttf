package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal data class KerxPair(
    internal val leftGlyph: Int,
    internal val rightGlyph: Int,
    internal val value: Int,
)

internal class KerxPairs internal constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal operator fun get(index: Int): KerxPair? {
        if (index !in 0 until size) return null
        val offset = index * 6
        val pair = data.readUInt32(offset) ?: return null
        return KerxPair(
            leftGlyph = (pair ushr 16).toInt(),
            rightGlyph = (pair and 0xFFFF).toInt(),
            value = data.readInt16(offset + 4) ?: return null,
        )
    }
}

internal sealed interface KerxFormat {
    fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int?
}

internal class KerxFormat0 internal constructor(
    internal val pairs: KerxPairs,
) : KerxFormat {
    override fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? {
        if (leftGlyph !in 0..0xFFFF || rightGlyph !in 0..0xFFFF) return null
        val needle = (leftGlyph.toLong() shl 16) or rightGlyph.toLong()
        var low = 0
        var high = pairs.size - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val pair = pairs[middle] ?: return null
            val value = (pair.leftGlyph.toLong() shl 16) or pair.rightGlyph.toLong()
            when {
                needle < value -> high = middle - 1
                needle > value -> low = middle + 1
                else -> return pair.value
            }
        }
        return null
    }
}

internal data class KerxEntryData(
    internal val actionIndex: Int,
)

internal class KerxFormat1 internal constructor(
    internal val stateTable: AatExtendedStateTable<KerxEntryData>,
    private val actionsData: ByteSlice,
) : KerxFormat {
    internal val actionCount: Int
        get() = actionsData.size / 2

    override fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? = null

    internal fun actionKerning(actionIndex: Int): Int? {
        if (actionIndex < 0 || actionIndex > Int.MAX_VALUE / 2) return null
        return actionsData.readInt16(actionIndex * 2)
    }
}

internal class KerxFormat2 internal constructor(
    private val data: ByteSlice,
) : KerxFormat {
    override fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? {
        if (leftGlyph !in 0..0xFFFF || rightGlyph !in 0..0xFFFF) return null
        val leftOffset = data.readUInt32(4)?.toIntOrNull()?.minusHeader() ?: return null
        val rightOffset = data.readUInt32(8)?.toIntOrNull()?.minusHeader() ?: return null
        val arrayOffset = data.readUInt32(12)?.toIntOrNull()?.minusHeader() ?: return null
        val leftClass = format2Class(leftGlyph, leftOffset, data) ?: 0
        val rightClass = format2Class(rightGlyph, rightOffset, data) ?: 0
        if (leftClass < arrayOffset) return null
        return data.readInt16(leftClass + rightClass - HEADER_SIZE)
    }
}

internal class KerxAnchorPoints internal constructor(
    private val data: ByteSlice,
) {
    internal val size: Int
        get() = data.size / 4

    internal fun get(actionIndex: Int): Pair<Int, Int>? {
        if (actionIndex < 0 || actionIndex > Int.MAX_VALUE / 4) return null
        val offset = actionIndex * 4
        return Pair(data.readUInt16(offset) ?: return null, data.readUInt16(offset + 2) ?: return null)
    }
}

internal class KerxFormat4 internal constructor(
    internal val stateTable: AatExtendedStateTable<KerxEntryData>,
    internal val anchorPoints: KerxAnchorPoints,
) : KerxFormat {
    override fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? = null
}

internal class KerxFormat6 internal constructor(
    private val data: ByteSlice,
    private val numberOfGlyphs: Int,
) : KerxFormat {
    override fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? {
        if (leftGlyph !in 0..0xFFFF || rightGlyph !in 0..0xFFFF) return null
        val flags = data.readUInt32(0) ?: return null
        val rowOffset = data.readUInt32(8)?.toIntOrNull()?.minusHeader() ?: return null
        val columnOffset = data.readUInt32(12)?.toIntOrNull()?.minusHeader() ?: return null
        val arrayOffset = data.readUInt32(16)?.toIntOrNull()?.minusHeader() ?: return null
        val vectorOffset = data.readUInt32(20)?.toIntOrNull()?.minusHeader() ?: return null
        val rowData = data.sliceFrom(rowOffset) ?: return null
        val columnData = data.sliceFrom(columnOffset) ?: return null
        val arrayData = data.sliceFrom(arrayOffset) ?: return null
        val vectorData = data.sliceFrom(vectorOffset) ?: return null
        val left = AatLookup.parse(numberOfGlyphs, rowData)?.value(leftGlyph) ?: 0
        val right = AatLookup.parse(numberOfGlyphs, columnData)?.value(rightGlyph) ?: 0
        val index = left + right
        return if (flags and 1L != 0L) {
            val arrayIndex = index.toLong() * 4
            if (arrayIndex > Int.MAX_VALUE) return null
            val offset = arrayData.readUInt32(arrayIndex.toInt())?.toIntOrNull() ?: return null
            vectorData.readInt16(offset)
        } else {
            val arrayIndex = index.toLong() * 2
            if (arrayIndex > Int.MAX_VALUE) return null
            val offset = arrayData.readUInt16(arrayIndex.toInt()) ?: return null
            vectorData.readInt16(offset)
        }
    }
}

internal data class KerxSubtable(
    internal val horizontal: Boolean,
    internal val variable: Boolean,
    internal val hasCrossStream: Boolean,
    internal val hasStateMachine: Boolean,
    internal val tupleCount: Long,
    internal val format: KerxFormat,
) {
    internal fun glyphsKerning(
        leftGlyph: Int,
        rightGlyph: Int,
    ): Int? = format.glyphsKerning(leftGlyph, rightGlyph)
}

internal class KerxSubtables internal constructor(
    private val numberOfGlyphs: Int,
    private val count: Long,
    private val data: ByteSlice,
) : Iterable<KerxSubtable> {
    override fun iterator(): Iterator<KerxSubtable> =
        object : Iterator<KerxSubtable> {
            private var index = 0L
            private var offset = 0
            private var next: KerxSubtable? = null
            private var prepared = false

            override fun hasNext(): Boolean {
                prepare()
                return next != null
            }

            override fun next(): KerxSubtable {
                prepare()
                val result = next ?: throw NoSuchElementException()
                next = null
                prepared = false
                return result
            }

            private fun prepare() {
                if (prepared) return
                prepared = true
                if (index == count || offset >= data.size) return
                val length = data.readUInt32(offset)?.toIntOrNull() ?: return
                if (length < HEADER_SIZE) return
                val coverage = data.readUInt8(offset + 4) ?: return
                val rawFormat = data.readUInt8(offset + 7) ?: return
                val tupleCount = data.readUInt32(offset + 8) ?: return
                val body = data.slice(offset + HEADER_SIZE, length - HEADER_SIZE) ?: return
                val format = parseFormat(rawFormat, body) ?: return
                next =
                    KerxSubtable(
                        horizontal = coverage and 0x80 == 0,
                        variable = coverage and 0x20 != 0,
                        hasCrossStream = coverage and 0x40 != 0,
                        hasStateMachine = rawFormat == 1 || rawFormat == 4,
                        tupleCount = tupleCount,
                        format = format,
                    )
                offset += length
                index++
            }

            private fun parseFormat(
                rawFormat: Int,
                body: ByteSlice,
            ): KerxFormat? =
                when (rawFormat) {
                    0 -> parseFormat0(body)
                    1 -> parseFormat1(body)
                    2 -> KerxFormat2(body)
                    4 -> parseFormat4(body)
                    6 -> KerxFormat6(body, numberOfGlyphs)
                    else -> null
                }

            private fun parseFormat0(data: ByteSlice): KerxFormat0? {
                val count = data.readUInt32(0)?.toIntOrNull() ?: return null
                if (count > (data.size - 16).coerceAtLeast(0) / 6) return null
                val pairs = data.slice(16, count * 6) ?: return null
                return KerxFormat0(KerxPairs(pairs, count))
            }

            private fun parseFormat1(data: ByteSlice): KerxFormat1? {
                val state =
                    AatExtendedStateTable.parse(numberOfGlyphs, data, 2) { extra ->
                        KerxEntryData(extra.readUInt16(0) ?: return@parse null)
                    } ?: return null
                val actionsOffset = data.readUInt32(AatExtendedStateTable.HEADER_SIZE)?.toIntOrNull() ?: return null
                return KerxFormat1(state, data.sliceFrom(actionsOffset) ?: return null)
            }

            private fun parseFormat4(data: ByteSlice): KerxFormat4? {
                val state =
                    AatExtendedStateTable.parse(numberOfGlyphs, data, 2) { extra ->
                        KerxEntryData(extra.readUInt16(0) ?: return@parse null)
                    } ?: return null
                val flags = data.readUInt32(AatExtendedStateTable.HEADER_SIZE) ?: return null
                val actionType = ((flags and 0xC000_0000L) ushr 30).toInt()
                if (actionType != 1) return null
                val pointsOffset = (flags and 0x00FF_FFFFL).toInt()
                val points = data.sliceFrom(pointsOffset) ?: return null
                return KerxFormat4(state, KerxAnchorPoints(points))
            }
        }
}

internal class KerxTable private constructor(
    internal val subtables: KerxSubtables,
) {
    internal companion object {
        internal fun parse(
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): KerxTable? {
            if (numberOfGlyphs !in 1..0xFFFF) return null
            val count = data.readUInt32(4) ?: return null
            return KerxTable(KerxSubtables(numberOfGlyphs, count, data.sliceFrom(8) ?: return null))
        }
    }
}

private const val HEADER_SIZE: Int = 12

private fun Int.minusHeader(): Int? = if (this >= HEADER_SIZE) this - HEADER_SIZE else null

private fun format2Class(
    glyphId: Int,
    offset: Int,
    data: ByteSlice,
): Int? {
    val firstGlyph = data.readUInt16(offset) ?: return null
    val index = glyphId - firstGlyph
    if (index < 0) return null
    val count = data.readUInt16(offset + 2) ?: return null
    if (index >= count) return null
    return data.readUInt16(offset + 4 + index * 2)
}
