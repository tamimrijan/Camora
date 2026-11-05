package com.tamimrijan.camora.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.tamimrijan.camora.engine.FaceInfo
import com.tamimrijan.camora.engine.FloatPlane
import com.tamimrijan.camora.engine.PixelBuffer
import com.tamimrijan.camora.engine.Pt
import com.tamimrijan.camora.engine.RgbImage
import com.tamimrijan.camora.util.awaitResult
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteOrder

/** PixelBuffer over a Bitmap. Pixel data lives in native memory, so 50 MP frames are fine. */
class BitmapPixelBuffer(val bitmap: Bitmap) : PixelBuffer {
    override val width = bitmap.width
    override val height = bitmap.height
    private val lock = Any()

    override fun readRows(y: Int, rows: Int, dst: IntArray, offset: Int) =
        synchronized(lock) { bitmap.getPixels(dst, offset, width, 0, y, width, rows) }

    override fun writeRows(y: Int, rows: Int, src: IntArray, offset: Int) =
        synchronized(lock) { bitmap.setPixels(src, offset, width, 0, y, width, rows) }

    override fun readRect(x: Int, y: Int, w: Int, h: Int, dst: IntArray) =
        synchronized(lock) { bitmap.getPixels(dst, 0, w, x, y, w, h) }

    override fun writeRect(x: Int, y: Int, w: Int, h: Int, src: IntArray) =
        synchronized(lock) { bitmap.setPixels(src, 0, w, x, y, w, h) }
}

object AndroidImaging {
    fun RgbImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

    /** EXIF orientation of a JPEG, or null if the tag is missing. */
    fun exifOrientation(jpeg: ByteArray): Int? = runCatching {
        val o = ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        o.takeIf { it != ExifInterface.ORIENTATION_UNDEFINED }
    }.getOrNull()

    fun orientationFromDegrees(deg: Int): Int = when ((deg % 360 + 360) % 360) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    /** Matrix that turns stored pixels into the upright image for an EXIF orientation. */
    fun orientationMatrix(orientation: Int, w: Int, h: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> {}
        }
    }

    /**
     * Decodes a camera JPEG into a mutable, upright, optionally mirrored and cropped
     * ARGB_8888 bitmap. [aspect] (w/h of the final frame, in upright orientation) crops
     * the centre, e.g. 1.0 for square.
     */
    fun decodeUpright(jpeg: ByteArray, rotationDegrees: Int, mirror: Boolean, aspect: Float?, sampleSize: Int = 1): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: error("JPEG decode failed")
        val orient = exifOrientation(jpeg) ?: orientationFromDegrees(rotationDegrees)
        val m = orientationMatrix(orient, src.width, src.height)
        if (mirror) m.postScale(-1f, 1f)
        val r = android.graphics.RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())
        m.mapRect(r)
        val uw = Math.round(r.width())
        val uh = Math.round(r.height())
        // centre crop to the requested aspect
        var cw = uw
        var ch = uh
        if (aspect != null && aspect > 0f) {
            val cur = uw.toFloat() / uh
            val target = if (uw >= uh) maxOf(aspect, 1f / aspect) else minOf(aspect, 1f / aspect)
            if (cur > target + 1e-3f) cw = Math.round(uh * target)
            else if (cur < target - 1e-3f) ch = Math.round(uw / target)
        }
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        m.postTranslate(-r.left - (uw - cw) / 2f, -r.top - (uh - ch) / 2f)
        // No filtering: 90° rotations and mirroring map pixels exactly.
        Canvas(out).drawBitmap(src, m, Paint(0))
        src.recycle()
        return out
    }

    // ---- ML Kit (on-device) ---------------------------------------------------------------
    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.06f)
                .build(),
        )
    }

    private val segmenter by lazy {
        Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build())
    }

    /** Faces in [bitmap] coordinates, scaled by [scale]. */
    suspend fun detectFaces(bitmap: Bitmap, scale: Float): List<FaceInfo> {
        val faces = runCatching { faceDetector.process(InputImage.fromBitmap(bitmap, 0)).awaitResult() }.getOrElse { return emptyList() }
        return faces.map { f ->
            val b = f.boundingBox
            fun lm(type: Int) = f.getLandmark(type)?.position?.let { Pt(it.x * scale, it.y * scale) }
            FaceInfo(
                b.left * scale, b.top * scale, b.right * scale, b.bottom * scale,
                lm(FaceLandmark.LEFT_EYE), lm(FaceLandmark.RIGHT_EYE),
                lm(FaceLandmark.MOUTH_LEFT), lm(FaceLandmark.MOUTH_RIGHT), lm(FaceLandmark.MOUTH_BOTTOM), lm(FaceLandmark.NOSE_BASE),
            )
        }
    }

    /** Person mask (0..1) at the bitmap's resolution, or null if nobody was found. */
    suspend fun segmentPerson(bitmap: Bitmap): FloatPlane? {
        val mask = runCatching { segmenter.process(InputImage.fromBitmap(bitmap, 0)).awaitResult() }.getOrNull() ?: return null
        val w = mask.width
        val h = mask.height
        val fb = mask.buffer.apply { rewind() }.order(ByteOrder.nativeOrder()).asFloatBuffer()
        val data = FloatArray(w * h) { fb.get(it) }
        val coverage = data.count { it > 0.5f }.toFloat() / data.size
        if (coverage < 0.01f) return null
        return FloatPlane(w, h, data)
    }

    // ---- Encoding -------------------------------------------------------------------------
    fun writeJpeg(bitmap: Bitmap, quality: Int, file: File) {
        file.outputStream().buffered(1 shl 16).use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    /** HEIF via the hardware HEVC encoder. Returns false if the device cannot encode HEIF. */
    fun writeHeif(bitmap: Bitmap, quality: Int, file: File): Boolean = try {
        val w = androidx.heifwriter.HeifWriter.Builder(
            file.absolutePath, bitmap.width, bitmap.height, androidx.heifwriter.HeifWriter.INPUT_MODE_BITMAP,
        ).setQuality(quality).setMaxImages(1).build()
        w.start()
        w.addBitmap(bitmap)
        w.stop(10_000)
        w.close()
        file.length() > 0
    } catch (t: Throwable) {
        file.delete()
        false
    }

    private val EXIF_TAGS = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_EXPOSURE_TIME,
        ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_APERTURE_VALUE,
        ExifInterface.TAG_SHUTTER_SPEED_VALUE, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
    )

    /** Copies camera EXIF into an encoded JPEG and marks it upright. */
    fun copyExif(fromJpeg: ByteArray, toFile: File, width: Int, height: Int, description: String?) {
        runCatching {
            val src = ExifInterface(ByteArrayInputStream(fromJpeg))
            val dst = ExifInterface(toFile.absolutePath)
            for (t in EXIF_TAGS) src.getAttribute(t)?.let { dst.setAttribute(t, it) }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            dst.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "Camora")
            if (description != null) dst.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, description)
            dst.saveAttributes()
        }
    }

    fun isoOf(jpeg: ByteArray): Int = runCatching {
        ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
    }.getOrDefault(0)

    fun thumbnail(bitmap: Bitmap, maxSide: Int): Bitmap {
        val s = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (s >= 1f) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        return Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * s).toInt()), maxOf(1, (bitmap.height * s).toInt()), true)
    }

    @Suppress("unused")
    private fun Rect.area() = width() * height()
}
