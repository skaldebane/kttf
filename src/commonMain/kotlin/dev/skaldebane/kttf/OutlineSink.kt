package dev.skaldebane.kttf

/** Receives a glyph outline synchronously in font-space coordinates. */
public interface OutlineSink {
    public fun moveTo(
        x: Float,
        y: Float,
    )

    public fun lineTo(
        x: Float,
        y: Float,
    )

    public fun quadTo(
        controlX: Float,
        controlY: Float,
        x: Float,
        y: Float,
    )

    public fun curveTo(
        control1X: Float,
        control1Y: Float,
        control2X: Float,
        control2Y: Float,
        x: Float,
        y: Float,
    )

    public fun close()
}
