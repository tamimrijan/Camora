package com.tamimrijan.camora.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Runs [block] for every band index using [workers] coroutines that pull work from a counter. */
internal suspend fun parallelBands(bands: Int, workers: Int, block: (band: Int, worker: Int) -> Unit) = coroutineScope {
    val next = AtomicInteger(0)
    (0 until max(1, min(workers, bands))).map { w ->
        async {
            while (true) {
                val b = next.getAndIncrement()
                if (b >= bands) break
                block(b, w)
            }
        }
    }.awaitAll()
}

/**
 * Low-resolution local exposure map. [base] is the edge-aware (guided filter) log2
 * luminance, [gain] the EV change to apply. Upsampling is joint-bilateral: each full-res
 * pixel only borrows gain from low-res neighbours of similar brightness, which keeps
 * bright windows and dark door frames from bleeding into each other (no halos).
 */
class GainMap(val w: Int, val h: Int, val scale: Float, val base: FloatArray, val gain: FloatArray) {
    fun sample(px: Float, py: Float, lp: Float): Float {
        val u = clampf(px / scale - 0.5f, 0f, (w - 1).toFloat())
        val v = clampf(py / scale - 0.5f, 0f, (h - 1).toFloat())
        val x0 = u.toInt()
        val y0 = v.toInt()
        val fx = u - x0
        val fy = v - y0
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val i00 = y0 * w + x0
        val i10 = y0 * w + x1
        val i01 = y1 * w + x0
        val i11 = y1 * w + x1
        val b00 = (1f - fx) * (1f - fy)
        val b10 = fx * (1f - fy)
        val b01 = (1f - fx) * fy
        val b11 = fx * fy
        val w00 = b00 * rangeW(lp - base[i00])
        val w10 = b10 * rangeW(lp - base[i10])
        val w01 = b01 * rangeW(lp - base[i01])
        val w11 = b11 * rangeW(lp - base[i11])
        val s = w00 + w10 + w01 + w11
        return if (s > 1e-4f) (w00 * gain[i00] + w10 * gain[i10] + w01 * gain[i01] + w11 * gain[i11]) / s
        else b00 * gain[i00] + b10 * gain[i10] + b01 * gain[i01] + b11 * gain[i11]
    }

    companion object {
        private const val SIGMA = 1.2f
        private const val MAX_D2 = 36f
        private const val STEPS = 1024
        private val table = FloatArray(STEPS + 2) {
            val d2 = MAX_D2 * minOf(it, STEPS) / STEPS
            exp(-d2 / (2f * SIGMA * SIGMA))
        }

        fun rangeW(d: Float): Float {
            val d2 = d * d
            if (d2 >= MAX_D2) return 0f
            return table[(d2 * (STEPS / MAX_D2)).toInt()]
        }

        /**
         * @param a analysis image (1/f of full res)
         * @param faceMaskA face mask at analysis resolution
         */
        fun build(a: RgbImage, f: Int, tone: ToneParams, faceMaskA: FloatPlane?, faceLiftEv: Float, faceLog2: Float = 0f): GainMap {
            val small = Resample.boxDownscale(a, 2)
            val w = small.width
            val h = small.height
            val n = w * h
            val logL = FloatArray(n) { ColorMath.log2Lum(small.pixels[it]) }
            val r = max(2, max(w, h) / 48)
            val base = GuidedFilter.selfGuided(logL, w, h, r, 0.3f)
            val gain = FloatArray(n)
            val ev = tone.exposureEv
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * w + x
                val b = base[i] + ev
                val local = LocalTone.gainEv(b, tone.shadowLiftEv, tone.highlightCompEv)
                var g = ev + local
                if (faceMaskA != null) {
                    var wf = faceMaskA.sample((x + 0.5f) * 2f, (y + 0.5f) * 2f)
                    // Only skin-brightness pixels belong to the face; sky or a bright wall
                    // behind the head inside the ellipse must not be lifted (no glow halo).
                    if (wf > 0f) wf *= rangeW((base[i] - faceLog2) * 1.3f)
                    if (wf > 0.001f) {
                        // Inside faces, generic shadow lifting is mostly replaced by the
                        // lighting-aware face correction so skin tone is not changed.
                        val gFace = ev + 0.35f * local + faceLiftEv
                        g = lerpf(g, gFace, wf)
                    }
                }
                gain[i] = g
            }
            return GainMap(w, h, (f * 2).toFloat(), base, gain)
        }
    }
}

/** Low-resolution chroma reference (box-averaged, so noise-free) for chroma noise reduction. */
class ChromaRef(val w: Int, val h: Int, val scale: Float, val cb: FloatPlane, val cr: FloatPlane) {
    companion object {
        fun build(a: RgbImage, f: Int): ChromaRef {
            val w = a.width
            val h = a.height
            val cb = FloatArray(w * h)
            val cr = FloatArray(w * h)
            for (i in 0 until w * h) {
                val p = a.pixels[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                cb[i] = b - y
                cr[i] = r - y
            }
            return ChromaRef(
                w, h, f.toFloat(),
                FloatPlane(w, h, GuidedFilter.boxMean(cb, w, h, 1)),
                FloatPlane(w, h, GuidedFilter.boxMean(cr, w, h, 1)),
            )
        }
    }
}

/**
 * Skin-only smoothing with texture retention. Operates on a crop around each face, edits
 * only pixels inside a skin-colour mask learned from that face, and always keeps the
 * finest detail (pores, stubble) so skin never looks plastic. Eyes, brows and lips are
 * excluded.
 */
object BeautyPass {
    fun apply(img: PixelBuffer, faces: List<FaceInfo>, s: BeautySettings) {
        if (!s.active) return
        for (f in faces) {
            val fw = f.width
            val fh = f.height
            if (fw < 48f) continue
            val x0 = clampi((f.cx - fw * 0.75f).toInt(), 0, img.width - 1)
            val x1 = clampi((f.cx + fw * 0.75f).toInt(), x0 + 1, img.width)
            val y0 = clampi((f.top - fh * 0.35f).toInt(), 0, img.height - 1)
            val y1 = clampi((f.bottom + fh * 0.2f).toInt(), y0 + 1, img.height)
            val w = x1 - x0
            val h = y1 - y0
            val n = w * h
            val px = IntArray(n)
            img.readRect(x0, y0, w, h, px)
            val ly = FloatArray(n)
            val cb = FloatArray(n)
            val cr = FloatArray(n)
            for (i in 0 until n) {
                val p = px[i]
                val r = ((p shr 16) and 0xFF) / 255f
                val g = ((p shr 8) and 0xFF) / 255f
                val b = (p and 0xFF) / 255f
                val y = 0.299f * r + 0.587f * g + 0.114f * b
                ly[i] = y; cb[i] = b - y; cr[i] = r - y
            }
            val local = FaceInfo(
                f.left - x0, f.top - y0, f.right - x0, f.bottom - y0,
                f.leftEye?.let { Pt(it.x - x0, it.y - y0) }, f.rightEye?.let { Pt(it.x - x0, it.y - y0) },
                f.mouthLeft?.let { Pt(it.x - x0, it.y - y0) }, f.mouthRight?.let { Pt(it.x - x0, it.y - y0) },
                f.mouthBottom?.let { Pt(it.x - x0, it.y - y0) }, f.noseBase?.let { Pt(it.x - x0, it.y - y0) },
            )
            val model = FaceRegions.skinModel(ly, cb, cr, w, h, local.left, local.top, fw, fh)
            var mask = FloatArray(n)
            val cx = local.cx
            val cy = local.cy - fh * 0.05f
            val rx = fw * 0.6f
            val ry = fh * 0.85f
            for (yy in 0 until h) for (xx in 0 until w) {
                val i = yy * w + xx
                val dx = (xx - cx) / rx
                val dy = (yy - cy) / ry
                val ell = 1f - smoothstep(0.75f, 1.05f, sqrt(dx * dx + dy * dy))
                if (ell <= 0f) continue
                val luma = ly[i]
                val lumaGate = smoothstep(0.06f, 0.14f, luma) * (1f - smoothstep(0.96f, 1f, luma))
                val skin = FaceRegions.skinProbability(model, cb[i], cr[i])
                mask[i] = ell * skin * lumaGate * (1f - FaceRegions.featureMask(xx.toFloat(), yy.toFloat(), local))
            }
            mask = GuidedFilter.boxMean(mask, w, h, max(1, (fw * 0.012f).toInt()))

            if (s.smoothing > 0f) {
                val r = clampi((fw * 0.018f).toInt(), 2, 24)
                val smooth = GuidedFilter.selfGuided(ly, w, h, r, 0.0014f)
                val fine = GuidedFilter.boxMean(ly, w, h, 1)
                val amount = s.smoothing * 0.75f
                for (i in 0 until n) {
                    val m = mask[i] * amount
                    if (m <= 0.002f) continue
                    val target = smooth[i] + (ly[i] - fine[i]) * 0.6f
                    ly[i] += m * (target - ly[i])
                }
            }
            if (s.evenTone > 0f) {
                val rc = clampi((fw * 0.04f).toInt(), 3, 40)
                val cbS = GuidedFilter.boxMean(cb, w, h, rc)
                val crS = GuidedFilter.boxMean(cr, w, h, rc)
                for (i in 0 until n) {
                    val k = mask[i] * s.evenTone * 0.55f
                    if (k <= 0.002f) continue
                    cb[i] += k * (cbS[i] - cb[i])
                    cr[i] += k * (crS[i] - cr[i])
                    // blotchy redness sits above the face's own skin hue: pull it back gently
                    val excessRed = max(0f, cr[i] - model.cr)
                    cr[i] -= k * 0.35f * excessRed
                }
            }
            for (i in 0 until n) {
                if (mask[i] <= 0.002f) continue
                val y = ly[i]
                val r = y + cr[i]
                val b = y + cb[i]
                val g = (y - 0.299f * r - 0.114f * b) / 0.587f
                px[i] = ColorMath.pack(r, g, b)
            }
            img.writeRect(x0, y0, w, h, px)
        }
    }
}

/**
 * Portrait background blur from a person-segmentation mask. The mask is refined with a
 * guided filter so hair edges follow the image, the background is blurred with a disc
 * kernel in linear light (so highlights bloom into bokeh discs like a real lens), and the
 * subject never bleeds into the blur because only background pixels feed the kernel.
 */
object PortraitPass {
    suspend fun apply(
        img: PixelBuffer,
        a: RgbImage,
        f: Int,
        personMask: FloatPlane,
        strength: Float,
        sigma8: Float,
        workers: Int,
    ): FloatPlane {
        val w = a.width
        val h = a.height
        val n = w * h
        val guide = FloatArray(n) {
            val p = a.pixels[it]
            (((p shr 16) and 0xFF) * 0.299f + ((p shr 8) and 0xFF) * 0.587f + (p and 0xFF) * 0.114f) / 255f
        }
        val m = if (personMask.width == w && personMask.height == h) personMask else Resample.resizePlane(personMask, w, h)
        val refined = GuidedFilter.guided(guide, m.data, w, h, max(2, max(w, h) / 220), 2e-3f)
        for (i in 0 until n) refined[i] = smoothstep(0.2f, 0.8f, refined[i])
        val alpha = FloatPlane(w, h, refined)

        // Background blur at a small scale; it is smooth by definition.
        val bf = Resample.factorFor(w, h, 360)
        val small = Resample.boxDownscale(a, bf)
        val sw = small.width
        val sh = small.height
        val sn = sw * sh
        val alphaS = Resample.resizePlane(alpha, sw, sh)
        val lr = FloatArray(sn)
        val lg = FloatArray(sn)
        val lb = FloatArray(sn)
        val wt = FloatArray(sn)
        for (i in 0 until sn) {
            val p = small.pixels[i]
            var r = ColorMath.SRGB8_TO_LINEAR[(p shr 16) and 0xFF]
            var g = ColorMath.SRGB8_TO_LINEAR[(p shr 8) and 0xFF]
            var b = ColorMath.SRGB8_TO_LINEAR[p and 0xFF]
            val boost = 1f + 1.6f * smoothstep(0.55f, 1f, max(r, max(g, b)))
            r *= boost; g *= boost; b *= boost
            val bw = (1f - alphaS.data[i]).let { it * it }
            lr[i] = r * bw; lg[i] = g * bw; lb[i] = b * bw; wt[i] = bw
        }
        val radius = max(1.5f, strength * max(sw, sh) * 0.03f)
        val ri = radius.toInt() + 1
        val offs = ArrayList<Int>()
        for (dy in -ri..ri) for (dx in -ri..ri) if (dx * dx + dy * dy <= radius * radius) {
            offs.add(dx); offs.add(dy)
        }
        val off = offs.toIntArray()
        val br = FloatArray(sn)
        val bg = FloatArray(sn)
        val bb = FloatArray(sn)
        parallelBands(sh, workers) { y, _ ->
            for (x in 0 until sw) {
                var sr = 0f; var sg = 0f; var sb = 0f; var sw8 = 0f
                var ur = 0f; var ug = 0f; var ub = 0f; var cnt = 0
                var k = 0
                while (k < off.size) {
                    val xx = x + off[k]
                    val yy = y + off[k + 1]
                    k += 2
                    if (xx < 0 || yy < 0 || xx >= sw || yy >= sh) continue
                    val j = yy * sw + xx
                    sr += lr[j]; sg += lg[j]; sb += lb[j]; sw8 += wt[j]
                    val p = small.pixels[j]
                    ur += ColorMath.SRGB8_TO_LINEAR[(p shr 16) and 0xFF]
                    ug += ColorMath.SRGB8_TO_LINEAR[(p shr 8) and 0xFF]
                    ub += ColorMath.SRGB8_TO_LINEAR[p and 0xFF]
                    cnt++
                }
                val i = y * sw + x
                if (sw8 > 0.02f * cnt) {
                    br[i] = sr / sw8; bg[i] = sg / sw8; bb[i] = sb / sw8
                } else if (cnt > 0) {
                    br[i] = ur / cnt; bg[i] = ug / cnt; bb[i] = ub / cnt
                }
            }
        }
        val pr = FloatPlane(sw, sh, br)
        val pg = FloatPlane(sw, sh, bg)
        val pb = FloatPlane(sw, sh, bb)
        val scaleS = (f * bf).toFloat()
        val grain = sigma8 / 255f * 0.8f

        val bandH = 64
        val bands = (img.height + bandH - 1) / bandH
        val bufs = Array(max(1, workers)) { IntArray(img.width * bandH) }
        parallelBands(bands, workers) { band, wk ->
            val y0 = band * bandH
            val rows = min(bandH, img.height - y0)
            val buf = bufs[wk]
            img.readRows(y0, rows, buf)
            val ww = img.width
            for (ry in 0 until rows) {
                val y = y0 + ry
                val fy = (y + 0.5f) / f
                val sy = (y + 0.5f) / scaleS
                for (x in 0 until ww) {
                    val al = alpha.sample((x + 0.5f) / f, fy)
                    if (al > 0.995f) continue
                    val idx = ry * ww + x
                    val p = buf[idx]
                    val sx = (x + 0.5f) / scaleS
                    val r = ColorMath.SRGB8_TO_LINEAR[(p shr 16) and 0xFF]
                    val g = ColorMath.SRGB8_TO_LINEAR[(p shr 8) and 0xFF]
                    val b = ColorMath.SRGB8_TO_LINEAR[p and 0xFF]
                    val nr = al * r + (1f - al) * pr.sample(sx, sy)
                    val ng = al * g + (1f - al) * pg.sample(sx, sy)
                    val nb = al * b + (1f - al) * pb.sample(sx, sy)
                    // matching grain so the blurred area does not look pasted-in
                    val noise = (hashNoise(x, y) * grain) * (1f - al)
                    buf[idx] = ColorMath.pack(
                        ColorMath.encode(nr) + noise, ColorMath.encode(ng) + noise, ColorMath.encode(nb) + noise,
                    )
                }
            }
            img.writeRows(y0, rows, buf)
        }
        return alpha
    }

    /** Deterministic pseudo-random value in [-1,1]. */
    private fun hashNoise(x: Int, y: Int): Float {
        var hsh = x * 374761393 + y * 668265263
        hsh = (hsh xor (hsh ushr 13)) * 1274126177
        hsh = hsh xor (hsh ushr 16)
        return ((hsh and 0xFFFF) / 32767.5f) - 1f
    }
}
