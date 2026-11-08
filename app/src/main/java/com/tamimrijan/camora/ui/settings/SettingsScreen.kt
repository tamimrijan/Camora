package com.tamimrijan.camora.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import com.tamimrijan.camora.data.UpdateState
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
            SliderRow("Intensity", s.profileIntensity, { v -> "${(v * 100).toInt()}%" }) { v -> set { it.copy(profileIntensity = v) } }
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
        Group("Skin (all modes except Pro)") {
            SliderRow("Skin smoothing", s.beautySmoothing, { v -> if (v == 0f) "Off" else "${(v * 100).toInt()}" }) { v -> set { it.copy(beautySmoothing = v) } }
            Divider()
            SliderRow("Even skin tone", s.beautyEvenTone, { v -> if (v == 0f) "Off" else "${(v * 100).toInt()}" }) { v -> set { it.copy(beautyEvenTone = v) } }
            Divider()
            SliderRow("Face lighting", s.faceLight, { v -> "${(v * 100).toInt()}" }) { v -> set { it.copy(faceLight = v) } }
            Text(
                "Pores and texture are always kept; eyes, brows, lips and hair are never smoothed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
    item {
        Group("Portrait") {
            SliderRow("Background blur", s.portraitBlur, { v -> "f/%.1f".format(blurToAperture(v)) }) { v -> set { it.copy(portraitBlur = v) } }
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
                supportingContent = { Text("Photos and face data are processed on this phone and never uploaded. Camora only goes online when you tap Check for updates. Photos are saved to DCIM/Camora.") },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

private fun LazyListScope.aboutTab(ctx: Context) {
    item { AboutHero() }
    item {
        // Three short promises; one word each so the tiles never wrap.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AboutPillar(Icons.Rounded.Palette, "Natural", "True colour", Modifier.weight(1f))
            AboutPillar(Icons.Rounded.CameraAlt, "Live", "Real time look", Modifier.weight(1f))
            AboutPillar(Icons.Rounded.Lock, "Private", "On device", Modifier.weight(1f))
        }
    }
    item { UpdateCard() }
    item { AboutCreator(ctx) }
    item { AboutTech() }
    item {
        val app = ctx.applicationContext as CamoraApp
        val scope = rememberCoroutineScope()
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
            AboutLink(Icons.Rounded.AutoAwesome, "Welcome tour", "See everything Camora can do") {
                scope.launch { app.settings.update { it.copy(onboardingDone = false) } }
            }
            AboutLink(Icons.Rounded.Share, "Share Camora", "Send the project link to a friend") {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "Camora, a natural camera app for Android by ${CamoraApp.DEVELOPER}: ${CamoraApp.GITHUB}/Camora")
                }
                runCatching { ctx.startActivity(Intent.createChooser(send, "Share Camora").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
    item {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Made with care for natural photography",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "© 2026 ${CamoraApp.DEVELOPER}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

/** Big, calm header: logo on a soft glow, name, one line promise, version pill. */
@Composable
private fun AboutHero() {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.verticalGradient(listOf(cs.primaryContainer, cs.surfaceContainer))),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(124.dp).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(cs.primary.copy(alpha = 0.28f), Color.Transparent))),
                contentAlignment = Alignment.Center,
            ) {
                Image(painterResource(R.drawable.camora_logo), "Camora logo", Modifier.size(92.dp))
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Camora", color = cs.onSurface, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineLarge, letterSpacing = 0.5.sp,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Natural photos, developed on your phone.",
                style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Box(
                Modifier.clip(CircleShape).background(cs.surface.copy(alpha = 0.7f))
                    .border(1.dp, cs.outlineVariant, CircleShape)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    "Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelLarge,
                    color = cs.onSurface, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun AboutPillar(icon: ImageVector, title: String, caption: String, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.clip(RoundedCornerShape(22.dp)).background(cs.surfaceContainer).padding(vertical = 16.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(cs.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = cs.onSecondaryContainer)
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = cs.onSurface, maxLines = 1, softWrap = false)
        Text(
            caption, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One tap updates from GitHub: check, download with progress, then Android's installer. */
@Composable
private fun UpdateCard() {
    val ctx = LocalContext.current
    val up = (ctx.applicationContext as CamoraApp).updater
    val st by up.state.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme
    val current = up.currentVersion
    val (title, subtitle) = when (val s = st) {
        UpdateState.Idle -> "Version $current" to "See if a newer Camora is out"
        UpdateState.Checking -> "Checking GitHub" to "One moment"
        UpdateState.UpToDate -> "You're up to date" to "Version $current is the latest"
        is UpdateState.Available -> "Version ${s.info.version} is available" to "You have version $current"
        is UpdateState.Downloading -> "Downloading ${s.info.version}" to "${(s.progress * 100).toInt()}%"
        is UpdateState.Ready -> "Ready to install" to "Version ${s.info.version} is downloaded"
        is UpdateState.Failed -> (if (s.info == null) "Could not check" else "Download stopped") to s.message
    }
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("UPDATES", style = MaterialTheme.typography.labelMedium, color = cs.primary, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                    when (st) {
                        UpdateState.Checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp, color = cs.onPrimaryContainer)
                        else -> Icon(
                            when (st) {
                                UpdateState.UpToDate -> Icons.Rounded.CheckCircle
                                is UpdateState.Failed -> Icons.Rounded.ErrorOutline
                                else -> Icons.Rounded.SystemUpdate
                            },
                            null, Modifier.size(22.dp), tint = cs.onPrimaryContainer,
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            (st as? UpdateState.Downloading)?.let { d ->
                Spacer(Modifier.height(14.dp))
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().clip(CircleShape))
            }
            (st as? UpdateState.Available)?.info?.notes?.takeIf { it.isNotBlank() }?.let { notes ->
                Spacer(Modifier.height(12.dp))
                Text(
                    notes, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceContainerHigh).padding(12.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
            val s = st
            val (label, action, enabled) = when (s) {
                UpdateState.Checking -> Triple("Checking", {}, false)
                is UpdateState.Available -> Triple(
                    "Download and install" + if (s.info.sizeBytes > 0) " (%.1f MB)".format(s.info.sizeBytes / 1e6) else "",
                    { up.download(s.info) }, true,
                )
                is UpdateState.Downloading -> Triple("Downloading", {}, false)
                is UpdateState.Ready -> Triple("Install update", { up.install() }, true)
                is UpdateState.Failed -> if (s.info != null) Triple("Try again", { up.download(s.info) }, true) else Triple("Try again", { up.check() }, true)
                UpdateState.UpToDate -> Triple("Check again", { up.check() }, true)
                UpdateState.Idle -> Triple("Check for updates", { up.check() }, true)
            }
            Button(onClick = action, enabled = enabled, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text(label) }
            Spacer(Modifier.height(10.dp))
            Text(
                "Camora connects to GitHub only when you tap this button.",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Who made it, with the two links as clear rows rather than squeezed buttons. */
@Composable
private fun AboutCreator(ctx: Context) {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 6.dp)) {
            Text(
                "CREATED BY", style = MaterialTheme.typography.labelMedium, color = cs.primary,
                letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                // Initials on a gradient ring.
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
                        .padding(3.dp).clip(CircleShape).background(cs.surfaceContainerHighest),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("TR", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = cs.primary)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(CamoraApp.DEVELOPER, style = MaterialTheme.typography.titleLarge, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Designer and developer", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = cs.outlineVariant.copy(alpha = 0.6f))
            AboutLink(Icons.Rounded.Code, "GitHub", "Source code and releases") { openLink(ctx, CamoraApp.GITHUB) }
            AboutLink(Icons.Rounded.PhotoCamera, "Instagram", "Follow my work") { openLink(ctx, CamoraApp.INSTAGRAM) }
        }
    }
}

@Composable
private fun AboutLink(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ListItem(
        leadingContent = {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(20.dp), tint = cs.onPrimaryContainer)
            }
        },
        headlineContent = { Text(title, fontWeight = FontWeight.Medium) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(18.dp), tint = cs.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    )
}

/** Technology as pills: each name is one unbreakable chip, so nothing ever splits across lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AboutTech() {
    val cs = MaterialTheme.colorScheme
    Card(colors = CardDefaults.cardColors(containerColor = cs.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text("BUILT WITH", style = MaterialTheme.typography.labelMedium, color = cs.primary, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(14.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Kotlin", "Jetpack Compose", "Material 3", "CameraX", "Camera2", "OpenGL ES", "ML Kit", "Natural engine").forEach { name ->
                    Box(
                        Modifier.clip(CircleShape).border(1.dp, cs.outlineVariant, CircleShape)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    ) {
                        Text(name, style = MaterialTheme.typography.labelLarge, color = cs.onSurface, maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Faces and portraits are detected on the phone, and every photo is developed by Camora's own image engine.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
            )
        }
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
private fun SliderRow(title: String, value: Float, valueText: (Float) -> String, onChange: (Float) -> Unit) {
    // Follow the finger locally and save once on release: writing settings on every drag
    // event made these sliders stutter and jump back while the storage caught up.
    var drag by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(valueText(drag ?: value), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = drag ?: value,
            onValueChange = { drag = it },
            onValueChangeFinished = { drag?.let(onChange); drag = null },
        )
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
