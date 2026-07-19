package dev.skaldebane.kttf

import kotlin.jvm.JvmInline

/** A four-byte OpenType table tag. */
@JvmInline
public value class Tag private constructor(
    private val packed: Int,
) {
    internal val value: Int
        get() = packed

    /** This tag's unsigned packed 32-bit value represented as a [Long]. */
    public val packedValue: Long
        get() = packed.toLong() and 0xFFFF_FFFFL

    public val isNull: Boolean
        get() = packed == 0

    /** Returns the four encoded bytes. */
    public fun toByteArray(): ByteArray =
        byteArrayOf(
            (packed ushr 24).toByte(),
            (packed ushr 16).toByte(),
            (packed ushr 8).toByte(),
            packed.toByte(),
        )

    /** Returns the four one-byte characters. */
    public fun toCharArray(): CharArray =
        charArrayOf(
            ((packed ushr 24) and 0xFF).toChar(),
            ((packed ushr 16) and 0xFF).toChar(),
            ((packed ushr 8) and 0xFF).toChar(),
            (packed and 0xFF).toChar(),
        )

    /** Returns this tag as four characters. This operation allocates a [String]. */
    override fun toString(): String = toCharArray().concatToString()

    public companion object {
        /** Creates a tag from exactly four one-byte characters. */
        public fun parse(value: String): Tag =
            parseOrNull(value)
                ?: throw IllegalArgumentException("An OpenType tag must contain four one-byte characters")

        /** Creates a tag from exactly four one-byte characters, or returns null. */
        public fun parseOrNull(value: String): Tag? {
            if (value.length != 4) return null
            return ofOrNull(value[0], value[1], value[2], value[3])
        }

        /** Creates a tag from exactly four bytes. */
        public fun fromBytes(value: ByteArray): Tag {
            require(value.size == 4) { "An OpenType tag must contain exactly four bytes" }
            return Tag(
                ((value[0].toInt() and 0xFF) shl 24) or
                    ((value[1].toInt() and 0xFF) shl 16) or
                    ((value[2].toInt() and 0xFF) shl 8) or
                    (value[3].toInt() and 0xFF),
            )
        }

        /** Pads short input with spaces and ignores bytes after the fourth. */
        public fun fromBytesLossy(value: ByteArray): Tag {
            if (value.isEmpty()) return Tag(0)
            val bytes = ByteArray(4) { ' '.code.toByte() }
            value.copyInto(bytes, endIndex = minOf(value.size, bytes.size))
            return fromBytes(bytes)
        }

        /** Creates a tag from four one-byte characters. */
        public fun of(
            first: Char,
            second: Char,
            third: Char,
            fourth: Char,
        ): Tag =
            ofOrNull(first, second, third, fourth)
                ?: throw IllegalArgumentException("OpenType tag characters must be in U+0000..U+00FF")

        internal fun fromInt(value: Int): Tag = Tag(value)

        private fun ofOrNull(
            first: Char,
            second: Char,
            third: Char,
            fourth: Char,
        ): Tag? {
            if (first.code > 0xFF || second.code > 0xFF || third.code > 0xFF || fourth.code > 0xFF) return null

            return Tag(
                (first.code shl 24) or
                    (second.code shl 16) or
                    (third.code shl 8) or
                    fourth.code,
            )
        }
    }
}
