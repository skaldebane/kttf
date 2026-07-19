package dev.skaldebane.kttf.internal.tables.layout

import dev.skaldebane.kttf.GlyphId
import dev.skaldebane.kttf.Tag
import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class UInt16View private constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Int? = if (index in 0 until size) data.readUInt16(index * 2) else null

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            offset: Int,
            count: Int,
        ): UInt16View? {
            val length = count.checkedTimes(2) ?: return null
            return UInt16View(data.slice(offset, length) ?: return null, count)
        }
    }
}

internal class UInt32View private constructor(
    private val data: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Int? =
        if (index in 0 until size) data.readUInt32(index * 4)?.toIndex() else null

    internal companion object {
        internal fun parse(
            data: ByteSlice,
            offset: Int,
            count: Int,
        ): UInt32View? {
            val length = count.checkedTimes(4) ?: return null
            return UInt32View(data.slice(offset, length) ?: return null, count)
        }
    }
}

internal class Offset16View<T> internal constructor(
    private val base: ByteSlice,
    private val offsets: UInt16View,
    private val zeroIsNull: Boolean = false,
    private val parse: (ByteSlice) -> T?,
) {
    internal val size: Int
        get() = offsets.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): T? {
        val offset = offsets[index] ?: return null
        if (zeroIsNull && offset == 0) return null
        return parse(base.sliceFrom(offset) ?: return null)
    }
}

internal data class RangeRecord(
    internal val start: Int,
    internal val end: Int,
    internal val value: Int,
)

internal sealed interface Coverage {
    fun indexOf(glyph: GlyphId): Int?

    fun contains(glyph: GlyphId): Boolean = indexOf(glyph) != null

    class Format1(
        private val glyphs: UInt16View,
    ) : Coverage {
        override fun indexOf(glyph: GlyphId): Int? {
            var low = 0
            var high = glyphs.size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val value = glyphs[middle] ?: return null
                when {
                    value < glyph.value -> low = middle + 1
                    value > glyph.value -> high = middle - 1
                    else -> return middle
                }
            }
            return null
        }
    }

    class Format2(
        private val records: ByteSlice,
        internal val size: Int,
    ) : Coverage {
        internal val isEmpty: Boolean get() = size == 0

        internal operator fun get(index: Int): RangeRecord? {
            if (index !in 0 until size) return null
            val offset = index * 6
            return RangeRecord(
                records.readUInt16(offset) ?: return null,
                records.readUInt16(offset + 2) ?: return null,
                records.readUInt16(offset + 4) ?: return null,
            )
        }

        override fun indexOf(glyph: GlyphId): Int? {
            var low = 0
            var high = size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val record = get(middle) ?: return null
                when {
                    glyph.value < record.start -> {
                        high = middle - 1
                    }

                    glyph.value > record.end -> {
                        low = middle + 1
                    }

                    else -> {
                        val result = record.value + glyph.value - record.start
                        return result.takeIf { it <= 0xFFFF }
                    }
                }
            }
            return null
        }
    }

    companion object {
        internal fun parse(data: ByteSlice): Coverage? =
            when (data.readUInt16(0)) {
                1 -> {
                    val count = data.readUInt16(2) ?: return null
                    Format1(UInt16View.parse(data, 4, count) ?: return null)
                }

                2 -> {
                    val count = data.readUInt16(2) ?: return null
                    val length = count.checkedTimes(6) ?: return null
                    Format2(data.slice(4, length) ?: return null, count)
                }

                else -> {
                    null
                }
            }
    }
}

internal sealed interface ClassDefinition {
    fun classOf(glyph: GlyphId): Int

    class Format1(
        private val startGlyph: Int,
        private val classes: UInt16View,
    ) : ClassDefinition {
        override fun classOf(glyph: GlyphId): Int = classes[glyph.value - startGlyph] ?: 0
    }

    class Format2(
        private val records: ByteSlice,
        private val size: Int,
    ) : ClassDefinition {
        override fun classOf(glyph: GlyphId): Int {
            var low = 0
            var high = size - 1
            while (low <= high) {
                val middle = (low + high) ushr 1
                val offset = middle * 6
                val start = records.readUInt16(offset) ?: return 0
                val end = records.readUInt16(offset + 2) ?: return 0
                when {
                    glyph.value < start -> high = middle - 1
                    glyph.value > end -> low = middle + 1
                    else -> return records.readUInt16(offset + 4) ?: 0
                }
            }
            return 0
        }
    }

    data object Empty : ClassDefinition {
        override fun classOf(glyph: GlyphId): Int = 0
    }

    companion object {
        internal fun parse(data: ByteSlice): ClassDefinition? =
            when (data.readUInt16(0)) {
                1 -> {
                    val start = data.readUInt16(2) ?: return null
                    val count = data.readUInt16(4) ?: return null
                    Format1(start, UInt16View.parse(data, 6, count) ?: return null)
                }

                2 -> {
                    val count = data.readUInt16(2) ?: return null
                    val length = count.checkedTimes(6) ?: return null
                    Format2(data.slice(4, length) ?: return null, count)
                }

                else -> {
                    null
                }
            }
    }
}

internal sealed interface Device {
    class Hinting(
        internal val startSize: Int,
        internal val endSize: Int,
        private val format: Int,
        private val words: UInt16View,
    ) : Device {
        internal fun xDelta(
            unitsPerEm: Int,
            pixelsPerEm: Pair<Int, Int>?,
        ): Int? = pixelsPerEm?.let { delta(it.first, unitsPerEm) }

        internal fun yDelta(
            unitsPerEm: Int,
            pixelsPerEm: Pair<Int, Int>?,
        ): Int? = pixelsPerEm?.let { delta(it.second, unitsPerEm) }

        internal fun delta(
            pixelsPerEm: Int,
            unitsPerEm: Int,
        ): Int? {
            if (pixelsPerEm == 0 || pixelsPerEm !in startSize..endSize) return null
            val index = pixelsPerEm - startSize
            val valuesPerWord = 16 shr format
            val word = words[index / valuesPerWord] ?: return null
            val shift = 16 - ((index % valuesPerWord + 1) shl format)
            val mask = (1 shl (1 shl format)) - 1
            var value = (word ushr shift) and mask
            if (value >= (mask + 1) / 2) value -= mask + 1
            val scaled = value.toLong() * unitsPerEm / pixelsPerEm
            return scaled.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        }
    }

    data class Variation(
        internal val outerIndex: Int,
        internal val innerIndex: Int,
    ) : Device

    companion object {
        internal fun parse(data: ByteSlice): Device? {
            val first = data.readUInt16(0) ?: return null
            val second = data.readUInt16(2) ?: return null
            return when (val format = data.readUInt16(4) ?: return null) {
                in 1..3 -> {
                    if (second < first) return null
                    val valuesPerWord = 16 shr format
                    val count = (second - first + valuesPerWord) / valuesPerWord
                    Hinting(first, second, format, UInt16View.parse(data, 6, count) ?: return null)
                }

                0x8000 -> {
                    Variation(first, second)
                }

                else -> {
                    null
                }
            }
        }
    }
}

internal data class LookupFlags(
    internal val value: Int,
) {
    internal val rightToLeft: Boolean get() = value and 0x0001 != 0
    internal val ignoreBaseGlyphs: Boolean get() = value and 0x0002 != 0
    internal val ignoreLigatures: Boolean get() = value and 0x0004 != 0
    internal val ignoreMarks: Boolean get() = value and 0x0008 != 0
    internal val useMarkFilteringSet: Boolean get() = value and 0x0010 != 0
    internal val ignoreFlags: Boolean get() = value and 0x000E != 0
    internal val markAttachmentType: Int get() = value ushr 8
}

internal class Lookup<T> private constructor(
    internal val flags: LookupFlags,
    internal val markFilteringSet: Int?,
    private val kind: Int,
    private val data: ByteSlice,
    private val offsets: UInt16View,
    private val parser: (ByteSlice, Int, Int) -> T?,
) {
    internal val size: Int get() = offsets.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): T? {
        val offset = offsets[index] ?: return null
        return parser(data.sliceFrom(offset) ?: return null, kind, MAX_EXTENSION_DEPTH)
    }

    internal companion object {
        private const val MAX_EXTENSION_DEPTH = 8

        internal fun <T> parse(
            data: ByteSlice,
            parser: (ByteSlice, Int, Int) -> T?,
        ): Lookup<T>? {
            val kind = data.readUInt16(0) ?: return null
            val flags = LookupFlags(data.readUInt16(2) ?: return null)
            val count = data.readUInt16(4) ?: return null
            val offsets = UInt16View.parse(data, 6, count) ?: return null
            val markSet = if (flags.useMarkFilteringSet) data.readUInt16(6 + count * 2) ?: return null else null
            return Lookup(flags, markSet, kind, data, offsets, parser)
        }
    }
}

internal class LookupList<T> private constructor(
    private val data: ByteSlice,
    private val offsets: UInt16View,
    private val parser: (ByteSlice, Int, Int) -> T?,
) {
    internal val size: Int get() = offsets.size
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Lookup<T>? {
        val offset = offsets[index] ?: return null
        return Lookup.parse(data.sliceFrom(offset) ?: return null, parser)
    }

    internal companion object {
        internal fun <T> parse(
            data: ByteSlice,
            parser: (ByteSlice, Int, Int) -> T?,
        ): LookupList<T>? {
            val count = data.readUInt16(0) ?: return null
            return LookupList(data, UInt16View.parse(data, 2, count) ?: return null, parser)
        }
    }
}

internal data class LanguageSystem(
    internal val tag: Tag,
    internal val requiredFeature: Int?,
    internal val featureIndices: UInt16View,
)

internal class LanguageList private constructor(
    private val scriptData: ByteSlice,
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): LanguageSystem? {
        if (index !in 0 until size) return null
        val record = records.slice(index * 6, 6) ?: return null
        val tag = Tag.fromInt(record.readInt32(0) ?: return null)
        val offset = record.readUInt16(4) ?: return null
        return parseLanguage(tag, scriptData.sliceFrom(offset) ?: return null)
    }

    internal fun find(tag: Tag): LanguageSystem? =
        binaryFind(size, tag) { index ->
            records.readInt32(index * 6)
        }?.let(::get)

    internal fun index(tag: Tag): Int? = binaryFind(size, tag) { records.readInt32(it * 6) }

    internal companion object {
        internal fun parse(
            scriptData: ByteSlice,
            offset: Int,
        ): LanguageList? {
            val count = scriptData.readUInt16(offset) ?: return null
            val length = count.checkedTimes(6) ?: return null
            return LanguageList(scriptData, scriptData.slice(offset + 2, length) ?: return null, count)
        }
    }
}

internal data class Script(
    internal val tag: Tag,
    internal val defaultLanguage: LanguageSystem?,
    internal val languages: LanguageList,
)

internal class ScriptList private constructor(
    private val data: ByteSlice,
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Script? {
        if (index !in 0 until size) return null
        val record = records.slice(index * 6, 6) ?: return null
        val tag = Tag.fromInt(record.readInt32(0) ?: return null)
        val script = data.sliceFrom(record.readUInt16(4) ?: return null) ?: return null
        val defaultOffset = script.readUInt16(0) ?: return null
        val defaultLanguage =
            if (defaultOffset ==
                0
            ) {
                null
            } else {
                parseLanguage(Tag.parse("dflt"), script.sliceFrom(defaultOffset) ?: return null)
            }
        return Script(tag, defaultLanguage, LanguageList.parse(script, 2) ?: return null)
    }

    internal fun find(tag: Tag): Script? = binaryFind(size, tag) { records.readInt32(it * 6) }?.let(::get)

    internal fun index(tag: Tag): Int? = binaryFind(size, tag) { records.readInt32(it * 6) }

    internal companion object {
        internal fun parse(data: ByteSlice): ScriptList? {
            val count = data.readUInt16(0) ?: return null
            val length = count.checkedTimes(6) ?: return null
            return ScriptList(data, data.slice(2, length) ?: return null, count)
        }
    }
}

internal data class Feature(
    internal val tag: Tag,
    internal val lookupIndices: UInt16View,
)

internal class FeatureList private constructor(
    private val data: ByteSlice,
    private val records: ByteSlice,
    internal val size: Int,
) {
    internal val isEmpty: Boolean get() = size == 0

    internal operator fun get(index: Int): Feature? {
        if (index !in 0 until size) return null
        val record = records.slice(index * 6, 6) ?: return null
        val tag = Tag.fromInt(record.readInt32(0) ?: return null)
        val feature = data.sliceFrom(record.readUInt16(4) ?: return null) ?: return null
        if (feature.readUInt16(0) == null) return null
        val count = feature.readUInt16(2) ?: return null
        return Feature(tag, UInt16View.parse(feature, 4, count) ?: return null)
    }

    internal fun find(tag: Tag): Feature? = binaryFind(size, tag) { records.readInt32(it * 6) }?.let(::get)

    internal fun index(tag: Tag): Int? = binaryFind(size, tag) { records.readInt32(it * 6) }

    internal companion object {
        internal fun parse(data: ByteSlice): FeatureList? {
            val count = data.readUInt16(0) ?: return null
            val length = count.checkedTimes(6) ?: return null
            return FeatureList(data, data.slice(2, length) ?: return null, count)
        }
    }
}

internal class LayoutTable<T> private constructor(
    internal val scripts: ScriptList,
    internal val features: FeatureList,
    internal val lookups: LookupList<T>,
    internal val variations: FeatureVariations?,
) {
    internal companion object {
        internal fun <T> parse(
            data: ByteSlice,
            parser: (ByteSlice, Int, Int) -> T?,
        ): LayoutTable<T>? {
            if (data.readUInt16(0) != 1) return null
            val minorVersion = data.readUInt16(2) ?: return null
            val scripts = data.at16(4, ScriptList::parse) ?: return null
            val features = data.at16(6, FeatureList::parse) ?: return null
            val lookups = data.at16(8) { LookupList.parse(it, parser) } ?: return null
            val variations =
                if (minorVersion >= 1) {
                    val rawOffset = data.readUInt32(10) ?: return null
                    val offset = rawOffset.toIndex()
                    if (offset == null || offset == 0) null else data.sliceFrom(offset)?.let(FeatureVariations::parse)
                } else {
                    null
                }
            return LayoutTable(scripts, features, lookups, variations)
        }
    }
}

internal inline fun <T> ByteSlice.at16(
    fieldOffset: Int,
    parse: (ByteSlice) -> T?,
): T? {
    val offset = readUInt16(fieldOffset) ?: return null
    if (offset == 0) return null
    return parse(sliceFrom(offset) ?: return null)
}

internal inline fun <T> ByteSlice.at32(
    fieldOffset: Int,
    parse: (ByteSlice) -> T?,
): T? {
    val offset = readUInt32(fieldOffset)?.toIndex() ?: return null
    if (offset == 0) return null
    return parse(sliceFrom(offset) ?: return null)
}

internal fun Int.checkedTimes(other: Int): Int? {
    val result = toLong() * other
    return if (result in 0..Int.MAX_VALUE.toLong()) result.toInt() else null
}

internal fun Long.toIndex(): Int? = if (this in 0..Int.MAX_VALUE.toLong()) toInt() else null

private fun parseLanguage(
    tag: Tag,
    data: ByteSlice,
): LanguageSystem? {
    if (data.readUInt16(0) == null) return null
    val requiredValue = data.readUInt16(2) ?: return null
    val required = if (requiredValue == 0xFFFF) null else requiredValue
    val count = data.readUInt16(4) ?: return null
    return LanguageSystem(tag, required, UInt16View.parse(data, 6, count) ?: return null)
}

private fun binaryFind(
    size: Int,
    tag: Tag,
    value: (Int) -> Int?,
): Int? {
    var low = 0
    var high = size - 1
    while (low <= high) {
        val middle = (low + high) ushr 1
        val current = value(middle) ?: return null
        when {
            current.toUInt() < tag.value.toUInt() -> low = middle + 1
            current.toUInt() > tag.value.toUInt() -> high = middle - 1
            else -> return middle
        }
    }
    return null
}
