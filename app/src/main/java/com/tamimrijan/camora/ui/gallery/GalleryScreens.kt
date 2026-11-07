package com.tamimrijan.camora.ui.gallery

import android.app.Application
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clipToBounds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlin.math.abs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tamimrijan.camora.CamoraApp
import com.tamimrijan.camora.data.MediaItem
import com.tamimrijan.camora.ui.common.ImageLoading
import com.tamimrijan.camora.ui.common.rememberDecoded
import com.tamimrijan.camora.ui.common.rememberThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class GalleryViewModel(app: Application) : AndroidViewModel(app) {
    private val nc = app as CamoraApp
    private val _items = MutableStateFlow<List<MediaItem>>(emptyList())
    val items: StateFlow<List<MediaItem>> = _items
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    init {
        viewModelScope.launch {
            nc.media.version.collect {
                _items.value = runCatching { nc.media.query() }.getOrDefault(emptyList())
                _loaded.value = true
            }
        }
    }

    suspend fun delete(items: List<MediaItem>): IntentSender? {
        ImageLoading.invalidate()
        return nc.media.delete(items)
    }

    fun refresh() = nc.media.notifyChanged()
}

private fun share(ctx: android.content.Context, items: List<MediaItem>) {
    if (items.isEmpty()) return
    val intent = if (items.size == 1) Intent(Intent.ACTION_SEND).apply {
        type = items[0].mime
        putExtra(Intent.EXTRA_STREAM, items[0].uri)
    } else Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(items.map { it.uri }))
    }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(intent, "Share"))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(vm: GalleryViewModel, onBack: () -> Unit, onOpen: (Int) -> Unit, onEdit: (Uri) -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val selected = remember { mutableStateListOf<Long>() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.refresh() }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    // System back leaves selection mode first instead of closing the gallery.
    BackHandler(enabled = selected.isNotEmpty()) { selected.clear() }
    // Drop selections of photos that no longer exist (deleted here or in another app).
    LaunchedEffect(items) { val ids = items.mapTo(HashSet()) { it.id }; selected.retainAll { it in ids } }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (selected.isEmpty()) "Gallery" else "${selected.size} selected")
                        if (selected.isEmpty() && items.isNotEmpty()) Text(
                            "${items.size} photo${if (items.size == 1) "" else "s"}", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { if (selected.isNotEmpty()) selected.clear() else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                actions = {
                    if (selected.size == 1) IconButton(onClick = {
                        items.firstOrNull { it.id == selected[0] }?.let { selected.clear(); onEdit(it.uri) }
                    }) { Icon(Icons.Rounded.Edit, "Edit") }
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { share(ctx, items.filter { it.id in selected }) }) { Icon(Icons.Rounded.Share, "Share") }
                        IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.Delete, "Delete") }
                    }
                },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
    ) { pad ->
        if (loaded && items.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.PhotoCamera, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Text("No photos yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text("Photos you take with Camora appear here", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(4.dp),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }) { idx, item ->
                val sel = item.id in selected
                val thumb by rememberThumbnail(item.uri, 320)
                Box(
                    Modifier
                        .padding(2.dp)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                        .combinedClickable(
                            onClick = {
                                if (selected.isNotEmpty()) { if (sel) selected.remove(item.id) else selected.add(item.id) } else onOpen(idx)
                            },
                            onLongClick = { if (!sel) selected.add(item.id) },
                        ),
                ) {
                    thumb?.let { Image(it.asImageBitmap(), item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Delete ${selected.size} photo${if (selected.size > 1) "s" else ""}?") },
            text = { Text("They will be permanently removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    val toDelete = items.filter { it.id in selected }
                    selected.clear()
                    scope.launch { vm.delete(toDelete)?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/** Full-screen photo viewer with a clear Edit action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(vm: GalleryViewModel, startIndex: Int, onBack: () -> Unit, onEdit: (Uri) -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    // Leave the viewer once the last photo is gone (only after the delete really happened,
    // so cancelling the system delete dialog keeps you here).
    LaunchedEffect(loaded, items.isEmpty()) { if (loaded && items.isEmpty()) onBack() }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (loaded) Text("No photos", color = Color.White)
        }
        return
    }
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, items.size - 1)) { items.size }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<MediaItem?>(null) }
    var confirm by remember { mutableStateOf<MediaItem?>(null) }
    var chromeVisible by remember { mutableStateOf(true) }
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { vm.refresh() }

    // Stay on the same photo when the list changes (a new edit or photo is added in front).
    var currentId by remember { mutableStateOf(items.getOrNull(pager.currentPage)?.id) }
    LaunchedEffect(pager.currentPage) { currentId = items.getOrNull(pager.currentPage)?.id }
    LaunchedEffect(items) {
        val idx = items.indexOfFirst { it.id == currentId }
        if (idx >= 0 && idx != pager.currentPage) pager.scrollToPage(idx)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { items.getOrNull(it)?.id ?: it.toLong() }) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            ZoomablePhoto(
                uri = item.uri,
                active = page == pager.currentPage,
                onTap = { chromeVisible = !chromeVisible },
                onSwipeUp = { info = item },
                onSwipeDown = onBack,
            )
        }
        val current = items.getOrNull(pager.currentPage)
        if (chromeVisible && current != null) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.5f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(current.dateTaken)), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("${pager.currentPage + 1} of ${items.size}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                }
                IconButton(onClick = { info = current }) { Icon(Icons.Outlined.Info, "Details", tint = Color.White) }
            }
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.5f))
                    .navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Share | Edit | Delete in equal halves: Edit sits exactly in the centre and the
                // two side buttons mirror each other. Details lives in the top bar.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ViewerAction(Icons.Outlined.Share, "Share") { share(ctx, listOf(current)) }
                }
                FilledTonalButton(onClick = { onEdit(current.uri) }, contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
                    Icon(Icons.Rounded.Edit, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit")
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ViewerAction(Icons.Outlined.Delete, "Delete") { confirm = current }
                }
            }
        }
    }
    info?.let { item -> ModalBottomSheet(onDismissRequest = { info = null }) { InfoSheet(item) } }
    confirm?.let { item ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Outlined.Delete, null) },
            title = { Text("Delete this photo?") },
            text = { Text("It will be permanently removed from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch { vm.delete(listOf(item))?.let { deleteLauncher.launch(IntentSenderRequest.Builder(it).build()) } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}
@Composable
private fun ViewerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, label, tint = Color.White)
        Spacer(Modifier.height(2.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

// The producers do assign value; this lint check misreads suspend calls on the right side.
@android.annotation.SuppressLint("ProduceStateDoesNotAssignValue")
@Composable
private fun InfoSheet(item: MediaItem) {
    val ctx = LocalContext.current
    val rows by produceState<List<Pair<String, String>>>(emptyList(), item.uri) {
        value = withContext(Dispatchers.IO) {
            val base = listOf(
                "File" to item.name,
                "Resolution" to "${item.width} × ${item.height} (%.1f MP)".format(item.width * item.height / 1e6f),
                "Size" to "%.1f MB".format(item.sizeBytes / 1e6),
            )
            base + runCatching {
                ctx.contentResolver.openInputStream(item.uri)!!.use { s ->
                    val e = ExifInterface(s)
                    listOfNotNull(
                        e.getAttribute(ExifInterface.TAG_MODEL)?.let { "Camera" to it },
                        e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO" to it },
                        e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let { "Shutter" to if (it >= 0.5) "%.1fs".format(it) else "1/${(1 / it).toInt()}" },
                        e.getAttribute(ExifInterface.TAG_F_NUMBER)?.let { "Aperture" to "f/$it" },
                        e.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.let { "Focal length" to "$it mm (35 mm eq.)" },
                        e.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)?.let { "Processing" to processingText(it) },
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
    Column(Modifier.fillMaxWidth().widthIn(max = 640.dp).padding(horizontal = 24.dp, vertical = 8.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Details", style = MaterialTheme.typography.titleLarge)
        rows.forEach { (k, v) ->
            Row {
                Text(k, Modifier.width(110.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(v, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * One photo page. A single gesture handler does everything, so nothing fights over touches:
 * tap shows/hides the bars, double tap zooms in or out at the finger, pinch zooms around the
 * fingers, drag pans while zoomed (clamped to the photo), swipe up opens Details, swipe down
 * closes the viewer. Horizontal swipes at 1x (or at the edge of a zoomed photo) go to the pager.
 */
@Composable
private fun ZoomablePhoto(uri: Uri, active: Boolean, onTap: () -> Unit, onSwipeUp: () -> Unit, onSwipeDown: () -> Unit) {
    val bmp by rememberDecoded(uri, 2560)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val ui = rememberCoroutineScope()
    var anim by remember { mutableStateOf<Job?>(null) }
    // Swiping to another photo resets this one.
    LaunchedEffect(active) { if (!active) { anim?.cancel(); scale = 1f; offset = Offset.Zero } }
    val tap by rememberUpdatedState(onTap)
    val up by rememberUpdatedState(onSwipeUp)
    val down by rememberUpdatedState(onSwipeDown)
    val imgW = bmp?.width ?: 0
    val imgH = bmp?.height ?: 0
    val imgSize by rememberUpdatedState(imgW to imgH)

    Box(
        Modifier
            .fillMaxSize()
            .clipToBounds()
            .pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val doubleTapMs = viewConfiguration.doubleTapTimeoutMillis
                val swipePx = 90.dp.toPx()
                var lastTapAt = 0L
                var lastTapPos = Offset.Zero
                var pendingTap: Job? = null

                // How far the zoomed photo may move before its edge leaves the screen edge.
                fun clamp(o: Offset, s: Float): Offset {
                    val (iw, ih) = imgSize
                    val bw = size.width.toFloat()
                    val bh = size.height.toFloat()
                    val fit = if (iw > 0 && ih > 0) minOf(bw / iw, bh / ih) else 1f
                    val cw = if (iw > 0) iw * fit else bw
                    val ch = if (ih > 0) ih * fit else bh
                    val mx = ((cw * s - bw) / 2f).coerceAtLeast(0f)
                    val my = ((ch * s - bh) / 2f).coerceAtLeast(0f)
                    return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
                }

                fun animateTo(s: Float, o: Offset) {
                    anim?.cancel()
                    val s0 = scale
                    val o0 = offset
                    anim = ui.launch {
                        animate(0f, 1f, animationSpec = tween(220)) { v, _ ->
                            scale = s0 + (s - s0) * v
                            offset = o0 + (o - o0) * v
                        }
                    }
                }

                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    anim?.cancel()
                    val start = first.position
                    var moved = false
                    var multi = false
                    var vertical = false
                    var total = Offset.Zero
                    var upTime = first.uptimeMillis
                    while (true) {
                        val e = awaitPointerEvent()
                        val pressed = e.changes.filter { it.pressed }
                        if (pressed.isEmpty()) {
                            upTime = e.changes.firstOrNull()?.uptimeMillis ?: upTime
                            break
                        }
                        if (pressed.size >= 2) {
                            multi = true
                            val a = pressed[0]
                            val b = pressed[1]
                            val prev = (a.previousPosition - b.previousPosition).getDistance()
                            val cur = (a.position - b.position).getDistance()
                            val zoom = if (prev > 0f) cur / prev else 1f
                            val prevC = (a.previousPosition + b.previousPosition) / 2f
                            val curC = (a.position + b.position) / 2f
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            val k = newScale / scale
                            // Keep the spot between the fingers under the fingers while zooming.
                            val rel = prevC - Offset(size.width / 2f, size.height / 2f)
                            val o = (offset - rel) * k + rel + (curC - prevC)
                            scale = newScale
                            offset = if (newScale <= 1.01f) Offset.Zero else clamp(o, newScale)
                            e.changes.forEach { it.consume() }
                            continue
                        }
                        if (multi) continue
                        val c = pressed[0]
                        total = c.position - start
                        if (!moved && total.getDistance() > slop) {
                            moved = true
                            vertical = abs(total.y) > abs(total.x)
                        }
                        if (!moved) continue
                        val d = c.position - c.previousPosition
                        if (scale > 1.01f) {
                            val next = clamp(offset + d, scale)
                            // Pinned at a left/right edge and swiping sideways: let the pager
                            // move to the next photo instead.
                            val atEdge = abs(next.x - offset.x) < 0.5f && abs(d.x) > abs(d.y)
                            if (!atEdge) {
                                offset = next
                                c.consume()
                            }
                        } else if (vertical) {
                            c.consume()
                        }
                    }
                    if (multi) return@awaitEachGesture
                    if (!moved) {
                        // Tap or double tap.
                        if (pendingTap?.isActive == true && upTime - lastTapAt < doubleTapMs && (start - lastTapPos).getDistance() < 60.dp.toPx()) {
                            pendingTap?.cancel()
                            lastTapAt = 0L
                            if (scale > 1.1f) animateTo(1f, Offset.Zero)
                            else {
                                val target = 2.5f
                                val center = Offset(size.width / 2f, size.height / 2f)
                                animateTo(target, clamp((center - start) * (target - 1f), target))
                            }
                        } else {
                            lastTapAt = upTime
                            lastTapPos = start
                            pendingTap = ui.launch {
                                delay(doubleTapMs)
                                tap()
                            }
                        }
                    } else if (scale <= 1.01f && vertical) {
                        if (total.y < -swipePx) up() else if (total.y > swipePx) down()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(), null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}
/** EXIF text is ASCII only, so items are stored with commas (older photos have " ? "). Show them as a clean list. */
private fun processingText(raw: String): String =
    raw.split(", ", " ? ").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
