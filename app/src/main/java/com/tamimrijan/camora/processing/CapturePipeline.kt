package com.tamimrijan.camora.processing

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.tamimrijan.camora.camera.AspectSetting
import com.tamimrijan.camora.camera.CameraMode
import com.tamimrijan.camora.camera.CapturedFrame
import com.tamimrijan.camora.data.AppSettings
import com.tamimrijan.camora.data.MediaRepository
import com.tamimrijan.camora.data.PhotoFormat
import com.tamimrijan.camora.engine.BeautySettings
import com.tamimrijan.camora.engine.CameraTuning
import com.tamimrijan.camora.engine.EngineReport
import com.tamimrijan.camora.engine.LutBuilder
import com.tamimrijan.camora.engine.MultiFrameMerger
import com.tamimrijan.camora.engine.NaturalEngine
import com.tamimrijan.camora.engine.ProcessParams
import com.tamimrijan.camora.engine.Resample
import com.tamimrijan.camora.engine.SceneAnalyzer
import com.tamimrijan.camora.engine.UserAdjust
import com.tamimrijan.camora.processing.AndroidImaging.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A photo request handed from the camera screen to the background pipeline. */
class CaptureJob(
    val id: Long,
    val frames: List<CapturedFrame>,     // one frame, or the HDR burst to merge
    val mode: CameraMode,
    val front: Boolean,
    val settings: AppSettings,
    val baselineEv: Float = 0f,
    val manualWarmth: Float = 0f,        // residual WB from Pro mode Kelvin between presets
)

data class CaptureResult(
    val id: Long,
    val thumbnail: Bitmap?,
    val uri: Uri? = null,
    val report: EngineReport? = null,
    val error: String? = null,
)

/**
 * Background photo pipeline: decode -> orient/mirror/crop -> (HDR burst merge) -> faces
 * and person mask (ML Kit, on device) -> Natural engine -> encode (JPEG/HEIF + EXIF) ->
 * save the developed photo to the gallery. Jobs run one at a time so the camera stays
 * responsive while a photo develops.
 */
class CapturePipeline(private val context: Context, private val media: MediaRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<CaptureJob>(capacity = 3)
    private val _results = MutableSharedFlow<CaptureResult>(extraBufferCapacity = 8)
    val results: SharedFlow<CaptureResult> = _results
    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight
    private val engine = NaturalEngine()
    private val workDir = File(context.cacheDir, "develop").apply { mkdirs() }

    init {
        workDir.listFiles()?.forEach { it.delete() }
        scope.launch {
            for (job in queue) {
                val r = try {
                    process(job)
                } catch (t: Throwable) {
                    Log.e(TAG, "processing failed", t)
                    CaptureResult(job.id, null, error = t.message ?: t.javaClass.simpleName)
                } finally {
                    _inFlight.update { it - 1 }
                }
                _results.emit(r)
            }
        }
    }

    fun submit(job: CaptureJob): Boolean {
        val ok = queue.trySend(job).isSuccess
        if (ok) _inFlight.update { it + 1 }
        return ok
    }

    private fun paramsFor(job: CaptureJob, iso: Int, merged: Int): ProcessParams {
        val s = job.settings
        // Skin smoothing on both cameras in every mode except Pro (same rule as the viewfinder).
        val beautyAllowed = job.mode != CameraMode.PRO
        return ProcessParams(
            profile = s.profile,
            profileIntensity = s.profileIntensity,
            hdrMode = s.hdr,
            tuning = if (job.front) CameraTuning.FRONT else CameraTuning.REAR,
            beauty = BeautySettings(
                smoothing = if (beautyAllowed) s.beautySmoothing else 0f,
                evenTone = if (beautyAllowed) s.beautyEvenTone else 0f,
                faceLight = s.faceLight,
            ),
            detailLevel = s.detail.factor,
            noiseReductionLevel = s.noiseReduction.factor,
            faceExposure = s.faceExposure,
            iso = iso,
            mergedFrames = merged,
            baselineEv = job.baselineEv,
            portraitBlur = if (job.mode == CameraMode.PORTRAIT) s.portraitBlur else 0f,
            adjust = if (job.manualWarmth != 0f) UserAdjust(warmth = job.manualWarmth) else UserAdjust.NONE,
        )
    }

    private fun aspectOf(s: AppSettings): Float? = when (s.aspect) {
        AspectSetting.R1_1 -> 1f
        AspectSetting.FULL -> s.aspect.ratio
        else -> null // 4:3 and 16:9 come straight from the sensor stream
    }

    private suspend fun process(job: CaptureJob): CaptureResult {
        val s = job.settings
        val mirror = job.front && s.mirrorFront
        val aspect = aspectOf(s)
        val first = job.frames.first()
        val iso = AndroidImaging.isoOf(first.jpeg)

        // 1. Decode (and merge an HDR burst) into a mutable working bitmap.
        var merged = 1
        val work: Bitmap = if (job.frames.size > 1) {
            val bitmaps = job.frames.map { AndroidImaging.decodeUpright(it.jpeg, it.rotationDegrees, mirror, aspect) }
            val out = Bitmap.createBitmap(bitmaps[0].width, bitmaps[0].height, Bitmap.Config.ARGB_8888)
            val sigma = SceneAnalyzer.noiseSigma8(iso, null, 1)
            val res = MultiFrameMerger.merge(bitmaps.map { BitmapPixelBuffer(it) }, BitmapPixelBuffer(out), sigma, Runtime.getRuntime().availableProcessors())
            merged = Math.round(res.effectiveFrames).coerceAtLeast(1)
            bitmaps.forEach { it.recycle() }
            out
        } else AndroidImaging.decodeUpright(first.jpeg, first.rotationDegrees, mirror, aspect)
        val w = work.width
        val h = work.height

        // 2. Analysis image, faces and (portrait) person mask.
        val f = Resample.factorFor(w, h, NaturalEngine.ANALYSIS_SIDE)
        val input = BitmapPixelBuffer(work)
        val analysis = Resample.boxDownscale(input, f)
        val needFaces = s.faceExposure || s.beautySmoothing > 0f || s.beautyEvenTone > 0f || job.mode == CameraMode.PORTRAIT
        val needMask = job.mode == CameraMode.PORTRAIT && s.portraitBlur > 0f
        var faces = emptyList<com.tamimrijan.camora.engine.FaceInfo>()
        var mask: com.tamimrijan.camora.engine.FloatPlane? = null
        if (needFaces || needMask) {
            val analysisBitmap = analysis.toBitmap()
            // Face detection and person segmentation are independent ML Kit models: run them
            // at the same time instead of one after the other.
            kotlinx.coroutines.coroutineScope {
                val fd = if (needFaces) async { AndroidImaging.detectFaces(analysisBitmap, f.toFloat()) } else null
                val sd = if (needMask) async { AndroidImaging.segmentPerson(analysisBitmap) } else null
                faces = fd?.await() ?: emptyList()
                mask = sd?.await()
            }
            analysisBitmap.recycle()
            mask?.let { com.tamimrijan.camora.engine.FaceRegions.protectFaces(it, faces, f.toFloat()) }
        }

        // 3. Natural engine.
        val params = paramsFor(job, iso, merged)
        val lut = LutBuilder.forProfile(params.profile, params.adjust)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val report = engine.process(NaturalEngine.Input(input, analysis, f, faces, mask), BitmapPixelBuffer(output), params, lut)
        work.recycle()

        // 4. Encode and save the developed photo.
        val desc = "Camora ${s.profile.label} ${(s.profileIntensity * 100).toInt()}%, HDR ${s.hdr.name.lowercase()}" +
            (if (merged > 1) ", ${job.frames.size} frames merged" else "") + ", ${report.summary()}"
        var format = s.format
        var file = File(workDir, "${job.id}.${format.ext}")
        if (format == PhotoFormat.HEIF && !AndroidImaging.writeHeif(output, s.jpegQuality, file)) {
            format = PhotoFormat.JPEG
            file = File(workDir, "${job.id}.jpg")
        }
        if (format == PhotoFormat.JPEG) {
            AndroidImaging.writeJpeg(output, s.jpegQuality, file)
            AndroidImaging.copyExif(first.jpeg, file, w, h, desc)
        }
        val thumb = AndroidImaging.thumbnail(output, 256)
        output.recycle()
        val uri = try {
            media.saveFile(file, MediaRepository.newName() + "." + format.ext, format.mime)
        } finally {
            file.delete()
        }
        return CaptureResult(job.id, thumb, uri, report)
    }

    companion object {
        private const val TAG = "CapturePipeline"
    }
}
