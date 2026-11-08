package com.tamimrijan.camora.engine

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * End-to-end checks of the capture pipeline on synthetic scenes, plus PNG renders of
 * before/after in app/build/engine-samples for visual inspection on a desktop.
 */
class EngineTest {

    private fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (clampi(r, 0, 255) shl 16) or (clampi(g, 0, 255) shl 8) or clampi(b, 0, 255)

    /** Backlit portrait-like scene: bright sky, dark foreground, a skin-coloured face, a grey card. */
    private fun scene(w: Int, h: Int, noise: Float, seed: Long = 1): RgbImage {
        val rnd = Random(seed)
        val img = RgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            var r: Float
            var g: Float
            var b: Float
            if (y < h * 0.45f) { // sky gradient, near clipping at top
                val t = y / (h * 0.45f)
                r = 0.80f - 0.25f * t; g = 0.90f - 0.2f * t; b = 0.99f - 0.1f * t
            } else { // dark ground with texture
                val tex = 0.03f * ((x / 6 + y / 6) % 2)
                r = 0.13f + tex; g = 0.12f + tex; b = 0.10f + tex
            }
            // backlit face (darker than sky)
            val dx = (x - w * 0.5f) / (w * 0.12f)
            val dy = (y - h * 0.5f) / (h * 0.16f)
            if (dx * dx + dy * dy < 1f) {
                r = 0.42f; g = 0.29f; b = 0.22f
                // fine skin texture
                val t = ((x * 7 + y * 13) % 5 - 2) * 0.006f
                r += t; g += t; b += t
            }
            // neutral grey card
            if (x in (w * 0.08f).toInt()..(w * 0.22f).toInt() && y in (h * 0.6f).toInt()..(h * 0.8f).toInt()) {
                r = 0.46f; g = 0.46f; b = 0.46f
            }
            val n = { (rnd.nextGaussian() * noise).toFloat() }
            img.pixels[y * w + x] = pack(((r + n()) * 255).toInt(), ((g + n()) * 255).toInt(), ((b + n()) * 255).toInt())
        }
        return img
    }

    private fun faceFor(w: Int, h: Int) = FaceInfo(w * 0.38f, h * 0.34f, w * 0.62f, h * 0.66f)

    private fun meanLuma(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        var s = 0.0
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = img[x, y]
            s += (((p shr 16) and 0xFF) * 0.299 + ((p shr 8) and 0xFF) * 0.587 + (p and 0xFF) * 0.114)
            n++
        }
        return (s / n).toFloat()
    }

    private fun save(img: RgbImage, name: String) {
        val dir = File("build/engine-samples").apply { mkdirs() }
        File(dir, "$name.png").writeBytes(Png.encode(img))
    }

    @Test
    fun backlitSceneGetsBalanced() = runBlocking {
        val w = 1200
        val h = 900
        val src = scene(w, h, 0.01f)
        val before = RgbImage(w, h, src.pixels.copyOf())
        val dst = RgbImage(w, h)
        val report = NaturalEngine.processSimple(src, dst, ProcessParams(hdrMode = HdrMode.AUTO), listOf(faceFor(w, h)))
        save(before, "backlit_before")
        save(dst, "backlit_after")
        println("backlit: ${report.summary()}")

        val faceBefore = meanLuma(before, (w * 0.46).toInt(), (h * 0.45).toInt(), (w * 0.54).toInt(), (h * 0.55).toInt())
        val faceAfter = meanLuma(dst, (w * 0.46).toInt(), (h * 0.45).toInt(), (w * 0.54).toInt(), (h * 0.55).toInt())
        val skyBefore = meanLuma(before, 0, 0, w, (h * 0.1).toInt())
        val skyAfter = meanLuma(dst, 0, 0, w, (h * 0.1).toInt())
        assertTrue("face brightened $faceBefore -> $faceAfter", faceAfter > faceBefore + 8f)
        assertTrue("face not blown out", faceAfter < 175f)
        assertTrue("sky not pushed to clipping $skyBefore -> $skyAfter", skyAfter <= skyBefore + 4f)

        // Grey card stays neutral through the whole pipeline.
        val gx = (w * 0.15).toInt()
        val gy = (h * 0.7).toInt()
        val p = dst[gx, gy]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        assertTrue("grey card neutral ($r,$g,$b)", maxOf(abs(r - g), abs(g - b), abs(r - b)) <= 8)
    }

    @Test
    fun hdrOffAndOriginalIsNearlyIdentity() = runBlocking {
        val w = 640
        val h = 480
        val src = scene(w, h, 0f)
        val copy = src.pixels.copyOf()
        val dst = RgbImage(w, h)
        NaturalEngine.processSimple(
            src, dst,
            ProcessParams(profile = ColorProfileId.ORIGINAL, hdrMode = HdrMode.OFF, autoEnhance = false, detailLevel = 0f),
        )
        var maxDiff = 0
        for (i in copy.indices) {
            val a = copy[i]
            val b = dst.pixels[i]
            for (s in intArrayOf(16, 8, 0)) maxDiff = maxOf(maxDiff, abs(((a shr s) and 0xFF) - ((b shr s) and 0xFF)))
        }
        assertTrue("neutral settings must not change the photo (max diff $maxDiff)", maxDiff <= 6)
    }

    /** Skin with pore level noise and small dark spots (blemishes), on a green background. */
    private fun skinScene(w: Int, h: Int): Pair<RgbImage, (Int, Int) -> Boolean> {
        val rnd = Random(3)
        val img = RgbImage(w, h)
        // spots: 9 px across, 20 levels darker, every 48 px
        val isSpot = { x: Int, y: Int -> val dx = (x % 48) - 24; val dy = (y % 48) - 24; dx * dx + dy * dy <= 20 }
        for (y in 0 until h) for (x in 0 until w) {
            val inFace = ((x - 500f) / 200f).let { it * it } + ((y - 500f) / 250f).let { it * it } < 1f
            val spot = if (inFace && isSpot(x, y)) -20 else 0
            val tex = (rnd.nextGaussian() * 3).toInt()
            img.pixels[y * w + x] = if (inFace) pack(205 + spot + tex, 150 + spot + tex, 125 + spot + tex)
            else pack(40 + tex, 90 + tex, 60 + tex)
        }
        return img to isSpot
    }

    private fun luma(img: RgbImage, x: Int, y: Int) = (img[x, y] shr 8) and 0xFF

    /** Average darkness of spots versus the skin right next to them, on the cheek. */
    private fun spotContrast(img: RgbImage, isSpot: (Int, Int) -> Boolean): Float {
        var sIn = 0.0; var nIn = 0; var sOut = 0.0; var nOut = 0
        for (y in 520 until 640) for (x in 380 until 620) {
            val dx = (x % 48) - 24; val dy = (y % 48) - 24; val d2 = dx * dx + dy * dy
            if (isSpot(x, y)) { sIn += luma(img, x, y); nIn++ } else if (d2 in 64..196) { sOut += luma(img, x, y); nOut++ }
        }
        return (sOut / nOut - sIn / nIn).toFloat()
    }

    /** Pore level texture: spread of each pixel around its 3x3 neighbours, away from spots. */
    private fun fineTexture(img: RgbImage): Float {
        var s2 = 0.0; var n = 0
        for (y in 520 until 640) for (x in 380 until 620) {
            val dx = (x % 48) - 24; val dy = (y % 48) - 24
            if (dx * dx + dy * dy < 120) continue
            var m = 0.0
            for (j in -1..1) for (i in -1..1) m += luma(img, x + i, y + j)
            val d = luma(img, x, y) - m / 9.0
            s2 += d * d; n++
        }
        return kotlin.math.sqrt(s2 / n).toFloat()
    }

    @Test
    fun beautyCleansSpotsKeepsTextureAndOnlyTouchesSkin() = runBlocking {
        val w = 1000
        val h = 1000
        val face = FaceInfo(300f, 250f, 700f, 750f)
        val (src, isSpot) = skinScene(w, h)
        val before = RgbImage(w, h, src.pixels.copyOf())
        BeautyPass.apply(src, listOf(face), BeautySettings(smoothing = 1f, evenTone = 0.5f))
        // outside the face nothing changes
        for (y in 0 until h step 7) for (x in 0 until 200 step 7) assertEquals(before[x, y], src[x, y])
        // spots are cleaned away at full strength...
        val cb = spotContrast(before, isSpot)
        val ca = spotContrast(src, isSpot)
        assertTrue("spots cleaned $cb -> $ca", ca < cb * 0.6f)
        // ...while real skin texture stays (no plastic skin)
        val tb = fineTexture(before)
        val ta = fineTexture(src)
        println("beauty 100%: spot contrast $cb -> $ca levels, fine texture $tb -> $ta")
        assertTrue("texture kept naturally $tb -> $ta", ta > tb * 0.45f)
    }

    @Test
    fun lowSmoothingStaysGentle() = runBlocking {
        val face = FaceInfo(300f, 250f, 700f, 750f)
        val (src, isSpot) = skinScene(1000, 1000)
        val before = RgbImage(1000, 1000, src.pixels.copyOf())
        BeautyPass.apply(src, listOf(face), BeautySettings(smoothing = 0.25f, evenTone = 0f))
        val cb = spotContrast(before, isSpot)
        val ca = spotContrast(src, isSpot)
        println("beauty 25%: spot contrast $cb -> $ca levels, fine texture ${fineTexture(before)} -> ${fineTexture(src)}")
        assertTrue("gentle at 25%: $cb -> $ca", ca > cb * 0.45f && ca < cb * 0.97f)
        assertTrue("texture mostly kept at 25%", fineTexture(src) > fineTexture(before) * 0.7f)
    }
    @Test
    fun multiFrameMergeAlignsAndDenoises() = runBlocking {
        val w = 768
        val h = 576
        val clean = scene(w, h, 0f)
        val frames = (0 until 4).map { k ->
            val rnd = Random(10L + k)
            val sx = intArrayOf(0, 3, -4, 2)[k]
            val sy = intArrayOf(0, -2, 3, 5)[k]
            val f = RgbImage(w, h)
            for (y in 0 until h) for (x in 0 until w) {
                val p = clean[clampi(x + sx, 0, w - 1), clampi(y + sy, 0, h - 1)]
                val n = (rnd.nextGaussian() * 8).toInt()
                f.pixels[y * w + x] = pack(((p shr 16) and 0xFF) + n, ((p shr 8) and 0xFF) + n, (p and 0xFF) + n)
            }
            f
        }
        val out = RgbImage(w, h)
        val res = MultiFrameMerger.merge(frames, out, 8f, 4)
        println("merge: ref=${res.referenceIndex} effective=${res.effectiveFrames}")
        assertTrue("most frames contributed", res.effectiveFrames > 2.5f)
        // Noise in a flat sky patch should drop clearly.
        fun noise(img: RgbImage, ox: Int, oy: Int): Float {
            var s = 0.0; var n = 0
            for (y in 60 until 140) for (x in 100 until 300) {
                val a = (img[x, y] shr 8) and 0xFF
                val b = (clean[clampi(x + ox, 0, w - 1), clampi(y + oy, 0, h - 1)] shr 8) and 0xFF
                s += (a - b) * (a - b); n++
            }
            return sqrt(s / n).toFloat()
        }
        val refShift = listOf(0 to 0, 3 to -2, -4 to 3, 2 to 5)[res.referenceIndex]
        val single = noise(frames[res.referenceIndex], refShift.first, refShift.second)
        val merged = noise(out, refShift.first, refShift.second)
        assertTrue("noise $single -> $merged", merged < single * 0.7f)
    }

    @Test
    fun portraitBlursBackgroundNotSubject() = runBlocking {
        val w = 800
        val h = 600
        val src = RgbImage(w, h)
        val mask = FloatPlane(w / 4, h / 4)
        for (y in 0 until h) for (x in 0 until w) {
            val subject = abs(x - 400) < 120 && y > 120
            val stripes = if ((x / 8) % 2 == 0) 200 else 60
            src.pixels[y * w + x] = if (subject) pack(stripes, stripes - 20, stripes - 40) else pack(stripes, stripes, stripes)
        }
        for (y in 0 until h / 4) for (x in 0 until w / 4) mask[x, y] = if (abs(x * 4 - 400) < 120 && y * 4 > 120) 1f else 0f
        val before = RgbImage(w, h, src.pixels.copyOf())
        val dst = RgbImage(w, h)
        NaturalEngine.processSimple(src, dst, ProcessParams(portraitBlur = 1f, hdrMode = HdrMode.OFF), personMask = mask)
        save(before, "portrait_before")
        save(dst, "portrait_after")
        fun contrast(img: RgbImage, x0: Int, y0: Int): Int {
            var mn = 255; var mx = 0
            for (x in x0 until x0 + 40) {
                val l = (img[x, y0] shr 8) and 0xFF; mn = minOf(mn, l); mx = maxOf(mx, l)
            }
            return mx - mn
        }
        assertTrue("background blurred", contrast(dst, 60, 300) < contrast(before, 60, 300) / 3)
        assertTrue("subject sharp", contrast(dst, 380, 400) > contrast(before, 380, 400) * 3 / 4)
    }

    @Test
    fun renderProfileSamples() = runBlocking {
        // Colour chart: ColorChecker-like patches + a skin-tone row + a grey ramp.
        val patches = listOf(
            0x735244, 0xC29682, 0x627A9D, 0x576C43, 0x8580B1, 0x67BDAA,
            0xD67E2C, 0x505BA6, 0xC15A63, 0x5E3C6C, 0x9DBC40, 0xE0A32E,
            0x383D96, 0x469449, 0xAF363C, 0xE7C71F, 0xBB5695, 0x0885A1,
            0xF3F3F2, 0xC8C8C8, 0xA0A0A0, 0x7A7A79, 0x555555, 0x343434,
            0xF5D3BE, 0xE0AC8E, 0xC68863, 0x9C6644, 0x6E4630, 0x3E2A1E,
        )
        val cols = 6
        val ps = 80
        val img = RgbImage(cols * ps, (patches.size / cols) * ps)
        for ((i, c) in patches.withIndex()) {
            val px = (i % cols) * ps
            val py = (i / cols) * ps
            for (y in py until py + ps) for (x in px until px + ps) img.pixels[y * img.width + x] = c or (0xFF shl 24)
        }
        save(img, "chart_original")
        for (id in ColorProfileId.entries) {
            val lut = LutBuilder.forProfile(id) ?: continue
            val out = RgbImage(img.width, img.height)
            val t = FloatArray(3)
            for (i in img.pixels.indices) {
                val p = img.pixels[i]
                lut.sample(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f, t)
                out.pixels[i] = ColorMath.pack(t[0], t[1], t[2])
            }
            save(out, "chart_${id.name.lowercase()}")
        }
    }

    @Test
    fun fullResolutionPerformanceSmoke() = runBlocking {
        // 12 MP-class frame (4000x3000) to catch accidental O(n*r) work.
        val w = 4000
        val h = 3000
        val src = RgbImage(w, h)
        val rnd = Random(5)
        for (i in src.pixels.indices) {
            val v = 90 + (exp(-((i % w) - 2000f).let { it * it } / 8e5f) * 120).toInt() + rnd.nextInt(6)
            src.pixels[i] = pack(v, v - 10, v - 20)
        }
        val dst = RgbImage(w, h)
        val t0 = System.currentTimeMillis()
        val rep = NaturalEngine.processSimple(src, dst, ProcessParams(iso = 1600), listOf(FaceInfo(1600f, 1000f, 2400f, 2000f)))
        val ms = System.currentTimeMillis() - t0
        println("12MP desktop processing: $ms ms (${rep.summary()})")
        assertTrue(ms < 20000)
    }
}
