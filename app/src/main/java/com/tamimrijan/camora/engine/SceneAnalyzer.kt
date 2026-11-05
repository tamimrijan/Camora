package com.tamimrijan.camora.engine

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Global tone/colour decisions for a frame. The same parameters drive the GLSL preview
 * shader (as uniforms) and the CPU capture pipeline.
 */
data class ToneParams(
    val exposureEv: Float = 0f,
    val wbR: Float = 1f,
    val wbG: Float = 1f,
    val wbB: Float = 1f,
    val shadowLiftEv: Float = 0f,
    val highlightCompEv: Float = 0f,
) {
    fun lerp(o: ToneParams, t: Float) = ToneParams(
        lerpf(exposureEv, o.exposureEv, t), lerpf(wbR, o.wbR, t), lerpf(wbG, o.wbG, t), lerpf(wbB, o.wbB, t),
        lerpf(shadowLiftEv, o.shadowLiftEv, t), lerpf(highlightCompEv, o.highlightCompEv, t),
    )

    companion object {
        val NEUTRAL = ToneParams()
    }
}

object LocalTone {
    /**
     * Local exposure change (EV) for a pixel whose smoothed ("base") log2 luminance is [b]
     * after global exposure. Shadows are lifted in a band that leaves true blacks alone,
     * highlights are pulled down. Both slopes stay below 1 so ordering of brightness is
     * never inverted (no HDR "halo" look). Mirrored exactly in the preview shader.
     */
    fun gainEv(b: Float, shadowLift: Float, highlightComp: Float): Float {
        val s = shadowLift * smoothstep(-10.5f, -6.5f, b) * (1f - smoothstep(-5.0f, -2.2f, b))
        val h = -highlightComp * smoothstep(-2.2f, 0.2f, b)
        return s + h
    }
}

class SceneStats(
    val p02: Float, val p10: Float, val p50: Float, val p90: Float, val p98: Float,
    val clipFraction: Float,
    val histogram: IntArray, // 64 bins of sRGB-encoded luma
)

object SceneAnalyzer {
    fun stats(img: RgbImage): SceneStats {
        val n = img.width * img.height
        val logs = FloatArray(n)
        val hist = IntArray(64)
        var clipped = 0
        for (i in 0 until n) {
            val p = img.pixels[i]
            val r8 = (p shr 16) and 0xFF
            val g8 = (p shr 8) and 0xFF
            val b8 = p and 0xFF
            if (r8 >= 250 || g8 >= 250 || b8 >= 250) clipped++
            logs[i] = ColorMath.log2Lum(p)
            val luma = (r8 * 77 + g8 * 150 + b8 * 29) shr 8
            hist[luma shr 2]++
        }
        logs.sort()
        fun pct(q: Float) = logs[clampi((q * (n - 1)).toInt(), 0, n - 1)]
        return SceneStats(pct(0.02f), pct(0.10f), pct(0.5f), pct(0.9f), pct(0.98f), clipped.toFloat() / n, hist)
    }

    /**
     * @param img small (~128 px wide) image of the scene
     * @param hdrStrength 0..1 local tone mapping strength
     * @param wbStrength 0..1 how much of the measured cast to remove
     */
    fun analyze(
        img: RgbImage,
        hdrStrength: Float,
        night: Boolean,
        wbStrength: Float,
        adjust: UserAdjust = UserAdjust.NONE,
        baselineEv: Float = 0f,
        autoEnhance: Boolean = true,
    ): ToneParams {
        val st = stats(img)
        val p02 = st.p02 + baselineEv
        val p10 = st.p10 + baselineEv
        val p50 = st.p50 + baselineEv
        val p98 = st.p98 + baselineEv

        var ev = baselineEv
        var lift = 0f
        var comp = 0f
        var wb = floatArrayOf(1f, 1f, 1f)
        if (autoEnhance) {
            // Global exposure: only a gentle nudge toward a pleasant key; camera AE already
            // did most of the work. Night scenes are kept dark on purpose.
            val target = if (night) -3.4f else -2.55f
            var dEv = clampf((target - p50) * 0.35f, -0.4f, 0.6f)
            if (dEv > 0f) dEv = min(dEv, max(0f, -0.15f - p98)) // never push highlights into clipping
            ev += dEv

            // Local tone mapping, scaled by how much dynamic range the scene actually has.
            val dr = p98 - p02
            val drScale = clamp01((dr - 3f) / 2.5f)
            lift = hdrStrength * drScale * clampf((-4.2f - (p10 + dEv)) * 0.45f, 0f, 1.5f)
            comp = hdrStrength * drScale * clampf(((p98 + dEv) + 1.3f) * 0.55f + st.clipFraction * 3f, 0f, 1.1f)
            if (night) {
                lift *= 0.7f // keep the night mood: lift, but don't turn night into day
                comp = max(comp, 0.5f * hdrStrength) // tame street lights
            }
            wb = whiteBalance(img, wbStrength)
        }

        // Manual adjustments from the editor.
        ev += adjust.exposure
        lift = clampf(lift + adjust.shadows * 1.2f, 0f, 1.8f)
        comp = clampf(comp - adjust.highlights * 1.0f, 0f, 1.3f)

        return ToneParams(ev, wb[0], wb[1], wb[2], lift, comp)
    }

    /**
     * Constrained grey-world on near-neutral mid-tones. Corrections are capped at a few
     * percent, and warm illuminants keep part of their warmth: a candle-lit room should
     * still feel warm, just not orange.
     */
    fun whiteBalance(img: RgbImage, strength: Float): FloatArray {
        var sr = 0.0
        var sg = 0.0
        var sb = 0.0
        var count = 0
        val n = img.width * img.height
        for (i in 0 until n) {
            val p = img.pixels[i]
            val r = ColorMath.SRGB8_TO_LINEAR[(p shr 16) and 0xFF]
            val g = ColorMath.SRGB8_TO_LINEAR[(p shr 8) and 0xFF]
            val b = ColorMath.SRGB8_TO_LINEAR[p and 0xFF]
            val y = ColorMath.luminance(r, g, b)
            if (y < 0.03f || y > 0.85f) continue
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if ((mx - mn) / (mx + 1e-4f) > 0.28f) continue
            sr += r / y; sg += g / y; sb += b / y
            count++
        }
        if (count < n * 0.03f) return floatArrayOf(1f, 1f, 1f)
        val ar = sr / count
        val ag = sg / count
        val ab = sb / count
        val conf = clamp01(count / (0.15f * n))
        var gr = (ag / ar).toFloat()
        var gb = (ag / ab).toFloat()
        val warm = ar > ab
        val k = strength * conf
        val kRb = if (warm) k * 0.6f else k
        gr = 1f + (gr - 1f) * kRb
        gb = 1f + (gb - 1f) * kRb
        gr = clampf(gr, 0.93f, 1.07f)
        gb = clampf(gb, 0.93f, 1.07f)
        val norm = 1f / (ColorMath.KR * gr + ColorMath.KG + ColorMath.KB * gb)
        return floatArrayOf(gr * norm, norm, gb * norm)
    }

    /**
     * Noise standard deviation in 8-bit code values. ISO from EXIF is the primary signal;
     * when it is missing we measure high-frequency energy in flat areas.
     */
    fun noiseSigma8(iso: Int, img: RgbImage?, mergedFrames: Int): Float {
        val base = if (iso > 0) 0.8f + 0.0026f * iso else estimateNoise(img)
        return clampf(base / sqrt(max(1, mergedFrames).toFloat()), 0.5f, 12f)
    }

    private fun estimateNoise(img: RgbImage?): Float {
        if (img == null || img.width < 8 || img.height < 8) return 1.5f
        val w = img.width
        val vals = ArrayList<Float>()
        var y = 1
        while (y < img.height - 1) {
            var x = 1
            while (x < w - 1) {
                fun l(p: Int) = ((p shr 16 and 0xFF) * 77 + (p shr 8 and 0xFF) * 150 + (p and 0xFF) * 29) / 256f
                val c = l(img.pixels[y * w + x])
                val lap = 4 * c - l(img.pixels[y * w + x - 1]) - l(img.pixels[y * w + x + 1]) -
                    l(img.pixels[(y - 1) * w + x]) - l(img.pixels[(y + 1) * w + x])
                vals.add(abs(lap))
                x += 3
            }
            y += 3
        }
        vals.sort()
        // Lower quartile ~ flat regions; analysis image is box-downscaled so scale noise back up.
        val q = vals[vals.size / 4]
        return clampf(q * 0.6f * 3f, 0.8f, 8f)
    }

    /** log2 luminance of the 10% brightest skin pixels and the mean, for one face box. */
    fun faceLuma(img: RgbImage, f: FaceInfo): Pair<Float, Float> {
        val x0 = clampi((f.left + f.width * 0.25f).toInt(), 0, img.width - 1)
        val x1 = clampi((f.right - f.width * 0.25f).toInt(), x0 + 1, img.width)
        val y0 = clampi((f.top + f.height * 0.2f).toInt(), 0, img.height - 1)
        val y1 = clampi((f.bottom - f.height * 0.15f).toInt(), y0 + 1, img.height)
        val list = FloatArray((x1 - x0) * (y1 - y0))
        var k = 0
        var sum = 0.0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = img.pixels[y * img.width + x]
            val r = ColorMath.SRGB8_TO_LINEAR[(p shr 16) and 0xFF]
            val g = ColorMath.SRGB8_TO_LINEAR[(p shr 8) and 0xFF]
            val b = ColorMath.SRGB8_TO_LINEAR[p and 0xFF]
            val yl = ColorMath.luminance(r, g, b)
            sum += yl
            list[k++] = yl
        }
        if (k == 0) return -3f to -3f
        list.sort(0, k)
        val p90 = list[clampi((k * 0.9f).toInt(), 0, k - 1)]
        return log2(max(sum / k, 1e-5).toFloat()) to log2(max(p90, 1e-5f))
    }
}
