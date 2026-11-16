package com.tamimrijan.camora.ui.theme

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Material 3 Expressive building blocks drawn with the stable Compose APIs:
 * wavy progress indicators, a floating pill toolbar and a button whose shape morphs
 * between a circle and a rounded square when it is selected.
 */

/**
 * Wavy circular progress (Expressive "squiggly" indicator). [progress] null = spinning.
 * The arc is a sine wave wrapped around the circle; the unfilled part is a calm track.
 */
@Composable
fun WavyCircularProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    track: Color = color.copy(alpha = 0.22f),
    stroke: Dp = 4.dp,
    waves: Int = 9,
) {
    val spin by rememberInfiniteTransition(label = "wavySpin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "spin",
    )
    val phase by rememberInfiniteTransition(label = "wavyPhase").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    Canvas(modifier) {
        val sw = stroke.toPx()
        val amp = sw * 0.75f
        val r = size.minDimension / 2f - sw - amp
        val c = Offset(size.width / 2f, size.height / 2f)
        val sweep = if (progress == null) 0.3f else progress.coerceIn(0f, 1f)
        val start = if (progress == null) spin else -90f
        // track
        drawCircle(track, r, c, style = Stroke(sw))
        if (sweep <= 0.001f) return@Canvas
        val path = Path()
        val steps = (180 * sweep).toInt().coerceAtLeast(8)
        for (i in 0..steps) {
            val f = i / steps.toFloat()
            val a = Math.toRadians((start + 360f * sweep * f).toDouble())
            val rr = r + amp * sin(waves * a + phase).toFloat()
            val x = c.x + rr * cos(a).toFloat()
            val y = c.y + rr * sin(a).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Wavy linear progress: the filled part is a moving sine wave, the rest a flat track. */
@Composable
fun WavyLinearProgress(progress: Float, modifier: Modifier = Modifier, color: Color, track: Color = color.copy(alpha = 0.24f), stroke: Dp = 5.dp) {
    val phase by rememberInfiniteTransition(label = "wavyLin").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "phase",
    )
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), ExpressiveMotion.smooth(), label = "lin")
    Canvas(modifier.height(stroke * 3)) {
        val sw = stroke.toPx()
        val cy = size.height / 2f
        val end = size.width * shown
        val gap = sw * 1.6f
        if (end + gap < size.width) drawLine(track, Offset(end + gap, cy), Offset(size.width, cy), sw, StrokeCap.Round)
        if (end <= sw) return@Canvas
        val path = Path()
        val wl = sw * 7f
        var x = 0f
        path.moveTo(0f, cy)
        while (x <= end) {
            path.lineTo(x, cy + sw * 0.7f * sin((x / wl) * 2f * PI.toFloat() - phase))
            x += 2f
        }
        drawPath(path, color, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Floating pill toolbar (Expressive): a raised, fully rounded container for actions. */
@Composable
fun FloatingToolbar(modifier: Modifier = Modifier, container: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .shadow(10.dp, RoundedCornerShape(50), clip = false)
            .clip(RoundedCornerShape(50))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Expressive button: a tonal circle that morphs into a filled rounded square (with its
 * label sliding in) when selected or when it is the primary action.
 */
@Composable
fun MorphButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedContainer: Color,
    selectedContent: Color,
    container: Color,
    content: Color,
    size: Dp = 56.dp,
    showLabelWhenSelected: Boolean = true,
) {
    val corner by animateDpAsState(if (selected) size * 0.3f else size / 2, ExpressiveMotion.bouncy(), label = "corner")
    val bg by animateColorAsState(if (selected) selectedContainer else container, label = "bg")
    val fg by animateColorAsState(if (selected) selectedContent else content, label = "fg")
    Row(
        Modifier
            .height(size)
            .pressSquish(0.9f)
            .clip(RoundedCornerShape(corner))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = (size - 24.dp) / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, label, tint = fg, modifier = Modifier.size(24.dp))
        AnimatedVisibility(
            visible = selected && showLabelWhenSelected,
            enter = fadeIn() + expandHorizontally(ExpressiveMotion.smooth()),
            exit = fadeOut() + shrinkHorizontally(ExpressiveMotion.smooth()),
        ) {
            Row {
                Spacer(Modifier.width(10.dp))
                Text(label, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

/** Big Expressive pill chip; the selected one is filled. */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedContainer: Color,
    selectedContent: Color,
    content: Color,
    container: Color = Color.Transparent,
    icon: ImageVector? = null,
) {
    val bg by animateColorAsState(if (selected) selectedContainer else container, label = "chipBg")
    val fg by animateColorAsState(if (selected) selectedContent else content, label = "chipFg")
    Row(
        Modifier
            .pressSquish(0.92f)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1, softWrap = false)
    }
}

/** Box helper so callers can centre a wavy spinner on top of something. */
@Composable
fun WavySpinnerBox(modifier: Modifier, color: Color) {
    Box(modifier, contentAlignment = Alignment.Center) { WavyCircularProgress(null, Modifier.size(30.dp), color = color, stroke = 3.dp) }
}