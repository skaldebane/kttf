package dev.skaldebane.kttf.internal.tables.cff

internal fun Double.toIntExactOrNull(): Int? {
    if (!isFinite() || this < Int.MIN_VALUE.toDouble() || this > Int.MAX_VALUE.toDouble()) return null
    val integer = toInt()
    return integer.takeIf { integer.toDouble() == this }
}

internal fun Double.toNonNegativeIntExactOrNull(): Int? = toIntExactOrNull()?.takeIf { it >= 0 }

internal fun Float.toIntExactOrNull(): Int? = toDouble().toIntExactOrNull()

internal fun Double.toFiniteFloatOrNull(): Float? {
    if (!isFinite() || this < -Float.MAX_VALUE.toDouble() || this > Float.MAX_VALUE.toDouble()) return null
    return toFloat()
}
