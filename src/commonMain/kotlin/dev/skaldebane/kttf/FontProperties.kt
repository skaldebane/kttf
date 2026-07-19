package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.OS2Permissions
import dev.skaldebane.kttf.internal.tables.OS2Style
import dev.skaldebane.kttf.internal.tables.OS2UnicodeRanges
import dev.skaldebane.kttf.internal.tables.OS2Width
import kotlin.jvm.JvmInline

/** Numeric OpenType font weight, conventionally from 1 through 1000. */
@JvmInline
public value class FontWeight(
    public val value: Int,
) {
    public companion object {
        public val Normal: FontWeight = FontWeight(400)
        public val Bold: FontWeight = FontWeight(700)
    }
}

/** OpenType width classification. */
public enum class FontWidth(
    public val value: Int,
) {
    UltraCondensed(1),
    ExtraCondensed(2),
    Condensed(3),
    SemiCondensed(4),
    Normal(5),
    SemiExpanded(6),
    Expanded(7),
    ExtraExpanded(8),
    UltraExpanded(9),
}

/** Style selected from OS/2 metadata. */
public enum class FontStyle {
    Normal,
    Italic,
    Oblique,
}

/** Embedding permissions declared by OS/2. */
public enum class EmbeddingPermissions {
    Installable,
    Restricted,
    PreviewAndPrint,
    Editable,
}

/** Position and thickness of a font-space decoration line. */
public data class FontLineMetrics(
    public val position: Int,
    public val thickness: Int,
)

/** Font-space size and offset recommended for subscript or superscript text. */
public data class FontScriptMetrics(
    public val xSize: Int,
    public val ySize: Int,
    public val xOffset: Int,
    public val yOffset: Int,
)

/** Binary OS/2 Unicode-range declarations. */
public class UnicodeRanges internal constructor(
    private val ranges: OS2UnicodeRanges?,
) {
    public fun contains(codePoint: Int): Boolean = ranges?.contains(codePoint) ?: false

    public fun contains(character: Char): Boolean = contains(character.code)

    public fun containsRange(index: Int): Boolean = ranges?.containsRange(index) ?: false
}

internal fun OS2Width.toPublic(): FontWidth = FontWidth.entries[value - 1]

internal fun OS2Style.toPublic(): FontStyle =
    when (this) {
        OS2Style.NORMAL -> FontStyle.Normal
        OS2Style.ITALIC -> FontStyle.Italic
        OS2Style.OBLIQUE -> FontStyle.Oblique
    }

internal fun OS2Permissions.toPublic(): EmbeddingPermissions =
    when (this) {
        OS2Permissions.INSTALLABLE -> EmbeddingPermissions.Installable
        OS2Permissions.RESTRICTED -> EmbeddingPermissions.Restricted
        OS2Permissions.PREVIEW_AND_PRINT -> EmbeddingPermissions.PreviewAndPrint
        OS2Permissions.EDITABLE -> EmbeddingPermissions.Editable
    }
