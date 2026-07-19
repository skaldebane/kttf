package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.parser.RawFace
import dev.skaldebane.kttf.internal.tables.AnkrTable
import dev.skaldebane.kttf.internal.tables.CbdtTable
import dev.skaldebane.kttf.internal.tables.CblcTable
import dev.skaldebane.kttf.internal.tables.CmapTable
import dev.skaldebane.kttf.internal.tables.ColrTable
import dev.skaldebane.kttf.internal.tables.CpalTable
import dev.skaldebane.kttf.internal.tables.FeatTable
import dev.skaldebane.kttf.internal.tables.GlyfTable
import dev.skaldebane.kttf.internal.tables.HeadTable
import dev.skaldebane.kttf.internal.tables.HheaTable
import dev.skaldebane.kttf.internal.tables.HmtxTable
import dev.skaldebane.kttf.internal.tables.KernTable
import dev.skaldebane.kttf.internal.tables.KerxTable
import dev.skaldebane.kttf.internal.tables.LocaTable
import dev.skaldebane.kttf.internal.tables.MaxpTable
import dev.skaldebane.kttf.internal.tables.MorxTable
import dev.skaldebane.kttf.internal.tables.OS2Table
import dev.skaldebane.kttf.internal.tables.PostTable
import dev.skaldebane.kttf.internal.tables.SbixTable
import dev.skaldebane.kttf.internal.tables.StatTable
import dev.skaldebane.kttf.internal.tables.SvgTable
import dev.skaldebane.kttf.internal.tables.TrakTable
import dev.skaldebane.kttf.internal.tables.VheaTable
import dev.skaldebane.kttf.internal.tables.VmtxTable
import dev.skaldebane.kttf.internal.tables.cff.Cff1Table
import dev.skaldebane.kttf.internal.tables.cff.Cff2Table
import dev.skaldebane.kttf.internal.tables.layout.GdefTable
import dev.skaldebane.kttf.internal.tables.layout.GposTable
import dev.skaldebane.kttf.internal.tables.layout.GsubTable
import dev.skaldebane.kttf.internal.tables.layout.MathTable
import dev.skaldebane.kttf.internal.tables.layout.parseGpos
import dev.skaldebane.kttf.internal.tables.layout.parseGsub
import dev.skaldebane.kttf.internal.tables.name.NameTable
import dev.skaldebane.kttf.internal.tables.variations.AvarTable
import dev.skaldebane.kttf.internal.tables.variations.FvarTable
import dev.skaldebane.kttf.internal.tables.variations.GvarTable
import dev.skaldebane.kttf.internal.tables.variations.HvarTable
import dev.skaldebane.kttf.internal.tables.variations.MvarTable
import dev.skaldebane.kttf.internal.tables.variations.NormalizedCoordinate
import dev.skaldebane.kttf.internal.tables.variations.VariationCoordinates
import dev.skaldebane.kttf.internal.tables.variations.VvarTable

/**
 * A parsed OpenType font face backed by the original source bytes.
 *
 * kttf retains [ByteArray] input without copying it. The caller must not mutate
 * the array while this face or a derived [FontTable] remains in use. If this
 * contract is respected, a face is safe for concurrent reads.
 */
public class FontFace private constructor(
    private val rawFace: RawFace,
    public val cmap: Cmap?,
    private val horizontalMetrics: HmtxTable?,
    private val trueTypeOutlines: GlyfTable?,
    private val os2: OS2Table?,
    private val post: PostTable?,
    private val verticalMetrics: VmtxTable?,
    private val cffOutlines: Cff1Table?,
    private val integrated: IntegratedTables,
    private val parsedKinds: List<ParsedTableKind>,
    private val normalizedCoordinates: VariationCoordinates,
    private val designCoordinates: FloatArray,
    public val names: Names,
    public val unitsPerEm: Int,
    public val bounds: FontBounds,
    /** Raw ascender value from the `hhea` table. */
    public val hheaAscender: Int,
    /** Raw descender value from the `hhea` table. */
    public val hheaDescender: Int,
    /** Raw line-gap value from the `hhea` table. */
    public val hheaLineGap: Int,
    public val glyphCount: Int,
    private val rawVheaAscender: Int?,
    private val rawVheaDescender: Int?,
    private val rawVheaLineGap: Int?,
) {
    public val isVariable: Boolean
        get() = integrated.fvar != null

    public val variationAxes: FontVariationAxes = FontVariationAxes(integrated.fvar)

    public val variationInstances: FontVariationInstances = FontVariationInstances(integrated.fvar)

    /** Exact SFNT directory records, or an empty view for [fromTables] faces. */
    public val tableRecords: FontTableRecords = FontTableRecords(rawFace)

    /** Uniform discovery of every integrated table that parsed successfully. */
    public val parsedTables: ParsedFontTables = ParsedFontTables(rawFace, parsedKinds)

    /** Current normalized, `avar`-mapped coordinates in axis order. */
    public val variationCoordinates: List<Float>
        get() = List(normalizedCoordinates.size) { normalizedCoordinates[it]?.toFloat() ?: 0f }

    public val hasNonDefaultVariations: Boolean
        get() = normalizedCoordinates.hasNonDefaultValues()

    public val kerningSubtables: KerningSubtables = KerningSubtables(integrated.kern, integrated.kerx)

    public val styleAttributes: StyleAttributes? = integrated.stat?.let(::StyleAttributes)

    public val glyphDefinitions: GlyphDefinitions? =
        integrated.gdef?.let {
            GlyphDefinitions(
                it,
                normalizedCoordinates,
            )
        }

    public val glyphPositioning: GlyphPositioning? =
        integrated.gpos?.let {
            GlyphPositioning(
                it,
                normalizedCoordinates,
            )
        }

    public val glyphSubstitutions: GlyphSubstitutions? =
        integrated.gsub?.let {
            GlyphSubstitutions(it, normalizedCoordinates)
        }

    public val mathLayout: MathLayout? = integrated.math?.let(::MathLayout)

    public val appleLayout: AppleLayout =
        AppleLayout(integrated.ankr, integrated.feat, integrated.trak, integrated.morx)

    public val colorPaletteCount: Int?
        get() = integrated.colr?.let { integrated.cpal?.paletteCount }

    public fun paletteColor(
        palette: Int,
        entry: Int,
    ): FontColor? =
        integrated.cpal
            ?.color(palette, entry)
            ?.let { FontColor(it.red, it.green, it.blue, it.alpha) }

    public val weight: FontWeight
        get() = FontWeight(os2?.weight?.value ?: 400)

    public val width: FontWidth
        get() = os2?.width?.toPublic() ?: FontWidth.Normal

    public val style: FontStyle
        get() = os2?.style?.toPublic() ?: FontStyle.Normal

    public val isRegular: Boolean
        get() = style == FontStyle.Normal

    public val isItalic: Boolean
        get() = style == FontStyle.Italic || italicAngle != 0f

    public val isBold: Boolean
        get() = os2?.isBold ?: false

    public val isOblique: Boolean
        get() = style == FontStyle.Oblique

    public val ascender: Int
        get() {
            val table = os2
            if (table?.usesTypographicMetrics == true) return variedMetric("hasc", table.typographicAscender)
            if (hheaAscender != 0) return hheaAscender
            if (table == null) return 0
            return table.typographicAscender.takeUnless { it == 0 }?.let { variedMetric("hasc", it) }
                ?: variedMetric("hcla", table.windowsAscender)
        }

    public val descender: Int
        get() {
            val table = os2
            if (table?.usesTypographicMetrics == true) return variedMetric("hdsc", table.typographicDescender)
            if (hheaDescender != 0) return hheaDescender
            if (table == null) return 0
            return table.typographicDescender.takeUnless { it == 0 }?.let { variedMetric("hdsc", it) }
                ?: variedMetric("hcld", table.windowsDescender)
        }

    public val lineGap: Int
        get() {
            val table = os2
            if (table?.usesTypographicMetrics == true) return variedMetric("hlgp", table.typographicLineGap)
            if (hheaAscender != 0 && hheaDescender != 0) return hheaLineGap
            if (table == null) return hheaLineGap
            return if (table.typographicAscender != 0 ||
                table.typographicDescender != 0
            ) {
                variedMetric("hlgp", table.typographicLineGap)
            } else {
                0
            }
        }

    public val height: Int
        get() = ascender - descender

    public val typographicAscender: Int?
        get() = os2?.typographicAscender?.let { variedMetric("hasc", it) }

    public val typographicDescender: Int?
        get() = os2?.typographicDescender?.let { variedMetric("hdsc", it) }

    public val typographicLineGap: Int?
        get() = os2?.typographicLineGap?.let { variedMetric("hlgp", it) }

    public val windowsAscender: Int?
        get() = os2?.windowsAscender

    public val windowsDescender: Int?
        get() = os2?.windowsDescender

    public val xHeight: Int?
        get() = os2?.xHeight?.let { variedMetric("xhgt", it) }

    public val capitalHeight: Int?
        get() = os2?.capitalHeight?.let { variedMetric("cpht", it) }

    public val permissions: EmbeddingPermissions?
        get() = os2?.permissions?.toPublic()

    public val isSubsettingAllowed: Boolean
        get() = os2?.isSubsettingAllowed ?: false

    public val isOutlineEmbeddingAllowed: Boolean
        get() = os2?.isOutlineEmbeddingAllowed ?: false

    public val unicodeRanges: UnicodeRanges = UnicodeRanges(os2?.unicodeRanges)

    public val italicAngle: Float
        get() = post?.italicAngle ?: 0f

    public val isMonospaced: Boolean
        get() = post?.isMonospaced ?: false

    public val underlineMetrics: FontLineMetrics?
        get() =
            post?.underlineMetrics?.let {
                FontLineMetrics(variedMetric("undo", it.position), variedMetric("unds", it.thickness))
            }

    public val strikeoutMetrics: FontLineMetrics?
        get() =
            os2?.strikeoutMetrics?.let {
                FontLineMetrics(variedMetric("stro", it.position), variedMetric("strs", it.thickness))
            }

    public val subscriptMetrics: FontScriptMetrics?
        get() =
            os2?.subscriptMetrics?.let {
                FontScriptMetrics(
                    variedMetric("sbxs", it.xSize),
                    variedMetric("sbys", it.ySize),
                    variedMetric("sbxo", it.xOffset),
                    variedMetric("sbyo", it.yOffset),
                )
            }

    public val superscriptMetrics: FontScriptMetrics?
        get() =
            os2?.superscriptMetrics?.let {
                FontScriptMetrics(
                    variedMetric("spxs", it.xSize),
                    variedMetric("spys", it.ySize),
                    variedMetric("spxo", it.xOffset),
                    variedMetric("spyo", it.yOffset),
                )
            }

    public val vheaAscender: Int?
        get() = rawVheaAscender?.let { variedMetric("vasc", it) }

    public val vheaDescender: Int?
        get() = rawVheaDescender?.let { variedMetric("vdsc", it) }

    public val vheaLineGap: Int?
        get() = rawVheaLineGap?.let { variedMetric("vlgp", it) }

    public val verticalHeight: Int?
        get() {
            val ascender = vheaAscender ?: return null
            val descender = vheaDescender ?: return null
            return ascender - descender
        }

    /** CFF1 top-dictionary FontMatrix, before units-per-em scaling. */
    public val cffMatrix: FontTransform?
        get() =
            cffOutlines?.matrix?.let {
                FontTransform(it.scaleX, it.skewY, it.skewX, it.scaleY, it.translateX, it.translateY)
            }

    /** Index of this face in the source collection, or zero for a standalone font. */
    public val faceIndex: Int
        get() = rawFace.faceIndex

    /** Number of faces reported by the parsed source. */
    public val faceCount: Int
        get() = rawFace.faceCount

    /** Returns a bounded view of [tag], or null when the table is absent or malformed. */
    public fun table(tag: Tag): FontTable? {
        val bytes = rawFace.table(tag) ?: return null
        return FontTable(tag, bytes)
    }

    /** Returns the horizontal advance for [glyphId], or null when unavailable. */
    public fun glyphHorizontalAdvance(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        val value = horizontalMetrics?.advance(glyphId.value) ?: return null
        if (!isVariable) return value
        val hvar = integrated.hvar
        val delta =
            if (hvar != null) {
                hvar.advanceWidthDelta(glyphId.value, normalizedCoordinates)
            } else {
                phantomDeltas(glyphId)?.phantom(1)?.x
            }
        return variedGlyphAdvance(value, delta)
    }

    /** Returns the horizontal side bearing for [glyphId], or null when unavailable. */
    public fun glyphHorizontalSideBearing(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        val value = horizontalMetrics?.sideBearing(glyphId.value) ?: return null
        if (!isVariable) return value
        return variedGlyphSignedMetric(
            value,
            integrated.hvar?.leftSideBearingDelta(glyphId.value, normalizedCoordinates),
        )
    }

    /** Returns the vertical advance for [glyphId], or null when unavailable. */
    public fun glyphVerticalAdvance(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        val value = verticalMetrics?.advance(glyphId.value) ?: return null
        if (!isVariable) return value
        val vvar = integrated.vvar
        val delta =
            if (vvar != null) {
                vvar.advanceHeightDelta(glyphId.value, normalizedCoordinates)
            } else {
                phantomDeltas(glyphId)?.phantom(3)?.y
            }
        return variedGlyphAdvance(value, delta)
    }

    /** Returns the vertical top side bearing for [glyphId], or null when unavailable. */
    public fun glyphVerticalSideBearing(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        val value = verticalMetrics?.sideBearing(glyphId.value) ?: return null
        if (!isVariable) return value
        return variedGlyphSignedMetric(
            value,
            integrated.vvar?.topSideBearingDelta(glyphId.value, normalizedCoordinates),
        )
    }

    /** Returns the VORG origin, including VVAR adjustment, or null. */
    public fun glyphVerticalOrigin(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        val value = integrated.vorg?.glyphYOrigin(glyphId.value) ?: return null
        if (!isVariable) return value
        return variedGlyphSignedMetric(
            value,
            integrated.vvar?.verticalOriginDelta(glyphId.value, normalizedCoordinates),
        )
    }

    /** Returns the selected encoded bitmap image using sbix, bdat, EBDT, then CBDT order. */
    public fun rasterGlyph(
        glyphId: GlyphId,
        pixelsPerEm: Int,
    ): GlyphRasterImage? {
        if (glyphId.value >= glyphCount || pixelsPerEm !in 0..0xFFFF) return null
        integrated.sbix
            ?.bestStrike(pixelsPerEm)
            ?.get(glyphId.value)
            ?.let { return it.toPublic() }
        integrated.bdat?.get(glyphId.value, pixelsPerEm)?.let { return it.toPublic() }
        integrated.ebdt?.get(glyphId.value, pixelsPerEm)?.let { return it.toPublic() }
        return integrated.cbdt?.get(glyphId.value, pixelsPerEm)?.toPublic()
    }

    public fun svgGlyph(glyphId: GlyphId): SvgGlyphDocument? {
        if (glyphId.value >= glyphCount) return null
        return integrated.svg?.document(glyphId.value)?.let(::SvgGlyphDocument)
    }

    public fun isColorGlyph(glyphId: GlyphId): Boolean =
        glyphId.value < glyphCount && integrated.colr?.contains(glyphId.value) == true

    /** Returns true when [glyphId] has a COLR v0 definition. */
    public fun isSimpleColorGlyph(glyphId: GlyphId): Boolean =
        glyphId.value < glyphCount &&
            integrated.colr?.let { it.isSimple && it.contains(glyphId.value) } == true

    /** Returns [glyphId]'s COLR clip box evaluated at this face's current coordinates. */
    public fun colorGlyphClipBox(glyphId: GlyphId): ColorClipBox? {
        if (glyphId.value >= glyphCount) return null
        return integrated.colr?.clipBox(glyphId.value, variationCoordinates.toFloatArray())?.toPublic()
    }

    public fun paintColorGlyph(
        glyphId: GlyphId,
        palette: Int,
        foreground: FontColor,
        painter: ColorGlyphPainter,
    ): Boolean {
        if (glyphId.value >= glyphCount) return false
        return integrated.colr?.paintPublic(
            glyphId,
            palette,
            foreground,
            painter,
            variationCoordinates.toFloatArray(),
        ) ==
            true
    }

    /** Returns the PostScript glyph name for [glyphId], or null. This may allocate. */
    public fun glyphName(glyphId: GlyphId): String? {
        if (glyphId.value >= glyphCount) return null
        return post?.glyphName(glyphId.value) ?: cffOutlines?.glyphName(glyphId.value)
    }

    /** Returns the glyph associated with PostScript [name], or null. */
    public fun glyphIdByName(name: String): GlyphId? =
        (post?.glyphIndexByName(name) ?: cffOutlines?.glyphIdByName(name))
            ?.takeIf { it < glyphCount }
            ?.let(::GlyphId)

    /** Maps the Unicode scalar [codePoint] to a glyph, or returns null. */
    public fun glyphId(codePoint: Int): GlyphId? {
        if (!codePoint.isUnicodeScalar()) return null
        return cmap?.table?.glyphId(codePoint.toLong())?.let(::GlyphId)
    }

    /** Maps [character] to a glyph, or returns null for an unmapped character or surrogate. */
    public fun glyphId(character: Char): GlyphId? = glyphId(character.code)

    /**
     * Maps an 8-bit [code] through this face's CFF Encoding and Charset.
     *
     * This is a PostScript CFF lookup, not a Unicode lookup. Use [glyphId] for Unicode text.
     */
    public fun glyphIdFromCffCode(code: Int): GlyphId? =
        cffOutlines
            ?.glyphIdForCode(code)
            ?.takeIf { it < glyphCount }
            ?.let(::GlyphId)

    /** Returns a CID for a CID-keyed CFF1 glyph, or null for SID-keyed fonts. */
    public fun glyphCid(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        return cffOutlines?.glyphCid(glyphId.value)
    }

    /** Evaluates a SID-keyed CFF1 Type 2 width without consulting `hmtx`. */
    public fun glyphCffWidth(glyphId: GlyphId): Int? {
        if (glyphId.value >= glyphCount) return null
        return cffOutlines?.glyphWidth(glyphId.value)
    }

    /** Maps a Unicode variation sequence to a glyph, or returns null. */
    public fun glyphVariationId(
        codePoint: Int,
        variationSelector: Int,
    ): GlyphId? {
        if (!codePoint.isUnicodeScalar() || !variationSelector.isUnicodeScalar()) return null
        return cmap?.table?.glyphVariation(codePoint.toLong(), variationSelector.toLong())?.let(::GlyphId)
    }

    /**
     * Streams [glyphId]'s outline into [sink] and returns its calculated bounds.
     *
     * A malformed outline may emit commands before this returns null. Callers must discard
     * emitted commands unless a non-null result is returned. Sink exceptions propagate.
     */
    public fun outlineGlyph(
        glyphId: GlyphId,
        sink: OutlineSink,
    ): FontBounds? {
        if (glyphId.value >= glyphCount) return null
        val gvar = integrated.gvar
        if (gvar != null) {
            return trueTypeOutlines?.outline(glyphId.value, sink, gvar, normalizedCoordinates)
        }
        val glyf = trueTypeOutlines
        if (glyf != null) {
            return glyf.outline(glyphId.value, sink)
        }
        cffOutlines?.outline(glyphId.value, sink)?.let { return it }
        return integrated.cff2?.outline(glyphId.value, normalizedCoordinates, sink)
    }

    /** Calculates [glyphId]'s bounds by traversing its outline. */
    public fun glyphBoundingBox(glyphId: GlyphId): FontBounds? = outlineGlyph(glyphId, NoOpOutlineSink)

    /** Returns a thread-safe derived face with [coordinates] applied by axis tag. */
    public fun withVariations(coordinates: Map<Tag, Float>): FontFace {
        val fvar = integrated.fvar ?: return this
        val values = designCoordinates.copyOf()
        for (index in 0 until fvar.axes.size) {
            val axis = fvar.axes[index] ?: continue
            coordinates[axis.tag]?.let { values[index] = it }
        }
        val normalized = normalizeCoordinates(fvar, integrated.avar, values)
        return FontFace(
            rawFace,
            cmap,
            horizontalMetrics,
            trueTypeOutlines,
            os2,
            post,
            verticalMetrics,
            cffOutlines,
            integrated,
            parsedKinds,
            normalized,
            values,
            names,
            unitsPerEm,
            bounds,
            hheaAscender,
            hheaDescender,
            hheaLineGap,
            glyphCount,
            rawVheaAscender,
            rawVheaDescender,
            rawVheaLineGap,
        )
    }

    /** Evaluates an arbitrary MVAR metric tag at this face's coordinates. */
    public fun metricVariation(tag: Tag): Float? =
        integrated.mvar
            ?.takeIf { isVariable }
            ?.metricDelta(tag, normalizedCoordinates)

    /** Returns gvar's four phantom-point deltas at this face's current coordinates. */
    public fun glyphPhantomPoints(glyphId: GlyphId): GlyphPhantomPoints? {
        if (glyphId.value >= glyphCount) return null
        val deltas = phantomDeltas(glyphId) ?: return null
        return GlyphPhantomPoints(
            left = deltas.phantom(0)?.let { FontPointF(it.x, it.y) } ?: return null,
            right = deltas.phantom(1)?.let { FontPointF(it.x, it.y) } ?: return null,
            top = deltas.phantom(2)?.let { FontPointF(it.x, it.y) } ?: return null,
            bottom = deltas.phantom(3)?.let { FontPointF(it.x, it.y) } ?: return null,
        )
    }

    private fun phantomDeltas(glyphId: GlyphId): dev.skaldebane.kttf.internal.tables.variations.GlyphVariationDeltas? {
        val glyf = trueTypeOutlines ?: return null
        val gvar = integrated.gvar ?: return null
        return glyf.phantomDeltas(glyphId.value, gvar, normalizedCoordinates)
    }

    public companion object {
        /** Parses the first face in [data], or throws [FontParsingException]. */
        public fun parse(data: ByteArray): FontFace = parse(data, 0)

        /** Parses [faceIndex] from [data], or throws [FontParsingException]. */
        public fun parse(
            data: ByteArray,
            faceIndex: Int,
        ): FontFace {
            val raw = RawFace.parseResult(data, faceIndex)
            if (raw.face == null) throw FontParsingException(raw.error ?: FontParsingError.MalformedFont)
            val parsed = parseRawFace(raw.face)
            return parsed.face ?: throw FontParsingException(parsed.error ?: FontParsingError.MalformedFont)
        }

        /** Parses the first face in [data], or returns null. */
        public fun parseOrNull(data: ByteArray): FontFace? = parseOrNull(data, 0)

        /** Parses [faceIndex] from [data], or returns null. */
        public fun parseOrNull(
            data: ByteArray,
            faceIndex: Int,
        ): FontFace? {
            val rawFace = RawFace.parseResult(data, faceIndex).face ?: return null
            return parseRawFace(rawFace).face
        }

        /**
         * Parses a face from independently stored table arrays.
         *
         * The map structure is snapshotted, but table arrays are retained without
         * copying and must not be mutated while the face or its views remain in use.
         */
        public fun fromTables(tables: Map<Tag, ByteArray>): FontFace {
            val parsed = parseRawFace(RawFace.fromTables(tables.toMap()))
            return parsed.face ?: throw FontParsingException(parsed.error ?: FontParsingError.MalformedFont)
        }

        /** Nullable counterpart to [fromTables]. */
        public fun fromTablesOrNull(tables: Map<Tag, ByteArray>): FontFace? =
            parseRawFace(RawFace.fromTables(tables.toMap())).face

        private fun parseRawFace(rawFace: RawFace): FontFaceParseResult {
            val tables = rawFace.collectInitialTables()
            val head =
                tables.head?.let(HeadTable::parse)
                    ?: return FontFaceParseResult(null, FontParsingError.MissingOrMalformedHead)
            val hhea =
                tables.hhea?.let(HheaTable::parse)
                    ?: return FontFaceParseResult(null, FontParsingError.MissingOrMalformedHorizontalHeader)
            val maxp =
                tables.maxp?.let(MaxpTable::parse)
                    ?: return FontFaceParseResult(null, FontParsingError.MissingOrMalformedMaximumProfile)
            val hmtx =
                tables.hmtx?.let {
                    HmtxTable.parse(
                        numberOfMetrics = hhea.numberOfMetrics,
                        numberOfGlyphs = maxp.glyphCount,
                        data = it,
                    )
                }
            val cmap = tables.cmap?.let(CmapTable::parse)?.let(::Cmap)
            val loca = tables.loca?.let { LocaTable.parse(maxp.glyphCount, head.longLocationOffsets, it) }
            val glyf = if (loca != null && tables.glyf != null) GlyfTable.parse(loca, tables.glyf) else null
            val name = tables.name?.let(NameTable::parse)
            val os2 = tables.os2?.let(OS2Table::parse)
            val post = tables.post?.let(PostTable::parse)
            val vhea = tables.vhea?.let(VheaTable::parse)
            val vmtx =
                if (vhea != null && tables.vmtx != null) {
                    VmtxTable.parse(vhea.numberOfMetrics, maxp.glyphCount, tables.vmtx)
                } else {
                    null
                }
            val cff = tables.cff?.let { Cff1Table.parse(it, head.unitsPerEm) }
            val fvar = tables.fvar?.let(FvarTable::parse)
            val avar = tables.avar?.let(AvarTable::parse)?.takeIf { it.segmentMaps.size == fvar?.axes?.size }
            val designCoordinates = FloatArray(fvar?.axes?.size ?: 0) { fvar?.axes?.get(it)?.defaultValue ?: 0f }
            val normalizedCoordinates = normalizeCoordinates(fvar, avar, designCoordinates)

            val cpal = tables.cpal?.let(CpalTable::parse)
            val cblc = tables.cblc?.let(CblcTable::parse)
            val eblc = tables.eblc?.let(CblcTable::parse)
            val bloc = tables.bloc?.let(CblcTable::parse)
            val integrated =
                IntegratedTables(
                    cff2 = tables.cff2?.let(Cff2Table::parse),
                    fvar = fvar,
                    avar = avar,
                    gvar = tables.gvar?.let(GvarTable::parse),
                    hvar = tables.hvar?.let(HvarTable::parse),
                    vvar = tables.vvar?.let(VvarTable::parse),
                    mvar = tables.mvar?.let(MvarTable::parse),
                    kern = tables.kern?.let(KernTable::parse),
                    vorg = tables.vorg?.let(dev.skaldebane.kttf.internal.tables.VorgTable::parse),
                    stat = tables.stat?.let(StatTable::parse),
                    cpal = cpal,
                    colr = if (cpal != null && tables.colr != null) ColrTable.parse(cpal, tables.colr) else null,
                    svg = tables.svg?.let(SvgTable::parse),
                    bdat = if (bloc != null && tables.bdat != null) CbdtTable.parse(bloc, tables.bdat) else null,
                    ebdt = if (eblc != null && tables.ebdt != null) CbdtTable.parse(eblc, tables.ebdt) else null,
                    cbdt = if (cblc != null && tables.cbdt != null) CbdtTable.parse(cblc, tables.cbdt) else null,
                    sbix = tables.sbix?.let { SbixTable.parse(maxp.glyphCount, it) },
                    gdef = tables.gdef?.let(GdefTable::parse),
                    gpos = tables.gpos?.let(::parseGpos),
                    gsub = tables.gsub?.let(::parseGsub),
                    math = tables.math?.let(MathTable::parse),
                    ankr = tables.ankr?.let { AnkrTable.parse(maxp.glyphCount, it) },
                    feat = tables.feat?.let(FeatTable::parse),
                    trak = tables.trak?.let(TrakTable::parse),
                    kerx = tables.kerx?.let { KerxTable.parse(maxp.glyphCount, it) },
                    morx = tables.morx?.let { MorxTable.parse(maxp.glyphCount, it) },
                )
            val parsedKinds =
                buildList {
                    add(ParsedTableKind.Head)
                    add(ParsedTableKind.HorizontalHeader)
                    add(ParsedTableKind.MaximumProfile)
                    if (cmap != null) add(ParsedTableKind.Cmap)
                    if (hmtx != null) add(ParsedTableKind.HorizontalMetrics)
                    if (loca != null) add(ParsedTableKind.Locations)
                    if (glyf != null) add(ParsedTableKind.TrueTypeOutlines)
                    if (name != null) add(ParsedTableKind.Names)
                    if (os2 != null) add(ParsedTableKind.Os2)
                    if (post != null) add(ParsedTableKind.Post)
                    if (vhea != null) add(ParsedTableKind.VerticalHeader)
                    if (vmtx != null) add(ParsedTableKind.VerticalMetrics)
                    if (cff != null) add(ParsedTableKind.Cff1)
                    if (integrated.cff2 != null) add(ParsedTableKind.Cff2)
                    if (integrated.fvar != null) add(ParsedTableKind.FontVariations)
                    if (integrated.avar != null) add(ParsedTableKind.AxisVariations)
                    if (integrated.gvar != null) add(ParsedTableKind.GlyphVariations)
                    if (integrated.hvar != null) add(ParsedTableKind.HorizontalMetricVariations)
                    if (integrated.vvar != null) add(ParsedTableKind.VerticalMetricVariations)
                    if (integrated.mvar != null) add(ParsedTableKind.MetricVariations)
                    if (integrated.kern != null) add(ParsedTableKind.Kerning)
                    if (integrated.vorg != null) add(ParsedTableKind.VerticalOrigins)
                    if (integrated.stat != null) add(ParsedTableKind.StyleAttributes)
                    if (integrated.cpal != null) add(ParsedTableKind.ColorPalettes)
                    if (integrated.colr != null) add(ParsedTableKind.ColorOutlines)
                    if (integrated.svg != null) add(ParsedTableKind.Svg)
                    if (cblc != null) add(ParsedTableKind.ColorBitmapLocations)
                    if (integrated.cbdt != null) add(ParsedTableKind.ColorBitmapData)
                    if (eblc != null) add(ParsedTableKind.EmbeddedBitmapLocations)
                    if (integrated.ebdt != null) add(ParsedTableKind.EmbeddedBitmapData)
                    if (bloc != null) add(ParsedTableKind.AppleBitmapLocations)
                    if (integrated.bdat != null) add(ParsedTableKind.AppleBitmapData)
                    if (integrated.sbix != null) add(ParsedTableKind.StandardBitmapGraphics)
                    if (integrated.gdef != null) add(ParsedTableKind.GlyphDefinitions)
                    if (integrated.gpos != null) add(ParsedTableKind.GlyphPositioning)
                    if (integrated.gsub != null) add(ParsedTableKind.GlyphSubstitutions)
                    if (integrated.math != null) add(ParsedTableKind.Math)
                    if (integrated.ankr != null) add(ParsedTableKind.AppleAnchors)
                    if (integrated.feat != null) add(ParsedTableKind.AppleFeatures)
                    if (integrated.trak != null) add(ParsedTableKind.AppleTracking)
                    if (integrated.kerx != null) add(ParsedTableKind.AppleKerning)
                    if (integrated.morx != null) add(ParsedTableKind.AppleMetamorphosis)
                }

            return FontFaceParseResult(
                FontFace(
                    rawFace = rawFace,
                    cmap = cmap,
                    horizontalMetrics = hmtx,
                    trueTypeOutlines = glyf,
                    os2 = os2,
                    post = post,
                    verticalMetrics = vmtx,
                    cffOutlines = cff,
                    integrated = integrated,
                    parsedKinds = parsedKinds,
                    normalizedCoordinates = normalizedCoordinates,
                    designCoordinates = designCoordinates,
                    names = Names(name),
                    unitsPerEm = head.unitsPerEm,
                    bounds = head.bounds,
                    hheaAscender = hhea.ascender,
                    hheaDescender = hhea.descender,
                    hheaLineGap = hhea.lineGap,
                    glyphCount = maxp.glyphCount,
                    rawVheaAscender = vhea?.ascender,
                    rawVheaDescender = vhea?.descender,
                    rawVheaLineGap = vhea?.lineGap,
                ),
                null,
            )
        }
    }

    private fun variedMetric(
        tag: String,
        value: Int,
    ): Int {
        if (!isVariable) return value
        val delta = integrated.mvar?.metricDelta(Tag.parse(tag), normalizedCoordinates) ?: return value
        return (value.toFloat() + delta).toIntIn(Short.MIN_VALUE..Short.MAX_VALUE) ?: value
    }

    private fun variedGlyphAdvance(
        value: Int,
        delta: Float?,
    ): Int? = if (delta == null) value else (value.toFloat() + delta + 0.5f).toIntIn(0..0xFFFF)

    private fun variedGlyphSignedMetric(
        value: Int,
        delta: Float?,
    ): Int? =
        if (delta == null) {
            value
        } else {
            (value.toFloat() + delta + 0.5f).toIntIn(Short.MIN_VALUE..Short.MAX_VALUE)
        }
}

private fun Float.toIntIn(range: IntRange): Int? {
    if (!isFinite()) return null
    val converted = toInt()
    return converted.takeIf { it in range }
}

private data class FontFaceParseResult(
    val face: FontFace?,
    val error: FontParsingError?,
)

private class IntegratedTables(
    val cff2: Cff2Table?,
    val fvar: FvarTable?,
    val avar: AvarTable?,
    val gvar: GvarTable?,
    val hvar: HvarTable?,
    val vvar: VvarTable?,
    val mvar: MvarTable?,
    val kern: KernTable?,
    val vorg: dev.skaldebane.kttf.internal.tables.VorgTable?,
    val stat: StatTable?,
    val cpal: CpalTable?,
    val colr: ColrTable?,
    val svg: SvgTable?,
    val bdat: CbdtTable?,
    val ebdt: CbdtTable?,
    val cbdt: CbdtTable?,
    val sbix: SbixTable?,
    val gdef: GdefTable?,
    val gpos: GposTable?,
    val gsub: GsubTable?,
    val math: MathTable?,
    val ankr: AnkrTable?,
    val feat: FeatTable?,
    val trak: TrakTable?,
    val kerx: KerxTable?,
    val morx: MorxTable?,
)

private fun normalizeCoordinates(
    fvar: FvarTable?,
    avar: AvarTable?,
    values: FloatArray,
): VariationCoordinates {
    if (fvar == null) return VariationCoordinates.zeros(0)!!
    val normalized =
        VariationCoordinates.copyOf(
            List(fvar.axes.size) { index ->
                fvar.axes[index]?.normalize(values.getOrElse(index) { 0f }) ?: NormalizedCoordinate.ZERO
            },
        )
    if (avar == null) return normalized
    val mapped =
        VariationCoordinates.copyOf(
            List(normalized.size) { normalized[it] ?: NormalizedCoordinate.ZERO },
        )
    return if (avar.mapAll(mapped)) mapped else normalized
}

private fun Int.isUnicodeScalar(): Boolean = this in 0..0x10FFFF && this !in 0xD800..0xDFFF

private data object NoOpOutlineSink : OutlineSink {
    override fun moveTo(
        x: Float,
        y: Float,
    ): Unit = Unit

    override fun lineTo(
        x: Float,
        y: Float,
    ): Unit = Unit

    override fun quadTo(
        controlX: Float,
        controlY: Float,
        x: Float,
        y: Float,
    ): Unit = Unit

    override fun curveTo(
        control1X: Float,
        control1Y: Float,
        control2X: Float,
        control2Y: Float,
        x: Float,
        y: Float,
    ): Unit = Unit

    override fun close(): Unit = Unit
}

/**
 * Returns the unsigned 32-bit face count stored by a TrueType/OpenType
 * collection, or null when [data] is not a collection or is too short.
 *
 * This is intentionally a shallow query and does not validate the collection's
 * face-offset array.
 */
public fun fontsInCollection(data: ByteArray): Long? = RawFace.fontsInCollection(data)
