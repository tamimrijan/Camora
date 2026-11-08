package com.tamimrijan.camora.engine

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

enum class ColorProfileId(val label: String, val description: String) {
    ORIGINAL("Original", "Camera colour, no profile"),
    NATURAL("Natural", "Soft, balanced, true to life colour with gentle highlights"),
    WARM("Warm", "Golden, flattering warmth for skin and evening light"),
    COOL("Cool", "Clean, crisp whites and calm blues"),
    VIVID("Vivid", "Richer colour and contrast, skin tones protected"),
}

/**
 * A colour profile is a parametric "look" evaluated in OKLab/OKLCh, which is perceptually
 * uniform: changing chroma does not shift hue, and changing lightness does not change
 * saturation. Each profile is baked into a 33^3 3D LUT that is used identically by the
 * GPU viewfinder and the CPU capture pipeline.
 */
data class ProfileSpec(
    val warmth: Float = 0f,        // + warmer (applied as white-preserving linear gains)
    val tint: Float = 0f,          // + magenta / - green
    val toeSlope: Float = 1f,      // slope of the tone curve at black (1 = neutral)
    val contrast: Float = 1f,      // slope at middle grey
    val shoulderSlope: Float = 1f, // slope at white (<1 = soft highlight roll-off)
    val saturation: Float = 1f,
    val vibrance: Float = 0f,      // boosts low-chroma colours more than saturated ones
    val chromaKnee: Float = 0.2f,  // soft-limit chroma above this to avoid neon/clipping
    val skinPull: Float = 0f,      // 0..1 how much skin hues converge to a pleasant hue
    val skinSat: Float = 1f,
    val skinLight: Float = 0f,
    val foliageSat: Float = 1f,
    val foliageHue: Float = 0f,    // degrees, + = toward cyan/blue-green
    val skySat: Float = 1f,
    val skyHue: Float = 0f,        // degrees, - = toward cyan
    val skyLight: Float = 0f,
    val redSat: Float = 1f,
) {
    companion object {
        // Natural: neutral-warm balance, flat-ish midtone contrast with a soft shoulder,
        // restrained saturation (Samsung's ISP already pushes chroma), golden-not-red skin,
        // less neon foliage and slightly cyan, lighter skies.
        val NATURAL = ProfileSpec(
            warmth = 0.018f, tint = 0.004f, toeSlope = 1.0f, contrast = 1.04f, shoulderSlope = 0.8f,
            saturation = 0.96f, vibrance = 0.10f, chromaKnee = 0.17f,
            skinPull = 0.22f, skinSat = 0.94f, skinLight = 0.005f,
            foliageSat = 0.92f, foliageHue = 2.5f, skySat = 1.02f, skyHue = -4f, skyLight = 0.01f, redSat = 0.95f,
        )
        val WARM = ProfileSpec(
            warmth = 0.05f, tint = 0.008f, toeSlope = 1.0f, contrast = 1.03f, shoulderSlope = 0.75f,
            saturation = 0.99f, vibrance = 0.12f, chromaKnee = 0.17f,
            skinPull = 0.2f, skinSat = 0.98f, skinLight = 0.012f,
            foliageSat = 0.95f, foliageHue = -2f, skySat = 0.98f, skyHue = -2f, skyLight = 0.005f, redSat = 0.97f,
        )
        val COOL = ProfileSpec(
            warmth = -0.035f, tint = -0.002f, toeSlope = 1.02f, contrast = 1.07f, shoulderSlope = 0.8f,
            saturation = 0.96f, vibrance = 0.06f, chromaKnee = 0.18f,
            skinPull = 0.25f, skinSat = 0.92f, skinLight = 0.006f,
            foliageSat = 0.94f, foliageHue = 4f, skySat = 1.06f, skyHue = -6f, skyLight = 0.012f, redSat = 0.94f,
        )
        // Vivid: iPhone style punch. Deeper blacks and more midtone contrast, clearly richer
        // colour (a higher chroma ceiling so blues, greens and reds can really saturate), deep
        // blue skies and lush foliage. Skin is pulled to a pleasant hue and kept only slightly
        // richer, so faces stay believable while everything else pops.
        val VIVID = ProfileSpec(
            warmth = 0.014f, tint = 0.003f, toeSlope = 0.84f, contrast = 1.2f, shoulderSlope = 0.62f,
            saturation = 1.24f, vibrance = 0.3f, chromaKnee = 0.27f,
            skinPull = 0.3f, skinSat = 0.77f, skinLight = 0.004f,
            foliageSat = 1.12f, foliageHue = 0.5f, skySat = 1.16f, skyHue = -4f, skyLight = -0.006f, redSat = 1.06f,
        )

        fun of(id: ColorProfileId): ProfileSpec? = when (id) {
            ColorProfileId.ORIGINAL -> null
            ColorProfileId.NATURAL -> NATURAL
            ColorProfileId.WARM -> WARM
            ColorProfileId.COOL -> COOL
            ColorProfileId.VIVID -> VIVID
        }
    }

    /** Folds the editor's manual sliders into the look. */
    fun withAdjust(a: UserAdjust): ProfileSpec = if (a.contrast == 0f && a.saturation == 0f && a.warmth == 0f && a.tint == 0f) this else copy(
        warmth = warmth + a.warmth * 0.07f,
        tint = tint + a.tint * 0.05f,
        contrast = max(0.6f, contrast + a.contrast * 0.3f),
        toeSlope = max(0.5f, toeSlope + a.contrast * 0.15f),
        saturation = max(0f, saturation * (1f + a.saturation * 0.6f)),
        vibrance = vibrance + a.saturation * 0.1f,
    )
}

/** 3D LUT in sRGB-encoded space, red varying fastest (matches GL_TEXTURE_3D layout). */
class Lut3D(val size: Int, val data: FloatArray) {
    private val sR = 3
    private val sG = size * 3
    private val sB = size * size * 3
    private val n1 = size - 1

    /** Tetrahedral interpolation; cheaper and more accurate than trilinear. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val fr = clamp01(r) * n1
        val fg = clamp01(g) * n1
        val fb = clamp01(b) * n1
        var ir = fr.toInt(); if (ir >= n1) ir = n1 - 1
        var ig = fg.toInt(); if (ig >= n1) ig = n1 - 1
        var ib = fb.toInt(); if (ib >= n1) ib = n1 - 1
        val dr = fr - ir
        val dg = fg - ig
        val db = fb - ib
        val base = ir * sR + ig * sG + ib * sB
        val c111 = base + sR + sG + sB
        val w0: Float
        val w1: Float
        val w2: Float
        val w3: Float
        val i1: Int
        val i2: Int
        if (dr >= dg) {
            if (dg >= db) {
                w0 = 1f - dr; w1 = dr - dg; w2 = dg - db; w3 = db; i1 = base + sR; i2 = base + sR + sG
            } else if (dr >= db) {
                w0 = 1f - dr; w1 = dr - db; w2 = db - dg; w3 = dg; i1 = base + sR; i2 = base + sR + sB
            } else {
                w0 = 1f - db; w1 = db - dr; w2 = dr - dg; w3 = dg; i1 = base + sB; i2 = base + sR + sB
            }
        } else {
            if (db >= dg) {
                w0 = 1f - db; w1 = db - dg; w2 = dg - dr; w3 = dr; i1 = base + sB; i2 = base + sG + sB
            } else if (db >= dr) {
                w0 = 1f - dg; w1 = dg - db; w2 = db - dr; w3 = dr; i1 = base + sG; i2 = base + sG + sB
            } else {
                w0 = 1f - dg; w1 = dg - dr; w2 = dr - db; w3 = db; i1 = base + sG; i2 = base + sR + sG
            }
        }
        val d = data
        out[0] = w0 * d[base] + w1 * d[i1] + w2 * d[i2] + w3 * d[c111]
        out[1] = w0 * d[base + 1] + w1 * d[i1 + 1] + w2 * d[i2 + 1] + w3 * d[c111 + 1]
        out[2] = w0 * d[base + 2] + w1 * d[i1 + 2] + w2 * d[i2 + 2] + w3 * d[c111 + 2]
    }

    /** RGBA8 bytes for glTexImage3D. */
    fun toRgba8(): ByteArray {
        val n = size * size * size
        val out = ByteArray(n * 4)
        for (i in 0 until n) {
            out[i * 4] = (clamp01(data[i * 3]) * 255f + 0.5f).toInt().toByte()
            out[i * 4 + 1] = (clamp01(data[i * 3 + 1]) * 255f + 0.5f).toInt().toByte()
            out[i * 4 + 2] = (clamp01(data[i * 3 + 2]) * 255f + 0.5f).toInt().toByte()
            out[i * 4 + 3] = 0xFF.toByte()
        }
        return out
    }

    companion object {
        const val DEFAULT_SIZE = 33

        fun identity(size: Int = DEFAULT_SIZE): Lut3D {
            val d = FloatArray(size * size * size * 3)
            var i = 0
            for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
                d[i++] = r / (size - 1f); d[i++] = g / (size - 1f); d[i++] = b / (size - 1f)
            }
            return Lut3D(size, d)
        }
    }
}

object LutBuilder {
    /** OKLab L of 18% grey; tone curve pivot. */
    const val PIVOT = 0.5646f

    fun build(spec: ProfileSpec, size: Int = Lut3D.DEFAULT_SIZE): Lut3D {
        val d = FloatArray(size * size * size * 3)
        val tmp = FloatArray(3)
        var i = 0
        val inv = 1f / (size - 1)
        for (b in 0 until size) for (g in 0 until size) for (r in 0 until size) {
            transform(spec, r * inv, g * inv, b * inv, tmp)
            d[i++] = tmp[0]; d[i++] = tmp[1]; d[i++] = tmp[2]
        }
        return Lut3D(size, d)
    }

    /** Build the LUT for a profile id, or null for ORIGINAL (identity, skipped entirely). */
    fun forProfile(id: ColorProfileId, adjust: UserAdjust = UserAdjust.NONE): Lut3D? {
        val spec = ProfileSpec.of(id) ?: if (adjust.isNeutral) return null else ProfileSpec()
        return build(spec.withAdjust(adjust))
    }

    private fun hermite(t: Float, y0: Float, y1: Float, m0: Float, m1: Float, dx: Float): Float {
        val t2 = t * t
        val t3 = t2 * t
        return (2 * t3 - 3 * t2 + 1) * y0 + (t3 - 2 * t2 + t) * dx * m0 + (-2 * t3 + 3 * t2) * y1 + (t3 - t2) * dx * m1
    }

    /** Monotone two-segment Hermite tone curve on OKLab L, fixed at 0, pivot and 1. */
    fun toneL(L: Float, s: ProfileSpec): Float {
        if (L <= 0f) return 0f
        if (L >= 1f) return L
        val p = PIVOT
        return if (L < p) hermite(L / p, 0f, p, s.toeSlope, s.contrast, p)
        else hermite((L - p) / (1f - p), p, 1f, s.contrast, s.shoulderSlope, 1f - p)
    }

    private fun hueDist(h: Float, c: Float): Float {
        var d = (h - c) % 360f
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }

    private fun bell(d: Float, sigma: Float): Float = exp(-(d * d) / (2f * sigma * sigma))

    const val SKIN_HUE = 55f

    /** sRGB-encoded in, sRGB-encoded out. */
    fun transform(s: ProfileSpec, r: Float, g: Float, b: Float, out: FloatArray) {
        var lr = ColorMath.srgbToLinear(r)
        var lg = ColorMath.srgbToLinear(g)
        var lb = ColorMath.srgbToLinear(b)

        // 1. White balance shift (luminance preserving von-Kries style gains).
        if (s.warmth != 0f || s.tint != 0f) {
            val y0 = ColorMath.luminance(lr, lg, lb)
            lr *= 1f + s.warmth
            lb *= 1f - s.warmth
            lg *= 1f - s.tint
            val y1 = ColorMath.luminance(lr, lg, lb)
            if (y1 > 1e-7f) {
                val k = y0 / y1
                lr *= k; lg *= k; lb *= k
            }
        }

        val lab = FloatArray(3)
        ColorMath.linearToOklab(lr, lg, lb, lab)
        var L = lab[0]
        val a = lab[1]
        val bb = lab[2]
        var C = hypot(a, bb)
        var h = Math.toDegrees(atan2(bb.toDouble(), a.toDouble())).toFloat()
        if (h < 0f) h += 360f

        // 2. Tone curve on lightness only (hue and chroma untouched).
        L = toneL(L, s)

        // 3. Chroma: global saturation with vibrance (low-chroma colours gain more).
        val vib = 1f + s.vibrance * (1f - smoothstep(0f, 0.12f, C))
        C *= s.saturation * vib

        // 4. Hue-selective refinements. Weights fade out for near-neutrals so greys stay grey.
        val chromaGate = smoothstep(0.012f, 0.035f, C)
        val wSkin = bell(hueDist(h, SKIN_HUE), 18f) * chromaGate *
            (1f - smoothstep(0.16f, 0.24f, C)) * smoothstep(0.25f, 0.4f, L)
        val wFoliage = bell(hueDist(h, 135f), 24f) * chromaGate
        val wSky = bell(hueDist(h, 240f), 24f) * chromaGate
        val wRed = bell(hueDist(h, 25f), 14f) * chromaGate

        h += wSkin * s.skinPull * hueDist(SKIN_HUE, h)
        h += wFoliage * s.foliageHue + wSky * s.skyHue
        C *= (1f + wSkin * (s.skinSat - 1f)) * (1f + wFoliage * (s.foliageSat - 1f)) *
            (1f + wSky * (s.skySat - 1f)) * (1f + wRed * (s.redSat - 1f))
        L += wSkin * s.skinLight + wSky * s.skyLight * smoothstep(0.5f, 0.8f, L)

        // 5. Soft chroma knee keeps saturated colours from turning neon or clipping.
        val knee = s.chromaKnee
        if (C > knee) {
            val cmax = 0.34f
            val x = C - knee
            C = knee + x / (1f + x / (cmax - knee))
        }

        // 6. Back to linear sRGB with chroma-reducing gamut mapping (constant L and hue).
        val hr = Math.toRadians(h.toDouble())
        val ca = cos(hr).toFloat()
        val sa = sin(hr).toFloat()
        L = clampf(L, 0f, 1f)
        val rgb = FloatArray(3)
        ColorMath.oklabToLinear(L, C * ca, C * sa, rgb)
        if (!inGamut(rgb)) {
            var lo = 0f
            var hi = 1f
            repeat(14) {
                val mid = (lo + hi) / 2f
                ColorMath.oklabToLinear(L, C * mid * ca, C * mid * sa, rgb)
                if (inGamut(rgb)) lo = mid else hi = mid
            }
            ColorMath.oklabToLinear(L, C * lo * ca, C * lo * sa, rgb)
        }
        out[0] = ColorMath.linearToSrgb(clamp01(rgb[0]))
        out[1] = ColorMath.linearToSrgb(clamp01(rgb[1]))
        out[2] = ColorMath.linearToSrgb(clamp01(rgb[2]))
    }

    private fun inGamut(rgb: FloatArray): Boolean {
        val e = 1e-4f
        return rgb[0] >= -e && rgb[0] <= 1f + e && rgb[1] >= -e && rgb[1] <= 1f + e && rgb[2] >= -e && rgb[2] <= 1f + e
    }
}
