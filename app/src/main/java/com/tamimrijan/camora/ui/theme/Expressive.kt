package com.tamimrijan.camora.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Material 3 Expressive motion, built on the stable Material 3 library: springy, slightly
 * bouncy feedback instead of flat easing. Used for presses, selections and screen changes.
 */
object ExpressiveMotion {
    /** Quick, lively spring for presses and toggles (a little overshoot). */
    fun <T> bouncy() = spring<T>(dampingRatio = 0.5f, stiffness = 520f)

    /** Smooth spring for things that move or resize (no visible wobble). */
    fun <T> smooth() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
}

/**
 * Expressive press feedback: the element squishes a little while pressed and springs back.
 * It only watches the touch and never consumes it, so clicks, scrolling and the camera
 * gestures underneath behave exactly as before.
 */
fun Modifier.pressSquish(pressedScale: Float = 0.92f): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) pressedScale else 1f, ExpressiveMotion.bouncy(), label = "squish")
    this
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                pressed = true
                waitForUpOrCancellation()
                pressed = false
            }
        }
        .graphicsLayer { scaleX = s; scaleY = s }
}