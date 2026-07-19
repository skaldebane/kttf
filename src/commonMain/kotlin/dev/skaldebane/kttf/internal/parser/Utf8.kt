package dev.skaldebane.kttf.internal.parser

internal fun ByteSlice.decodeUtf8(): String? =
    buildString {
        var offset = 0
        while (offset < size) {
            val first = readUInt8(offset) ?: return null
            val codePoint: Int
            val width: Int
            when {
                first <= 0x7F -> {
                    codePoint = first
                    width = 1
                }

                first in 0xC2..0xDF -> {
                    val second = continuation(offset + 1) ?: return null
                    codePoint = ((first and 0x1F) shl 6) or second
                    width = 2
                }

                first in 0xE0..0xEF -> {
                    val secondByte = readUInt8(offset + 1) ?: return null
                    if (secondByte !in 0x80..0xBF) return null
                    if (first == 0xE0 && secondByte < 0xA0) return null
                    if (first == 0xED && secondByte >= 0xA0) return null
                    val third = continuation(offset + 2) ?: return null
                    codePoint = ((first and 0x0F) shl 12) or ((secondByte and 0x3F) shl 6) or third
                    width = 3
                }

                first in 0xF0..0xF4 -> {
                    val secondByte = readUInt8(offset + 1) ?: return null
                    if (secondByte !in 0x80..0xBF) return null
                    if (first == 0xF0 && secondByte < 0x90) return null
                    if (first == 0xF4 && secondByte >= 0x90) return null
                    val third = continuation(offset + 2) ?: return null
                    val fourth = continuation(offset + 3) ?: return null
                    codePoint =
                        ((first and 0x07) shl 18) or
                        ((secondByte and 0x3F) shl 12) or
                        (third shl 6) or
                        fourth
                    width = 4
                }

                else -> {
                    return null
                }
            }

            if (codePoint <= 0xFFFF) {
                append(codePoint.toChar())
            } else {
                val supplementary = codePoint - 0x10000
                append((0xD800 + (supplementary ushr 10)).toChar())
                append((0xDC00 + (supplementary and 0x3FF)).toChar())
            }
            offset += width
        }
    }

private fun ByteSlice.continuation(offset: Int): Int? {
    val value = readUInt8(offset) ?: return null
    return if (value in 0x80..0xBF) value and 0x3F else null
}
