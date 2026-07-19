package dev.skaldebane.kttf.internal.tables.cff

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.Stream

internal class CffHeader(
    internal val majorVersion: Int,
    internal val minorVersion: Int,
    internal val size: Int,
    internal val remainder: ByteSlice,
)

internal fun parseCffHeader(
    stream: Stream,
    minimumSize: Int,
): CffHeader? {
    if (minimumSize < CFF_COMMON_HEADER_SIZE) return null

    val majorVersion = stream.readUInt8() ?: return null
    val minorVersion = stream.readUInt8() ?: return null
    val size = stream.readUInt8() ?: return null
    if (size < minimumSize) return null
    val remainder = stream.readBytes(size - CFF_COMMON_HEADER_SIZE) ?: return null
    return CffHeader(majorVersion, minorVersion, size, remainder)
}

private const val CFF_COMMON_HEADER_SIZE: Int = 3
