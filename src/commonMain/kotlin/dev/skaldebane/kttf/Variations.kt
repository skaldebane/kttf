package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.variations.FvarNamedInstance
import dev.skaldebane.kttf.internal.tables.variations.FvarTable
import dev.skaldebane.kttf.internal.tables.variations.VariationAxis

/** Metadata for one design-space axis. */
public data class FontVariationAxis(
    public val tag: Tag,
    public val minimum: Float,
    public val default: Float,
    public val maximum: Float,
    public val nameId: Int,
    public val isHidden: Boolean,
)

/** A named location in a variable font's design space. */
public data class FontVariationInstance(
    public val subfamilyNameId: Int,
    public val flags: Int,
    public val coordinates: List<Float>,
    public val postScriptNameId: Int?,
)

/** A floating-point point in font units. */
public data class FontPointF(
    public val x: Float,
    public val y: Float,
)

/** The four TrueType phantom-point deltas for a glyph at the face's current coordinates. */
public data class GlyphPhantomPoints(
    public val left: FontPointF,
    public val right: FontPointF,
    public val top: FontPointF,
    public val bottom: FontPointF,
)

/** Indexed, iterable variable-font axes backed by the source font. */
public class FontVariationAxes internal constructor(
    private val table: FvarTable?,
) : Iterable<FontVariationAxis> {
    public val size: Int
        get() = table?.axes?.size ?: 0

    public val isEmpty: Boolean
        get() = size == 0

    public operator fun get(index: Int): FontVariationAxis? = table?.axes?.get(index)?.toPublic()

    override fun iterator(): Iterator<FontVariationAxis> =
        object : Iterator<FontVariationAxis> {
            private var index: Int = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): FontVariationAxis = get(index++) ?: throw NoSuchElementException()
        }
}

/** Indexed, iterable named variable-font instances backed by the source font. */
public class FontVariationInstances internal constructor(
    private val table: FvarTable?,
) : Iterable<FontVariationInstance> {
    public val size: Int
        get() = table?.namedInstances?.size ?: 0

    public val isEmpty: Boolean
        get() = size == 0

    public operator fun get(index: Int): FontVariationInstance? = table?.namedInstances?.get(index)?.toPublic()

    override fun iterator(): Iterator<FontVariationInstance> =
        object : Iterator<FontVariationInstance> {
            private var index: Int = 0

            override fun hasNext(): Boolean = index < size

            override fun next(): FontVariationInstance = get(index++) ?: throw NoSuchElementException()
        }
}

private fun VariationAxis.toPublic(): FontVariationAxis =
    FontVariationAxis(tag, minValue, defaultValue, maxValue, nameId, hidden)

private fun FvarNamedInstance.toPublic(): FontVariationInstance =
    FontVariationInstance(
        subfamilyNameId,
        flags,
        List(coordinates.size) { coordinates[it] ?: 0f },
        postScriptNameId,
    )
