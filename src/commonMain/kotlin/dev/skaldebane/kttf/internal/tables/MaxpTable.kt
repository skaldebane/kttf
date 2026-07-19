package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class MaxpTable private constructor(
    internal val glyphCount: Int,
) {
    internal companion object {
        private const val VERSION_05: Long = 0x00005000
        private const val VERSION_10: Long = 0x00010000

        internal fun parse(data: ByteSlice): MaxpTable? {
            val version = data.readUInt32(0) ?: return null
            if (version != VERSION_05 && version != VERSION_10) return null

            val glyphCount = data.readUInt16(4) ?: return null
            if (glyphCount == 0) return null
            return MaxpTable(glyphCount)
        }
    }
}
