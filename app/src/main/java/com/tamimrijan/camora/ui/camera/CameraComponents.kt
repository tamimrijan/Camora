package com.tamimrijan.camora.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimrijan.camora.camera.CameraMode
import com.tamimrijan.camora.ui.theme.Accent
import com.tamimrijan.camora.ui.theme.ModeLabelStyle
import kotlin.math.abs
import kotlin.math.sqrt

/** Icon button for the dark viewfinder; rotates with the device so icons stay upright. */
@Composable
fun CamIcon(
    icon: ImageVector,
    description: String,
    rotation: Float,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) Accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            tint = when {
                !enabled -> Color.White.copy(alpha = 0.35f)
                active -> Accent
                else -> Color.White
            },
            modifier = Modifier.size(24.dp).rotate(rotation),
        )
    }
}

/** Animated rotation that always turns the short way (0 -> 270 goes -90, not +270). */
@Composable
fun rememberUiRotation(deviceDegrees: Int): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(deviceDegrees) {
        val target = -deviceDegrees.toFloat()
        var delta = (target - anim.value) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        anim.animateTo(anim.value + delta, spring(stiffness = 300f))
    }
    return anim.value
}

@Composable
fun ShutterButton(busy: Boolean, progress: Float?, size: Dp = 78.dp, onClick: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, label = "shutterScale")
    Box(
        Modifier
            .size(size)
            .scale(scale)
            .clickable(interactionSource = interaction, indication = null) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            drawCircle(Color.White, radius = this.size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
            drawCircle(if (busy) Color.White.copy(alpha = 0.55f) else Color.White, radius = this.size.minDimension / 2 - stroke - 3.dp.toPx())
            if (progress != null) {
                drawArc(
                    Accent, startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                )
            }
        }
    }
}

/** Horizontal mode carousel; the selected mode is centred and highlighted. */
@Composable
fun ModeSelector(modes: List<CameraMode>, selected: CameraMode, enabled: Boolean, onSelect: (CameraMode) -> Unit) {
    val state = rememberLazyListState()
    val idx = modes.indexOf(selected)
    BoxWithConstraints {
        // Wide enough for the longest label ("PORTRAIT") plus the selection pill.
        val itemW = 112.dp
        val side = ((maxWidth - itemW) / 2).coerceAtLeast(0.dp)
        // The horizontal padding is half the free width, so scrolling an item to the start
        // of the content area centres it - on any screen width.
        LaunchedEffect(idx) { if (idx >= 0) state.animateScrollToItem(idx) }
        LazyRow(
            state = state,
            modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(horizontal = side),
            userScrollEnabled = false,
        ) {
            items(modes) { m ->
                val sel = m == selected
                Box(
                    Modifier
                        .width(itemW)
                        .height(40.dp)
                        .clickable(enabled = enabled) { onSelect(m) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        m.label.uppercase(), style = ModeLabelStyle,
                        color = if (sel) Accent else Color.White.copy(alpha = if (enabled) 0.8f else 0.4f),
                        maxLines = 1,
                        softWrap = false,
                        modifier = if (sel) Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Accent.copy(alpha = 0.14f))
                            .padding(horizontal = 10.dp, vertical = 5.dp) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
fun ZoomChips(presets: List<Float>, current: Float, usingUltraWide: Boolean, rotation: Float, onSelect: (Float) -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.38f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val active = presets.lastOrNull { p ->
            if (p < 0.99f) usingUltraWide || current < 0.99f else !usingUltraWide && current >= p - 0.05f
        }
        for (p in presets) {
            val sel = p == active
            val label = if (sel && p >= 1f && abs(current - p) > 0.05f) "%.1f×".format(current)
            else if (p < 1f) ".6" else "${p.toInt()}" + (if (sel) "×" else "")
            Box(
                Modifier
                    .size(if (sel) 38.dp else 32.dp)
                    .clip(CircleShape)
                    .background(if (sel) Color.Black.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.45f))
                    .clickable { onSelect(p) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (sel) Accent else Color.White, fontSize = if (sel) 12.sp else 11.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.rotate(rotation),
                )
            }
        }
    }
}

/** Focus square with an exposure (sun) slider beside it. */
@Composable
fun FocusIndicator(x: Float, y: Float, evFraction: Float?, key: Any) {
    val density = LocalDensity.current
    val anim = remember(key) { Animatable(1.5f) }
    LaunchedEffect(key) { anim.animateTo(1f, tween(220)) }
    val sizePx = with(density) { 76.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        val s = sizePx * anim.value
        val tl = Offset(x - s / 2, y - s / 2)
        val w = 1.5.dp.toPx()
        drawRoundRect(Accent, topLeft = tl, size = Size(s, s), cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = Stroke(w))
        if (evFraction != null) {
            val lx = tl.x + s + 18.dp.toPx()
            val top = y - s * 0.75f
            val bottom = y + s * 0.75f
            val sunY = bottom - (bottom - top) * evFraction
            drawLine(Accent.copy(alpha = 0.7f), Offset(lx, top), Offset(lx, sunY - 9.dp.toPx()), 1.dp.toPx())
            drawLine(Accent.copy(alpha = 0.7f), Offset(lx, sunY + 9.dp.toPx()), Offset(lx, bottom), 1.dp.toPx())
            drawCircle(Accent, 4.5.dp.toPx(), Offset(lx, sunY))
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val r0 = 6.5.dp.toPx()
                val r1 = 9.dp.toPx()
                drawLine(
                    Accent,
                    Offset(lx + (r0 * Math.cos(a)).toFloat(), sunY + (r0 * Math.sin(a)).toFloat()),
                    Offset(lx + (r1 * Math.cos(a)).toFloat(), sunY + (r1 * Math.sin(a)).toFloat()),
                    1.2.dp.toPx(),
                )
            }
        }
    }
}

@Composable
fun GridOverlay() {
    Canvas(Modifier.fillMaxSize()) {
        val c = Color.White.copy(alpha = 0.28f)
        val w = 0.8.dp.toPx()
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), w)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), w)
        }
    }
}

/** Luminance histogram for Pro mode. */
@Composable
fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f))) {
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val path = Path().apply {
            moveTo(0f, size.height)
            bins.forEachIndexed { i, v ->
                lineTo(size.width * i / (bins.size - 1), size.height - size.height * sqrt(v / max))
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, Color.White.copy(alpha = 0.75f))
    }
}

/** Translucent pill used for notices on the viewfinder. */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.5f), textColor: Color = Color.White, onClick: (() -> Unit)? = null) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text, color = textColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ThumbnailFrame(modifier: Modifier = Modifier, size: Dp = 50.dp, content: @Composable () -> Unit) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .background(Color(0xFF1E1F25)),
        contentAlignment = Alignment.Center,
    ) { content() }
}
