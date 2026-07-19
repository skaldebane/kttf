package dev.skaldebane.kttf

/** A font-space bounding rectangle. */
public data class FontBounds(
    public val xMin: Int,
    public val yMin: Int,
    public val xMax: Int,
    public val yMax: Int,
) {
    public val width: Int
        get() = xMax - xMin

    public val height: Int
        get() = yMax - yMin

    public fun contains(
        x: Int,
        y: Int,
    ): Boolean = x in xMin..xMax && y in yMin..yMax
}
