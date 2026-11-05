package com.tamimrijan.camora.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.util.Range
import android.util.Size
import kotlin.math.atan
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * What a camera module really supports, read from Camera2 at runtime. Nothing in the app
 * assumes a feature exists: every control is gated on these values, because Samsung
 * exposes different subsets of the hardware to third-party apps than to its own camera.
 */
data class LensInfo(
    val cameraId: String,
    val facing: Int,                     // CameraCharacteristics.LENS_FACING_*
    val hardwareLevel: Int,
    val focalLengthMm: Float,
    val equivalentMm: Float,             // 35 mm equivalent focal length
    val aperture: Float,
    val pixelArray: Size,
    val maxResPixelArray: Size?,         // quad-bayer full resolution (Android 12+)
    val jpegSizes: List<Size>,
    val highResJpegSizes: List<Size>,
    val hasOis: Boolean,
    val hasEis: Boolean,
    val minFocusDistance: Float,         // diopters; 0 = fixed focus
    val manualSensor: Boolean,
    val manualPostProcessing: Boolean,
    val raw: Boolean,
    val logicalMultiCamera: Boolean,
    val ultraHighRes: Boolean,
    val isoRange: Range<Int>?,
    val exposureRangeNs: Range<Long>?,
    val fpsRanges: List<Range<Int>>,
    val awbModes: IntArray,
    val zoomRatioRange: Range<Float>?,
    val faceDetectModes: IntArray,
    val physicalIds: Set<String>,
    val tenBit: Boolean,
    val ultraHdrJpeg: Boolean,
) {
    val isFront get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    val isBack get() = facing == CameraCharacteristics.LENS_FACING_BACK
    val megapixels get() = pixelArray.width * pixelArray.height / 1_000_000f
    val maxJpeg get() = (jpegSizes + highResJpegSizes).maxByOrNull { it.width.toLong() * it.height } ?: pixelArray
    val canManualFocus get() = minFocusDistance > 0f && manualSensor

    val role: String
        get() = when {
            isFront -> "Front"
            equivalentMm in 1f..19f -> "Ultra wide"
            megapixels < 3f && minFocusDistance > 20f -> "Macro"
            megapixels < 3f -> "Depth"
            else -> "Main"
        }

    val levelName: String
        get() = when (hardwareLevel) {
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
            CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
            else -> "Unknown ($hardwareLevel)"
        }
}

object CameraCapabilities {
    @Volatile private var cache: List<LensInfo>? = null

    fun lenses(context: Context): List<LensInfo> {
        cache?.let { return it }
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val list = cm.cameraIdList.mapNotNull { id -> runCatching { read(cm, id) }.getOrNull() }
        cache = list
        return list
    }

    fun lens(context: Context, id: String): LensInfo? = lenses(context).firstOrNull { it.cameraId == id }

    private fun read(cm: CameraManager, id: String): LensInfo {
        val c = cm.getCameraCharacteristics(id)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
        val phys = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val eq = if (phys != null && focal > 0f) {
            // 35 mm equivalent by diagonal (43.27 mm full-frame diagonal)
            focal * 43.27f / hypot(phys.width, phys.height)
        } else 0f
        val ois = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: IntArray(0)
        val eis = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: IntArray(0)
        val maxRes = if (Build.VERSION.SDK_INT >= 31) c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION) else null
        val tenBit = Build.VERSION.SDK_INT >= 33 && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT)
        val ultraHdr = Build.VERSION.SDK_INT >= 34 && runCatching { map?.isOutputSupportedFor(ImageFormat.JPEG_R) == true }.getOrDefault(false)
        val zoom = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        return LensInfo(
            cameraId = id,
            facing = c.get(CameraCharacteristics.LENS_FACING) ?: -1,
            hardwareLevel = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1,
            focalLengthMm = focal,
            equivalentMm = eq,
            aperture = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 0f,
            pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: Size(0, 0),
            maxResPixelArray = maxRes,
            jpegSizes = map?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty().sortedByDescending { it.width.toLong() * it.height },
            highResJpegSizes = map?.getHighResolutionOutputSizes(ImageFormat.JPEG)?.toList().orEmpty().sortedByDescending { it.width.toLong() * it.height },
            hasOis = ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON),
            hasEis = eis.contains(CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON),
            minFocusDistance = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            manualSensor = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            manualPostProcessing = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING),
            raw = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW),
            logicalMultiCamera = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA),
            ultraHighRes = Build.VERSION.SDK_INT >= 31 && caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR),
            isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRangeNs = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList().orEmpty(),
            awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0),
            zoomRatioRange = zoom,
            faceDetectModes = c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0),
            physicalIds = if (Build.VERSION.SDK_INT >= 28) c.physicalCameraIds else emptySet(),
            tenBit = tenBit,
            ultraHdrJpeg = ultraHdr,
        )
    }

    /** Human-readable capability report (shown in Settings > Device camera report). */
    fun report(context: Context, extras: Map<String, String> = emptyMap()): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("SoC: ${if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "n/a"}")
        appendLine()
        for (l in lenses(context)) {
            appendLine("Camera ${l.cameraId}: ${l.role} (${if (l.isFront) "front" else if (l.isBack) "back" else "external"})")
            appendLine("  Hardware level: ${l.levelName}")
            appendLine("  Sensor: ${l.pixelArray.width}x${l.pixelArray.height} (%.1f MP)".format(l.megapixels) +
                (l.maxResPixelArray?.let { "  maximum resolution mode: ${it.width}x${it.height} (%.1f MP)".format(it.width * it.height / 1e6f) } ?: ""))
            appendLine("  Lens: %.2f mm (≈%d mm equiv.), f/%.1f, min focus %s".format(
                l.focalLengthMm, l.equivalentMm.roundToInt(), l.aperture,
                if (l.minFocusDistance > 0f) "%.1f cm".format(100f / l.minFocusDistance) else "fixed focus",
            ))
            appendLine("  Largest JPEG: ${l.jpegSizes.firstOrNull()?.let { "${it.width}x${it.height}" } ?: "none"}; " +
                "high resolution JPEG: ${l.highResJpegSizes.joinToString { "${it.width}x${it.height}" }.ifEmpty { "none" }}")
            appendLine("  OIS: ${yes(l.hasOis)}, EIS: ${yes(l.hasEis)}, Manual sensor: ${yes(l.manualSensor)}, " +
                "Manual post processing: ${yes(l.manualPostProcessing)}, RAW: ${yes(l.raw)}")
            appendLine("  Ultra high resolution sensor mode: ${yes(l.ultraHighRes)}, Logical multi camera: ${yes(l.logicalMultiCamera)}" +
                if (l.physicalIds.isNotEmpty()) " (physical ${l.physicalIds.joinToString()})" else "")
            l.isoRange?.let { appendLine("  ISO ${it.lower} to ${it.upper}") }
            l.exposureRangeNs?.let { appendLine("  Shutter ${formatShutter(it.lower)} to ${formatShutter(it.upper)}") }
            appendLine("  AE fps ranges: ${l.fpsRanges.joinToString { "[${it.lower},${it.upper}]" }}")
            l.zoomRatioRange?.let { appendLine("  Zoom ratio range: %.2fx to %.1fx".format(it.lower, it.upper)) }
            appendLine("  10 bit HDR video: ${yes(l.tenBit)}, Ultra HDR JPEG: ${yes(l.ultraHdrJpeg)}, " +
                "HW face detect modes: ${l.faceDetectModes.joinToString()}")
            appendLine("  Field of view (diagonal): %.0f°".format(fov(l)))
            appendLine()
        }
        for ((k, v) in extras) appendLine("$k: $v")
    }

    private fun fov(l: LensInfo): Float =
        if (l.equivalentMm > 0f) Math.toDegrees(2 * atan(43.27 / (2 * l.equivalentMm))).toFloat() else 0f

    private fun yes(b: Boolean) = if (b) "yes" else "no"

    fun formatShutter(ns: Long): String {
        val s = ns / 1e9
        return if (s >= 0.5) "%.1fs".format(s) else "1/${(1.0 / s).roundToInt()}"
    }
}
