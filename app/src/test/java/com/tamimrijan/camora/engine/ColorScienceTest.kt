package com.tamimrijan.camora.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.pow

class ColorScienceTest {

    @Test
    fun srgbRoundTrip() {
        for (i in 0..255) {
            val v = i / 255f
            val back = ColorMath.encode(ColorMath.decode(v))
            assertEquals("code $i", v, back, 0.6f / 255f)
        }
    }

    @Test
    fun fastLog2AndExp2AreAccurate() {
        var x = 1e-4f
        while (x < 64f) {
            assertEquals("log2($x)", log2(x), ColorMath.fastLog2(x), 0.003f)
            x *= 1.37f
        }
        var e = -8f
        while (e < 8f) {
            val exact = 2.0.pow(e.toDouble()).toFloat()
            assertEquals("exp2($e)", 1f, ColorMath.fastExp2(e) / exact, 0.002f)
            e += 0.173f
        }
    }

    @Test
    fun oklabRoundTrip() {
        val lab = FloatArray(3)
        val rgb = FloatArray(3)
        for (c in listOf(floatArrayOf(0.2f, 0.5f, 0.8f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0.7f, 0.3f, 0.1f))) {
            ColorMath.linearToOklab(c[0], c[1], c[2], lab)
            ColorMath.oklabToLinear(lab[0], lab[1], lab[2], rgb)
            for (k in 0..2) assertEquals(c[k], rgb[k], 1e-4f)
        }
        ColorMath.linearToOklab(0.18f, 0.18f, 0.18f, lab)
        assertEquals("pivot matches 18% grey", LutBuilder.PIVOT, lab[0], 0.002f)
    }

    private fun oklchOf(srgb: FloatArray): FloatArray {
        val lab = FloatArray(3)
        ColorMath.linearToOklab(
            ColorMath.srgbToLinear(srgb[0]), ColorMath.srgbToLinear(srgb[1]), ColorMath.srgbToLinear(srgb[2]), lab,
        )
        var h = Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())).toFloat()
        if (h < 0) h += 360f
        return floatArrayOf(lab[0], hypot(lab[1], lab[2]), h)
    }

    @Test
    fun profilesKeepGreysNeutralAndEndpointsFixed() {
        for (id in ColorProfileId.entries) {
            val lut = LutBuilder.forProfile(id) ?: continue
            val out = FloatArray(3)
            var prevL = -1f
            for (i in 0..20) {
                val v = i / 20f
                lut.sample(v, v, v, out)
                val lch = oklchOf(out)
                // Warm/Cool profiles intentionally tint neutrals a little; it must stay subtle.
                val maxC = if (id == ColorProfileId.WARM || id == ColorProfileId.COOL) 0.03f else 0.015f
                assertTrue("$id grey $v chroma ${lch[1]}", lch[1] < maxC)
                assertTrue("$id grey ramp monotonic at $v", lch[0] >= prevL - 1e-3f)
                prevL = lch[0]
            }
            lut.sample(0f, 0f, 0f, out)
            assertTrue("$id black stays black", out.max() < 0.02f)
            lut.sample(1f, 1f, 1f, out)
            assertTrue("$id white stays white ${out.toList()}", out.min() > 0.95f)
        }
    }

    @Test
    fun naturalProfileTreatsSkinGently() {
        val lut = LutBuilder.forProfile(ColorProfileId.NATURAL)!!
        val skins = listOf(
            // light to deep skin tones (approximate sRGB)
            floatArrayOf(0.96f, 0.80f, 0.69f), floatArrayOf(0.88f, 0.67f, 0.55f), floatArrayOf(0.78f, 0.57f, 0.44f),
            floatArrayOf(0.62f, 0.42f, 0.30f), floatArrayOf(0.45f, 0.30f, 0.21f), floatArrayOf(0.30f, 0.20f, 0.14f),
        )
        val out = FloatArray(3)
        for (s in skins) {
            lut.sample(s[0], s[1], s[2], out)
            val a = oklchOf(s)
            val b = oklchOf(out)
            // lightness preserved (no whitening), hue only nudged, chroma not boosted
            assertEquals("skin lightness ${s.toList()}", a[0], b[0], 0.035f)
            assertTrue("skin hue shift ${a[2]} -> ${b[2]}", abs(a[2] - b[2]) < 6f)
            assertTrue("skin chroma ${a[1]} -> ${b[1]}", b[1] < a[1] * 1.08f)
        }
    }

    @Test
    fun vividIsPunchyButKeepsSkinNatural() {
        val lut = LutBuilder.forProfile(ColorProfileId.VIVID)!!
        val out = FloatArray(3)
        // everyday colours: sky, foliage, red, orange, purple, teal (medium saturation)
        val colours = listOf(
            floatArrayOf(0.36f, 0.52f, 0.78f), floatArrayOf(0.34f, 0.48f, 0.25f), floatArrayOf(0.70f, 0.25f, 0.24f),
            floatArrayOf(0.85f, 0.55f, 0.20f), floatArrayOf(0.45f, 0.32f, 0.60f), floatArrayOf(0.20f, 0.55f, 0.55f),
        )
        var gain = 0f
        for (c in colours) {
            lut.sample(c[0], c[1], c[2], out)
            assertTrue("in gamut", out.all { it in 0f..1f })
            gain += oklchOf(out)[1] / oklchOf(c)[1]
        }
        gain /= colours.size
        println("vivid average chroma gain: $gain")
        assertTrue("vivid is clearly richer: $gain", gain > 1.22f)
        // skin: hue stays put and chroma rises only a little
        for (s in listOf(floatArrayOf(0.93f, 0.76f, 0.65f), floatArrayOf(0.78f, 0.57f, 0.44f), floatArrayOf(0.45f, 0.30f, 0.22f))) {
            lut.sample(s[0], s[1], s[2], out)
            val a = oklchOf(s)
            val b = oklchOf(out)
            println("vivid skin chroma ${a[1]} -> ${b[1]}, hue ${a[2]} -> ${b[2]}")
            assertTrue("skin hue ${a[2]} -> ${b[2]}", abs(a[2] - b[2]) < 7f)
            assertTrue("skin chroma ${a[1]} -> ${b[1]}", b[1] < a[1] * 1.18f)
        }
    }
    @Test
    fun vividAddsSaturationButCapsChroma() {
        val lut = LutBuilder.forProfile(ColorProfileId.VIVID)!!
        val out = FloatArray(3)
        lut.sample(0.3f, 0.5f, 0.75f, out) // mid blue
        assertTrue(oklchOf(out)[1] > oklchOf(floatArrayOf(0.3f, 0.5f, 0.75f))[1])
        lut.sample(1f, 0f, 0f, out) // already at gamut edge: must stay in range
        assertTrue(out.all { it in 0f..1f })
    }

    @Test
    fun localToneCurveIsMonotonic() {
        for (lift in listOf(0f, 0.8f, 1.5f)) for (comp in listOf(0f, 0.6f, 1.1f)) {
            var prev = Float.NEGATIVE_INFINITY
            var b = -14f
            while (b < 2f) {
                val outB = b + LocalTone.gainEv(b, lift, comp)
                assertTrue("lift=$lift comp=$comp b=$b", outB >= prev - 1e-4f)
                prev = outB
                b += 0.05f
            }
        }
    }

    @Test
    fun softClipIsContinuousAndBounded() {
        val v = FloatArray(3)
        var prev = 0f
        var x = 0f
        while (x < 4f) {
            v[0] = x; v[1] = x * 0.5f; v[2] = x * 0.2f
            SoftClip.apply(v)
            assertTrue(v[0] <= 1.0001f)
            assertTrue("monotonic at $x", v[0] >= prev - 1e-4f)
            prev = v[0]
            x += 0.01f
        }
    }
}
