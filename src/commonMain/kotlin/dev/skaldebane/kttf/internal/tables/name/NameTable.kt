package dev.skaldebane.kttf.internal.tables.name

import dev.skaldebane.kttf.internal.parser.ByteSlice
import dev.skaldebane.kttf.internal.parser.FixedRecordArray

internal enum class NamePlatform {
    UNICODE,
    MACINTOSH,
    ISO,
    WINDOWS,
    CUSTOM,
}

internal object NameId {
    internal const val COPYRIGHT_NOTICE: Int = 0
    internal const val FAMILY: Int = 1
    internal const val SUBFAMILY: Int = 2
    internal const val UNIQUE_ID: Int = 3
    internal const val FULL_NAME: Int = 4
    internal const val VERSION: Int = 5
    internal const val POST_SCRIPT_NAME: Int = 6
    internal const val TRADEMARK: Int = 7
    internal const val MANUFACTURER: Int = 8
    internal const val DESIGNER: Int = 9
    internal const val DESCRIPTION: Int = 10
    internal const val VENDOR_URL: Int = 11
    internal const val DESIGNER_URL: Int = 12
    internal const val LICENSE: Int = 13
    internal const val LICENSE_URL: Int = 14
    internal const val TYPOGRAPHIC_FAMILY: Int = 16
    internal const val TYPOGRAPHIC_SUBFAMILY: Int = 17
    internal const val COMPATIBLE_FULL: Int = 18
    internal const val SAMPLE_TEXT: Int = 19
    internal const val POST_SCRIPT_CID: Int = 20
    internal const val WWS_FAMILY: Int = 21
    internal const val WWS_SUBFAMILY: Int = 22
    internal const val LIGHT_BACKGROUND_PALETTE: Int = 23
    internal const val DARK_BACKGROUND_PALETTE: Int = 24
    internal const val VARIATIONS_POST_SCRIPT_NAME_PREFIX: Int = 25
}

internal class NameRecord internal constructor(
    internal val platform: NamePlatform,
    internal val encodingId: Int,
    internal val languageId: Int,
    internal val nameId: Int,
    internal val data: ByteSlice,
    private val languageTags: FixedRecordArray?,
    private val storage: ByteSlice,
) {
    internal val isUnicode: Boolean
        get() =
            platform == NamePlatform.UNICODE ||
                (platform == NamePlatform.WINDOWS && encodingId in WINDOWS_UNICODE_ENCODING_IDS)

    internal fun decode(): String? {
        if (!isUnicode) return null
        return data.decodeUtf16Be()
    }

    internal fun decodeLanguageTag(): String? {
        if (languageId < LANGUAGE_TAG_ID_START) return null
        val record = languageTags?.get(languageId - LANGUAGE_TAG_ID_START) ?: return null
        val length = record.readUInt16(0) ?: return null
        val offset = record.readUInt16(2) ?: return null
        return storage.slice(offset, length)?.decodeUtf16Be()
    }

    private companion object {
        private const val LANGUAGE_TAG_ID_START: Int = 0x8000
        private val WINDOWS_UNICODE_ENCODING_IDS: IntRange = 0..1
    }
}

internal class NameTable private constructor(
    internal val format: Int,
    private val records: FixedRecordArray,
    private val languageTags: FixedRecordArray?,
    private val storage: ByteSlice,
) {
    internal val size: Int
        get() = records.size

    internal val isEmpty: Boolean
        get() = size == 0

    internal operator fun get(index: Int): NameRecord? {
        val record = records[index] ?: return null
        val platform = record.readUInt16(0)?.toNamePlatform() ?: return null
        val encodingId = record.readUInt16(2) ?: return null
        val languageId = record.readUInt16(4) ?: return null
        val nameId = record.readUInt16(6) ?: return null
        val length = record.readUInt16(8) ?: return null
        val offset = record.readUInt16(10) ?: return null
        val nameData = storage.slice(offset, length) ?: return null
        return NameRecord(platform, encodingId, languageId, nameId, nameData, languageTags, storage)
    }

    internal companion object {
        private const val HEADER_SIZE: Int = 6
        private const val NAME_RECORD_SIZE: Int = 12
        private const val LANGUAGE_TAG_RECORD_SIZE: Int = 4

        internal fun parse(data: ByteSlice): NameTable? {
            val format = data.readUInt16(0) ?: return null
            if (format !in 0..1) return null

            val count = data.readUInt16(2) ?: return null
            val storageOffset = data.readUInt16(4) ?: return null
            val recordsLength = count * NAME_RECORD_SIZE
            val recordsData = data.slice(HEADER_SIZE, recordsLength) ?: return null
            val records = FixedRecordArray.create(recordsData, count, NAME_RECORD_SIZE) ?: return null
            val recordsEnd = HEADER_SIZE + recordsLength

            val languageTags =
                if (format == 1) {
                    val languageTagCount = data.readUInt16(recordsEnd) ?: return null
                    val tagsStart = recordsEnd + 2
                    val tagsLength = languageTagCount * LANGUAGE_TAG_RECORD_SIZE
                    val tagsData = data.slice(tagsStart, tagsLength) ?: return null
                    if (storageOffset < tagsStart + tagsLength) return null
                    FixedRecordArray.create(tagsData, languageTagCount, LANGUAGE_TAG_RECORD_SIZE) ?: return null
                } else {
                    if (storageOffset < recordsEnd) return null
                    null
                }

            val storage = data.sliceFrom(storageOffset) ?: return null
            return NameTable(format, records, languageTags, storage)
        }

        private fun Int.toNamePlatform(): NamePlatform? =
            when (this) {
                0 -> NamePlatform.UNICODE
                1 -> NamePlatform.MACINTOSH
                2 -> NamePlatform.ISO
                3 -> NamePlatform.WINDOWS
                4 -> NamePlatform.CUSTOM
                else -> null
            }
    }
}

private fun ByteSlice.decodeUtf16Be(): String? {
    if (size % 2 != 0) return null
    return buildString(size / 2) {
        var offset = 0
        while (offset < size) {
            val value = readUInt16(offset) ?: return null
            when (value) {
                in HIGH_SURROGATE_RANGE -> {
                    val low = readUInt16(offset + 2) ?: return null
                    if (low !in LOW_SURROGATE_RANGE) return null
                    append(value.toChar())
                    append(low.toChar())
                    offset += 4
                }

                in LOW_SURROGATE_RANGE -> {
                    return null
                }

                else -> {
                    append(value.toChar())
                    offset += 2
                }
            }
        }
    }
}

private val HIGH_SURROGATE_RANGE: IntRange = 0xD800..0xDBFF
private val LOW_SURROGATE_RANGE: IntRange = 0xDC00..0xDFFF
