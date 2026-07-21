package dev.skaldebane.kttf.internal.tables.cff

internal fun Double.toIntExactOrNull(): Int? {
    if (!isFinite() || this < Int.MIN_VALUE.toDouble() || this > Int.MAX_VALUE.toDouble()) return null
    val integer = toInt()
    return integer.takeIf { integer.toDouble() == this }
}

internal fun Double.toNonNegativeIntExactOrNull(): Int? = toIntExactOrNull()?.takeIf { it >= 0 }

internal fun Float.toIntExactOrNull(): Int? {
    // Workaround for https://youtrack.jetbrains.com/issue/KT-35422.
    if (this == Int.MIN_VALUE.toFloat()) return Int.MIN_VALUE
    if (!isFinite() || this < -2_147_483_648f || this >= 2_147_483_648f) return null
    val integer = toInt()
    return integer.takeIf { integer.toFloat() == this }
}

internal fun Double.toFiniteFloatOrNull(): Float? {
    if (!isFinite() || this < -Float.MAX_VALUE.toDouble() || this > Float.MAX_VALUE.toDouble()) return null
    return toFloat()
}
