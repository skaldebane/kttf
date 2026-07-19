package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.parser.RawFace

/** An exact table-directory record from an SFNT or TTC face. */
public data class FontTableRecord(
    public val tag: Tag,
    public val checksum: Long,
    public val offset: Long,
    public val length: Long,
)

/** Binary-backed table-directory records in file order. */
public class FontTableRecords internal constructor(
    private val rawFace: RawFace,
) : Iterable<FontTableRecord> {
    public val size: Int
        get() = rawFace.tableRecordCount

    public operator fun get(index: Int): FontTableRecord? =
        rawFace.tableRecord(index)?.let { FontTableRecord(it.tag, it.checksum, it.offset, it.length) }

    override fun iterator(): Iterator<FontTableRecord> =
        object : Iterator<FontTableRecord> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): FontTableRecord = get(index++) ?: throw NoSuchElementException()
        }
}

/** A table family accepted by kttf's integrated parser. */
public enum class ParsedTableKind(
    internal val tag: Tag,
) {
    Cmap(Tag.parse("cmap")),
    Head(Tag.parse("head")),
    HorizontalHeader(Tag.parse("hhea")),
    HorizontalMetrics(Tag.parse("hmtx")),
    Locations(Tag.parse("loca")),
    MaximumProfile(Tag.parse("maxp")),
    TrueTypeOutlines(Tag.parse("glyf")),
    Names(Tag.parse("name")),
    Os2(Tag.parse("OS/2")),
    Post(Tag.parse("post")),
    VerticalHeader(Tag.parse("vhea")),
    VerticalMetrics(Tag.parse("vmtx")),
    Cff1(Tag.parse("CFF ")),
    Cff2(Tag.parse("CFF2")),
    FontVariations(Tag.parse("fvar")),
    AxisVariations(Tag.parse("avar")),
    GlyphVariations(Tag.parse("gvar")),
    HorizontalMetricVariations(Tag.parse("HVAR")),
    VerticalMetricVariations(Tag.parse("VVAR")),
    MetricVariations(Tag.parse("MVAR")),
    Kerning(Tag.parse("kern")),
    VerticalOrigins(Tag.parse("VORG")),
    StyleAttributes(Tag.parse("STAT")),
    ColorPalettes(Tag.parse("CPAL")),
    ColorOutlines(Tag.parse("COLR")),
    Svg(Tag.parse("SVG ")),
    ColorBitmapLocations(Tag.parse("CBLC")),
    ColorBitmapData(Tag.parse("CBDT")),
    EmbeddedBitmapLocations(Tag.parse("EBLC")),
    EmbeddedBitmapData(Tag.parse("EBDT")),
    AppleBitmapLocations(Tag.parse("bloc")),
    AppleBitmapData(Tag.parse("bdat")),
    StandardBitmapGraphics(Tag.parse("sbix")),
    GlyphDefinitions(Tag.parse("GDEF")),
    GlyphPositioning(Tag.parse("GPOS")),
    GlyphSubstitutions(Tag.parse("GSUB")),
    Math(Tag.parse("MATH")),
    AppleAnchors(Tag.parse("ankr")),
    AppleFeatures(Tag.parse("feat")),
    AppleTracking(Tag.parse("trak")),
    AppleKerning(Tag.parse("kerx")),
    AppleMetamorphosis(Tag.parse("morx")),
}

/** A successfully parsed integrated table and its bounded source bytes. */
public class ParsedFontTable internal constructor(
    public val kind: ParsedTableKind,
    public val table: FontTable,
) {
    public val tag: Tag
        get() = table.tag
}

/**
 * Successfully parsed integrated tables.
 *
 * Existing typed APIs on [FontFace] remain the primary query route. This registry
 * provides uniform discovery and bounded byte access without exposing internal
 * parser representations.
 */
public class ParsedFontTables internal constructor(
    private val rawFace: RawFace,
    private val kinds: List<ParsedTableKind>,
) : Iterable<ParsedFontTable> {
    public val size: Int
        get() = kinds.size

    public operator fun get(index: Int): ParsedFontTable? {
        val kind = kinds.getOrNull(index) ?: return null
        val bytes = rawFace.table(kind.tag) ?: return null
        return ParsedFontTable(kind, FontTable(kind.tag, bytes))
    }

    public operator fun get(tag: Tag): ParsedFontTable? {
        val kind = kinds.firstOrNull { it.tag == tag } ?: return null
        val bytes = rawFace.table(tag) ?: return null
        return ParsedFontTable(kind, FontTable(tag, bytes))
    }

    public operator fun contains(tag: Tag): Boolean = kinds.any { it.tag == tag }

    override fun iterator(): Iterator<ParsedFontTable> =
        object : Iterator<ParsedFontTable> {
            private var index = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): ParsedFontTable = get(index++) ?: throw NoSuchElementException()
        }
}
