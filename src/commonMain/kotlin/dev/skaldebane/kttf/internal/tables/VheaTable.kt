package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class VheaTable private constructor(
    internal val ascender: Int,
    internal val descender: Int,
    internal val lineGap: Int,
    internal val numberOfMetrics: Int,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): VheaTable? {
            if (data.size < 36) return null

            return VheaTable(
                ascender = data.readInt16(4) ?: return null,
                descender = data.readInt16(6) ?: return null,
                lineGap = data.readInt16(8) ?: return null,
                numberOfMetrics = data.readUInt16(34) ?: return null,
            )
        }
    }
}
