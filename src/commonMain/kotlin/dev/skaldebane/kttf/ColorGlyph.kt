package dev.skaldebane.kttf

import dev.skaldebane.kttf.internal.tables.ColorRgba
import dev.skaldebane.kttf.internal.tables.ColrClipBox
import dev.skaldebane.kttf.internal.tables.ColrCompositeMode
import dev.skaldebane.kttf.internal.tables.ColrGradientExtend
import dev.skaldebane.kttf.internal.tables.ColrPaint
import dev.skaldebane.kttf.internal.tables.ColrPainter
import dev.skaldebane.kttf.internal.tables.ColrTable
import dev.skaldebane.kttf.internal.tables.ColrTransform
import dev.skaldebane.kttf.internal.tables.SvgDocument

/** Eight-bit non-premultiplied RGBA color. */
public data class FontColor(
    public val red: Int,
    public val green: Int,
    public val blue: Int,
    public val alpha: Int,
) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
    }
}

public data class FontTransform(
    public val a: Float,
    public val b: Float,
    public val c: Float,
    public val d: Float,
    public val e: Float,
    public val f: Float,
)

public data class ColorClipBox(
    public val xMin: Float,
    public val yMin: Float,
    public val xMax: Float,
    public val yMax: Float,
)

public enum class GradientExtend {
    Pad,
    Repeat,
    Reflect,
}

public enum class ColorCompositeMode {
    Clear,
    Source,
    Destination,
    SourceOver,
    DestinationOver,
    SourceIn,
    DestinationIn,
    SourceOut,
    DestinationOut,
    SourceAtop,
    DestinationAtop,
    Xor,
    Plus,
    Screen,
    Overlay,
    Darken,
    Lighten,
    ColorDodge,
    ColorBurn,
    HardLight,
    SoftLight,
    Difference,
    Exclusion,
    Multiply,
    Hue,
    Saturation,
    Color,
    Luminosity,
}

public data class ColorStop(
    public val offset: Float,
    public val color: FontColor,
)

public sealed interface ColorPaint {
    public data class Solid(
        public val color: FontColor,
    ) : ColorPaint

    public data class LinearGradient(
        public val x0: Float,
        public val y0: Float,
        public val x1: Float,
        public val y1: Float,
        public val x2: Float,
        public val y2: Float,
        public val extend: GradientExtend,
        public val stops: List<ColorStop>,
    ) : ColorPaint

    public data class RadialGradient(
        public val x0: Float,
        public val y0: Float,
        public val radius0: Float,
        public val x1: Float,
        public val y1: Float,
        public val radius1: Float,
        public val extend: GradientExtend,
        public val stops: List<ColorStop>,
    ) : ColorPaint

    public data class SweepGradient(
        public val centerX: Float,
        public val centerY: Float,
        public val startAngle: Float,
        public val endAngle: Float,
        public val extend: GradientExtend,
        public val stops: List<ColorStop>,
    ) : ColorPaint
}

/** Receives a balanced command stream for one COLR glyph. */
public interface ColorGlyphPainter {
    public fun outlineGlyph(glyphId: GlyphId)

    public fun paint(paint: ColorPaint)

    public fun pushClip()

    public fun pushClipBox(clipBox: ColorClipBox)

    public fun popClip()

    public fun pushLayer(mode: ColorCompositeMode)

    public fun popLayer()

    public fun pushTransform(transform: FontTransform)

    public fun popTransform()
}

/** Bounded, zero-copy SVG or SVGZ document data. */
public class SvgGlyphDocument internal constructor(
    private val document: SvgDocument,
) {
    public val startGlyphId: GlyphId
        get() = GlyphId(document.startGlyphId)

    public val endGlyphId: GlyphId
        get() = GlyphId(document.endGlyphId)

    public val size: Int
        get() = document.size

    public operator fun get(index: Int): Byte =
        document[index] ?: throw IndexOutOfBoundsException("index: $index, size: $size")

    public fun copyInto(
        destination: ByteArray,
        destinationOffset: Int = 0,
    ): ByteArray {
        if (!document.copyInto(destination, destinationOffset)) throw IndexOutOfBoundsException("SVG data does not fit")
        return destination
    }
}

internal fun ColrTable.paintPublic(
    glyphId: GlyphId,
    palette: Int,
    foreground: FontColor,
    painter: ColorGlyphPainter,
    coordinates: FloatArray,
): Boolean = paint(glyphId.value, palette, foreground.toInternal(), PublicPainter(painter), coordinates)

private class PublicPainter(
    private val sink: ColorGlyphPainter,
) : ColrPainter {
    override fun outlineGlyph(glyphId: Int): Unit = sink.outlineGlyph(GlyphId(glyphId))

    override fun paint(paint: ColrPaint): Unit = sink.paint(paint.toPublic())

    override fun pushClip(): Unit = sink.pushClip()

    override fun pushClipBox(clipBox: ColrClipBox): Unit = sink.pushClipBox(clipBox.toPublic())

    override fun popClip(): Unit = sink.popClip()

    override fun pushLayer(mode: ColrCompositeMode): Unit = sink.pushLayer(mode.toPublic())

    override fun popLayer(): Unit = sink.popLayer()

    override fun pushTransform(transform: ColrTransform): Unit = sink.pushTransform(transform.toPublic())

    override fun popTransform(): Unit = sink.popTransform()
}

private fun FontColor.toInternal(): ColorRgba = ColorRgba(red, green, blue, alpha)

private fun ColorRgba.toPublic(): FontColor = FontColor(red, green, blue, alpha)

internal fun ColrClipBox.toPublic(): ColorClipBox = ColorClipBox(xMin, yMin, xMax, yMax)

private fun ColrTransform.toPublic(): FontTransform = FontTransform(a, b, c, d, e, f)

private fun ColrGradientExtend.toPublic(): GradientExtend = GradientExtend.entries[ordinal]

private fun ColrCompositeMode.toPublic(): ColorCompositeMode = ColorCompositeMode.entries[ordinal]

private fun ColrPaint.toPublic(): ColorPaint =
    when (this) {
        is ColrPaint.Solid -> {
            ColorPaint.Solid(color.toPublic())
        }

        is ColrPaint.LinearGradient -> {
            ColorPaint.LinearGradient(
                x0,
                y0,
                x1,
                y1,
                x2,
                y2,
                extend.toPublic(),
                stops.map {
                    ColorStop(it.offset, it.color.toPublic())
                },
            )
        }

        is ColrPaint.RadialGradient -> {
            ColorPaint.RadialGradient(
                x0,
                y0,
                r0,
                x1,
                y1,
                r1,
                extend.toPublic(),
                stops.map {
                    ColorStop(it.offset, it.color.toPublic())
                },
            )
        }

        is ColrPaint.SweepGradient -> {
            ColorPaint.SweepGradient(
                centerX,
                centerY,
                startAngle,
                endAngle,
                extend.toPublic(),
                stops.map {
                    ColorStop(it.offset, it.color.toPublic())
                },
            )
        }
    }
