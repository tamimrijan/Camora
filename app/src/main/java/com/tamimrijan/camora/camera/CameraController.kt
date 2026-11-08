package com.tamimrijan.camora.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import com.tamimrijan.camora.gl.NaturalPreviewProcessor
import com.tamimrijan.camora.util.awaitResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the CameraX session (photo only). CameraX handles device quirks and use-case
 * combinations; Camera2 interop is used only where CameraX has no API: OIS, AE/AWB lock
 * during HDR bursts, manual ISO/shutter/focus/WB and live capture metadata. Every interop
 * option is gated on the module's reported capabilities.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context, val previewProcessor: NaturalPreviewProcessor) {
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var analyzer: LiveAnalyzer? = null
    private var zoomLive: androidx.lifecycle.LiveData<androidx.camera.core.ZoomState>? = null
    private var lens: LensInfo? = null
    private var targetRotation = Surface.ROTATION_0
    private var lastMetaPublish = 0L
    private var manual = ManualSettings()
    private var mode = CameraMode.PHOTO
    private var faceMetering = true
    private var front = false

    // The user's flash choice. Every bind creates a new ImageCapture (lens switch, mode or
    // setting change), so it is applied in the builder and again right before each shot.
    private var flash = FlashSetting.OFF

    // face-priority metering state
    // Written on the UI thread (tap to focus) and read on the analysis thread (face metering).
    @Volatile private var userMeteringUntil = 0L
    @Volatile private var faceActive = false
    private var lastFaceSeen = 0L
    private var lastFaceAction = 0L
    private var lastFaceX = -1f
    private var lastFaceY = -1f

    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView, req: BindRequest) {
        val p = provider ?: ProcessCameraProvider.getInstance(context).awaitResult().also { provider = it }
        p.unbindAll()
        camera = null
        mode = req.mode
        faceMetering = req.faceMetering
        front = req.front
        manual = ManualSettings()

        val lenses = CameraCapabilities.lenses(context)
        val facing = if (req.front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val defaultLens = lenses.firstOrNull { it.facing == facing && it.role != "Ultra wide" && it.role != "Macro" }
            ?: lenses.firstOrNull { it.facing == facing }
        val chosen = req.cameraId?.let { id -> lenses.firstOrNull { it.cameraId == id } } ?: defaultLens
        lens = chosen
        val ultraWide = if (!req.front) lenses.firstOrNull { it.isBack && it.role == "Ultra wide" && it.cameraId != defaultLens?.cameraId } else null

        val selector = if (chosen != null) {
            CameraSelector.Builder().addCameraFilter { infos ->
                infos.filter { Camera2CameraInfo.from(it).cameraId == chosen.cameraId }
            }.build()
        } else if (req.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        // Try the full configuration first, then progressively simpler ones: some HALs
        // reject certain stream combinations, and the app must still work.
        val attempts = listOf(
            Attempt(effect = req.naturalPreview, analysis = true, highRes = req.highRes),
            Attempt(effect = req.naturalPreview, analysis = false, highRes = req.highRes),
            Attempt(effect = req.naturalPreview, analysis = false, highRes = false),
            Attempt(effect = false, analysis = false, highRes = false),
        )
        var lastError: Throwable? = null
        for (a in attempts) {
            try {
                bindWith(owner, previewView, p, selector, req, chosen, a)
                val cam = camera!!
                // Camera2 interop options live on the camera, not the binding: clear any
                // manual exposure / AE lock left over from Pro mode or a burst.
                Camera2CameraControl.from(cam.cameraControl).clearCaptureRequestOptions()
                val zoom = cam.cameraInfo.zoomState.value
                val ev = cam.cameraInfo.exposureState
                _state.update {
                    it.copy(
                        bound = true, error = null, lens = chosen, front = req.front,
                        zoomRatio = zoom?.zoomRatio ?: 1f, minZoom = zoom?.minZoomRatio ?: 1f, maxZoom = zoom?.maxZoomRatio ?: 1f,
                        evIndex = ev.exposureCompensationIndex, evMin = ev.exposureCompensationRange.lower,
                        evMax = ev.exposureCompensationRange.upper, evStep = ev.exposureCompensationStep.toFloat(),
                        hasFlash = cam.cameraInfo.hasFlashUnit(),
                        ultraWideId = ultraWide?.cameraId, usingUltraWide = chosen?.role == "Ultra wide",
                        captureSize = imageCapture?.resolutionInfo?.resolution,
                        naturalPreviewActive = a.effect, faces = 0,
                        notice = when {
                            req.naturalPreview && !a.effect -> "Live Natural preview unavailable for this setting"
                            req.highRes && !a.highRes -> "High resolution mode is not available here"
                            else -> null
                        },
                    )
                }
                zoomLive?.removeObservers(owner)
                zoomLive = cam.cameraInfo.zoomState
                cam.cameraInfo.zoomState.observe(owner) { z ->
                    _state.update { it.copy(zoomRatio = z.zoomRatio, minZoom = z.minZoomRatio, maxZoom = z.maxZoomRatio) }
                }
                return
            } catch (t: Throwable) {
                Log.w(TAG, "bind attempt $a failed", t)
                lastError = t
                p.unbindAll()
            }
        }
        _state.update { it.copy(bound = false, error = lastError?.message ?: "Camera unavailable") }
    }

    private data class Attempt(val effect: Boolean, val analysis: Boolean, val highRes: Boolean)

    private fun bindWith(
        owner: LifecycleOwner,
        previewView: PreviewView,
        p: ProcessCameraProvider,
        selector: CameraSelector,
        req: BindRequest,
        l: LensInfo?,
        a: Attempt,
    ) {
        val aspectStrategy = if (req.aspect.streamIs16x9) AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        else AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY

        val previewSize = if (req.aspect.streamIs16x9) Size(1920, 1080) else Size(1440, 1080)
        val previewBuilder = Preview.Builder().setResolutionSelector(
            ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
                .setResolutionStrategy(ResolutionStrategy(previewSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                .build(),
        )
        val pExt = Camera2Interop.Extender(previewBuilder)
        if (l?.hasOis == true) pExt.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        pExt.setSessionCaptureCallback(metaCallback)
        val preview = previewBuilder.build()

        val group = UseCaseGroup.Builder().addUseCase(preview)

        val sizeSel = ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
        val target = req.photoSize
        if (target != null) sizeSel.setResolutionStrategy(ResolutionStrategy(target, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        else sizeSel.setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        if (a.highRes) sizeSel.setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
        val icb = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(98)
            .setFlashMode(cameraXFlash())
            .setResolutionSelector(sizeSel.build())
            .setTargetRotation(targetRotation)
        if (l?.hasOis == true) Camera2Interop.Extender(icb).setCaptureRequestOption(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
        )
        val ic = icb.build()
        imageCapture = ic
        group.addUseCase(ic)

        analyzer?.close()
        analyzer = null
        if (a.analysis && (req.faceMetering || req.faceTracking || req.portraitPreview)) {
            val ia = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(aspectStrategy)
                        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            val an = LiveAnalyzer(
                faces = req.faceMetering || req.faceTracking,
                landmarks = req.landmarks,
                segmentation = req.portraitPreview,
                processor = if (a.effect) previewProcessor else null,
                callbacks = analysisExecutor,
                onFaces = { list, w, h -> onFaces(list, w, h, ia) },
            )
            analyzer = an
            ia.setAnalyzer(analysisExecutor, an)
            group.addUseCase(ia)
        }

        if (a.effect) group.addEffect(previewProcessor.createEffect(CameraEffect.PREVIEW))
        preview.setSurfaceProvider(previewView.surfaceProvider)
        camera = p.bindToLifecycle(owner, selector, group.build())
    }

    private val metaCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (mode != CameraMode.PRO) return // live ISO/shutter readout is only shown in Pro mode
            val now = SystemClock.elapsedRealtime()
            if (now - lastMetaPublish < 250) return
            lastMetaPublish = now
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
            val fd = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: 0f
            _state.update { it.copy(iso = iso, exposureNs = exp, focusDistance = fd) }
        }
    }

    fun setTargetRotation(rotation: Int) {
        targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    fun setZoom(ratio: Float) {
        val s = _state.value
        camera?.cameraControl?.setZoomRatio(ratio.coerceIn(s.minZoom, s.maxZoom))
    }

    fun setExposureIndex(index: Int) {
        val s = _state.value
        val i = index.coerceIn(s.evMin, s.evMax)
        camera?.cameraControl?.setExposureCompensationIndex(i)
        _state.update { it.copy(evIndex = i) }
    }

    fun setFlash(f: FlashSetting) {
        flash = f
        imageCapture?.flashMode = cameraXFlash()
    }

    /** The front camera has no LED: its "flash" is the screen light handled by the UI. */
    private fun cameraXFlash(): Int = if (front) ImageCapture.FLASH_MODE_OFF else when (flash) {
        FlashSetting.OFF -> ImageCapture.FLASH_MODE_OFF
        FlashSetting.AUTO -> ImageCapture.FLASH_MODE_AUTO
        FlashSetting.ON -> ImageCapture.FLASH_MODE_ON
    }

    /** Tap to focus/expose. [lock] = long-press AE/AF lock (no auto-cancel). */
    fun focusAt(previewView: PreviewView, x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        val flags = FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        val action = FocusMeteringAction.Builder(point, flags).apply {
            if (lock) disableAutoCancel() else setAutoCancelDuration(5, TimeUnit.SECONDS)
        }.build()
        userMeteringUntil = SystemClock.elapsedRealtime() + if (lock) Long.MAX_VALUE / 2 else 5000L
        faceActive = false
        cam.cameraControl.startFocusAndMetering(action)
    }

    fun clearUserMetering() {
        userMeteringUntil = 0L
        camera?.cameraControl?.cancelFocusAndMetering()
    }

    /**
     * Face-priority auto exposure: when a face is visible and the user has not tapped,
     * meter exposure (and focus on the rear camera) on the largest face. Re-issued only
     * when the face moves noticeably, so exposure does not pump.
     */
    private fun onFaces(centers: List<FloatArray>, w: Int, h: Int, ia: ImageAnalysis) {
        val now = SystemClock.elapsedRealtime()
        if (_state.value.faces != centers.size) _state.update { it.copy(faces = centers.size) }
        val cam = camera ?: return
        if (now < userMeteringUntil || mode == CameraMode.PRO || !faceMetering) return
        if (centers.isEmpty()) {
            if (faceActive && now - lastFaceSeen > 1500) {
                faceActive = false
                cam.cameraControl.cancelFocusAndMetering()
            }
            return
        }
        lastFaceSeen = now
        val c = centers.maxBy { it[2] }
        val nx = c[0] / w
        val ny = c[1] / h
        val moved = hypot(nx - lastFaceX, ny - lastFaceY)
        if (faceActive && moved < 0.08f && now - lastFaceAction < 3000) return
        val factory = SurfaceOrientedMeteringPointFactory(w.toFloat(), h.toFloat(), ia)
        val point = factory.createPoint(c[0], c[1], 0.25f)
        var flags = FocusMeteringAction.FLAG_AE
        if ((lens?.minFocusDistance ?: 0f) > 0f && (!faceActive || moved > 0.15f)) flags = flags or FocusMeteringAction.FLAG_AF
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, flags).disableAutoCancel().build())
        faceActive = true
        lastFaceAction = now
        lastFaceX = nx
        lastFaceY = ny
    }

    // ---- Pro mode ---------------------------------------------------------------------------
    fun applyManual(m: ManualSettings) {
        val cam = camera ?: return
        val l = lens ?: return
        manual = m
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        val b = CaptureRequestOptions.Builder()
        if (l.manualSensor && (m.iso != null || m.shutterNs != null)) {
            val s = _state.value
            val iso = (m.iso ?: s.iso.takeIf { it > 0 } ?: 100).let { v -> l.isoRange?.let { v.coerceIn(it.lower, it.upper) } ?: v }
            val exp = (m.shutterNs ?: s.exposureNs.takeIf { it > 0 } ?: 16_666_666L)
                .let { v -> l.exposureRangeNs?.let { v.coerceIn(it.lower, it.upper) } ?: v }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(exp, 33_333_333L))
        }
        if (m.awbMode != null && l.awbModes.contains(m.awbMode)) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, m.awbMode)
        }
        if (m.focus != null && l.canManualFocus) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, m.focus * l.minFocusDistance)
        }
        c2.setCaptureRequestOptions(b.build())
    }

    // ---- Capture ----------------------------------------------------------------------------
    /**
     * Captures [count] frames. With [evOffset] the frames are under-exposed by that many
     * stops (HDR bursts are shot at -1 EV to protect highlights). Bursts lock AE/AWB so all
     * frames match, which the merger relies on.
     */
    suspend fun capture(
        count: Int,
        evOffset: Float = 0f,
        onShutter: () -> Unit = {},
        onFrame: (Int) -> Unit = {},
    ): List<CapturedFrame> {
        val ic = imageCapture ?: error("Camera not ready")
        // Bursts are shot without flash (each frame would need its own precapture).
        ic.flashMode = if (count > 1) ImageCapture.FLASH_MODE_OFF else cameraXFlash()
        val cam = camera ?: error("Camera not ready")
        val s = _state.value
        val originalEv = s.evIndex
        var changedEv = false
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        try {
            if (evOffset != 0f && s.evStep > 0f && manual.isAuto) {
                val idx = (originalEv + (evOffset / s.evStep).roundToInt()).coerceIn(s.evMin, s.evMax)
                if (idx != originalEv) {
                    cam.cameraControl.setExposureCompensationIndex(idx).awaitResult()
                    changedEv = true
                    delay(120)
                }
            }
            if (count > 1 && manual.isAuto) {
                c2.addCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                        .build(),
                ).awaitResult()
            }
            val frames = ArrayList<CapturedFrame>(count)
            // The shutter sound and blink follow the real exposure: with flash the camera first
            // runs a pre-flash metering step, and the photo is taken only after it.
            val fired = java.util.concurrent.atomic.AtomicBoolean(false)
            val shutter = { if (fired.compareAndSet(false, true)) onShutter() }
            repeat(count) { i ->
                frames += takeOne(ic, if (i == 0) shutter else ({}))
                shutter() // fallback if the HAL never reported the capture start
                onFrame(i + 1)
            }
            return frames
        } finally {
            if (count > 1 && manual.isAuto) runCatching {
                c2.addCaptureRequestOptions(
                    CaptureRequestOptions.Builder()
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, false)
                        .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, false)
                        .build(),
                )
            }
            if (changedEv) cam.cameraControl.setExposureCompensationIndex(originalEv)
            ic.flashMode = cameraXFlash()
        }
    }

    private suspend fun takeOne(ic: ImageCapture, onStarted: () -> Unit): CapturedFrame = suspendCancellableCoroutine { cont ->
        ic.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() = onStarted()

            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val buf = image.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    cont.resume(
                        CapturedFrame(bytes, image.imageInfo.rotationDegrees, image.width, image.height, image.imageInfo.timestamp, image.cropRect),
                    )
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                } finally {
                    image.close()
                }
            }

            override fun onError(exception: ImageCaptureException) {
                cont.resumeWithException(exception)
            }
        })
    }

    fun unbind() {
        provider?.unbindAll()
        camera = null
        _state.update { it.copy(bound = false) }
    }

    fun release() {
        unbind()
        analyzer?.close()
        cameraExecutor.shutdown()
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraController"

        /** Closest AWB preset for a colour temperature (manual WB without MANUAL_POST_PROCESSING). */
        fun awbForKelvin(k: Int): Int = when {
            k < 3200 -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
            k < 4500 -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
            k < 5900 -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
            k < 7000 -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
            else -> CaptureRequest.CONTROL_AWB_MODE_SHADE
        }

        fun presetKelvin(mode: Int): Int = when (mode) {
            CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> 2850
            CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> 4000
            CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> 5500
            CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> 6500
            CaptureRequest.CONTROL_AWB_MODE_SHADE -> 7500
            else -> 5500
        }
    }
}
