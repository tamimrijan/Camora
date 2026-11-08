package com.tamimrijan.camora.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.tamimrijan.camora.engine.CameraTuning
import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.HdrMode
import com.tamimrijan.camora.engine.LutBuilder
import com.tamimrijan.camora.engine.RgbImage
import com.tamimrijan.camora.engine.SceneAnalyzer
import com.tamimrijan.camora.engine.ToneParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Settings the viewfinder needs; written from the UI thread, read on the GL thread. */
data class PreviewLook(
    val profile: ColorProfileId = ColorProfileId.NATURAL,
    val intensity: Float = 1f,
    val hdr: HdrMode = HdrMode.AUTO,
    val tuning: CameraTuning = CameraTuning.REAR,
    val portraitBlur: Float = 0f,
    val enabled: Boolean = true,
    /** Live skin preview (Selfie, Portrait and front-camera Photo, like the capture engine). */
    val beautyAllowed: Boolean = false,
    val smoothing: Float = 0f,
    val evenTone: Float = 0f,
    val faceLight: Float = 0.6f,
    val faceExposure: Boolean = true,
) {
    val beautyOn get() = beautyAllowed && (smoothing > 0.001f || evenTone > 0.001f)
}

/** Live scene statistics (histogram for Pro mode), published ~5x per second when requested. */
data class PreviewStats(val histogram: IntArray, val medianEv: Float)

/**
 * CameraX SurfaceProcessor that renders the camera stream through the Natural colour
 * pipeline on the GPU. Per frame:
 *  1. camera OES texture -> 96 px wide illumination map (16-tap linear average)
 *  2. tent blur -> smooth base layer for local tone mapping
 *  3. every 6th frame the base layer is read back and fed to the same SceneAnalyzer
 *     the capture pipeline uses, so exposure/WB/HDR decisions match the final photo
 *  4. full-res pass: WB, exposure, local tone, soft-clip, profile LUT, optional
 *     portrait blur, drawn to the viewfinder.
 * Uniform locations are cached; the only per-frame CPU work is issuing draw calls.
 */
class NaturalPreviewProcessor : SurfaceProcessor {
    private val thread = HandlerThread("CamoraGL").apply { start() }
    private val handler = Handler(thread.looper)
    val executor: Executor = Executor { if (!handler.post(it)) Log.w(TAG, "GL thread gone") }
    private val lutExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var look: PreviewLook = PreviewLook()
        set(value) {
            val old = field
            field = value
            if (old.profile != value.profile) requestLut(value.profile)
        }

    /** Only set while something (Pro mode histogram) needs live statistics. */
    @Volatile var statsListener: ((PreviewStats) -> Unit)? = null

    /** Called (main thread) if the GPU pipeline cannot run on this device; the app then rebinds without it. */
    @Volatile var onFailure: ((Throwable) -> Unit)? = null
    @Volatile var broken = false
        private set

    private val pendingLut = AtomicReference<ByteArray?>(null)
    private val pendingMask = AtomicReference<MaskFrame?>(null)
    private val pendingFaces = AtomicReference<PreviewFaceSet?>(null)

    // face tracking (GL thread): detections arrive ~9x/s, the shader gets a smoothed
    // position every frame so the skin effect follows the face without jumping
    private var faceTarget = FloatArray(0)
    private var faceCurrent = FloatArray(0)
    private var faceCount = 0
    private var faceSeenNs = 0L
    private var faceQy = 0.75f
    private var skinCb = -0.06f
    private var skinCr = 0.08f
    private var skinSigma = 0.04f
    private var faceLiftEv = 0f
    private var faceLog2 = 0f
    private val uFace = FloatArray(8)
    private val uEyes = FloatArray(8)
    private val uMouth = FloatArray(8)

    class MaskFrame(val width: Int, val height: Int, val bytes: ByteBuffer, val rotationDegrees: Int, val timestamp: Long)

    // ---- GL-thread state -------------------------------------------------------------------
    private var egl: EglCore? = null
    private var progMain = 0
    private var progDown = 0
    private var progBlur = 0
    private var lutTex = 0
    private var hasLut = false
    private var maskTex = 0
    private var maskRotation = 0
    private var maskAge = Long.MAX_VALUE
    private val illumFbo = IntArray(2)
    private val illumTex = IntArray(2)
    private var illumW = 0
    private var illumH = 0
    private var readBuf: ByteBuffer? = null
    private var readPixels = IntArray(0)
    private var inputTex = 0
    private var input: SurfaceTexture? = null
    private var inputSurface: Surface? = null
    private var inputSize = Size(1, 1)
    private val outputs = LinkedHashMap<SurfaceOutput, Out>()
    private var frameCount = 0L
    private var tone = ToneParams.NEUTRAL
    private var released = false

    private class Out(val egl: EGLSurface, val size: Size, val surface: Surface)

    /** Last time a frame reached the screen (elapsedRealtime ms); read by the stall watchdog. */
    @Volatile var lastDrawMs = 0L
        private set

    /** Cached uniform locations. */
    private class MainLoc(p: Int) {
        val tex = GLES30.glGetUniformLocation(p, "uTex")
        val illum = GLES30.glGetUniformLocation(p, "uIllum")
        val lut = GLES30.glGetUniformLocation(p, "uLut")
        val mask = GLES30.glGetUniformLocation(p, "uMask")
        val wb = GLES30.glGetUniformLocation(p, "uWb")
        val exposure = GLES30.glGetUniformLocation(p, "uExposureEv")
        val tone = GLES30.glGetUniformLocation(p, "uTone")
        val lutMix = GLES30.glGetUniformLocation(p, "uLutMix")
        val blur = GLES30.glGetUniformLocation(p, "uBlur")
        val aspect = GLES30.glGetUniformLocation(p, "uAspect")
        val maskXf = GLES30.glGetUniformLocation(p, "uMaskXf")
        val texMatrix = GLES30.glGetUniformLocation(p, "uTexMatrix")
        val faceCount = GLES30.glGetUniformLocation(p, "uFaceCount")
        val face = GLES30.glGetUniformLocation(p, "uFace")
        val eyes = GLES30.glGetUniformLocation(p, "uEyes")
        val mouth = GLES30.glGetUniformLocation(p, "uMouth")
        val skin = GLES30.glGetUniformLocation(p, "uSkin")
        val beauty = GLES30.glGetUniformLocation(p, "uBeauty")
        val faceLight = GLES30.glGetUniformLocation(p, "uFaceLight")
        val qy = GLES30.glGetUniformLocation(p, "uQy")
        val texel = GLES30.glGetUniformLocation(p, "uTexel")
    }

    private class PassLoc(p: Int) {
        val tex = GLES30.glGetUniformLocation(p, "uTex")
        val step = GLES30.glGetUniformLocation(p, "uStep")
        val texMatrix = GLES30.glGetUniformLocation(p, "uTexMatrix")
    }

    private var locMain: MainLoc? = null
    private var locDown: PassLoc? = null
    private var locBlur: PassLoc? = null

    private val stMatrix = FloatArray(16)
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private val quadPos = GlUtil.floatBuffer(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    private val quadTex = GlUtil.floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    init {
        requestLut(look.profile)
    }

    fun createEffect(targets: Int): CameraEffect = NaturalEffect(targets, this)

    private class NaturalEffect(targets: Int, p: NaturalPreviewProcessor) :
        CameraEffect(targets, p.executor, p, { t -> Log.e(TAG, "effect error", t) })

    private fun requestLut(profile: ColorProfileId) {
        lutExecutor.execute {
            val lut = LutBuilder.forProfile(profile)
            pendingLut.set(lut?.toRgba8() ?: ByteArray(0))
        }
    }

    /** Called from the analysis thread with the latest person mask (portrait mode). */
    fun submitMask(frame: MaskFrame) {
        pendingMask.set(frame)
    }

    /** Called with the latest live face detections (buffer q-space). */
    fun submitFaces(set: PreviewFaceSet) {
        pendingFaces.set(set)
    }

    /** GL thread: take new detections and ease the drawn faces toward them. */
    private fun updateFaces() {
        val now = System.nanoTime()
        pendingFaces.getAndSet(null)?.let { set ->
            faceQy = set.qy
            val n = set.faces.size
            val arr = FloatArray(n * PreviewFace.FIELDS)
            set.faces.forEachIndexed { i, f -> f.toArray().copyInto(arr, i * PreviewFace.FIELDS) }
            if (n != faceCount || faceCurrent.size != arr.size) faceCurrent = arr.copyOf()
            faceTarget = arr
            faceCount = n
            if (n > 0) faceSeenNs = now
        }
        if (faceCount > 0 && now - faceSeenNs > 700_000_000L) {
            faceCount = 0 // face left the frame
        }
        for (i in faceCurrent.indices) faceCurrent[i] += (faceTarget[i] - faceCurrent[i]) * 0.4f
    }

    /**
     * GL thread, every 6th frame: learn the skin colour of the main face and compute the
     * lighting-aware face exposure with the same FaceRegions code the photo engine uses.
     */
    private fun analyzeFaces(img: RgbImage, lk: PreviewLook) {
        if (faceCount == 0) {
            faceLiftEv = 0f; return
        }
        val w = img.width
        val h = img.height
        val faces = (0 until faceCount).map { i ->
            val f = PreviewFace.fromArray(faceCurrent, i * PreviewFace.FIELDS)
            com.tamimrijan.camora.engine.FaceInfo(f.boxL * w, f.boxT / faceQy * h, f.boxR * w, f.boxB / faceQy * h)
        }
        val mask = com.tamimrijan.camora.engine.FaceRegions.ellipseMask(w, h, faces)
        val strength = if (lk.faceExposure) lk.faceLight * lk.tuning.faceLight else 0f
        val fe = com.tamimrijan.camora.engine.FaceRegions.faceExposure(img, faces, mask, tone, strength)
        faceLiftEv = fe.liftEv
        faceLog2 = fe.faceLog2
        // skin model: median chroma of the central part of the main face (orientation independent)
        val f = faces.maxBy { it.width * it.height }
        val x0 = (f.left + f.width * 0.3f).toInt().coerceIn(0, w - 1)
        val x1 = (f.right - f.width * 0.3f).toInt().coerceIn(x0 + 1, w)
        val y0 = (f.top + f.height * 0.3f).toInt().coerceIn(0, h - 1)
        val y1 = (f.bottom - f.height * 0.3f).toInt().coerceIn(y0 + 1, h)
        val cbs = ArrayList<Float>()
        val crs = ArrayList<Float>()
        for (y in y0 until y1) for (x in x0 until x1) {
            val p = img.pixels[y * w + x]
            val r = ((p shr 16) and 0xFF) / 255f
            val g = ((p shr 8) and 0xFF) / 255f
            val b = (p and 0xFF) / 255f
            val yy = 0.299f * r + 0.587f * g + 0.114f * b
            cbs.add(b - yy); crs.add(r - yy)
        }
        if (cbs.size >= 4) {
            cbs.sort(); crs.sort()
            val ncb = cbs[cbs.size / 2]
            val ncr = crs[crs.size / 2]
            val spread = maxOf(cbs[cbs.size * 3 / 4] - cbs[cbs.size / 4], crs[crs.size * 3 / 4] - crs[crs.size / 4])
            skinCb += (ncb - skinCb) * 0.5f
            skinCr += (ncr - skinCr) * 0.5f
            skinSigma += ((spread * 1.6f + 0.016f).coerceIn(0.02f, 0.065f) - skinSigma) * 0.5f
        }
    }

    private fun fail(t: Throwable) {
        Log.e(TAG, "GPU preview pipeline failed; falling back to plain preview", t)
        if (broken) return
        broken = true
        mainHandler.post { onFailure?.invoke(t) }
    }

    private fun ensureGl(): EglCore {
        egl?.let { return it }
        val e = EglCore()
        egl = e
        progMain = GlUtil.program(PreviewShaders.VERTEX, PreviewShaders.MAIN)
        progDown = GlUtil.program(PreviewShaders.VERTEX, PreviewShaders.DOWNSAMPLE)
        progBlur = GlUtil.program(PreviewShaders.VERTEX, PreviewShaders.BLUR)
        locMain = MainLoc(progMain)
        locDown = PassLoc(progDown)
        locBlur = PassLoc(progBlur)
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        lutTex = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)
        maskTex = GlUtil.texture2D()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, 1, 1, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1))
        GLES30.glGenFramebuffers(2, illumFbo, 0)
        illumTex[0] = GlUtil.texture2D()
        illumTex[1] = GlUtil.texture2D()
        return e
    }

    // ---- SurfaceProcessor ----------------------------------------------------------------
    override fun onInputSurface(request: SurfaceRequest) {
        if (released || broken) {
            request.willNotProvideSurface(); return
        }
        try {
            ensureGl()
        } catch (t: Throwable) {
            fail(t)
            request.willNotProvideSurface()
            return
        }
        val t = IntArray(1)
        GLES30.glGenTextures(1, t, 0)
        val tex = t[0]
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(tex)
        val res = request.resolution
        st.setDefaultBufferSize(res.width, res.height)
        val surface = Surface(st)
        request.provideSurface(surface, executor) {
            st.setOnFrameAvailableListener(null)
            st.release()
            surface.release()
            GLES30.glDeleteTextures(1, intArrayOf(tex), 0)
            if (input === st) {
                input = null; inputSurface = null
            }
        }
        st.setOnFrameAvailableListener({ onFrame(it) }, handler)
        input = st
        inputSurface = surface
        inputTex = tex
        inputSize = res
        setupIllum(res)
    }

    override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
        if (released || broken) {
            surfaceOutput.close(); return
        }
        val e = try {
            ensureGl()
        } catch (t: Throwable) {
            fail(t); surfaceOutput.close(); return
        }
        val surface = surfaceOutput.getSurface(executor) { event ->
            if (event.eventCode == SurfaceOutput.Event.EVENT_REQUEST_CLOSE) {
                outputs.remove(surfaceOutput)?.let { e.releaseSurface(it.egl) }
                surfaceOutput.close()
            }
        }
        // After a quick pause/resume (screen off and on, app switch) CameraX can hand over the
        // same screen surface before the old output was closed. EGL then refuses a second
        // window surface on it and the viewfinder stayed black. Drop stale outputs first.
        val stale = outputs.entries.filter { it.value.surface === surface || !it.value.surface.isValid }
        for ((so, out) in stale) {
            outputs.remove(so)
            e.releaseSurface(out.egl)
            so.close()
        }
        val win = try {
            e.createWindowSurface(surface)
        } catch (t: Throwable) {
            // Still held by an output CameraX has not closed yet: release every old output and
            // try once more. A failure here is never treated as a broken GPU (that would switch
            // the live look off for the whole session).
            Log.w(TAG, "output surface busy, releasing old outputs and retrying", t)
            for ((so, out) in outputs.entries.toList()) {
                e.releaseSurface(out.egl); so.close()
            }
            outputs.clear()
            try {
                e.createWindowSurface(surface)
            } catch (t2: Throwable) {
                Log.e(TAG, "cannot create output surface", t2)
                surfaceOutput.close()
                return
            }
        }
        outputs[surfaceOutput] = Out(win, surfaceOutput.size, surface)
    }

    private fun setupIllum(res: Size) {
        illumW = 96
        illumH = (96f * res.height / res.width).roundToInt().coerceIn(32, 192)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, illumTex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, illumW, illumH, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, illumFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, illumTex[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        readBuf = ByteBuffer.allocateDirect(illumW * illumH * 4).order(ByteOrder.nativeOrder())
        readPixels = IntArray(illumW * illumH)
    }

    private fun uploadPending() {
        pendingLut.getAndSet(null)?.let { bytes ->
            if (bytes.isEmpty()) {
                hasLut = false
            } else {
                val n = Math.round(Math.cbrt(bytes.size / 4.0)).toInt()
                GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
                GLES30.glTexImage3D(
                    GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, n, n, n, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
                    ByteBuffer.allocateDirect(bytes.size).put(bytes).position(0),
                )
                hasLut = true
            }
        }
        pendingMask.getAndSet(null)?.let { m ->
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            m.bytes.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, m.width, m.height, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, m.bytes)
            maskRotation = m.rotationDegrees
            maskAge = 0
        }
    }

    private fun drawQuad() {
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 0, quadPos)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 0, quadTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun onFrame(st: SurfaceTexture) {
        if (released || broken || st !== input) return
        try {
            renderFrame(st)
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun renderFrame(st: SurfaceTexture) {
        val e = egl ?: return
        e.makeCurrent(e.pbuffer)
        st.updateTexImage()
        st.getTransformMatrix(stMatrix)
        uploadPending()
        updateFaces()
        val lk = look
        frameCount++
        maskAge++

        // 1-2. illumination map (raw buffer orientation)
        val ld = locDown!!
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, illumFbo[0])
        GLES30.glViewport(0, 0, illumW, illumH)
        GLES30.glUseProgram(progDown)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
        GLES30.glUniform1i(ld.tex, 0)
        GLES30.glUniform2f(ld.step, 1f / illumW, 1f / illumH)
        GLES30.glUniformMatrix4fv(ld.texMatrix, 1, false, identity, 0)
        drawQuad()

        val lb = locBlur!!
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, illumFbo[1])
        GLES30.glUseProgram(progBlur)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, illumTex[0])
        GLES30.glUniform1i(lb.tex, 0)
        GLES30.glUniform2f(lb.step, 1f / illumW, 1f / illumH)
        GLES30.glUniformMatrix4fv(lb.texMatrix, 1, false, identity, 0)
        drawQuad()

        // 3. scene analysis on the same statistics the capture pipeline uses
        if (frameCount % 6 == 1L && lk.enabled) analyze(lk)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)

        // 4. outputs
        for ((so, out) in outputs) {
            so.updateTransformMatrix(texMatrix, stMatrix)
            e.makeCurrent(out.egl)
            GLES30.glViewport(0, 0, out.size.width, out.size.height)
            drawMain(lk)
            if (e.swap(out.egl)) lastDrawMs = android.os.SystemClock.elapsedRealtime()
            else Log.w(TAG, "swap failed")
        }
    }

    private fun analyze(lk: PreviewLook) {
        val buf = readBuf ?: return
        buf.position(0)
        GLES30.glReadPixels(0, 0, illumW, illumH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        val px = readPixels
        for (i in px.indices) {
            val r = buf.get(i * 4).toInt() and 0xFF
            val g = buf.get(i * 4 + 1).toInt() and 0xFF
            val b = buf.get(i * 4 + 2).toInt() and 0xFF
            px[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val img = RgbImage(illumW, illumH, px)
        val target = SceneAnalyzer.analyze(img, hdrStrength = lk.hdr.strength * lk.tuning.hdrStrength, night = false, wbStrength = lk.tuning.wbStrength)
        tone = tone.lerp(target, 0.3f)
        analyzeFaces(img, lk)
        val listener = statsListener ?: return
        val stats = SceneAnalyzer.stats(img)
        val ps = PreviewStats(stats.histogram, stats.p50)
        mainHandler.post { listener(ps) }
    }

    private fun drawMain(lk: PreviewLook) {
        val p = progMain
        val l = locMain!!
        GLES30.glUseProgram(p)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputTex)
        GLES30.glUniform1i(l.tex, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, illumTex[1])
        GLES30.glUniform1i(l.illum, 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTex)
        GLES30.glUniform1i(l.lut, 2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, maskTex)
        GLES30.glUniform1i(l.mask, 3)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)

        val t = if (lk.enabled) tone else ToneParams.NEUTRAL
        GLES30.glUniform3f(l.wb, t.wbR, t.wbG, t.wbB)
        GLES30.glUniform1f(l.exposure, t.exposureEv)
        GLES30.glUniform2f(l.tone, t.shadowLiftEv, t.highlightCompEv)
        val mix = if (lk.enabled && hasLut && lk.profile != ColorProfileId.ORIGINAL) lk.intensity else 0f
        GLES30.glUniform1f(l.lutMix, mix)
        // A stale mask (person left, analysis paused) must not keep blurring.
        val blur = if (lk.enabled && lk.portraitBlur > 0f && maskAge < 45) lk.portraitBlur * 0.018f else 0f
        GLES30.glUniform1f(l.blur, blur)
        GLES30.glUniform2f(l.aspect, 1f, inputSize.width.toFloat() / inputSize.height)
        GLES30.glUniformMatrix3fv(l.maskXf, 1, false, maskTransform(maskRotation), 0)
        GLES30.glUniformMatrix4fv(l.texMatrix, 1, false, texMatrix, 0)

        // faces: skin preview and face lighting
        val n = if (lk.enabled) minOf(faceCount, 2) else 0
        for (i in 0 until n) {
            val o = i * PreviewFace.FIELDS
            val c = faceCurrent
            uFace[i * 4] = c[o]; uFace[i * 4 + 1] = c[o + 1]; uFace[i * 4 + 2] = c[o + 2]; uFace[i * 4 + 3] = c[o + 3]
            uEyes[i * 4] = c[o + 4]; uEyes[i * 4 + 1] = c[o + 5]; uEyes[i * 4 + 2] = c[o + 6]; uEyes[i * 4 + 3] = c[o + 7]
            val faceWidth = c[o + 13] - c[o + 11]
            // same scale as BeautyPass: guided-filter radius ≈ 1.8% of the face width
            uMouth[i * 4] = c[o + 8]; uMouth[i * 4 + 1] = c[o + 9]; uMouth[i * 4 + 2] = c[o + 10]; uMouth[i * 4 + 3] = faceWidth * 0.022f * com.tamimrijan.camora.engine.BeautyCurve.of(lk.smoothing).radiusScale
        }
        GLES30.glUniform1i(l.faceCount, n)
        if (n > 0) {
            GLES30.glUniform4fv(l.face, 2, uFace, 0)
            GLES30.glUniform4fv(l.eyes, 2, uEyes, 0)
            GLES30.glUniform4fv(l.mouth, 2, uMouth, 0)
        }
        GLES30.glUniform3f(l.skin, skinCb, skinCr, skinSigma)
        GLES30.glUniform3f(l.beauty, lk.smoothing, lk.evenTone, if (lk.beautyOn && n > 0) 1f else 0f)
        GLES30.glUniform2f(l.faceLight, if (n > 0) faceLiftEv else 0f, faceLog2)
        GLES30.glUniform1f(l.qy, inputSize.height.toFloat() / inputSize.width)
        GLES30.glUniform2f(l.texel, 1f / inputSize.width, 1f / inputSize.height)
        drawQuad()
    }

    private val xf0 = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val xf90 = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 1f, 0f, 1f)   // u = 1 - t, v = s
    private val xf180 = floatArrayOf(-1f, 0f, 0f, 0f, -1f, 0f, 1f, 1f, 1f) // u = 1 - s, v = 1 - t
    private val xf270 = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f)  // u = t, v = 1 - s

    /**
     * Maps camera-buffer texture coordinates (s,t; t=0 is the first buffer row) to the
     * upright segmentation mask produced by ML Kit for an image rotated clockwise by
     * [rot] degrees. Column-major for glUniformMatrix3fv.
     */
    private fun maskTransform(rot: Int): FloatArray = when ((rot % 360 + 360) % 360) {
        90 -> xf90
        180 -> xf180
        270 -> xf270
        else -> xf0
    }

    /** Releases GL resources; the processor cannot be used afterwards. */
    fun release() {
        handler.post {
            released = true
            val e = egl
            for ((so, out) in outputs) {
                e?.releaseSurface(out.egl); so.close()
            }
            outputs.clear()
            input?.release()
            inputSurface?.release()
            if (e != null) {
                GLES30.glDeleteProgram(progMain); GLES30.glDeleteProgram(progDown); GLES30.glDeleteProgram(progBlur)
                e.release()
            }
            egl = null
            thread.quitSafely()
        }
        lutExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CamoraPreview"
    }
}
