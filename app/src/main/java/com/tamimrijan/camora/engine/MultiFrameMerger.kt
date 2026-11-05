package com.tamimrijan.camora.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Burst merge for Night and HDR modes: picks the sharpest frame as reference, aligns the
 * others with a coarse-to-fine tile search (handles hand shake that OIS does not remove),
 * and averages them with per-pixel robustness weights so moving people or cars never
 * ghost - pixels that disagree with the reference simply fall back to the reference.
 */
object MultiFrameMerger {
    private const val TILE = 128 // full-res tile size for local alignment

    class Result(val referenceIndex: Int, val effectiveFrames: Float, val alignedFrames: Int)

    private class Pyr(val w: Int, val h: Int, val px: IntArray)

    private fun lumaHalf(src: PixelBuffer): Pyr {
        val w = src.width / 2
        val h = src.height / 2
        val out = IntArray(w * h)
        val row = IntArray(src.width * 2)
        for (y in 0 until h) {
            src.readRows(y * 2, 2, row)
            for (x in 0 until w) {
                var s = 0
                for (k in 0..1) for (j in 0..1) {
                    val p = row[k * src.width + x * 2 + j]
                    s += ((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29
                }
                out[y * w + x] = s shr 10
            }
        }
        return Pyr(w, h, out)
    }

    private fun down4(p: Pyr): Pyr {
        val w = max(1, p.w / 4)
        val h = max(1, p.h / 4)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (k in 0..3) for (j in 0..3) s += p.px[(y * 4 + k) * p.w + x * 4 + j]
            out[y * w + x] = s shr 4
        }
        return Pyr(w, h, out)
    }

    private fun sharpness(p: Pyr): Long {
        var s = 0L
        var y = 1
        while (y < p.h - 1) {
            var x = 1
            while (x < p.w - 1) {
                val i = y * p.w + x
                s += abs(4 * p.px[i] - p.px[i - 1] - p.px[i + 1] - p.px[i - p.w] - p.px[i + p.w])
                x += 2
            }
            y += 2
        }
        return s
    }

    /** Mean absolute difference of a region of [a] against [b] shifted by (dx,dy). */
    private fun sad(a: Pyr, b: Pyr, x0: Int, y0: Int, x1: Int, y1: Int, dx: Int, dy: Int, step: Int): Float {
        var s = 0L
        var n = 0
        var y = max(y0, -dy)
        val yEnd = min(min(y1, a.h), b.h - dy)
        while (y < yEnd) {
            var x = max(x0, -dx)
            val xEnd = min(min(x1, a.w), b.w - dx)
            val ra = y * a.w
            val rb = (y + dy) * b.w + dx
            while (x < xEnd) {
                s += abs(a.px[ra + x] - b.px[rb + x])
                n++
                x += step
            }
            y += step
        }
        return if (n < 16) Float.MAX_VALUE else s.toFloat() / n
    }

    private fun search(a: Pyr, b: Pyr, x0: Int, y0: Int, x1: Int, y1: Int, cx: Int, cy: Int, r: Int, step: Int): Pair<Int, Int> {
        var best = Float.MAX_VALUE
        var bx = cx
        var by = cy
        for (dy in cy - r..cy + r) for (dx in cx - r..cx + r) {
            val v = sad(a, b, x0, y0, x1, y1, dx, dy, step)
            // tiny bias toward the predicted motion keeps flat tiles from wandering
            val cost = v + 0.02f * (abs(dx - cx) + abs(dy - cy))
            if (cost < best) {
                best = cost; bx = dx; by = dy
            }
        }
        return bx to by
    }

    /** Per-tile displacement (in full-res pixels) of [alt] relative to [ref]. */
    private class Flow(val tx: Int, val ty: Int, val dx: FloatArray, val dy: FloatArray) {
        fun at(x: Int, y: Int, out: IntArray) {
            val u = clampf((x + 0.5f) / TILE - 0.5f, 0f, (tx - 1).toFloat())
            val v = clampf((y + 0.5f) / TILE - 0.5f, 0f, (ty - 1).toFloat())
            val x0 = u.toInt()
            val y0 = v.toInt()
            val x1 = min(x0 + 1, tx - 1)
            val y1 = min(y0 + 1, ty - 1)
            val fx = u - x0
            val fy = v - y0
            fun bil(a: FloatArray): Float {
                val top = a[y0 * tx + x0] + (a[y0 * tx + x1] - a[y0 * tx + x0]) * fx
                val bot = a[y1 * tx + x0] + (a[y1 * tx + x1] - a[y1 * tx + x0]) * fx
                return top + (bot - top) * fy
            }
            out[0] = bil(dx).roundToInt()
            out[1] = bil(dy).roundToInt()
        }

        val minDy get() = dy.minOrNull()?.toInt()?.minus(1) ?: 0
        val maxDy get() = dy.maxOrNull()?.toInt()?.plus(1) ?: 0
    }

    private fun align(ref: List<Pyr>, alt: List<Pyr>, fullW: Int, fullH: Int): Flow {
        // level 2 = /32, level 1 = /8, level 0 = /2
        val (gx, gy) = search(ref[2], alt[2], 0, 0, ref[2].w, ref[2].h, 0, 0, 6, 1)
        val tx = (fullW + TILE - 1) / TILE
        val ty = (fullH + TILE - 1) / TILE
        val dx = FloatArray(tx * ty)
        val dy = FloatArray(tx * ty)
        for (j in 0 until ty) for (i in 0 until tx) {
            // level 1 tile (TILE/8 px)
            val t1 = TILE / 8
            val (x1, y1) = search(ref[1], alt[1], i * t1, j * t1, (i + 1) * t1, (j + 1) * t1, gx * 4, gy * 4, 2, 1)
            val t0 = TILE / 2
            val (x0, y0) = search(ref[0], alt[0], i * t0, j * t0, (i + 1) * t0, (j + 1) * t0, x1 * 4, y1 * 4, 2, 2)
            dx[j * tx + i] = x0 * 2f
            dy[j * tx + i] = y0 * 2f
        }
        return Flow(tx, ty, dx, dy)
    }

    /**
     * @param frames full-res frames of identical size
     * @param sigma8 per-frame noise (8-bit units) used to set the robustness threshold
     */
    suspend fun merge(frames: List<PixelBuffer>, out: PixelBuffer, sigma8: Float, workers: Int): Result {
        require(frames.isNotEmpty())
        val w = frames[0].width
        val h = frames[0].height
        if (frames.size == 1) {
            copy(frames[0], out); return Result(0, 1f, 1)
        }
        val pyrs = frames.map { f ->
            val l0 = lumaHalf(f)
            val l1 = down4(l0)
            listOf(l0, l1, down4(l1))
        }
        val candidates = min(3, frames.size)
        val refIdx = (0 until candidates).maxBy { sharpness(pyrs[it][0]) }
        val alts = frames.indices.filter { it != refIdx }
        val flows = alts.map { align(pyrs[refIdx], pyrs[it], w, h) }

        // Robustness: weight falls off as the luma difference exceeds the expected noise.
        val sigmaM = 2.5f * sigma8 * 1.414f + 4f
        val robust = FloatArray(256) { d -> exp(-(d / sigmaM).let { it * it }) }

        val band = 64
        val bands = (h + band - 1) / band
        var weightSum = 0.0
        val lock = Any()
        parallelBands(bands, workers) { bi, _ ->
            val y0 = bi * band
            val rows = min(band, h - y0)
            val refBuf = IntArray(w * rows)
            frames[refIdx].readRows(y0, rows, refBuf)
            val accR = FloatArray(w * rows)
            val accG = FloatArray(w * rows)
            val accB = FloatArray(w * rows)
            val accW = FloatArray(w * rows)
            for (i in 0 until w * rows) {
                val p = refBuf[i]
                accR[i] = ((p shr 16) and 0xFF).toFloat()
                accG[i] = ((p shr 8) and 0xFF).toFloat()
                accB[i] = (p and 0xFF).toFloat()
                accW[i] = 1f
            }
            val d = IntArray(2)
            for ((k, ai) in alts.withIndex()) {
                val flow = flows[k]
                val ya = clampi(y0 + flow.minDy - 2, 0, h - 1)
                val yb = clampi(y0 + rows + flow.maxDy + 2, ya + 1, h)
                val altRows = yb - ya
                val altBuf = IntArray(w * altRows)
                frames[ai].readRows(ya, altRows, altBuf)
                for (ry in 0 until rows) {
                    val y = y0 + ry
                    for (x in 0 until w) {
                        flow.at(x, y, d)
                        val sx = x + d[0]
                        val sy = y + d[1]
                        if (sx < 0 || sx >= w || sy < ya || sy >= yb) continue
                        val q = altBuf[(sy - ya) * w + sx]
                        val i = ry * w + x
                        val p = refBuf[i]
                        val lp = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
                        val lq = (((q shr 16) and 0xFF) * 77 + ((q shr 8) and 0xFF) * 150 + (q and 0xFF) * 29) shr 8
                        val wgt = robust[abs(lp - lq)]
                        if (wgt < 0.01f) continue
                        accR[i] += ((q shr 16) and 0xFF) * wgt
                        accG[i] += ((q shr 8) and 0xFF) * wgt
                        accB[i] += (q and 0xFF) * wgt
                        accW[i] += wgt
                    }
                }
            }
            var localW = 0.0
            for (i in 0 until w * rows) {
                val ww = accW[i]
                localW += ww
                refBuf[i] = ColorMath.pack(accR[i] / ww / 255f, accG[i] / ww / 255f, accB[i] / ww / 255f)
            }
            out.writeRows(y0, rows, refBuf)
            synchronized(lock) { weightSum += localW }
        }
        return Result(refIdx, (weightSum / (w.toDouble() * h)).toFloat(), frames.size)
    }

    fun copy(src: PixelBuffer, dst: PixelBuffer) {
        val rows = 64
        val buf = IntArray(src.width * rows)
        var y = 0
        while (y < src.height) {
            val n = min(rows, src.height - y)
            src.readRows(y, n, buf)
            dst.writeRows(y, n, buf)
            y += n
        }
    }
}
