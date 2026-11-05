package com.tamimrijan.camora.engine

import kotlin.math.cbrt
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow

internal fun clamp01(x: Float): Float = if (x < 0f) 0f else if (x > 1f) 1f else x
internal fun clampf(x: Float, lo: Float, hi: Float): Float = if (x < lo) lo else if (x > hi) hi else x
internal fun clampi(x: Int, lo: Int, hi: Int): Int = if (x < lo) lo else if (x > hi) hi else x
internal fun lerpf(a: Float, b: Float, t: Float): Float = a + (b - a) * t
internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = clamp01((x - e0) / (e1 - e0))
    return t * t * (3f - 2f * t)
}

/**
 * Colour-space helpers shared by the CPU engine and (mirrored in GLSL) by the live
 * preview shader. Everything that both paths use is defined here once so the
 * viewfinder and the final photo agree.
 */
object ColorMath {
    // Rec.709 / sRGB luminance weights (linear light)
    const val KR = 0.2126f
    const val KG = 0.7152f
    const val KB = 0.0722f

    fun srgbToLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    fun linearToSrgb(l: Float): Float =
        if (l <= 0.0031308f) l * 12.92f else (1.055 * l.toDouble().pow(1.0 / 2.4) - 0.055).toFloat()

    private const val DEC_SIZE = 4096
    private const val ENC_SIZE = 8192
    private val decTable = FloatArray(DEC_SIZE + 2) { srgbToLinear(minOf(it, DEC_SIZE) / DEC_SIZE.toFloat()) }
    private val encTable = FloatArray(ENC_SIZE + 2) { linearToSrgb(minOf(it, ENC_SIZE) / ENC_SIZE.toFloat()) }

    /** 8-bit sRGB code value -> linear light. */
    val SRGB8_TO_LINEAR = FloatArray(256) { srgbToLinear(it / 255f) }

    /** Fast sRGB decode for values in [0,1] (linear interpolation in a 4096-entry table). */
    fun decode(v: Float): Float {
        if (v <= 0f) return 0f
        if (v >= 1f) return if (v == 1f) 1f else srgbToLinear(v)
        val f = v * DEC_SIZE
        val i = f.toInt()
        val t = f - i
        return decTable[i] + (decTable[i + 1] - decTable[i]) * t
    }

    /** Fast sRGB encode for linear values; output is clamped to [0,1]. */
    fun encode(l: Float): Float {
        if (l <= 0f) return 0f
        if (l >= 1f) return 1f
        val f = l * ENC_SIZE
        val i = f.toInt()
        val t = f - i
        return encTable[i] + (encTable[i + 1] - encTable[i]) * t
    }

    fun luminance(r: Float, g: Float, b: Float): Float = KR * r + KG * g + KB * b

    // ---- fast log2 / exp2 for per-pixel work --------------------------------------------
    /** log2 with ~0.003 absolute error; x must be > 0. */
    fun fastLog2(x: Float): Float {
        val bits = java.lang.Float.floatToRawIntBits(x)
        val e = ((bits ushr 23) and 0xFF) - 127
        val m = java.lang.Float.intBitsToFloat((bits and 0x007FFFFF) or 0x3F800000) // [1,2)
        // quartic approximation of ln(m) on [1,2), converted to log2
        val p = -1.7417939f + (2.8212026f + (-1.4699568f + (0.44717955f - 0.056570851f * m) * m) * m) * m
        return e + p * 1.4426950f
    }

    private const val EXP_MIN = -10f
    private const val EXP_MAX = 10f
    private const val EXP_STEPS = 4096
    private val exp2Table = FloatArray(EXP_STEPS + 2) {
        2.0.pow((EXP_MIN + (EXP_MAX - EXP_MIN) * minOf(it, EXP_STEPS) / EXP_STEPS).toDouble()).toFloat()
    }

    fun fastExp2(x: Float): Float {
        val c = clampf(x, EXP_MIN, EXP_MAX)
        val f = (c - EXP_MIN) * (EXP_STEPS / (EXP_MAX - EXP_MIN))
        val i = f.toInt()
        val t = f - i
        return exp2Table[i] + (exp2Table[i + 1] - exp2Table[i]) * t
    }

    /** log2 of linear luminance for an 8-bit pixel, guarded against zero. */
    fun log2Lum(argb: Int): Float {
        val r = SRGB8_TO_LINEAR[(argb shr 16) and 0xFF]
        val g = SRGB8_TO_LINEAR[(argb shr 8) and 0xFF]
        val b = SRGB8_TO_LINEAR[argb and 0xFF]
        return log2(max(luminance(r, g, b), MIN_LUM))
    }

    const val MIN_LUM = 1f / 16384f

    // ---- OKLab (Björn Ottosson) -----------------------------------------------------------
    fun linearToOklab(r: Float, g: Float, b: Float, out: FloatArray) {
        val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
        val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
        val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b
        val l_ = cbrt(l)
        val m_ = cbrt(m)
        val s_ = cbrt(s)
        out[0] = (0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_).toFloat()
        out[1] = (1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_).toFloat()
        out[2] = (0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_).toFloat()
    }

    fun oklabToLinear(L: Float, a: Float, b: Float, out: FloatArray) {
        val l_ = L + 0.3963377774 * a + 0.2158037573 * b
        val m_ = L - 0.1055613458 * a - 0.0638541728 * b
        val s_ = L - 0.0894841775 * a - 1.2914855480 * b
        val l = l_ * l_ * l_
        val m = m_ * m_ * m_
        val s = s_ * s_ * s_
        out[0] = (4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s).toFloat()
        out[1] = (-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s).toFloat()
        out[2] = (-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s).toFloat()
    }

    fun pack(r: Float, g: Float, b: Float): Int {
        val ri = (clamp01(r) * 255f + 0.5f).toInt()
        val gi = (clamp01(g) * 255f + 0.5f).toInt()
        val bi = (clamp01(b) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }
}

/**
 * Highlight soft-clip shared with the preview shader: identity below the knee, then a
 * tanh shoulder. Ratio preserving (hue stays put) with a gentle desaturation toward
 * white for strongly over-range values, which is how film and the iPhone pipeline roll
 * off bright skies instead of clipping them to cyan/yellow.
 */
object SoftClip {
    const val KNEE = 0.9f

    /** Operates in place on a 3-element linear RGB array. */
    fun apply(rgb: FloatArray) {
        val m = max(rgb[0], max(rgb[1], rgb[2]))
        if (m <= KNEE) return
        val t = (m - KNEE) / (1f - KNEE)
        val mNew = KNEE + (1f - KNEE) * tanhApprox(t)
        val scale = mNew / m
        val over = clamp01((m - 1f) / 1.5f) * 0.6f
        for (i in 0..2) {
            val v = rgb[i] * scale
            rgb[i] = v + (mNew - v) * over
        }
    }

    fun tanhApprox(x: Float): Float {
        if (x > 4f) return 1f
        val e = ColorMath.fastExp2(-2.8853900818f * x) // e^(-2x)
        return (1f - e) / (1f + e)
    }
}
