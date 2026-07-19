package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.TestByteBuilder
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

internal fun u16(vararg values: Int): ByteArray = TestByteBuilder().apply { values.forEach(::uint16) }.build()

internal fun i16(vararg values: Int): ByteArray = TestByteBuilder().apply { values.forEach(::int16) }.build()

internal fun u32(vararg values: Long): ByteArray = TestByteBuilder().apply { values.forEach(::uint32) }.build()

internal fun join(vararg arrays: ByteArray): ByteArray = TestByteBuilder().apply { arrays.forEach(::raw) }.build()

internal fun coverage1(vararg glyphs: Int): ByteArray = join(u16(1, glyphs.size), u16(*glyphs))

internal fun coverage2(
    start: Int,
    end: Int,
    value: Int = 0,
): ByteArray = u16(2, 1, start, end, value)

internal fun class1(
    start: Int,
    vararg classes: Int,
): ByteArray = join(u16(1, start, classes.size), u16(*classes))

internal fun class2(
    start: Int,
    end: Int,
    value: Int,
): ByteArray = u16(2, 1, start, end, value)

internal fun slice(data: ByteArray): ByteSlice = ByteSlice.of(data)

internal fun lookup(
    kind: Int,
    subtable: ByteArray,
    flags: Int = 0,
    markSet: Int = 0,
): ByteArray {
    val header = if (flags and 0x10 != 0) u16(kind, flags, 1, 10, markSet) else u16(kind, flags, 1, 8)
    return join(header, subtable)
}

internal fun layoutTable(
    kind: Int,
    subtable: ByteArray,
    lookupFlags: Int = 0,
): ByteArray {
    val script = join(u16(4, 0), u16(0, 0xFFFF, 1, 0))
    val scripts = join(u16(1), "latn".encodeToByteArray(), u16(8), script)
    val feature = u16(0, 1, 0)
    val features = join(u16(1), "test".encodeToByteArray(), u16(8), feature)
    val lookup = lookup(kind, subtable, lookupFlags, 3)
    val lookups = join(u16(1, 4), lookup)
    val scriptsOffset = 10
    val featuresOffset = scriptsOffset + scripts.size
    val lookupsOffset = featuresOffset + features.size
    return join(u16(1, 0, scriptsOffset, featuresOffset, lookupsOffset), scripts, features, lookups)
}
