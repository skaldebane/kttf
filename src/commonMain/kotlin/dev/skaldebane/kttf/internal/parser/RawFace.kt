package dev.skaldebane.kttf.internal.parser

import dev.skaldebane.kttf.FontParsingError
import dev.skaldebane.kttf.Tag

internal data class RawTableRecord(
    val tag: Tag,
    val checksum: Long,
    val offset: Long,
    val length: Long,
)

internal data class RawFaceParseResult(
    val face: RawFace?,
    val error: FontParsingError?,
)

internal class RawFaceTables(
    internal val cmap: ByteSlice?,
    internal val head: ByteSlice?,
    internal val hhea: ByteSlice?,
    internal val hmtx: ByteSlice?,
    internal val loca: ByteSlice?,
    internal val maxp: ByteSlice?,
    internal val glyf: ByteSlice?,
    internal val name: ByteSlice?,
    internal val os2: ByteSlice?,
    internal val post: ByteSlice?,
    internal val vhea: ByteSlice?,
    internal val vmtx: ByteSlice?,
    internal val cff: ByteSlice?,
    internal val cff2: ByteSlice?,
    internal val fvar: ByteSlice?,
    internal val avar: ByteSlice?,
    internal val gvar: ByteSlice?,
    internal val hvar: ByteSlice?,
    internal val vvar: ByteSlice?,
    internal val mvar: ByteSlice?,
    internal val kern: ByteSlice?,
    internal val vorg: ByteSlice?,
    internal val stat: ByteSlice?,
    internal val cpal: ByteSlice?,
    internal val colr: ByteSlice?,
    internal val svg: ByteSlice?,
    internal val cblc: ByteSlice?,
    internal val cbdt: ByteSlice?,
    internal val eblc: ByteSlice?,
    internal val ebdt: ByteSlice?,
    internal val bloc: ByteSlice?,
    internal val bdat: ByteSlice?,
    internal val sbix: ByteSlice?,
    internal val gdef: ByteSlice?,
    internal val gpos: ByteSlice?,
    internal val gsub: ByteSlice?,
    internal val math: ByteSlice?,
    internal val ankr: ByteSlice?,
    internal val feat: ByteSlice?,
    internal val trak: ByteSlice?,
    internal val kerx: ByteSlice?,
    internal val morx: ByteSlice?,
)

internal class RawFace private constructor(
    private val data: ByteSlice?,
    private val tableRecords: FixedRecordArray?,
    private val externalTables: Map<Tag, ByteSlice>?,
    internal val faceIndex: Int,
    internal val faceCount: Int,
) {
    internal fun table(tag: Tag): ByteSlice? {
        externalTables?.let { return it[tag] }
        val records = tableRecords ?: return null
        val source = data ?: return null
        var low = 0
        var high = records.size - 1

        while (low <= high) {
            val middle = low + (high - low) / 2
            val record = records[middle] ?: return null
            val recordTag = record.readInt32(0) ?: return null
            val recordTagValue = recordTag.toLong() and 0xFFFF_FFFFL
            val requestedTagValue = tag.value.toLong() and 0xFFFF_FFFFL

            when {
                recordTagValue < requestedTagValue -> {
                    low = middle + 1
                }

                recordTagValue > requestedTagValue -> {
                    high = middle - 1
                }

                else -> {
                    val offset = record.readUInt32(8)?.toArrayIndexOrNull() ?: return null
                    val length = record.readUInt32(12)?.toArrayIndexOrNull() ?: return null
                    return source.slice(offset, length)
                }
            }
        }

        // Valid SFNT directories are sorted, but retain safe lookup for malformed
        // unsorted directories because integrated parsing already supports them.
        return initialTable(tag.value)
    }

    internal val tableRecordCount: Int
        get() = tableRecords?.size ?: 0

    internal fun tableRecord(index: Int): RawTableRecord? {
        val record = tableRecords?.get(index) ?: return null
        return RawTableRecord(
            tag = Tag.fromInt(record.readInt32(0) ?: return null),
            checksum = record.readUInt32(4) ?: return null,
            offset = record.readUInt32(8) ?: return null,
            length = record.readUInt32(12) ?: return null,
        )
    }

    internal fun collectInitialTables(): RawFaceTables =
        RawFaceTables(
            initialTable(CMAP_TAG),
            initialTable(HEAD_TAG),
            initialTable(HHEA_TAG),
            initialTable(HMTX_TAG),
            initialTable(LOCA_TAG),
            initialTable(MAXP_TAG),
            initialTable(GLYF_TAG),
            initialTable(NAME_TAG),
            initialTable(OS2_TAG),
            initialTable(POST_TAG),
            initialTable(VHEA_TAG),
            initialTable(VMTX_TAG),
            initialTable(CFF_TAG),
            initialTable(CFF2_TAG),
            initialTable(FVAR_TAG),
            initialTable(AVAR_TAG),
            initialTable(GVAR_TAG),
            initialTable(HVAR_TAG),
            initialTable(VVAR_TAG),
            initialTable(MVAR_TAG),
            initialTable(KERN_TAG),
            initialTable(VORG_TAG),
            initialTable(STAT_TAG),
            initialTable(CPAL_TAG),
            initialTable(COLR_TAG),
            initialTable(SVG_TAG),
            initialTable(CBLC_TAG),
            initialTable(CBDT_TAG),
            initialTable(EBLC_TAG),
            initialTable(EBDT_TAG),
            initialTable(BLOC_TAG),
            initialTable(BDAT_TAG),
            initialTable(SBIX_TAG),
            initialTable(GDEF_TAG),
            initialTable(GPOS_TAG),
            initialTable(GSUB_TAG),
            initialTable(MATH_TAG),
            initialTable(ANKR_TAG),
            initialTable(FEAT_TAG),
            initialTable(TRAK_TAG),
            initialTable(KERX_TAG),
            initialTable(MORX_TAG),
        )

    private fun initialTable(tagValue: Int): ByteSlice? {
        externalTables?.let { return it[Tag.fromInt(tagValue)] }
        val records = tableRecords ?: return null
        val source = data ?: return null
        var result: ByteSlice? = null
        for (index in 0 until records.size) {
            val record = records[index] ?: continue
            if (record.readInt32(0) != tagValue) continue
            val offset = record.readUInt32(8)?.toArrayIndexOrNull() ?: continue
            val length = record.readUInt32(12)?.toArrayIndexOrNull() ?: continue
            result = source.slice(offset, length)
        }
        return result
    }

    internal companion object {
        private const val TRUE_TYPE_MAGIC: Long = 0x00010000
        private const val APPLE_TRUE_TYPE_MAGIC: Long = 0x74727565
        private const val OPEN_TYPE_MAGIC: Long = 0x4F54544F
        private const val COLLECTION_MAGIC: Long = 0x74746366
        private const val TABLE_RECORD_SIZE: Int = 16
        private const val CMAP_TAG: Int = 0x636D6170
        private const val HEAD_TAG: Int = 0x68656164
        private const val HHEA_TAG: Int = 0x68686561
        private const val HMTX_TAG: Int = 0x686D7478
        private const val LOCA_TAG: Int = 0x6C6F6361
        private const val MAXP_TAG: Int = 0x6D617870
        private const val GLYF_TAG: Int = 0x676C7966
        private const val NAME_TAG: Int = 0x6E616D65
        private const val OS2_TAG: Int = 0x4F532F32
        private const val POST_TAG: Int = 0x706F7374
        private const val VHEA_TAG: Int = 0x76686561
        private const val VMTX_TAG: Int = 0x766D7478
        private const val CFF_TAG: Int = 0x43464620
        private const val CFF2_TAG: Int = 0x43464632
        private const val FVAR_TAG: Int = 0x66766172
        private const val AVAR_TAG: Int = 0x61766172
        private const val GVAR_TAG: Int = 0x67766172
        private const val HVAR_TAG: Int = 0x48564152
        private const val VVAR_TAG: Int = 0x56564152
        private const val MVAR_TAG: Int = 0x4D564152
        private const val KERN_TAG: Int = 0x6B65726E
        private const val VORG_TAG: Int = 0x564F5247
        private const val STAT_TAG: Int = 0x53544154
        private const val CPAL_TAG: Int = 0x4350414C
        private const val COLR_TAG: Int = 0x434F4C52
        private const val SVG_TAG: Int = 0x53564720
        private const val CBLC_TAG: Int = 0x43424C43
        private const val CBDT_TAG: Int = 0x43424454
        private const val EBLC_TAG: Int = 0x45424C43
        private const val EBDT_TAG: Int = 0x45424454
        private const val BLOC_TAG: Int = 0x626C6F63
        private const val BDAT_TAG: Int = 0x62646174
        private const val SBIX_TAG: Int = 0x73626978
        private const val GDEF_TAG: Int = 0x47444546
        private const val GPOS_TAG: Int = 0x47504F53
        private const val GSUB_TAG: Int = 0x47535542
        private const val MATH_TAG: Int = 0x4D415448
        private const val ANKR_TAG: Int = 0x616E6B72
        private const val FEAT_TAG: Int = 0x66656174
        private const val TRAK_TAG: Int = 0x7472616B
        private const val KERX_TAG: Int = 0x6B657278
        private const val MORX_TAG: Int = 0x6D6F7278

        internal fun parse(
            source: ByteArray,
            faceIndex: Int,
        ): RawFace? = parseResult(source, faceIndex).face

        internal fun parseResult(
            source: ByteArray,
            faceIndex: Int,
        ): RawFaceParseResult {
            if (faceIndex < 0) return failure(FontParsingError.FaceIndexOutOfBounds)

            val data = ByteSlice.of(source)
            val root = Stream.atStart(data)
            val outerMagic = root.readUInt32() ?: return failure(FontParsingError.UnknownMagic)

            val faceCount: Int
            val faceStream: Stream

            if (outerMagic == COLLECTION_MAGIC) {
                if (!root.advance(4)) return failure(FontParsingError.MalformedFont)
                val count = root.readUInt32()?.toArrayIndexOrNull() ?: return failure(FontParsingError.MalformedFont)
                if (faceIndex >= count) return failure(FontParsingError.FaceIndexOutOfBounds)

                val offsetsLength = checkedProduct(count, 4) ?: return failure(FontParsingError.MalformedFont)
                val offsetsBytes = root.readBytes(offsetsLength) ?: return failure(FontParsingError.MalformedFont)
                val faceOffset =
                    offsetsBytes.readUInt32(faceIndex * 4)?.toArrayIndexOrNull()
                        ?: return failure(FontParsingError.MalformedFont)
                if (faceOffset < root.offset) return failure(FontParsingError.MalformedFont)
                faceStream = Stream.at(data, faceOffset) ?: return failure(FontParsingError.MalformedFont)

                val faceMagic = faceStream.readUInt32() ?: return failure(FontParsingError.UnknownMagic)
                if (!faceMagic.isFaceMagic()) return failure(FontParsingError.UnknownMagic)
                faceCount = count
            } else {
                if (!outerMagic.isFaceMagic()) return failure(FontParsingError.UnknownMagic)
                if (faceIndex != 0) return failure(FontParsingError.FaceIndexOutOfBounds)
                faceStream = root
                faceCount = 1
            }

            val tableCount = faceStream.readUInt16() ?: return failure(FontParsingError.MalformedFont)
            if (!faceStream.advance(6)) return failure(FontParsingError.MalformedFont)
            val tableRecordsLength =
                checkedProduct(tableCount, TABLE_RECORD_SIZE)
                    ?: return failure(FontParsingError.MalformedFont)
            val tableBytes =
                faceStream.readBytes(tableRecordsLength)
                    ?: return failure(FontParsingError.MalformedFont)
            val records =
                FixedRecordArray.create(tableBytes, tableCount, TABLE_RECORD_SIZE)
                    ?: return failure(FontParsingError.MalformedFont)

            return RawFaceParseResult(
                RawFace(
                    data = data,
                    tableRecords = records,
                    externalTables = null,
                    faceIndex = faceIndex,
                    faceCount = faceCount,
                ),
                null,
            )
        }

        internal fun fromTables(tables: Map<Tag, ByteArray>): RawFace =
            RawFace(
                data = null,
                tableRecords = null,
                externalTables = tables.mapValues { ByteSlice.of(it.value) },
                faceIndex = 0,
                faceCount = 1,
            )

        internal fun fontsInCollection(source: ByteArray): Long? {
            val data = ByteSlice.of(source)
            if (data.readUInt32(0) != COLLECTION_MAGIC) return null
            return data.readUInt32(8)
        }

        private fun Long.isFaceMagic(): Boolean =
            this == TRUE_TYPE_MAGIC || this == APPLE_TRUE_TYPE_MAGIC || this == OPEN_TYPE_MAGIC

        private fun failure(error: FontParsingError): RawFaceParseResult = RawFaceParseResult(null, error)

        private fun Long.toArrayIndexOrNull(): Int? {
            if (this > Int.MAX_VALUE.toLong()) return null
            return toInt()
        }

        private fun checkedProduct(
            count: Int,
            elementSize: Int,
        ): Int? {
            if (count < 0 || elementSize < 0) return null
            if (count > Int.MAX_VALUE / elementSize) return null
            return count * elementSize
        }
    }
}
