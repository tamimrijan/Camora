package com.tamimrijan.camora.engine

enum class HdrMode(val strength: Float) { OFF(0f), AUTO(0.75f), ON(1f) }

/**
 * Per-camera tuning. The front and rear modules on the Galaxy M35 have very different
 * optics, noise and ISP sharpening, so each gets its own defaults.
 */
enum class CameraTuning(
    val sharpen: Float,      // unsharp-mask amount at "Natural" detail
    val hdrStrength: Float,  // scales local tone mapping
    val chromaNr: Float,     // 0..1 chroma noise reduction blend
    val lumaNr: Float,       // 0..1 luma noise reduction blend at high ISO
    val faceLight: Float,    // scales backlit-face exposure correction
    val wbStrength: Float,   // how much of the measured cast we correct
) {
    // 50 MP main camera, f/1.8 + OIS: strong optics, ISP already sharpens moderately.
    REAR(sharpen = 0.42f, hdrStrength = 1.0f, chromaNr = 0.75f, lumaNr = 0.45f, faceLight = 0.8f, wbStrength = 0.5f),
    // 13 MP fixed-focus front camera: softer lens, noisier indoors, faces matter most.
    FRONT(sharpen = 0.22f, hdrStrength = 0.85f, chromaNr = 0.9f, lumaNr = 0.55f, faceLight = 1.0f, wbStrength = 0.6f),
}

/** Beauty controls. All default to zero: realism first. */
data class BeautySettings(
    val smoothing: Float = 0f,   // 0..1 skin smoothing (texture always partly retained)
    val evenTone: Float = 0f,    // 0..1 reduces blotchy redness inside the skin mask only
    val faceLight: Float = 0.6f, // 0..1 backlit face correction (not a brightening filter)
) {
    val active get() = smoothing > 0.001f || evenTone > 0.001f
}

/** Manual adjustments from the editor (all centred on 0, range about -1..1). */
data class UserAdjust(
    val exposure: Float = 0f,   // EV, -2..2
    val contrast: Float = 0f,
    val highlights: Float = 0f, // negative recovers highlights
    val shadows: Float = 0f,    // positive lifts shadows
    val saturation: Float = 0f,
    val warmth: Float = 0f,
    val tint: Float = 0f,
    val sharpness: Float = 0f,
) {
    val isNeutral get() = this == NONE

    companion object {
        val NONE = UserAdjust()
    }
}

data class ProcessParams(
    val profile: ColorProfileId = ColorProfileId.NATURAL,
    val profileIntensity: Float = 1f,
    val hdrMode: HdrMode = HdrMode.AUTO,
    val tuning: CameraTuning = CameraTuning.REAR,
    val beauty: BeautySettings = BeautySettings(),
    val detailLevel: Float = 1f,         // sharpening multiplier from settings (0.5 low, 1 natural, 1.4 high)
    val noiseReductionLevel: Float = 1f, // NR multiplier from settings
    val faceExposure: Boolean = true,
    val iso: Int = 0,                    // from EXIF, 0 if unknown
    val night: Boolean = false,
    val mergedFrames: Int = 1,
    val baselineEv: Float = 0f,          // e.g. +1 when frames were captured at -1 EV for HDR
    val portraitBlur: Float = 0f,        // 0..1 (0 = no blur)
    val adjust: UserAdjust = UserAdjust.NONE,
    val autoEnhance: Boolean = true,     // editor can turn off scene-adaptive corrections
)

/** What the engine did, shown in the review screen so the user can see why it looks different. */
data class EngineReport(
    val exposureEv: Float,
    val shadowLiftEv: Float,
    val highlightCompEv: Float,
    val wbGains: FloatArray,
    val faceLiftEv: Float,
    val faces: Int,
    val noiseSigma: Float,
    val sharpenAmount: Float,
    val millis: Long,
) {
    fun summary(): String = buildString {
        append(
            when {
                exposureEv > 0.05f -> "Brightened %.1f EV".format(exposureEv)
                exposureEv < -0.05f -> "Darkened %.1f EV".format(-exposureEv)
                else -> "Exposure kept"
            },
        )
        if (shadowLiftEv > 0.05f) append(", Shadows lifted %.1f EV".format(shadowLiftEv))
        if (highlightCompEv > 0.05f) append(", Highlights recovered %.1f EV".format(highlightCompEv))
        if (faces > 0) append(", $faces face" + if (faces > 1) "s" else "")
        if (faceLiftEv > 0.05f) append(" lit %.1f EV".format(faceLiftEv))
        append(", ${millis} ms")
    }
}
