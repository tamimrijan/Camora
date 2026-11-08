package com.tamimrijan.camora.ui.camera

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.view.OrientationEventListener
import android.view.Surface
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.HdrAuto
import androidx.compose.material.icons.rounded.HdrOff
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Timer10
import androidx.compose.material.icons.rounded.Timer3
import androidx.compose.material.icons.rounded.TimerOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimrijan.camora.camera.AspectSetting
import com.tamimrijan.camora.camera.CameraMode
import com.tamimrijan.camora.camera.FlashSetting
import com.tamimrijan.camora.data.AppSettings
import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.HdrMode
import com.tamimrijan.camora.engine.LutBuilder
import com.tamimrijan.camora.ui.theme.Accent
import com.tamimrijan.camora.ui.theme.RecordRed
import com.tamimrijan.camora.util.signed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

private val MODES = listOf(CameraMode.PORTRAIT, CameraMode.PHOTO, CameraMode.SELFIE, CameraMode.PRO)
private val TOP_BAR = 52.dp
private val CONTROLS = 200.dp

@Composable
fun CameraScreen(
    vm: CameraViewModel,
    shutterKeys: Flow<Unit>,
    onOpenLatest: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val cs by vm.cameraState.collectAsStateWithLifecycle()
    val s by vm.settings.collectAsStateWithLifecycle()
    val req by vm.bindRequest.collectAsStateWithLifecycle()
    val busy by vm.pipelineBusy.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    // Frozen, blurred frame shown while the camera re-binds (mode/lens switch).
    var transitionFrame by remember { mutableStateOf<Bitmap?>(null) }
    // Where the zoom buttons sit inside the viewfinder, so taps on them don't move focus.
    var zoomBarRect by remember { mutableStateOf(Rect.Zero) }
    val transitionAlpha = remember { Animatable(0f) }
    LaunchedEffect(req) {
        val r = req ?: return@LaunchedEffect
        previewView.bitmap?.let {
            transitionFrame = it
            transitionAlpha.snapTo(1f)
        }
        vm.bind(owner, previewView, r)
        // Keep the frozen frame until the new lens is really streaming (the ultra wide can take
        // a second to open), so the viewfinder never flashes black.
        kotlinx.coroutines.delay(120)
        kotlinx.coroutines.withTimeoutOrNull(2500) {
            while (previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) kotlinx.coroutines.delay(30)
        }
        kotlinx.coroutines.delay(120)
        transitionAlpha.animateTo(0f, tween(300))
        transitionFrame = null
    }

    // Device orientation (activity is portrait-locked): rotate icons and capture.
    var deviceDeg by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val d = when (o) {
                    in 45..134 -> 90
                    in 135..224 -> 180
                    in 225..314 -> 270
                    else -> 0
                }
                if (d != deviceDeg) {
                    deviceDeg = d
                    vm.onOrientation(
                        when (d) {
                            90 -> Surface.ROTATION_270
                            180 -> Surface.ROTATION_180
                            270 -> Surface.ROTATION_90
                            else -> Surface.ROTATION_0
                        },
                    )
                }
            }
        }
        l.enable()
        onDispose { l.disable() }
    }
    val rot = rememberUiRotation(deviceDeg)

    LaunchedEffect(Unit) { vm.messages.collect { m -> scope.launch { snackbar.showSnackbar(m) } } }
    LaunchedEffect(Unit) { shutterKeys.collect { vm.onShutter() } }
    LaunchedEffect(Unit) { vm.refreshThumb() }

    // Watchdog for a frozen (black) viewfinder. It is deliberately patient: a cold start
    // (camera open, GPU shaders, face models) can take several seconds before the first frame.
    // It only checks after the camera has been bound AND the screen resumed for 8 seconds, it
    // only reacts if frames had been arriving and then stopped for 6 seconds, and all it does
    // is rebind the camera. It never turns the live look off.
    var resumedAt by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumedAt = android.os.SystemClock.elapsedRealtime()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(owner, req) {
        val boundAt = android.os.SystemClock.elapsedRealtime()
        while (true) {
            kotlinx.coroutines.delay(1000)
            val now = android.os.SystemClock.elapsedRealtime()
            val st = vm.cameraState.value
            val settled = owner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) &&
                st.bound && st.naturalPreviewActive && now - boundAt > 8000 && now - resumedAt > 8000
            val lastDraw = vm.processor.lastDrawMs
            if (settled && lastDraw > 0L && now - lastDraw > 6000) {
                vm.onPreviewStalled()
                break // the rebind changes req, which restarts this watchdog with a fresh grace period
            }
        }
    }

    // Screen flash (front camera): full brightness, warm white.
    val activity = ctx as? Activity
    val flashOn = ui.screenFlash
    DisposableEffect(flashOn) {
        val w = activity?.window
        val old = w?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (flashOn && w != null) w.attributes = w.attributes.apply { screenBrightness = 1f }
        onDispose { if (flashOn && w != null) w.attributes = w.attributes.apply { screenBrightness = old } }
    }

    // Shutter blink
    val blink = remember { Animatable(0f) }
    LaunchedEffect(ui.shutterFlash) {
        if (ui.shutterFlash != 0L) {
            blink.snapTo(0.85f); blink.animateTo(0f, tween(180))
        }
    }

    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusKey by remember { mutableIntStateOf(0) }
    var evDragAccum by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(focusKey) {
        if (focusPoint != null && !ui.focusLocked) {
            kotlinx.coroutines.delay(3500)
            if (!vm.ui.value.focusLocked) focusPoint = null
        }
    }

    val aspect = req?.aspect ?: s.aspect
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- responsive viewfinder geometry ----------------------------------------------
        // Portrait-phone layout: viewfinder docked under the top bar with controls below.
        // When the screen is too short (or wide, e.g. a tablet or foldable) the viewfinder is
        // fitted to the available space and the controls float over it.
        val ratio = when (aspect) {
            AspectSetting.R16_9 -> 16f / 9f
            AspectSetting.FULL -> maxHeight / maxWidth
            else -> 4f / 3f
        }
        val topReserve = statusTop + TOP_BAR
        val bottomReserve = CONTROLS + navBottom
        val fullW = maxWidth
        val dockedH = fullW * ratio
        val docked = aspect != AspectSetting.FULL && topReserve + dockedH + bottomReserve <= maxHeight
        val vfW: Dp
        val vfH: Dp
        val vfTop: Dp
        if (aspect == AspectSetting.FULL) {
            vfW = fullW; vfH = maxHeight; vfTop = 0.dp
        } else if (docked) {
            vfW = fullW; vfH = dockedH; vfTop = topReserve
        } else {
            vfH = min(maxHeight, fullW * ratio)
            vfW = vfH / ratio
            vfTop = (maxHeight - vfH) / 2
        }
        val overlayControls = !docked
        // How much of the viewfinder is covered by the top bar / bottom controls, measured on
        // the real screen. Overlays inside the viewfinder (zoom bar, pills, histogram) sit just
        // clear of the controls on every phone shape, instead of at a fixed gap from the
        // viewfinder edge (which floated them to the middle on phones where it is centred).
        val vfBottom = vfTop + vfH
        val coveredBottom = (vfBottom - (maxHeight - bottomReserve)).coerceAtLeast(0.dp)
        val coveredTop = (topReserve - vfTop).coerceAtLeast(0.dp)
        val zoomBarPad = if (coveredBottom > 0.dp) coveredBottom + 10.dp else 14.dp
        val topOverlayPad = if (coveredTop > 0.dp) coveredTop + 8.dp else 12.dp
        val shutterSize = if (maxWidth < 360.dp) 70.dp else 78.dp

        Box(
            Modifier
                .offset(x = (fullW - vfW) / 2, y = vfTop)
                .size(vfW, vfH)
                .clip(if (docked) RoundedCornerShape(0.dp) else RoundedCornerShape(0.dp))
                .pointerInput(ui.mode, cs.evMin, cs.evMax) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.position
                        // Touches on the zoom buttons belong to them: never focus or swipe there.
                        val guard = 10.dp.toPx()
                        val bar = zoomBarRect
                        if (bar.width > 0f && start.x in (bar.left - guard)..(bar.right + guard) && start.y in (bar.top - guard)..(bar.bottom + guard)) {
                            return@awaitEachGesture
                        }
                        val t0 = down.uptimeMillis
                        var moved = false
                        var pinch = false
                        var vertical = false
                        var lastDist = 0f
                        var longPressed = false
                        var dx = 0f
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                val d = hypot(pressed[0].position.x - pressed[1].position.x, pressed[0].position.y - pressed[1].position.y)
                                if (pinch && lastDist > 0f) vm.pinchZoom(d / lastDist)
                                lastDist = d
                                pinch = true
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val c = pressed[0]
                            val delta = c.position - start
                            if (!moved && (abs(delta.x) > slop || abs(delta.y) > slop)) {
                                moved = true
                                vertical = abs(delta.y) > abs(delta.x)
                            }
                            if (moved && vertical && focusPoint != null && !pinch) {
                                evDragAccum -= c.positionChange().y
                                val stepPx = 14.dp.toPx()
                                while (abs(evDragAccum) >= stepPx) {
                                    val dir = if (evDragAccum > 0) 1 else -1
                                    vm.setExposureIndex(vm.cameraState.value.evIndex + dir)
                                    evDragAccum -= dir * stepPx
                                }
                                c.consume()
                            }
                            if (moved && !vertical) dx = delta.x
                            if (!moved && !longPressed && c.uptimeMillis - t0 > 550) {
                                longPressed = true
                                focusPoint = c.position
                                focusKey++
                                vm.tapFocus(previewView, c.position.x, c.position.y, lock = true)
                            }
                        }
                        when {
                            pinch || longPressed -> {}
                            moved && !vertical && abs(dx) > 70.dp.toPx() -> {
                                val i = MODES.indexOf(vm.ui.value.mode) + if (dx < 0) 1 else -1
                                if (i in MODES.indices) vm.setMode(MODES[i])
                            }
                            !moved -> {
                                if (vm.ui.value.panel != Panel.NONE) vm.closePanels()
                                else {
                                    focusPoint = start
                                    focusKey++
                                    evDragAccum = 0f
                                    vm.tapFocus(previewView, start.x, start.y, lock = false)
                                }
                            }
                        }
                    }
                },
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            transitionFrame?.let {
                Image(
                    it.asImageBitmap(), null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(transitionAlpha.value)
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(24.dp) else Modifier),
                )
            }
            if (s.grid) GridOverlay()
            if (aspect == AspectSetting.R1_1) SquareMask()
            focusPoint?.let { p ->
                val range = (cs.evMax - cs.evMin).coerceAtLeast(1)
                FocusIndicator(p.x, p.y, (cs.evIndex - cs.evMin).toFloat() / range, focusKey)
            }
            Column(
                Modifier.align(Alignment.TopCenter).padding(top = topOverlayPad),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (ui.focusLocked) Pill("AE/AF LOCK", color = Accent, textColor = Color(0xFF002E6A)) { vm.unlockFocus(); focusPoint = null }
                if (focusPoint != null && cs.evIndex != 0) Pill("${signed(cs.evIndex * cs.evStep)} EV")
                if (ui.burst != null) Pill("Capturing HDR…", color = Color.Black.copy(alpha = 0.6f), textColor = Accent)
                if (ui.mode == CameraMode.PORTRAIT && cs.faces == 0) Pill("Portrait works best with a person in frame")
            }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = zoomBarPad)
                    .onGloballyPositioned { zoomBarRect = it.boundsInParent() },
            ) {
                if (!ui.front && ui.mode != CameraMode.PRO) {
                    val presets = buildList {
                        if (cs.minZoom < 0.99f || cs.ultraWideId != null || cs.usingUltraWide) add(0.6f)
                        add(1f); add(2f)
                        if (cs.maxZoom >= 4f) add(4f)
                    }
                    ZoomChips(presets, cs.zoomRatio, cs.usingUltraWide, rot) { vm.zoomPreset(it) }
                }
            }
            if (ui.mode == CameraMode.PRO) ProHistogram(vm, Modifier.align(Alignment.TopEnd).padding(top = topOverlayPad - 2.dp, end = 10.dp))
            if (blink.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = blink.value)))
        }

        // ---- top bar -----------------------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (overlayControls || aspect == AspectSetting.FULL) Color.Black.copy(alpha = 0.3f) else Color.Transparent)
                .padding(top = statusTop)
                .height(TOP_BAR)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val flashIcon = when (s.flash) {
                FlashSetting.OFF -> Icons.Rounded.FlashOff
                FlashSetting.AUTO -> Icons.Rounded.FlashAuto
                FlashSetting.ON -> Icons.Rounded.FlashOn
            }
            CamIcon(flashIcon, "Flash", rot, active = s.flash != FlashSetting.OFF, enabled = cs.hasFlash || ui.front) { vm.cycleFlash() }
            if (ui.mode != CameraMode.PRO) {
                val hdrIcon = when (s.hdr) {
                    HdrMode.AUTO -> Icons.Rounded.HdrAuto
                    HdrMode.ON -> Icons.Rounded.HdrOn
                    HdrMode.OFF -> Icons.Rounded.HdrOff
                }
                CamIcon(hdrIcon, "HDR", rot, active = s.hdr == HdrMode.ON) { vm.cycleHdr() }
            }
            val timerIcon = when (ui.timer) {
                3 -> Icons.Rounded.Timer3
                10 -> Icons.Rounded.Timer10
                else -> Icons.Rounded.TimerOff
            }
            CamIcon(timerIcon, "Timer", rot, active = ui.timer > 0) { vm.cycleTimer() }
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { vm.cycleAspect() }, contentAlignment = Alignment.Center) {
                Text(s.aspect.label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(rot))
            }
            Spacer(Modifier.weight(1f))
            if (cs.faces > 0) Icon(
                Icons.Rounded.FaceRetouchingNatural, "Face exposure active", tint = Accent,
                modifier = Modifier.padding(end = 4.dp).size(20.dp).rotate(rot),
            )
            CamIcon(Icons.Rounded.Settings, "Settings", rot) { onOpenSettings() }
        }

        // ---- bottom controls ---------------------------------------------------------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(if (overlayControls || aspect == AspectSetting.FULL) Color.Black.copy(alpha = 0.35f) else Color.Transparent)
                .padding(bottom = navBottom + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(
                visible = ui.panel != Panel.NONE,
                enter = fadeIn() + slideInVertically { it / 3 }, exit = fadeOut() + slideOutVertically { it / 3 },
            ) {
                Box(Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    when (ui.panel) {
                        Panel.PROFILE -> ProfilePanel(s, vm)
                        Panel.BEAUTY -> BeautyPanel(s, vm)
                        Panel.BLUR -> BlurPanel(s, vm)
                        Panel.PRO -> ProPanel(ui, cs, vm)
                        Panel.NONE -> {}
                    }
                }
            }
            QuickRow(ui, s, rot, vm)
            Spacer(Modifier.height(4.dp))
            ModeSelector(MODES, ui.mode, enabled = !ui.capturing) { vm.setMode(it) }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.widthIn(max = 520.dp).fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                ThumbnailFrame(Modifier.rotate(rot).clickable {
                    if (ui.lastUri != null) onOpenLatest() else onOpenGallery()
                }) {
                    val t = ui.lastThumb
                    if (t != null) Image(t.asImageBitmap(), "Latest photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    else Icon(Icons.Rounded.PhotoLibrary, "Gallery", tint = Color.White)
                    if (busy > 0) CircularProgressIndicator(Modifier.size(26.dp), color = Accent, strokeWidth = 2.5.dp)
                }
                val progress = ui.burst?.let { (n, total) -> n.toFloat() / total }
                ShutterButton(busy >= 3 || ui.capturing, progress, shutterSize) { vm.onShutter() }
                Box(
                    Modifier.size(50.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.14f))
                        .clickable(enabled = !ui.capturing) { vm.flipCamera() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Cameraswitch, "Switch camera", tint = Color.White, modifier = Modifier.rotate(rot)) }
            }
        }

        if (ui.countdown > 0) {
            Text(
                "${ui.countdown}", color = Color.White, fontSize = 120.sp, fontWeight = FontWeight.Light,
                modifier = Modifier.align(Alignment.Center).rotate(rot),
            )
        }
        if (ui.screenFlash) Box(Modifier.fillMaxSize().background(Color(0xFFFFF3E2)))
        cs.error?.let { Pill("Camera error: $it", Modifier.align(Alignment.Center), color = RecordRed) }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = topReserve + 4.dp))
    }
}

/** Reads the histogram flow separately so only this small composable recomposes. */
@Composable
private fun ProHistogram(vm: CameraViewModel, modifier: Modifier) {
    val st by vm.stats.collectAsStateWithLifecycle()
    st?.let { Histogram(it.histogram, modifier.size(96.dp, 48.dp)) }
}

@Composable
private fun SquareMask() {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val bar = ((maxHeight - maxWidth) / 2).coerceAtLeast(0.dp)
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(bar).background(Color.Black.copy(alpha = 0.7f)))
            Spacer(Modifier.weight(1f))
            Box(Modifier.fillMaxWidth().height(bar).background(Color.Black.copy(alpha = 0.7f)))
        }
    }
}

@Composable
private fun QuickRow(ui: CameraUi, s: AppSettings, rot: Float, vm: CameraViewModel) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        QuickChip(Icons.Rounded.Palette, s.profile.label + if (s.profileIntensity < 0.99f) " ${(s.profileIntensity * 100).toInt()}" else "", ui.panel == Panel.PROFILE, rot) {
            vm.togglePanel(Panel.PROFILE)
        }
        if (ui.mode != CameraMode.PRO) {
            val b = s.beautySmoothing
            QuickChip(Icons.Rounded.FaceRetouchingNatural, if (b > 0f) "Skin ${(b * 100).toInt()}" else "Skin off", ui.panel == Panel.BEAUTY, rot) {
                vm.togglePanel(Panel.BEAUTY)
            }
        }
        if (ui.mode == CameraMode.PORTRAIT) {
            QuickChip(Icons.Rounded.BlurOn, "f/%.1f".format(blurToAperture(s.portraitBlur)), ui.panel == Panel.BLUR, rot) { vm.togglePanel(Panel.BLUR) }
        }
        if (ui.mode == CameraMode.PRO) {
            QuickChip(Icons.Rounded.Tune, "Manual", ui.panel == Panel.PRO, rot) { vm.togglePanel(Panel.PRO) }
        }
    }
}

fun blurToAperture(b: Float): Float = 16f * Math.pow(1.4 / 16.0, b.toDouble()).toFloat()

@Composable
private fun QuickChip(icon: ImageVector, text: String, active: Boolean, rot: Float, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) Accent.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) Accent else Color.White, modifier = Modifier.size(16.dp).rotate(rot))
        Spacer(Modifier.width(6.dp))
        Text(text, color = if (active) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ProfilePanel(s: AppSettings, vm: CameraViewModel) {
    PanelSurface {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ColorProfileId.entries) { p ->
                val sel = p == s.profile
                Column(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) Accent.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.06f))
                        .clickable { vm.setProfile(p) }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ProfileSwatch(p)
                    Spacer(Modifier.height(6.dp))
                    Text(p.label, color = if (sel) Accent else Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (s.profile != ColorProfileId.ORIGINAL) {
            Spacer(Modifier.height(4.dp))
            LabeledSlider("Intensity", s.profileIntensity, 0f..1f, "${(s.profileIntensity * 100).toInt()}%") { vm.setIntensity(it) }
        }
        Text(s.profile.description, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

private val swatchCache = HashMap<ColorProfileId, List<Color>>()

@Composable
private fun ProfileSwatch(p: ColorProfileId) {
    val colors = remember(p) {
        swatchCache.getOrPut(p) {
            val lut = LutBuilder.forProfile(p)
            listOf(0xE0AC8E, 0x627A9D, 0x576C43, 0xD67E2C).map { c ->
                if (lut == null) Color(0xFF000000.toInt() or c) else {
                    val o = FloatArray(3)
                    lut.sample(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f, o)
                    Color(o[0], o[1], o[2])
                }
            }
        }
    }
    Row(Modifier.clip(RoundedCornerShape(6.dp))) {
        colors.forEach { Box(Modifier.size(width = 10.dp, height = 22.dp).background(it)) }
    }
}

@Composable
private fun BeautyPanel(s: AppSettings, vm: CameraViewModel) {
    PanelSurface {
        LabeledSlider("Skin smoothing", s.beautySmoothing, 0f..1f, if (s.beautySmoothing == 0f) "Off" else "${(s.beautySmoothing * 100).toInt()}") {
            vm.updateSettings { st -> st.copy(beautySmoothing = it) }
        }
        LabeledSlider("Even skin tone", s.beautyEvenTone, 0f..1f, if (s.beautyEvenTone == 0f) "Off" else "${(s.beautyEvenTone * 100).toInt()}") {
            vm.updateSettings { st -> st.copy(beautyEvenTone = it) }
        }
        LabeledSlider("Face lighting", s.faceLight, 0f..1f, "${(s.faceLight * 100).toInt()}") {
            vm.updateSettings { st -> st.copy(faceLight = it) }
        }
        Text("Shown live on faces in the viewfinder. Texture, eyes and lips are always preserved.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
    }
}

@Composable
private fun BlurPanel(s: AppSettings, vm: CameraViewModel) {
    PanelSurface {
        LabeledSlider("Background blur", s.portraitBlur, 0f..1f, "f/%.1f".format(blurToAperture(s.portraitBlur))) {
            vm.updateSettings { st -> st.copy(portraitBlur = it) }
        }
    }
}

@Composable
fun PanelSurface(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xEB1A1B20))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, steps: Int = 0, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(label, color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(valueText, color = Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value, onValueChange = onChange, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Accent, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}
