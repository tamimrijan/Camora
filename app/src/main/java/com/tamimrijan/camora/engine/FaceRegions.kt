package com.tamimrijan.camora.engine

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Face-region helpers: soft face masks, face exposure and the skin model for beauty. */
object FaceRegions {

    /** Feathered ellipse mask covering each face (forehead to chin), at the given resolution. */
    /**
     * Portrait: segmentation sometimes leaves holes in a face (glasses, hats, hair, strong
     * light). Every detected face is the subject, so its soft face ellipse is merged into the
     * person mask and the face can never be blurred. [faces] are in full image pixels and
     * [scale] is full image pixels per mask pixel.
     */
    fun protectFaces(mask: FloatPlane, faces: List<FaceInfo>, scale: Float) {
        if (faces.isEmpty() || scale <= 0f) return
        val inMask = faces.map { f -> FaceInfo(f.left / scale, f.top / scale, f.right / scale, f.bottom / scale) }
        val face = ellipseMask(mask.width, mask.height, inMask)
        val m = mask.data
        val e = face.data
        for (i in m.indices) if (e[i] > m[i]) m[i] = e[i]
    }

    fun ellipseMask(w: Int, h: Int, faces: List<FaceInfo>): FloatPlane {
        val m = FloatPlane(w, h)
        for (f in faces) {
            val cx = f.cx
            val cy = f.cy - f.height * 0.04f
            val rx = f.width * 0.62f
            val ry = f.height * 0.78f
            val x0 = clampi((cx - rx).toInt() - 1, 0, w - 1)
            val x1 = clampi((cx + rx).toInt() + 1, 0, w - 1)
            val y0 = clampi((cy - ry).toInt() - 1, 0, h - 1)
            val y1 = clampi((cy + ry).toInt() + 1, 0, h - 1)
            for (y in y0..y1) {
                val dy = (y + 0.5f - cy) / ry
                for (x in x0..x1) {
                    val dx = (x + 0.5f - cx) / rx
                    val d = sqrt(dx * dx + dy * dy)
                    val v = 1f - smoothstep(0.7f, 1f, d)
                    val i = y * w + x
                    if (v > m.data[i]) m.data[i] = v
                }
            }
        }
        return m
    }

    /**
     * Exposure correction for faces, in EV. This intentionally reasons about *lighting*
     * (is the face much darker than its surroundings, i.e. backlit?) and never about how
     * light or dark the skin itself is, so darker skin tones are not "whitened".
     */
    class FaceExposure(val liftEv: Float, val faceLog2: Float)

    fun faceExposure(analysis: RgbImage, facesA: List<FaceInfo>, faceMask: FloatPlane, tone: ToneParams, strength: Float): FaceExposure {
        if (facesA.isEmpty()) return FaceExposure(0f, 0f)
        val f = facesA.maxBy { it.width * it.height }
        val faceMean = SceneAnalyzer.faceLuma(analysis, f).first
        val lift = if (strength > 0f) faceLiftEv(analysis, facesA, faceMask, tone, strength) else 0f
        return FaceExposure(lift, faceMean)
    }

    fun faceLiftEv(analysis: RgbImage, facesA: List<FaceInfo>, faceMask: FloatPlane, tone: ToneParams, strength: Float): Float {
        if (facesA.isEmpty() || strength <= 0f) return 0f
        // Use the largest face as the reference subject.
        val f = facesA.maxBy { it.width * it.height }
        val (faceMean, faceP90) = SceneAnalyzer.faceLuma(analysis, f)
        // Background: median log luminance outside faces.
        val bg = ArrayList<Float>(analysis.width * analysis.height / 4)
        var i = 0
        while (i < analysis.pixels.size) {
            if (faceMask.data[i] < 0.05f) bg.add(ColorMath.log2Lum(analysis.pixels[i]))
            i += 3
        }
        if (bg.isEmpty()) return 0f
        bg.sort()
        val bgMid = bg[bg.size / 2]
        val ev = tone.exposureEv
        val face = faceMean + ev
        var lift = strength * clampf((bgMid + ev - face - 1.5f) * 0.55f, 0f, 0.85f)
        // Clearly underexposed face for any skin tone: even its brightest areas are dark.
        if (faceP90 + ev < -4.5f) lift = max(lift, strength * clampf((-4.5f - (faceP90 + ev)) * 0.6f, 0f, 0.8f))
        // Overexposed face (harsh sun / flash): bring it down a little.
        if (faceP90 + ev > -0.5f) lift = min(lift, -clampf((faceP90 + ev + 0.5f) * 0.7f, 0f, 0.6f))
        return lift
    }

    /** Per-face skin colour model sampled from the cheeks/nose region (adapts to any skin tone). */
    class SkinModel(val cb: Float, val cr: Float, val sigma: Float, val lumaMean: Float)

    fun skinModel(y: FloatArray, cb: FloatArray, cr: FloatArray, w: Int, h: Int, fx0: Float, fy0: Float, fw: Float, fh: Float): SkinModel {
        val x0 = clampi((fx0 + fw * 0.28f).toInt(), 0, w - 1)
        val x1 = clampi((fx0 + fw * 0.72f).toInt(), x0 + 1, w)
        val y0 = clampi((fy0 + fh * 0.35f).toInt(), 0, h - 1)
        val y1 = clampi((fy0 + fh * 0.72f).toInt(), y0 + 1, h)
        val cbs = ArrayList<Float>()
        val crs = ArrayList<Float>()
        var ly = 0.0
        for (yy in y0 until y1 step 2) for (xx in x0 until x1 step 2) {
            val i = yy * w + xx
            cbs.add(cb[i]); crs.add(cr[i]); ly += y[i]
        }
        if (cbs.isEmpty()) return SkinModel(-0.06f, 0.08f, 0.04f, 0.5f)
        cbs.sort(); crs.sort()
        val mcb = cbs[cbs.size / 2]
        val mcr = crs[crs.size / 2]
        // robust spread: interquartile range
        val spread = max(cbs[cbs.size * 3 / 4] - cbs[cbs.size / 4], crs[crs.size * 3 / 4] - crs[crs.size / 4])
        return SkinModel(mcb, mcr, clampf(spread * 1.6f + 0.012f, 0.018f, 0.06f), (ly / cbs.size).toFloat())
    }

    fun skinProbability(m: SkinModel, cb: Float, cr: Float): Float {
        val dcb = cb - m.cb
        val dcr = cr - m.cr
        return exp(-(dcb * dcb + dcr * dcr) / (2f * m.sigma * m.sigma))
    }

    /**
     * Regions that must stay crisp: eyes, brows and lips. Returns 1 inside them. Uses ML Kit
     * landmarks when present, otherwise standard facial proportions.
     */
    fun featureMask(px: Float, py: Float, f: FaceInfo): Float {
        val fw = f.width
        val le = f.leftEye ?: Pt(f.left + fw * 0.32f, f.top + f.height * 0.4f)
        val re = f.rightEye ?: Pt(f.left + fw * 0.68f, f.top + f.height * 0.4f)
        var m = 0f
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float) {
            val dx = (px - cx) / rx
            val dy = (py - cy) / ry
            val d = sqrt(dx * dx + dy * dy)
            m = max(m, 1f - smoothstep(0.75f, 1.15f, d))
        }
        val eyeDist = max(fw * 0.2f, kotlin.math.abs(re.x - le.x))
        ellipse(le.x, le.y, eyeDist * 0.42f, eyeDist * 0.26f)
        ellipse(re.x, re.y, eyeDist * 0.42f, eyeDist * 0.26f)
        // brows sit ~0.28 eye-distance above the eyes
        ellipse(le.x, le.y - eyeDist * 0.32f, eyeDist * 0.5f, eyeDist * 0.16f)
        ellipse(re.x, re.y - eyeDist * 0.32f, eyeDist * 0.5f, eyeDist * 0.16f)
        val mouthC: Pt
        val mouthW: Float
        if (f.mouthLeft != null && f.mouthRight != null) {
            val mb = f.mouthBottom
            mouthC = Pt((f.mouthLeft.x + f.mouthRight.x) / 2f, ((f.mouthLeft.y + f.mouthRight.y) / 2f + (mb?.y ?: f.mouthLeft.y)) / 2f)
            mouthW = max(fw * 0.25f, kotlin.math.abs(f.mouthRight.x - f.mouthLeft.x))
        } else {
            mouthC = Pt(f.cx, f.top + f.height * 0.78f)
            mouthW = fw * 0.38f
        }
        ellipse(mouthC.x, mouthC.y, mouthW * 0.62f, mouthW * 0.3f)
        return m
    }
}
