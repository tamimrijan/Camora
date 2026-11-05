package com.tamimrijan.camora.util

import kotlin.math.abs
import kotlin.math.roundToInt

/** Plus/minus prefix using the typographic minus sign (U+2212), never a hyphen. */
private fun sign(v: Float) = if (v > 0f) "+" else if (v < 0f) "−" else ""

/** e.g. "+0.7", "−0.3", "0.0" */
fun signed(v: Float, decimals: Int = 1): String {
    val a = "%.${decimals}f".format(abs(v))
    return if (a.trimEnd('0', '.').isEmpty()) a else sign(v) + a
}

/** e.g. "+35", "−12", "0" */
fun signedInt(v: Int): String = if (v == 0) "0" else sign(v.toFloat()) + abs(v)

fun signedPercent(v: Float): String = signedInt((v * 100).roundToInt())
