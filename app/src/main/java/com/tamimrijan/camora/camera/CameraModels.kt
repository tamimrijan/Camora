package com.tamimrijan.camora.camera

import android.graphics.Rect
import android.util.Size

/** Photo-only camera modes. */
enum class CameraMode(val label: String) {
    PORTRAIT("Portrait"), PHOTO("Photo"), SELFIE("Selfie"), PRO("Pro");
}

enum class FlashSetting { OFF, AUTO, ON }

enum class AspectSetting(val label: String, val ratio: Float) {
    R4_3("4:3", 4f / 3f), R16_9("16:9", 16f / 9f), R1_1("1:1", 1f), FULL("Full", 20f / 9f);

    /** Sensor stream used: 16:9 and Full come from a 16:9 stream, others from 4:3. */
    val streamIs16x9 get() = this == R16_9 || this == FULL
}

data class BindRequest(
    val mode: CameraMode,
    val front: Boolean,
    val cameraId: String? = null,          // explicit module (e.g. ultra-wide), else default for facing
    val aspect: AspectSetting = AspectSetting.R4_3,
    val photoSize: Size? = null,           // requested capture size (null = best standard size)
    val highRes: Boolean = false,          // allow high-resolution (slow) sensor modes, e.g. 50 MP
    val naturalPreview: Boolean = true,    // GPU colour pipeline in the viewfinder
    val faceMetering: Boolean = true,      // move AE/AF to faces
    val faceTracking: Boolean = true,      // feed faces to the live preview (face light, skin)
    val landmarks: Boolean = false,        // eyes/mouth positions for the live skin preview
    val portraitPreview: Boolean = false,
)

/** One JPEG straight from the camera HAL. */
class CapturedFrame(
    val jpeg: ByteArray,
    val rotationDegrees: Int,
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    val cropRect: Rect,
)

data class CameraState(
    val bound: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lens: LensInfo? = null,
    val front: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val evIndex: Int = 0,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Float = 0f,
    val hasFlash: Boolean = false,
    val iso: Int = 0,
    val exposureNs: Long = 0L,
    val focusDistance: Float = 0f,
    val faces: Int = 0,
    val ultraWideId: String? = null,
    val usingUltraWide: Boolean = false,
    val captureSize: Size? = null,
    val naturalPreviewActive: Boolean = false,
)

/** Manual (Pro mode) values. null means "auto" for that parameter. */
data class ManualSettings(
    val iso: Int? = null,
    val shutterNs: Long? = null,
    val awbMode: Int? = null,       // CaptureRequest.CONTROL_AWB_MODE_* preset
    val kelvin: Int? = null,        // for display; mapped to the closest preset
    val focus: Float? = null,       // 0 = infinity .. 1 = closest focus
) {
    val isAuto get() = iso == null && shutterNs == null && awbMode == null && focus == null
}
