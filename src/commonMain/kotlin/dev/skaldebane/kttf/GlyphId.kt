package dev.skaldebane.kttf

import kotlin.jvm.JvmInline

/** A glyph identifier encoded by OpenType as an unsigned 16-bit value. */
@JvmInline
public value class GlyphId(
    public val value: Int,
) {
    init {
        require(value in 0..0xFFFF) { "Glyph ID must be in 0..65535" }
    }
}
