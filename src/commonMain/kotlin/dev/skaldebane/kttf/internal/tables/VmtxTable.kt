package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class VmtxTable private constructor(
    private val delegate: HmtxTable,
) {
    internal fun advance(glyphIndex: Int): Int? = delegate.advance(glyphIndex)

    internal fun sideBearing(glyphIndex: Int): Int? = delegate.sideBearing(glyphIndex)

    internal companion object {
        internal fun parse(
            numberOfMetrics: Int,
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): VmtxTable? = HmtxTable.parse(numberOfMetrics, numberOfGlyphs, data)?.let(::VmtxTable)
    }
}
