package com.tamimrijan.camora.ui.editor

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tamimrijan.camora.CamoraApp
import com.tamimrijan.camora.data.MediaRepository
import com.tamimrijan.camora.engine.BeautySettings
import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.EngineReport
import com.tamimrijan.camora.engine.FaceInfo
import com.tamimrijan.camora.engine.HdrMode
import com.tamimrijan.camora.engine.LutBuilder
import com.tamimrijan.camora.engine.NaturalEngine
import com.tamimrijan.camora.engine.ProcessParams
import com.tamimrijan.camora.engine.Resample
import com.tamimrijan.camora.engine.UserAdjust
import com.tamimrijan.camora.processing.AndroidImaging
import com.tamimrijan.camora.processing.AndroidImaging.toBitmap
import com.tamimrijan.camora.processing.BitmapPixelBuffer
import com.tamimrijan.camora.ui.camera.LabeledSlider
import com.tamimrijan.camora.ui.common.ImageLoading
import com.tamimrijan.camora.ui.theme.Accent
import com.tamimrijan.camora.util.signedPercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Photos are already developed by the Natural engine when they are saved, so editing
 * starts neutral (no profile, no auto analysis) to avoid processing twice. Every tool
 * can be applied on top; the result is saved as a new copy.
 */
data class EditState(
    val adjust: UserAdjust = UserAdjust.NONE,
    val profile: ColorProfileId = ColorProfileId.ORIGINAL,
    val intensity: Float = 1f,
    val hdr: HdrMode = HdrMode.OFF,
    val beauty: BeautySettings = BeautySettings(faceLight = 0f),
    val autoEnhance: Boolean = false,
    val rotation: Int = 0,
    val crop: Float? = null, // aspect w/h or null
) {
    val isNeutral get() = this == EditState()
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val nc = app as CamoraApp
    private val _edit = MutableStateFlow(EditState())
    val edit: StateFlow<EditState> = _edit
    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview
    private val _source = MutableStateFlow<Bitmap?>(null)
    val source: StateFlow<Bitmap?> = _source
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving
    private val _saved = MutableStateFlow<Uri?>(null)
    val saved: StateFlow<Uri?> = _saved
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var sourceUri: Uri? = null
    private var base: Bitmap? = null
    private var faces: List<FaceInfo> = emptyList()
    private var facesFor: Pair<Int, Float?>? = null
    private var job: Job? = null

    fun load(uri: Uri) {
        if (sourceUri == uri) return
        sourceUri = uri
        viewModelScope.launch {
            _edit.value = EditState()
            base = ImageLoading.decode(nc, uri, 1600, mutable = true)
            _source.value = base
            _preview.value = base
        }
    }

    fun update(t: (EditState) -> EditState) {
        _edit.update(t)
        render()
    }

    fun reset() = update { EditState() }

    private fun transformed(src: Bitmap, e: EditState): Bitmap {
        val m = Matrix().apply { setRotate(e.rotation.toFloat()) }
        val r = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        var w = Math.round(r.width())
        var h = Math.round(r.height())
        val fullW = w
        val fullH = h
        e.crop?.let { a ->
            val t = if (w >= h) maxOf(a, 1f / a) else minOf(a, 1f / a)
            if (w.toFloat() / h > t) w = Math.round(h * t) else h = Math.round(w / t)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        m.postTranslate(-r.left - (fullW - w) / 2f, -r.top - (fullH - h) / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun params(e: EditState) = ProcessParams(
        profile = e.profile, profileIntensity = e.intensity, hdrMode = e.hdr, beauty = e.beauty,
        adjust = e.adjust, autoEnhance = e.autoEnhance, faceExposure = e.autoEnhance,
        detailLevel = if (e.autoEnhance) 1f else 0f, noiseReductionLevel = if (e.autoEnhance) 1f else 0f,
    )

    private suspend fun run(src: Bitmap, e: EditState, preview: Boolean): Pair<Bitmap, EngineReport> = withContext(Dispatchers.Default) {
        val input = transformed(src, e)
        val f = Resample.factorFor(input.width, input.height, NaturalEngine.ANALYSIS_SIDE)
        val buf = BitmapPixelBuffer(input)
        val analysis = Resample.boxDownscale(buf, f)
        val needFaces = e.autoEnhance || e.beauty.active || e.beauty.faceLight > 0f
        val key = e.rotation to e.crop
        val fs = when {
            !needFaces -> emptyList()
            preview && facesFor == key -> faces
            else -> {
                val ab = analysis.toBitmap()
                AndroidImaging.detectFaces(ab, f.toFloat()).also {
                    ab.recycle()
                    if (preview) {
                        faces = it; facesFor = key
                    }
                }
            }
        }
        val out = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
        val p = params(e)
        val rep = NaturalEngine().process(NaturalEngine.Input(buf, analysis, f, fs), BitmapPixelBuffer(out), p, LutBuilder.forProfile(p.profile, p.adjust))
        input.recycle()
        out to rep
    }

    private fun render() {
        val src = base ?: return
        job?.cancel()
        job = viewModelScope.launch {
            delay(60) // debounce slider drags
            _busy.value = true
            val e = _edit.value
            _preview.value = if (e.isNeutral) src else run(src, e, preview = true).first
            _busy.value = false
        }
    }

    fun save() {
        val src = sourceUri ?: return
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            try {
                val full = ImageLoading.decode(nc, src, 12000, mutable = true) ?: error("cannot read image")
                val (out, _) = run(full, _edit.value, preview = false)
                full.recycle()
                val file = File(nc.cacheDir, "edit${System.currentTimeMillis()}.jpg")
                withContext(Dispatchers.IO) {
                    AndroidImaging.writeJpeg(out, 95, file)
                    runCatching {
                        val bytes = nc.contentResolver.openInputStream(src)?.use { s -> s.readBytes() }
                        if (bytes != null) AndroidImaging.copyExif(bytes, file, out.width, out.height, "Edited in Camora")
                    }
                }
                out.recycle()
                _saved.value = nc.media.saveFile(file, MediaRepository.newName(" Edit") + ".jpg", "image/jpeg")
                file.delete()
            } catch (t: Throwable) {
                _error.value = "Save failed: ${t.message}"
            } finally {
                _saving.value = false
            }
        }
    }
}

private enum class EditTab(val label: String, val icon: ImageVector) {
    ADJUST("Adjust", Icons.Rounded.Tune), LOOK("Look", Icons.Rounded.Palette), FACE("Face", Icons.Rounded.Face), CROP("Crop", Icons.Rounded.Crop)
}

@Composable
fun EditorScreen(vm: EditorViewModel, uri: Uri, onBack: () -> Unit) {
    LaunchedEffect(uri) { vm.load(uri) }
    val e by vm.edit.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var showSource by remember { mutableStateOf(false) }
    LaunchedEffect(saved) { if (saved != null) onBack() }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Cancel", tint = Color.White) }
            Text("Edit", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (busy && !saving) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(20.dp), color = Accent, strokeWidth = 2.dp)
            if (!e.isNeutral) OutlinedButton(onClick = { vm.reset() }, modifier = Modifier.padding(end = 8.dp)) { Text("Reset", color = Color.White) }
            Button(onClick = { vm.save() }, enabled = !saving && !e.isNeutral) {
                if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color(0xFF002E6A))
                else {
                    Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text("Save copy")
                }
            }
            Spacer(Modifier.size(8.dp))
        }
        error?.let { Text(it, color = Color(0xFFFFB4AB), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp)) }
        Box(
            Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) {
                detectTapGestures(onPress = { showSource = true; tryAwaitRelease(); showSource = false })
            },
            contentAlignment = Alignment.Center,
        ) {
            val img = if (showSource) source else preview ?: source
            img?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            Text(
                if (showSource) "Before" else "Hold to compare", color = Color.White, fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        BoxWithConstraints(Modifier.fillMaxWidth().background(Color(0xFF111318))) {
            val panelH = if (maxWidth > 600.dp) 200.dp else 230.dp
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier.widthIn(max = 640.dp).fillMaxWidth().height(panelH).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    when (EditTab.entries[tab]) {
                        EditTab.ADJUST -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Auto enhance", color = Color.White, fontWeight = FontWeight.Medium)
                                    Text("Run Camora's scene analysis again", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                                }
                                Switch(e.autoEnhance, { v -> vm.update { it.copy(autoEnhance = v) } })
                            }
                            val a = e.adjust
                            AdjSlider("Exposure", a.exposure / 2f) { v -> vm.update { it.copy(adjust = it.adjust.copy(exposure = v * 2f)) } }
                            AdjSlider("Contrast", a.contrast) { v -> vm.update { it.copy(adjust = it.adjust.copy(contrast = v)) } }
                            AdjSlider("Highlights", a.highlights) { v -> vm.update { it.copy(adjust = it.adjust.copy(highlights = v)) } }
                            AdjSlider("Shadows", a.shadows) { v -> vm.update { it.copy(adjust = it.adjust.copy(shadows = v)) } }
                            AdjSlider("Saturation", a.saturation) { v -> vm.update { it.copy(adjust = it.adjust.copy(saturation = v)) } }
                            AdjSlider("Warmth", a.warmth) { v -> vm.update { it.copy(adjust = it.adjust.copy(warmth = v)) } }
                            AdjSlider("Tint", a.tint) { v -> vm.update { it.copy(adjust = it.adjust.copy(tint = v)) } }
                            AdjSlider("Sharpness", a.sharpness) { v -> vm.update { it.copy(adjust = it.adjust.copy(sharpness = v)) } }
                        }
                        EditTab.LOOK -> {
                            ChipRow(ColorProfileId.entries.map { it.label }, ColorProfileId.entries.indexOf(e.profile)) { i ->
                                vm.update { it.copy(profile = ColorProfileId.entries[i]) }
                            }
                            if (e.profile != ColorProfileId.ORIGINAL) {
                                LabeledSlider("Intensity", e.intensity, 0f..1f, "${(e.intensity * 100).toInt()}%") { v -> vm.update { it.copy(intensity = v) } }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("HDR tone mapping", color = Color.White, fontWeight = FontWeight.Medium)
                            ChipRow(listOf("Off", "Auto", "Strong"), listOf(HdrMode.OFF, HdrMode.AUTO, HdrMode.ON).indexOf(e.hdr)) { i ->
                                vm.update { it.copy(hdr = listOf(HdrMode.OFF, HdrMode.AUTO, HdrMode.ON)[i], autoEnhance = it.autoEnhance || i > 0) }
                            }
                        }
                        EditTab.FACE -> {
                            val b = e.beauty
                            LabeledSlider("Skin smoothing", b.smoothing, 0f..1f, "${(b.smoothing * 100).toInt()}") { v -> vm.update { it.copy(beauty = b.copy(smoothing = v)) } }
                            LabeledSlider("Even skin tone", b.evenTone, 0f..1f, "${(b.evenTone * 100).toInt()}") { v -> vm.update { it.copy(beauty = b.copy(evenTone = v)) } }
                            LabeledSlider("Face lighting", b.faceLight, 0f..1f, "${(b.faceLight * 100).toInt()}") { v ->
                                vm.update { it.copy(beauty = b.copy(faceLight = v), autoEnhance = it.autoEnhance || v > 0f) }
                            }
                            Text("Only skin is touched; eyes, brows, lips and hair stay sharp.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        EditTab.CROP -> {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedButton(onClick = { vm.update { it.copy(rotation = (it.rotation + 90) % 360) } }) {
                                    Icon(Icons.Rounded.Rotate90DegreesCw, null, tint = Color.White); Spacer(Modifier.size(6.dp)); Text("Rotate", color = Color.White)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            val crops = listOf<Pair<String, Float?>>("Original" to null, "1:1" to 1f, "4:5" to 0.8f, "4:3" to 4f / 3f, "16:9" to 16f / 9f)
                            ChipRow(crops.map { it.first }, crops.indexOfFirst { it.second == e.crop }) { i -> vm.update { it.copy(crop = crops[i].second) } }
                            Text("Edits are saved as a new copy; the original photo is never changed.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                }
                NavigationBar(containerColor = Color(0xFF1A1B20), modifier = Modifier.navigationBarsPadding().height(72.dp)) {
                    EditTab.entries.forEachIndexed { i, t ->
                        NavigationBarItem(
                            selected = tab == i, onClick = { tab = i },
                            icon = { Icon(t.icon, t.label) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF002E6A), indicatorColor = Accent,
                                unselectedIconColor = Color.White.copy(alpha = 0.7f), unselectedTextColor = Color.White.copy(alpha = 0.7f),
                                selectedTextColor = Accent,
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            FilterChip(
                selected = i == selected, onClick = { onSelect(i) }, label = { Text(l) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = Color.White, selectedContainerColor = Accent, selectedLabelColor = Color(0xFF002E6A),
                ),
            )
        }
    }
}

@Composable
private fun AdjSlider(label: String, v: Float, onChange: (Float) -> Unit) {
    LabeledSlider(label, v, -1f..1f, signedPercent(v), onChange = onChange)
}
