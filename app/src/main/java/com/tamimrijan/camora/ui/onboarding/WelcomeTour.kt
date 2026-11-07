package com.tamimrijan.camora.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FaceRetouchingNatural
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tamimrijan.camora.R
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.floor

private data class TourPage(
    val icon: ImageVector?,          // null = the Camora logo
    val accent: Color,
    val title: String,
    val body: String,
    val chips: List<String> = emptyList(),
)

private val PAGES = listOf(
    TourPage(null, Color(0xFFD71921), "Welcome to Camora", "Natural photos, developed on your phone by Camora's own image engine."),
    TourPage(
        Icons.Rounded.Palette, Color(0xFFFF8A65), "Five natural looks",
        "Pick a look and see it live in the viewfinder before you shoot. What you see is what you get.",
        listOf("Original", "Natural", "Warm", "Cool", "Vivid"),
    ),
    TourPage(
        Icons.Rounded.HdrOn, Color(0xFFFFC94A), "Smart HDR",
        "Bright skies and deep shadows in one photo. HDR On merges three frames for the cleanest result.",
        listOf("Auto", "On", "Off"),
    ),
    TourPage(
        Icons.Rounded.FaceRetouchingNatural, Color(0xFFFF6FAE), "Faces done right",
        "Face lighting, gentle skin smoothing and portrait blur, all live. Pores and texture always stay real.",
        listOf("Portrait", "Selfie", "Face light"),
    ),
    TourPage(
        Icons.Rounded.Tune, Color(0xFF7C9CFF), "Pro when you want it",
        "Manual ISO, shutter, focus and white balance. Plus widgets, app shortcuts and a lock screen camera.",
        listOf("Pro mode", "Widgets", "Editor"),
    ),
    TourPage(
        Icons.Rounded.Lock, Color(0xFF3DDC84), "Private by design",
        "Photos are processed on this phone and never uploaded. Camora only goes online when you check for updates.",
    ),
)

/**
 * First run feature tour. Pages slide with a parallax: the artwork moves slower than the
 * text and fades and shrinks as it leaves, while the background glow blends between the
 * page colours as you swipe.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WelcomeTour(onFinish: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES.lastIndex

    // Entrance: everything rises and fades in once.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }

    // Background glow colour follows the swipe position continuously.
    val pos = pager.currentPage + pager.currentPageOffsetFraction
    val i0 = floor(pos).toInt().coerceIn(0, PAGES.lastIndex)
    val i1 = (i0 + 1).coerceAtMost(PAGES.lastIndex)
    val glow = lerp(PAGES[i0].accent, PAGES[i1].accent, (pos - i0).coerceIn(0f, 1f))

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF050505))) {
        val w = constraints.maxWidth.toFloat()
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(glow.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.36f),
                    radius = size.width * 0.95f,
                ),
            )
        }

        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 60f },
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Camora", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (!last) {
                    Text(
                        "Skip", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp,
                        modifier = Modifier.clip(CircleShape).clickable(onClick = onFinish).padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }

            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                val d = (pager.currentPage - page) + pager.currentPageOffsetFraction
                val ad = abs(d).coerceIn(0f, 1f)
                val pg = PAGES[page]
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Artwork: parallax (moves at 35% of the swipe), shrinks and fades out.
                    Box(
                        Modifier.size(240.dp).graphicsLayer {
                            translationX = d * w * 0.65f
                            alpha = 1f - ad * 0.8f
                            val s = 1f - ad * 0.25f
                            scaleX = s; scaleY = s
                        },
                        contentAlignment = Alignment.Center,
                    ) { Artwork(pg) }
                    Spacer(Modifier.height(28.dp))
                    // Text follows the finger a little faster than the art for depth.
                    Column(
                        Modifier.widthIn(max = 420.dp).graphicsLayer {
                            translationX = d * w * 0.15f
                            alpha = 1f - ad
                        },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(pg.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 36.sp)
                        Spacer(Modifier.height(12.dp))
                        Text(pg.body, color = Color.White.copy(alpha = 0.72f), fontSize = 16.sp, textAlign = TextAlign.Center, lineHeight = 23.sp)
                        if (pg.chips.isNotEmpty()) {
                            Spacer(Modifier.height(18.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                pg.chips.forEach { c ->
                                    Text(
                                        c, color = Color.White, fontSize = 13.sp, maxLines = 1, softWrap = false,
                                        modifier = Modifier.clip(CircleShape).border(1.dp, pg.accent.copy(alpha = 0.6f), CircleShape)
                                            .background(pg.accent.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 6.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Page dots: the active one stretches into a pill in the page colour.
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center) {
                PAGES.forEachIndexed { i, pg ->
                    val active = i == pager.currentPage
                    val wDot by animateDpAsState(if (active) 26.dp else 8.dp, tween(300), label = "dot")
                    Box(
                        Modifier.padding(horizontal = 4.dp).height(8.dp).width(wDot).clip(CircleShape)
                            .background(if (active) pg.accent else Color.White.copy(alpha = 0.25f)),
                    )
                }
            }

            Button(
                onClick = { if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 20.dp).fillMaxWidth().widthIn(max = 480.dp).height(56.dp),
            ) {
                AnimatedContent(
                    last,
                    transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
                    label = "cta",
                ) { isLast ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (isLast) "Get started" else "Next", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/** Icon in a glowing disc with slowly expanding rings (the logo on the first page). */
@Composable
private fun Artwork(pg: TourPage) {
    val t by rememberInfiniteTransition(label = "rings").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "t",
    )
    Canvas(Modifier.fillMaxSize()) {
        val c = center
        val base = size.minDimension * 0.30f
        for (k in 0 until 3) {
            val f = (t + k / 3f) % 1f
            drawCircle(pg.accent.copy(alpha = (1f - f) * 0.45f), radius = base + f * size.minDimension * 0.22f, center = c, style = Stroke(width = 2.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(listOf(pg.accent.copy(alpha = 0.55f), pg.accent.copy(alpha = 0.08f)), center = c, radius = base), radius = base, center = c)
    }
    if (pg.icon == null) {
        Image(painterResource(R.drawable.camora_logo), "Camora logo", Modifier.size(110.dp).clip(CircleShape))
    } else {
        Icon(pg.icon, null, tint = Color.White, modifier = Modifier.size(64.dp))
    }
}