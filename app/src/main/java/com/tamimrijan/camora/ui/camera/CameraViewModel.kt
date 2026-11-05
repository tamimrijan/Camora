package com.tamimrijan.camora.ui.camera

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.camera.view.PreviewView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.tamimrijan.camora.CamoraApp
import com.tamimrijan.camora.camera.AspectSetting
import com.tamimrijan.camora.camera.BindRequest
import com.tamimrijan.camora.camera.CameraController
import com.tamimrijan.camora.camera.CameraMode
import com.tamimrijan.camora.camera.FlashSetting
import com.tamimrijan.camora.camera.ManualSettings
import com.tamimrijan.camora.data.AppSettings
import com.tamimrijan.camora.engine.CameraTuning
import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.HdrMode
import com.tamimrijan.camora.gl.NaturalPreviewProcessor
import com.tamimrijan.camora.gl.PreviewLook
import com.tamimrijan.camora.gl.PreviewStats
import com.tamimrijan.camora.processing.CaptureJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Panel { NONE, PROFILE, BEAUTY, BLUR, PRO }
enum class ProParam(val label: String) { ISO("ISO"), SHUTTER("S"), WB("WB"), FOCUS("MF"), EV("EV") }

data class CameraUi(
    val mode: CameraMode = CameraMode.PHOTO,
    val front: Boolean = false,
    val cameraId: String? = null,
    val capturing: Boolean = false,
    val burst: Pair<Int, Int>? = null,
    val countdown: Int = 0,
    val screenFlash: Boolean = false,
    val shutterFlash: Long = 0L,
    val lastThumb: Bitmap? = null,
    val lastUri: Uri? = null,
    val timer: Int = 0,
    val manual: ManualSettings = ManualSettings(),
    val panel: Panel = Panel.NONE,
    val proParam: ProParam? = null,
    val focusLocked: Boolean = false,
)

class CameraViewModel(app: Application) : AndroidViewModel(app) {
    private val nc = app as CamoraApp
    val processor = NaturalPreviewProcessor()
    val controller = CameraController(app, processor)
    val cameraState = controller.state
    val pipelineBusy = nc.pipeline.inFlight

    /**
     * Slider changes are applied here immediately (so the viewfinder reacts in real time)
     * and written to DataStore shortly after the user stops dragging.
     */
    private val local = MutableStateFlow<AppSettings?>(null)
    private var persistJob: Job? = null

    val settings: StateFlow<AppSettings> = combine(nc.settings.settings, local) { stored, l -> l ?: stored }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val _ui = MutableStateFlow(CameraUi())
    val ui: StateFlow<CameraUi> = _ui.asStateFlow()

    /** Live histogram; kept out of [ui] so the whole camera screen does not recompose 5x per second. */
    private val _stats = MutableStateFlow<PreviewStats?>(null)
    val stats: StateFlow<PreviewStats?> = _stats.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val sound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    private var pendingZoom: Float? = null

    /** Set if the GPU preview pipeline failed on this device; the camera then runs without it. */
    private val effectFailed = MutableStateFlow(false)

    /** Everything that requires re-binding CameraX. */
    val bindRequest: StateFlow<BindRequest?> = combine(_ui.map { Triple(it.mode, it.front, it.cameraId) }.distinctUntilChanged(), settings, effectFailed) { (mode, front, id), s, broken ->
        val skinMode = beautyMode(mode, front)
        BindRequest(
            mode = mode,
            front = front,
            cameraId = id,
            aspect = s.aspect,
            photoSize = parseSize(s.photoResolution),
            highRes = s.highRes,
            naturalPreview = !broken && s.naturalPreview,
            faceMetering = s.faceExposure && mode != CameraMode.PRO,
            faceTracking = s.faceExposure || skinMode,
            landmarks = skinMode,
            portraitPreview = mode == CameraMode.PORTRAIT && s.portraitBlur > 0f,
        )
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Modes where skin smoothing applies (same rule as the capture pipeline). */
    private fun beautyMode(mode: CameraMode, front: Boolean) = mode == CameraMode.SELFIE || mode == CameraMode.PORTRAIT || front

    init {
        processor.onFailure = { t ->
            effectFailed.value = true
            _messages.tryEmit("Live preview effect unavailable (${t.javaClass.simpleName}); photos are still processed")
        }
        viewModelScope.launch {
            combine(_ui.map { it.mode to it.front }.distinctUntilChanged(), settings) { (mode, front), s ->
                PreviewLook(
                    profile = s.profile,
                    intensity = s.profileIntensity,
                    hdr = s.hdr,
                    tuning = if (front) CameraTuning.FRONT else CameraTuning.REAR,
                    portraitBlur = if (mode == CameraMode.PORTRAIT) s.portraitBlur else 0f,
                    enabled = s.naturalPreview,
                    beautyAllowed = beautyMode(mode, front),
                    smoothing = s.beautySmoothing,
                    evenTone = s.beautyEvenTone,
                    faceLight = s.faceLight,
                    faceExposure = s.faceExposure,
                )
            }.distinctUntilChanged().collect { processor.look = it }
        }
        viewModelScope.launch {
            // Histogram statistics are only computed while Pro mode shows them.
            _ui.map { it.mode == CameraMode.PRO }.distinctUntilChanged().collect { pro ->
                processor.statsListener = if (pro) { st -> _stats.value = st } else null
                if (!pro) _stats.value = null
            }
        }
        viewModelScope.launch {
            nc.pipeline.results.collect { r ->
                if (r.error != null) {
                    _messages.emit("Could not save photo: ${r.error}")
                    return@collect
                }
                _ui.update { it.copy(lastThumb = r.thumbnail ?: it.lastThumb, lastUri = r.uri ?: it.lastUri) }
            }
        }
        viewModelScope.launch {
            combine(settings.map { it.flash }.distinctUntilChanged(), cameraState.map { it.bound }.distinctUntilChanged()) { f, b -> f to b }
                .collect { (f, bound) -> if (bound) controller.setFlash(if (_ui.value.front) FlashSetting.OFF else f) }
        }
        refreshThumb()
    }

    fun refreshThumb() {
        viewModelScope.launch {
            val first = runCatching { nc.media.query() }.getOrDefault(emptyList()).firstOrNull()
            if (first == null) {
                _ui.update { it.copy(lastThumb = null, lastUri = null) }; return@launch
            }
            if (first.uri == _ui.value.lastUri && _ui.value.lastThumb != null) return@launch
            val bmp = withContext(Dispatchers.IO) {
                runCatching { getApplication<Application>().contentResolver.loadThumbnail(first.uri, Size(256, 256), null) }.getOrNull()
            }
            _ui.update { it.copy(lastThumb = bmp, lastUri = first.uri) }
        }
    }

    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView, req: BindRequest) {
        controller.bind(owner, previewView, req)
        pendingZoom?.let { controller.setZoom(it) }
        pendingZoom = null
        if (_ui.value.mode == CameraMode.PRO) controller.applyManual(_ui.value.manual)
        cameraState.value.notice?.let { _messages.emit(it) }
    }

    // ---- UI actions -----------------------------------------------------------------------
    fun setMode(m: CameraMode) {
        if (m == _ui.value.mode) return
        _ui.update {
            val front = when {
                m == CameraMode.SELFIE -> true
                it.mode == CameraMode.SELFIE -> false
                else -> it.front
            }
            it.copy(
                mode = m, front = front, cameraId = if (front != it.front) null else it.cameraId,
                panel = Panel.NONE, proParam = null, manual = ManualSettings(), focusLocked = false,
            )
        }
    }

    fun flipCamera() {
        _ui.update {
            val front = !it.front
            val mode = if (it.mode == CameraMode.SELFIE && !front) CameraMode.PHOTO else it.mode
            it.copy(front = front, cameraId = null, mode = mode, manual = ManualSettings(), focusLocked = false)
        }
    }

    /** Zoom presets: 0.6 may be a separate ultra-wide module or a logical-camera zoom ratio. */
    fun zoomPreset(ratio: Float) {
        val s = cameraState.value
        if (ratio < 0.99f) {
            if (s.minZoom < 0.99f) controller.setZoom(s.minZoom)
            else if (s.ultraWideId != null && !s.usingUltraWide) _ui.update { it.copy(cameraId = s.ultraWideId) }
            return
        }
        if (s.usingUltraWide) {
            pendingZoom = ratio
            _ui.update { it.copy(cameraId = null) }
        } else controller.setZoom(ratio)
    }

    fun pinchZoom(scale: Float) {
        controller.setZoom(cameraState.value.zoomRatio * scale)
    }

    fun togglePanel(p: Panel) = _ui.update { it.copy(panel = if (it.panel == p) Panel.NONE else p) }
    fun closePanels() = _ui.update { it.copy(panel = Panel.NONE, proParam = null) }
    fun selectProParam(p: ProParam?) = _ui.update { it.copy(proParam = if (it.proParam == p) null else p) }
    fun cycleTimer() = _ui.update { it.copy(timer = when (it.timer) { 0 -> 3; 3 -> 10; else -> 0 }) }

    fun updateSettings(t: (AppSettings) -> AppSettings) {
        val next = t(settings.value)
        local.value = next
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(300)
            nc.settings.update { next }
            delay(400) // let DataStore emit the saved value before dropping the local copy
            if (local.value === next) local.value = null
        }
    }

    fun cycleFlash() = updateSettings {
        it.copy(flash = when (it.flash) { FlashSetting.OFF -> FlashSetting.AUTO; FlashSetting.AUTO -> FlashSetting.ON; FlashSetting.ON -> FlashSetting.OFF })
    }

    fun cycleHdr() = updateSettings {
        it.copy(hdr = when (it.hdr) { HdrMode.AUTO -> HdrMode.ON; HdrMode.ON -> HdrMode.OFF; HdrMode.OFF -> HdrMode.AUTO })
    }

    fun cycleAspect() = updateSettings {
        val all = AspectSetting.entries
        it.copy(aspect = all[(all.indexOf(it.aspect) + 1) % all.size])
    }

    fun setProfile(p: ColorProfileId) = updateSettings { it.copy(profile = p) }
    fun setIntensity(v: Float) = updateSettings { it.copy(profileIntensity = v) }

    fun tapFocus(view: PreviewView, x: Float, y: Float, lock: Boolean) {
        controller.focusAt(view, x, y, lock)
        _ui.update { it.copy(focusLocked = lock) }
        if (!lock && cameraState.value.evIndex != 0) controller.setExposureIndex(0)
    }

    fun unlockFocus() {
        controller.clearUserMetering()
        _ui.update { it.copy(focusLocked = false) }
    }

    fun setExposureIndex(i: Int) = controller.setExposureIndex(i)

    fun setManual(m: ManualSettings) {
        _ui.update { it.copy(manual = m) }
        controller.applyManual(m)
    }

    fun onOrientation(rotation: Int) = controller.setTargetRotation(rotation)

    // ---- Capture ---------------------------------------------------------------------------
    fun onShutter() {
        if (!cameraState.value.bound) return
        val u = _ui.value
        if (u.capturing || u.countdown > 0) return
        if (pipelineBusy.value >= 3) {
            _messages.tryEmit("Still developing previous photos…"); return
        }
        val s = settings.value
        viewModelScope.launch {
            _ui.update { it.copy(capturing = true) }
            try {
                for (i in u.timer downTo 1) {
                    _ui.update { it.copy(countdown = i) }
                    delay(1000)
                }
                _ui.update { it.copy(countdown = 0) }
                val screenFlash = u.front && s.flash != FlashSetting.OFF
                if (screenFlash) {
                    _ui.update { it.copy(screenFlash = true) }
                    delay(700) // let AE settle on the screen light
                }
                val (frames, ev) = plan(u, s)
                if (s.shutterSound || (Build.VERSION.SDK_INT >= 33 && MediaActionSound.mustPlayShutterSound())) sound.play(MediaActionSound.SHUTTER_CLICK)
                _ui.update { it.copy(shutterFlash = System.nanoTime(), burst = if (frames > 1) 0 to frames else null) }
                val captured = controller.capture(frames, ev) { n -> if (frames > 1) _ui.update { it.copy(burst = n to frames) } }
                val job = CaptureJob(
                    id = System.currentTimeMillis(),
                    frames = captured,
                    mode = u.mode,
                    front = u.front,
                    settings = s,
                    baselineEv = -ev,
                    manualWarmth = manualWarmth(u.manual),
                )
                if (!nc.pipeline.submit(job)) _messages.emit("Processing queue full")
            } catch (t: Throwable) {
                _messages.emit("Capture failed: ${t.message}")
            } finally {
                _ui.update { it.copy(capturing = false, screenFlash = false, burst = null, countdown = 0) }
            }
        }
    }

    /** (frames, EV offset). HDR On shoots a 3-frame burst at -1 EV; everything else is one frame. */
    private fun plan(u: CameraUi, s: AppSettings): Pair<Int, Float> {
        val size = cameraState.value.captureSize
        val mp = if (size != null) size.width.toLong() * size.height / 1_000_000f else 12f
        val burstOk = mp <= 16.5f && u.manual.isAuto && u.mode != CameraMode.PRO
        val flashOn = !u.front && s.flash == FlashSetting.ON
        return if (burstOk && !flashOn && s.hdr == HdrMode.ON) 3 to -1f else 1 to 0f
    }

    private fun manualWarmth(m: ManualSettings): Float {
        val k = m.kelvin ?: return 0f
        val preset = CameraController.presetKelvin(m.awbMode ?: return 0f)
        // residual in mireds between the preset the HAL applied and the requested Kelvin
        val mired = 1e6f / k - 1e6f / preset
        return (mired / 120f).coerceIn(-1f, 1f)
    }

    override fun onCleared() {
        controller.release()
        processor.release()
        sound.release()
    }

    companion object {
        fun parseSize(s: String): Size? {
            val p = s.split("x")
            if (p.size != 2) return null
            val w = p[0].toIntOrNull() ?: return null
            val h = p[1].toIntOrNull() ?: return null
            return Size(w, h)
        }
    }
}
