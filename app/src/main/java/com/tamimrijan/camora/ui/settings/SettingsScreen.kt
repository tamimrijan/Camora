package com.tamimrijan.camora.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tamimrijan.camora.BuildConfig
import com.tamimrijan.camora.R
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.tamimrijan.camora.CamoraApp
import com.tamimrijan.camora.camera.AspectSetting
import com.tamimrijan.camora.camera.CameraCapabilities
import com.tamimrijan.camora.data.AppSettings
import com.tamimrijan.camora.data.Level
import com.tamimrijan.camora.data.PhotoFormat
import com.tamimrijan.camora.data.ThemeSetting
import com.tamimrijan.camora.engine.ColorProfileId
import com.tamimrijan.camora.engine.HdrMode
import com.tamimrijan.camora.ui.camera.blurToAperture
import com.tamimrijan.camora.util.awaitResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class SettingsTab(val label: String, val icon: ImageVector) {
    CAMERA("Camera", Icons.Rounded.CameraAlt),
    LOOK("Look", Icons.Rounded.Palette),
    PORTRAIT("Portrait", Icons.Rounded.Face),
    APP("App", Icons.Rounded.Smartphone),
    ABOUT("About", Icons.Rounded.Info),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenReport: () -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext as CamoraApp
    val s by app.settings.settings.collectAsStateWithLifecycle(AppSettings())
    val scope = rememberCoroutineScope()
    val set: ((AppSettings) -> AppSettings) -> Unit = { t -> scope.launch { app.settings.update(t) } }
    val pager = rememberPagerState { SettingsTab.entries.size }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                )
                PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                    SettingsTab.entries.forEachIndexed { i, t ->
                        Tab(
                            selected = pager.currentPage == i,
                            onClick = { scope.launch { pager.animateScrollToPage(i) } },
                            icon = { Icon(t.icon, null, Modifier.size(20.dp)) },
                            text = { Text(t.label, maxLines = 1, fontSize = 12.sp) },
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
    ) { pad ->
        HorizontalPager(pager, Modifier.fillMaxSize().padding(pad), beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (SettingsTab.entries[page]) {
                        SettingsTab.CAMERA -> cameraTab(s, set, ctx)
                        SettingsTab.LOOK -> lookTab(s, set)
                        SettingsTab.PORTRAIT -> portraitTab(s, set)
                        SettingsTab.APP -> appTab(s, set, onOpenReport)
                        SettingsTab.ABOUT -> aboutTab(ctx)
                    }
                }
            }
        }
    }
}

// ---- tabs ------------------------------------------------------------------------------------

private fun LazyListScope.cameraTab(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, ctx: Context) {
    item {
        Group("Photo") {
            SegmentRow("Aspect ratio", AspectSetting.entries.map { it.label }, s.aspect.ordinal) { i -> set { it.copy(aspect = AspectSetting.entries[i]) } }
            Divider()
            val rear = CameraCapabilities.lenses(ctx).firstOrNull { it.isBack && it.role == "Main" }
            val target = if (s.aspect.streamIs16x9) 16f / 9f else 4f / 3f
            val sizes = (rear?.jpegSizes.orEmpty() + rear?.highResJpegSizes.orEmpty())
                .filter { kotlin.math.abs(it.width.toFloat() / it.height - target) < 0.02f }
                .distinct().sortedByDescending { it.width * it.height }.take(5)
            val labels = listOf("Best standard (recommended)") + sizes.map { "${it.width}×${it.height} · %.1f MP".format(it.width * it.height / 1e6f) }
            val sel = (sizes.indexOfFirst { "${it.width}x${it.height}" == s.photoResolution } + 1).coerceIn(0, labels.size - 1)
            Choice("Rear resolution", labels[sel], labels, sel) { i ->
                set { it.copy(photoResolution = if (i == 0) "" else sizes[i - 1].let { r -> "${r.width}x${r.height}" }) }
            }
            Divider()
            Toggle("High resolution sensor modes", "Use the 50 MP mode if your camera exposes it. Slower; best in bright light.", s.highRes) { v -> set { it.copy(highRes = v) } }
        }
    }
    item {
        Group("File") {
            SegmentRow("Format", PhotoFormat.entries.map { it.name }, s.format.ordinal) { i -> set { it.copy(format = PhotoFormat.entries[i]) } }
            Divider()
            val qs = listOf(85, 90, 95, 100)
            SegmentRow("Quality", qs.map { "$it" }, qs.indexOf(s.jpegQuality).coerceAtLeast(0)) { i -> set { it.copy(jpegQuality = qs[i]) } }
        }
    }
    item {
        Group("Shooting") {
            Toggle("Grid lines", "Rule of thirds guide on the viewfinder", s.grid) { v -> set { it.copy(grid = v) } }
            Divider()
            Toggle("Shutter sound", "Some regions always play it", s.shutterSound) { v -> set { it.copy(shutterSound = v) } }
            Divider()
            Toggle("Volume keys take photos", null, s.volumeKeyShutter) { v -> set { it.copy(volumeKeyShutter = v) } }
            Divider()
            Toggle("Mirror front camera", "Save selfies as seen in the preview", s.mirrorFront) { v -> set { it.copy(mirrorFront = v) } }
        }
    }
}

private fun LazyListScope.lookTab(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    item {
        Group("Colour profile") {
            Choice("Default profile", s.profile.label, ColorProfileId.entries.map { "${it.label}: ${it.description}" }, s.profile.ordinal) { i ->
                set { it.copy(profile = ColorProfileId.entries[i]) }
            }
            Divider()
            SliderRow("Intensity", s.profileIntensity, "${(s.profileIntensity * 100).toInt()}%") { v -> set { it.copy(profileIntensity = v) } }
            Divider()
            Toggle("Live preview of the look", "Shows colour and HDR in the viewfinder exactly as they will be saved", s.naturalPreview) { v -> set { it.copy(naturalPreview = v) } }
        }
    }
    item {
        Group("Processing") {
            val hdrs = listOf(HdrMode.OFF, HdrMode.AUTO, HdrMode.ON)
            SegmentRow("HDR", listOf("Off", "Auto", "On"), hdrs.indexOf(s.hdr)) { i -> set { it.copy(hdr = hdrs[i]) } }
            Text(
                when (s.hdr) {
                    HdrMode.OFF -> "Camera tone only."
                    HdrMode.AUTO -> "Recovers shadows and highlights when the scene needs it (single frame)."
                    HdrMode.ON -> "Three frames are merged for the widest dynamic range and lowest noise."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
            Divider()
            SegmentRow("Detail", Level.entries.map { it.label }, s.detail.ordinal) { i -> set { it.copy(detail = Level.entries[i]) } }
            Divider()
            SegmentRow("Noise reduction", Level.entries.map { it.label }, s.noiseReduction.ordinal) { i -> set { it.copy(noiseReduction = Level.entries[i]) } }
            Divider()
            Toggle("Face priority exposure", "Balances exposure for faces using the lighting, never the skin tone", s.faceExposure) { v -> set { it.copy(faceExposure = v) } }
        }
    }
}

private fun LazyListScope.portraitTab(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit) {
    item {
        Group("Skin (Selfie & Portrait)") {
            SliderRow("Skin smoothing", s.beautySmoothing, if (s.beautySmoothing == 0f) "Off" else "${(s.beautySmoothing * 100).toInt()}") { v -> set { it.copy(beautySmoothing = v) } }
            Divider()
            SliderRow("Even skin tone", s.beautyEvenTone, if (s.beautyEvenTone == 0f) "Off" else "${(s.beautyEvenTone * 100).toInt()}") { v -> set { it.copy(beautyEvenTone = v) } }
            Divider()
            SliderRow("Face lighting", s.faceLight, "${(s.faceLight * 100).toInt()}") { v -> set { it.copy(faceLight = v) } }
            Text(
                "Pores and texture are always kept; eyes, brows, lips and hair are never smoothed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
    item {
        Group("Portrait") {
            SliderRow("Background blur", s.portraitBlur, "f/%.1f".format(blurToAperture(s.portraitBlur))) { v -> set { it.copy(portraitBlur = v) } }
        }
    }
}

private fun LazyListScope.appTab(s: AppSettings, set: ((AppSettings) -> AppSettings) -> Unit, onOpenReport: () -> Unit) {
    item {
        Group("Appearance") {
            SegmentRow("Theme", listOf("System", "Light", "Dark"), s.theme.ordinal) { i -> set { it.copy(theme = ThemeSetting.entries[i]) } }
            Divider()
            Toggle("Dynamic colour", "Use your wallpaper colours on app screens (Android 12+)", s.dynamicColor) { v -> set { it.copy(dynamicColor = v) } }
        }
    }
    item {
        Group("Device") {
            Nav(Icons.Rounded.PhotoCamera, "Device camera report", "What this phone's cameras expose to apps", onOpenReport)
        }
    }
    item {
        Group("Privacy") {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text("Everything stays on your phone") },
                supportingContent = { Text("Camora has no internet permission. Photos and face data never leave the device. Photos are saved to DCIM/Camora.") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

private fun LazyListScope.aboutTab(ctx: Context) {
    item {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.camora_logo), "Camora logo", Modifier.size(84.dp))
                Spacer(Modifier.height(14.dp))
                Text("Camora", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                Spacer(Modifier.height(10.dp))
                Text(
                    "Natural, true to life photos with soft highlights, balanced exposure and real skin texture, developed on your phone by Camora's own image engine.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
    item {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("MADE BY", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("TR", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(CamoraApp.DEVELOPER, style = MaterialTheme.typography.titleLarge)
                        Text("Designer & developer of Camora", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    FilledTonalButton(onClick = { openLink(ctx, CamoraApp.GITHUB) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("GitHub")
                    }
                    FilledTonalButton(onClick = { openLink(ctx, CamoraApp.INSTAGRAM) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Instagram")
                    }
                }
            }
        }
    }
    item {
        Group("Built with") {
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary) },
                headlineContent = { Text("Kotlin · Jetpack Compose · Material 3") },
                supportingContent = { Text("CameraX with Camera2 controls, an OpenGL ES live preview, ML Kit face detection that runs on the phone, and the Camora Natural image engine.") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
    item {
        Text(
            "© 2026 ${CamoraApp.DEVELOPER}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun openLink(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

// ---- building blocks -------------------------------------------------------------------------

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun Toggle(title: String, summary: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked, onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun Nav(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Text(
        text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
    )
}

@Composable
private fun SliderRow(title: String, value: Float, valueText: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value, onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SegmentRow(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = i == selected, onClick = { onSelect(i) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    label = { Text(o, maxLines = 1, fontSize = 13.sp) },
                    icon = {},
                )
            }
        }
    }
}

@Composable
private fun Choice(title: String, current: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) }, supportingContent = { Text(current) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEachIndexed { i, o ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).selectable(selected = i == selected) { onSelect(i); open = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(i == selected, { onSelect(i); open = false })
                            Text(o, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

// ---- device report -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CapabilitiesScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val report by produceState("Reading camera characteristics…") {
        value = withContext(Dispatchers.Default) { buildReport(ctx) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device camera report") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Camera report", report))
                    }) { Icon(Icons.Rounded.ContentCopy, "Copy") }
                    IconButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, report) }, "Share report"))
                    }) { Icon(Icons.Rounded.Share, "Share") }
                },
            )
        },
    ) { pad ->
        SelectionContainer(Modifier.padding(pad)) {
            Text(
                report, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp),
            )
        }
    }
}

private suspend fun buildReport(ctx: Context): String {
    val extras = LinkedHashMap<String, String>()
    runCatching {
        val provider = androidx.camera.lifecycle.ProcessCameraProvider.getInstance(ctx).awaitResult()
        val ext = androidx.camera.extensions.ExtensionsManager.getInstanceAsync(ctx, provider).awaitResult()
        val modes = mapOf(
            "HDR" to androidx.camera.extensions.ExtensionMode.HDR,
            "Night" to androidx.camera.extensions.ExtensionMode.NIGHT,
            "Bokeh" to androidx.camera.extensions.ExtensionMode.BOKEH,
            "Face retouch" to androidx.camera.extensions.ExtensionMode.FACE_RETOUCH,
            "Auto" to androidx.camera.extensions.ExtensionMode.AUTO,
        )
        for ((label, sel) in listOf("Rear" to androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA, "Front" to androidx.camera.core.CameraSelector.DEFAULT_FRONT_CAMERA)) {
            val avail = modes.filter { (_, m) -> runCatching { ext.isExtensionAvailable(sel, m) }.getOrDefault(false) }.keys
            extras["CameraX OEM extensions ($label)"] = avail.joinToString().ifEmpty { "none" }
        }
        extras["Cameras visible to CameraX"] = provider.availableCameraInfos.joinToString {
            @Suppress("UnsafeOptInUsageError")
            androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId
        }
    }.onFailure { extras["CameraX info"] = "unavailable: ${it.message}" }
    val rt = Runtime.getRuntime()
    extras["App heap limit"] = "${rt.maxMemory() / (1024 * 1024)} MB"
    extras["CPU cores"] = "${rt.availableProcessors()}"
    return CameraCapabilities.report(ctx, extras)
}

@Suppress("unused")
@Composable
private fun OutlinedLink(text: String, onClick: () -> Unit) = OutlinedButton(onClick = onClick) { Text(text) }
