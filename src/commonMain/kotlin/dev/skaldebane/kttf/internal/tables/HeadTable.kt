package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.FontBounds
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class HeadTable private constructor(
    internal val unitsPerEm: Int,
    internal val bounds: FontBounds,
    internal val longLocationOffsets: Boolean,
) {
    internal companion object {
        internal fun parse(data: ByteSlice): HeadTable? {
            if (data.size < 54) return null

            val unitsPerEm = data.readUInt16(18) ?: return null
            if (unitsPerEm !in 16..16384) return null

            val locationFormat = data.readUInt16(50) ?: return null
            val longLocationOffsets =
                when (locationFormat) {
                    0 -> false
                    1 -> true
                    else -> return null
                }

            return HeadTable(
                unitsPerEm = unitsPerEm,
                bounds =
                    FontBounds(
                        xMin = data.readInt16(36) ?: return null,
                        yMin = data.readInt16(38) ?: return null,
                        xMax = data.readInt16(40) ?: return null,
                        yMax = data.readInt16(42) ?: return null,
                    ),
                longLocationOffsets = longLocationOffsets,
            )
        }
    }
}
