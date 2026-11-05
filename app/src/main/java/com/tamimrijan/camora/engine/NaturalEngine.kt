package com.tamimrijan.camora.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Natural Image Processing Engine - full-resolution capture path.
 *
 * Pipeline (per photo):
 *   analysis image (box-downscaled) ─┬─> scene statistics ──> ToneParams (exposure, WB, HDR)
 *                                    ├─> faces ──> face mask + lighting-aware face exposure
 *                                    ├─> edge-aware base layer ──> local exposure (gain) map
 *                                    └─> noise-free chroma reference
 *   full-res, in bands of rows, on all cores:
 *     [beauty pre-pass on face crops] [portrait pre-pass]
 *     chroma NR -> luma NR (high ISO) -> WB x exposure x local gain (linear light)
 *     -> highlight soft-clip -> colour profile LUT (blended by intensity)
 *     -> skin-aware, halo-clamped sharpening
 */
class NaturalEngine(private val workers: Int = max(2, Runtime.getRuntime().availableProcessors())) {

    class Input(
        val image: PixelBuffer,
        val analysis: RgbImage,
        val analysisFactor: Int,
        val faces: List<FaceInfo> = emptyList(),  // full-res coordinates
        val personMask: FloatPlane? = null,       // any resolution, 0..1
    )

    suspend fun process(input: Input, output: PixelBuffer, params: ProcessParams, lut: Lut3D?): EngineReport =
        withContext(Dispatchers.Default) {
            val t0 = System.currentTimeMillis()
            val img = input.image
            require(output.width == img.width && output.height == img.height)
            val a = input.analysis
            val f = input.analysisFactor
            val tuning = params.tuning

            val small = Resample.boxDownscale(a, max(1, a.width / 128))
            val tone = SceneAnalyzer.analyze(
                small,
                hdrStrength = params.hdrMode.strength * tuning.hdrStrength,
                night = params.night,
                wbStrength = tuning.wbStrength,
                adjust = params.adjust,
                baselineEv = params.baselineEv,
                autoEnhance = params.autoEnhance,
            )

            val facesA = input.faces.map { it.scaled(1f / f) }
            val faceMaskA = if (facesA.isNotEmpty()) FaceRegions.ellipseMask(a.width, a.height, facesA) else null
            val faceExp = if (faceMaskA != null) FaceRegions.faceExposure(
                a, facesA, faceMaskA, tone,
                if (params.faceExposure && params.autoEnhance) params.beauty.faceLight * tuning.faceLight else 0f,
            ) else null
            val faceLift = faceExp?.liftEv ?: 0f

            val gainMap = GainMap.build(a, f, tone, faceMaskA, faceLift, faceExp?.faceLog2 ?: 0f)
            val chroma = ChromaRef.build(a, f)
            val sigma8 = SceneAnalyzer.noiseSigma8(params.iso, a, params.mergedFrames)

            if (params.beauty.active && input.faces.isNotEmpty()) BeautyPass.apply(img, input.faces, params.beauty)

            val portraitAlpha = if (input.personMask != null && params.portraitBlur > 0.01f)
                PortraitPass.apply(img, a, f, input.personMask, params.portraitBlur, sigma8, workers) else null

            val sharpen = (tuning.sharpen * params.detailLevel + params.adjust.sharpness * 0.5f).coerceAtLeast(0f) *
                (1f - 0.5f * smoothstep(2f, 8f, sigma8))
            val lumaNr = tuning.lumaNr * params.noiseReductionLevel * smoothstep(2f, 6f, sigma8) *
                (if (params.night) 1.2f else 1f)
            val chromaNr = clamp01(tuning.chromaNr * params.noiseReductionLevel)

            val ctx = MainPass.Ctx(
                input = img, output = output, tone = tone, gain = gainMap, chroma = chroma,
                lut = lut, lutMix = clamp01(params.profileIntensity),
                chromaNr = chromaNr, chromaThr = (3f + sigma8 * 1.5f) / 255f,
                lumaNr = clamp01(lumaNr), lumaEps = (sigma8 * 2.2f / 255f).let { it * it },
                sharpen = sharpen, coring = (1f + sigma8 * 0.8f) / 255f,
                faceMask = faceMaskA, portraitAlpha = portraitAlpha, analysisFactor = f.toFloat(),
            )
            MainPass.run(ctx, workers)

            EngineReport(
                exposureEv = tone.exposureEv, shadowLiftEv = tone.shadowLiftEv, highlightCompEv = tone.highlightCompEv,
                wbGains = floatArrayOf(tone.wbR, tone.wbG, tone.wbB), faceLiftEv = faceLift, faces = input.faces.size,
                noiseSigma = sigma8, sharpenAmount = sharpen, millis = System.currentTimeMillis() - t0,
            )
        }

    companion object {
        /** Analysis image long side; ~1/4 of a 12.5 MP frame. */
        const val ANALYSIS_SIDE = 1024

        /** Convenience for tests and the editor: whole pipeline from a single buffer. */
        suspend fun processSimple(
            src: PixelBuffer,
            dst: PixelBuffer,
            params: ProcessParams,
            faces: List<FaceInfo> = emptyList(),
            personMask: FloatPlane? = null,
        ): EngineReport {
            val f = Resample.factorFor(src.width, src.height, ANALYSIS_SIDE)
            val a = Resample.boxDownscale(src, f)
            val lut = LutBuilder.forProfile(params.profile, params.adjust)
            return NaturalEngine().process(Input(src, a, f, faces, personMask), dst, params, lut)
        }
    }
}

internal object MainPass {
    class Ctx(
        val input: PixelBuffer,
        val output: PixelBuffer,
        val tone: ToneParams,
        val gain: GainMap,
        val chroma: ChromaRef,
        val lut: Lut3D?,
        val lutMix: Float,
        val chromaNr: Float,
        val chromaThr: Float,
        val lumaNr: Float,
        val lumaEps: Float,
        val sharpen: Float,
        val coring: Float,
        val faceMask: FloatPlane?,
        val portraitAlpha: FloatPlane?,
        val analysisFactor: Float,
    )

    private const val BAND = 64
    private const val MARGIN = 4

    private class Buffers(w: Int) {
        val rowsMax = BAND + 2 * MARGIN
        val src = IntArray(w * rowsMax)
        val y = FloatArray(w * rowsMax)
        val out = FloatArray(w * rowsMax * 3)
        val outY = FloatArray(w * rowsMax)
        val dst = IntArray(w * BAND)
        val tmp = FloatArray(3)
        val rgb = FloatArray(3)
        val yNr by lazy { FloatArray(w * rowsMax) }
        val gf by lazy { GuidedFilter.Scratch(w * rowsMax) }
    }

    suspend fun run(c: Ctx, workers: Int) {
        val w = c.input.width
        val h = c.input.height
        val bands = (h + BAND - 1) / BAND
        val bufs = Array(max(1, workers)) { Buffers(w) }
        parallelBands(bands, workers) { band, wk ->
            processBand(c, band * BAND, min(h, band * BAND + BAND), bufs[wk])
        }
    }

    private fun processBand(c: Ctx, y0: Int, y1: Int, b: Buffers) {
        val w = c.input.width
        val h = c.input.height
        val ys = max(0, y0 - MARGIN)
        val ye = min(h, y1 + MARGIN)
        val rows = ye - ys
        val n = w * rows
        c.input.readRows(ys, rows, b.src)

        // Luma plane (sRGB-encoded) for noise reduction.
        val lumaNr = c.lumaNr > 0.01f
        var yNr: FloatArray? = null
        if (lumaNr) {
            for (i in 0 until n) {
                val p = b.src[i]
                b.y[i] = (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) / 255f
            }
            GuidedFilter.selfGuidedInto(b.y, w, rows, 2, c.lumaEps, b.yNr, b.gf)
            yNr = b.yNr
        }

        val t = c.tone
        val tmp = b.tmp
        val rgb = b.rgb
        val f = c.analysisFactor
        val chromaScale = c.chroma.scale
        for (ry in 0 until rows) {
            val y = ys + ry
            val pyF = y + 0.5f
            for (x in 0 until w) {
                val i = ry * w + x
                val p = b.src[i]
                var r = ((p shr 16) and 0xFF) / 255f
                var g = ((p shr 8) and 0xFF) / 255f
                var bl = (p and 0xFF) / 255f

                if (yNr != null) {
                    val d = (yNr[i] - b.y[i]) * c.lumaNr
                    r += d; g += d; bl += d
                }

                // Chroma noise reduction against the box-averaged reference.
                if (c.chromaNr > 0f) {
                    val yy = 0.299f * r + 0.587f * g + 0.114f * bl
                    var cb = bl - yy
                    var cr = r - yy
                    val sx = (x + 0.5f) / chromaScale
                    val sy = pyF / chromaScale
                    val cbL = c.chroma.cb.sample(sx, sy)
                    val crL = c.chroma.cr.sample(sx, sy)
                    val d = max(kotlin.math.abs(cb - cbL), kotlin.math.abs(cr - crL))
                    val wgt = c.chromaNr * (1f - smoothstep(c.chromaThr, c.chromaThr * 2.5f, d))
                    if (wgt > 0f) {
                        cb += (cbL - cb) * wgt
                        cr += (crL - cr) * wgt
                        r = yy + cr
                        bl = yy + cb
                        g = (yy - 0.299f * r - 0.114f * bl) / 0.587f
                    }
                }

                // Linear light: white balance, global + local exposure.
                var lr = ColorMath.decode(r)
                var lg = ColorMath.decode(g)
                var lb = ColorMath.decode(bl)
                val lum = max(ColorMath.luminance(lr, lg, lb), ColorMath.MIN_LUM)
                val gEv = c.gain.sample(x + 0.5f, pyF, ColorMath.fastLog2(lum))
                val gn = ColorMath.fastExp2(gEv)
                rgb[0] = lr * gn * t.wbR
                rgb[1] = lg * gn * t.wbG
                rgb[2] = lb * gn * t.wbB
                SoftClip.apply(rgb)
                r = ColorMath.encode(rgb[0])
                g = ColorMath.encode(rgb[1])
                bl = ColorMath.encode(rgb[2])

                val lut = c.lut
                if (lut != null && c.lutMix > 0f) {
                    lut.sample(r, g, bl, tmp)
                    val k = c.lutMix
                    r += (tmp[0] - r) * k
                    g += (tmp[1] - g) * k
                    bl += (tmp[2] - bl) * k
                }
                val o = i * 3
                b.out[o] = r
                b.out[o + 1] = g
                b.out[o + 2] = bl
                b.outY[i] = 0.299f * r + 0.587f * g + 0.114f * bl
            }
        }

        // Sharpening on luma, applied equally to RGB (no colour fringing), with coring so
        // noise is not amplified and an overshoot clamp so edges never get halos.
        val sh = c.sharpen
        for (y in y0 until y1) {
            val ry = y - ys
            val dRow = (y - y0) * w
            val fy = (y + 0.5f) / f
            for (x in 0 until w) {
                val i = ry * w + x
                val o = i * 3
                var r = b.out[o]
                var g = b.out[o + 1]
                var bl = b.out[o + 2]
                if (sh > 0.001f && x > 0 && x < w - 1 && ry > 0 && ry < rows - 1) {
                    val oy = b.outY
                    val cIdx = i
                    val up = i - w
                    val dn = i + w
                    val center = oy[cIdx]
                    val l = oy[cIdx - 1]
                    val rr = oy[cIdx + 1]
                    val u = oy[up]
                    val d = oy[dn]
                    val ul = oy[up - 1]
                    val ur = oy[up + 1]
                    val dl = oy[dn - 1]
                    val dr = oy[dn + 1]
                    val blur = (center * 4f + (l + rr + u + d) * 2f + (ul + ur + dl + dr)) / 16f
                    var detail = center - blur
                    val ad = kotlin.math.abs(detail)
                    detail = if (ad <= c.coring) 0f else (if (detail > 0) ad - c.coring else c.coring - ad)
                    var amt = sh
                    val fm = c.faceMask
                    if (fm != null) amt *= 1f - 0.7f * fm.sample((x + 0.5f) / f, fy)
                    val pa = c.portraitAlpha
                    if (pa != null) amt *= pa.sample((x + 0.5f) / f, fy)
                    if (amt > 0f) {
                        var target = center + detail * amt * 2.2f
                        val mn = min(min(min(l, rr), min(u, d)), center) - 0.012f
                        val mx = max(max(max(l, rr), max(u, d)), center) + 0.012f
                        target = clampf(target, mn, mx)
                        val delta = target - center
                        r += delta; g += delta; bl += delta
                    }
                }
                b.dst[dRow + x] = ColorMath.pack(r, g, bl)
            }
        }
        c.output.writeRows(y0, y1 - y0, b.dst)
    }
}
