package dev.skaldebane.kttf.internal.tables

import dev.skaldebane.kttf.internal.parser.ByteSlice

internal class HmtxTable private constructor(
    private val metrics: ByteSlice,
    private val bearings: ByteSlice,
    private val metricsCount: Int,
    private val addressableGlyphCount: Int,
) {
    internal fun advance(glyphIndex: Int): Int? {
        if (glyphIndex !in 0 until addressableGlyphCount) return null
        val metricIndex = minOf(glyphIndex, metricsCount - 1)
        return metrics.readUInt16(metricIndex * METRIC_SIZE)
    }

    internal fun sideBearing(glyphIndex: Int): Int? {
        if (glyphIndex !in 0 until addressableGlyphCount) return null
        if (glyphIndex < metricsCount) return metrics.readInt16(glyphIndex * METRIC_SIZE + 2)
        return bearings.readInt16((glyphIndex - metricsCount) * 2)
    }

    internal companion object {
        private const val METRIC_SIZE: Int = 4

        internal fun parse(
            numberOfMetrics: Int,
            numberOfGlyphs: Int,
            data: ByteSlice,
        ): HmtxTable? {
            if (numberOfMetrics <= 0 || numberOfGlyphs <= 0) return null
            if (numberOfMetrics > data.size / METRIC_SIZE) return null

            val metricsLength = numberOfMetrics * METRIC_SIZE
            val metrics = data.slice(0, metricsLength) ?: return null
            val bearingCount = (numberOfGlyphs - numberOfMetrics).coerceAtLeast(0)
            val bearingsLength = if (bearingCount <= (Int.MAX_VALUE / 2)) bearingCount * 2 else return null
            val bearings = data.slice(metricsLength, bearingsLength) ?: data.slice(metricsLength, 0) ?: return null

            return HmtxTable(
                metrics = metrics,
                bearings = bearings,
                metricsCount = numberOfMetrics,
                addressableGlyphCount = maxOf(numberOfMetrics, numberOfGlyphs),
            )
        }
    }
}
