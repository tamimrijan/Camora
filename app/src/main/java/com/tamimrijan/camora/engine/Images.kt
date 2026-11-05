package com.tamimrijan.camora.engine

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Row-oriented access to a full-resolution ARGB_8888 image. On Android this is backed by
 * a Bitmap (pixels live in native memory, so a 50 MP frame never sits in the Java heap);
 * in unit tests it is backed by an IntArray. The engine only ever touches bands of rows.
 */
interface PixelBuffer {
    val width: Int
    val height: Int
    fun readRows(y: Int, rows: Int, dst: IntArray, offset: Int = 0)
    fun writeRows(y: Int, rows: Int, src: IntArray, offset: Int = 0)
    fun readRect(x: Int, y: Int, w: Int, h: Int, dst: IntArray)
    fun writeRect(x: Int, y: Int, w: Int, h: Int, src: IntArray)
}

/** In-memory ARGB image, used for analysis-sized images and tests. */
class RgbImage(override val width: Int, override val height: Int, val pixels: IntArray = IntArray(width * height)) :
    PixelBuffer {
    init {
        require(pixels.size >= width * height) { "pixel array too small" }
    }

    override fun readRows(y: Int, rows: Int, dst: IntArray, offset: Int) {
        System.arraycopy(pixels, y * width, dst, offset, rows * width)
    }

    override fun writeRows(y: Int, rows: Int, src: IntArray, offset: Int) {
        System.arraycopy(src, offset, pixels, y * width, rows * width)
    }

    override fun readRect(x: Int, y: Int, w: Int, h: Int, dst: IntArray) {
        for (r in 0 until h) System.arraycopy(pixels, (y + r) * width + x, dst, r * w, w)
    }

    override fun writeRect(x: Int, y: Int, w: Int, h: Int, src: IntArray) {
        for (r in 0 until h) System.arraycopy(src, r * w, pixels, (y + r) * width + x, w)
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
}

/** Single-channel float image with clamped bilinear sampling. */
class FloatPlane(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    operator fun get(x: Int, y: Int): Float = data[y * width + x]
    operator fun set(x: Int, y: Int, v: Float) {
        data[y * width + x] = v
    }

    /** Bilinear sample at continuous pixel coordinates (pixel centres at integer + 0.5). */
    fun sample(px: Float, py: Float): Float {
        val u = clampf(px - 0.5f, 0f, (width - 1).toFloat())
        val v = clampf(py - 0.5f, 0f, (height - 1).toFloat())
        val x0 = u.toInt()
        val y0 = v.toInt()
        val fx = u - x0
        val fy = v - y0
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val r0 = y0 * width
        val r1 = y1 * width
        val a = data[r0 + x0] + (data[r0 + x1] - data[r0 + x0]) * fx
        val b = data[r1 + x0] + (data[r1 + x1] - data[r1 + x0]) * fx
        return a + (b - a) * fy
    }

    fun copy(): FloatPlane = FloatPlane(width, height, data.copyOf())

    fun mapInPlace(f: (Float) -> Float) {
        for (i in data.indices) data[i] = f(data[i])
    }
}

object Resample {
    /**
     * Area-average downscale by an integer factor. This is a true box filter, so it does
     * not alias like a bilinear resize would - important because analysis statistics and
     * the noise-free chroma reference are computed from this image.
     */
    fun boxDownscale(src: PixelBuffer, factor: Int): RgbImage {
        require(factor >= 1)
        val ow = max(1, src.width / factor)
        val oh = max(1, src.height / factor)
        if (factor == 1 && src is RgbImage) return RgbImage(ow, oh, src.pixels.copyOf(ow * oh))
        val out = IntArray(ow * oh)
        val rowBuf = IntArray(src.width * factor)
        val accR = IntArray(ow)
        val accG = IntArray(ow)
        val accB = IntArray(ow)
        val n = factor * factor
        val half = n / 2
        for (oy in 0 until oh) {
            src.readRows(oy * factor, factor, rowBuf)
            java.util.Arrays.fill(accR, 0)
            java.util.Arrays.fill(accG, 0)
            java.util.Arrays.fill(accB, 0)
            for (r in 0 until factor) {
                val base = r * src.width
                for (ox in 0 until ow) {
                    var sr = 0
                    var sg = 0
                    var sb = 0
                    var i = base + ox * factor
                    for (k in 0 until factor) {
                        val p = rowBuf[i++]
                        sr += (p shr 16) and 0xFF
                        sg += (p shr 8) and 0xFF
                        sb += p and 0xFF
                    }
                    accR[ox] += sr
                    accG[ox] += sg
                    accB[ox] += sb
                }
            }
            val o = oy * ow
            for (ox in 0 until ow) {
                out[o + ox] = (0xFF shl 24) or
                    (((accR[ox] + half) / n) shl 16) or
                    (((accG[ox] + half) / n) shl 8) or
                    ((accB[ox] + half) / n)
            }
        }
        return RgbImage(ow, oh, out)
    }

    /** Factor so that the long side of the result is at most [maxSide]. */
    fun factorFor(width: Int, height: Int, maxSide: Int): Int {
        val long = max(width, height)
        return max(1, (long + maxSide - 1) / maxSide)
    }

    /** Bilinear resample of a plane to a new size (used to move masks between scales). */
    fun resizePlane(src: FloatPlane, w: Int, h: Int): FloatPlane {
        val out = FloatPlane(w, h)
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        for (y in 0 until h) {
            val py = (y + 0.5f) * sy
            for (x in 0 until w) out.data[y * w + x] = src.sample((x + 0.5f) * sx, py)
        }
        return out
    }
}

/**
 * O(1)-per-pixel box filters and the guided filter (He et al.). The guided filter is the
 * workhorse of the engine: edge-aware base layer for local tone mapping, luma noise
 * reduction, skin smoothing and portrait-mask refinement all use it.
 */
object GuidedFilter {
    /** Normalised box mean with radius r (window (2r+1)^2), border-aware. */
    fun boxMean(
        src: FloatArray,
        w: Int,
        h: Int,
        r: Int,
        dst: FloatArray = FloatArray(w * h),
        tmp: FloatArray = FloatArray(w * h),
    ): FloatArray {
        if (r <= 0) {
            System.arraycopy(src, 0, dst, 0, w * h); return dst
        }
        // horizontal pass
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            val first = min(r, w - 1)
            for (x in 0..first) sum += src[row + x]
            for (x in 0 until w) {
                val lo = x - r
                val hi = x + r
                val count = min(hi, w - 1) - max(lo, 0) + 1
                tmp[row + x] = sum / count
                val add = hi + 1
                if (add < w) sum += src[row + add]
                if (lo >= 0) sum -= src[row + lo]
            }
        }
        // vertical pass, column sums kept in an array for cache-friendly row traversal
        val colSum = FloatArray(w)
        val firstRows = min(r, h - 1)
        for (y in 0..firstRows) {
            val row = y * w
            for (x in 0 until w) colSum[x] += tmp[row + x]
        }
        for (y in 0 until h) {
            val lo = y - r
            val hi = y + r
            val count = (min(hi, h - 1) - max(lo, 0) + 1).toFloat()
            val row = y * w
            for (x in 0 until w) dst[row + x] = colSum[x] / count
            val add = hi + 1
            if (add < h) {
                val ar = add * w
                for (x in 0 until w) colSum[x] += tmp[ar + x]
            }
            if (lo >= 0) {
                val lr = lo * w
                for (x in 0 until w) colSum[x] -= tmp[lr + x]
            }
        }
        return dst
    }

    /** Self-guided filter: edge-preserving smoothing of [p]. */
    fun selfGuided(p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val mean = boxMean(p, w, h, r)
        val sq = FloatArray(n) { p[it] * p[it] }
        val meanSq = boxMean(sq, w, h, r)
        val a = sq // reuse
        val b = FloatArray(n)
        for (i in 0 until n) {
            val m = mean[i]
            val v = max(0f, meanSq[i] - m * m)
            val ai = v / (v + eps)
            a[i] = ai
            b[i] = m - ai * m
        }
        val ma = boxMean(a, w, h, r, mean)
        val mb = boxMean(b, w, h, r, meanSq)
        val q = FloatArray(n)
        for (i in 0 until n) q[i] = ma[i] * p[i] + mb[i]
        return q
    }

    /** Reusable work arrays so per-band filtering does not allocate (no GC churn on 12-50 MP photos). */
    class Scratch(capacity: Int) {
        val mean = FloatArray(capacity)
        val meanSq = FloatArray(capacity)
        val a = FloatArray(capacity)
        val b = FloatArray(capacity)
        val tmp = FloatArray(capacity)
    }

    /** Allocation-free self-guided filter writing into [out]. */
    fun selfGuidedInto(p: FloatArray, w: Int, h: Int, r: Int, eps: Float, out: FloatArray, s: Scratch) {
        val n = w * h
        boxMean(p, w, h, r, s.mean, s.tmp)
        for (i in 0 until n) s.b[i] = p[i] * p[i]
        boxMean(s.b, w, h, r, s.meanSq, s.tmp)
        for (i in 0 until n) {
            val m = s.mean[i]
            val v = max(0f, s.meanSq[i] - m * m)
            val ai = v / (v + eps)
            s.a[i] = ai
            s.b[i] = m - ai * m
        }
        boxMean(s.a, w, h, r, s.mean, s.tmp)
        boxMean(s.b, w, h, r, s.meanSq, s.tmp)
        for (i in 0 until n) out[i] = s.mean[i] * p[i] + s.meanSq[i]
    }

    /** Guided filter: smooth [p] following the edges of guide [g]. */
    fun guided(g: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val mg = boxMean(g, w, h, r)
        val mp = boxMean(p, w, h, r)
        val tmp = FloatArray(n)
        for (i in 0 until n) tmp[i] = g[i] * p[i]
        val mgp = boxMean(tmp, w, h, r)
        for (i in 0 until n) tmp[i] = g[i] * g[i]
        val mgg = boxMean(tmp, w, h, r)
        val a = FloatArray(n)
        val b = tmp
        for (i in 0 until n) {
            val cov = mgp[i] - mg[i] * mp[i]
            val v = max(0f, mgg[i] - mg[i] * mg[i])
            val ai = cov / (v + eps)
            a[i] = ai
            b[i] = mp[i] - ai * mg[i]
        }
        val ma = boxMean(a, w, h, r, mg)
        val mb = boxMean(b, w, h, r, mp)
        val q = FloatArray(n)
        for (i in 0 until n) q[i] = ma[i] * g[i] + mb[i]
        return q
    }
}

/** Simple geometric types so the engine stays free of android.graphics. */
data class Pt(val x: Float, val y: Float)

data class FaceInfo(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val leftEye: Pt? = null,
    val rightEye: Pt? = null,
    val mouthLeft: Pt? = null,
    val mouthRight: Pt? = null,
    val mouthBottom: Pt? = null,
    val noseBase: Pt? = null,
) {
    val width get() = right - left
    val height get() = bottom - top
    val cx get() = (left + right) / 2f
    val cy get() = (top + bottom) / 2f

    fun scaled(s: Float) = FaceInfo(
        left * s, top * s, right * s, bottom * s,
        leftEye?.let { Pt(it.x * s, it.y * s) }, rightEye?.let { Pt(it.x * s, it.y * s) },
        mouthLeft?.let { Pt(it.x * s, it.y * s) }, mouthRight?.let { Pt(it.x * s, it.y * s) },
        mouthBottom?.let { Pt(it.x * s, it.y * s) }, noseBase?.let { Pt(it.x * s, it.y * s) },
    )
}
