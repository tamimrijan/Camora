package com.tamimrijan.camora.camera

import android.annotation.SuppressLint
import android.graphics.PointF
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.tamimrijan.camora.gl.NaturalPreviewProcessor
import com.tamimrijan.camora.gl.PreviewFace
import com.tamimrijan.camora.gl.PreviewFaceSet
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot
import kotlin.math.max

/**
 * Low-resolution (≈640x480) on-device analysis of the live stream:
 *  - ML Kit face detection (fast mode) for face-priority metering and, with landmarks,
 *    for the live skin/face-light preview in the viewfinder shader
 *  - ML Kit selfie segmentation (stream mode) for the live portrait blur preview
 * Everything stays on the device; ML Kit models are bundled in the APK.
 */
class LiveAnalyzer(
    private val faces: Boolean,
    private val landmarks: Boolean,
    private val segmentation: Boolean,
    private val processor: NaturalPreviewProcessor?,
    /** face centres in camera-buffer pixels: [x, y, area] */
    private val onFaces: (List<FloatArray>, Int, Int) -> Unit,
) : ImageAnalysis.Analyzer {

    private val faceDetector = if (faces) FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(if (landmarks) FaceDetectorOptions.LANDMARK_MODE_ALL else FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setMinFaceSize(0.1f)
            .build(),
    ) else null

    private val segmenter = if (segmentation && processor != null) Segmentation.getClient(
        SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .enableRawSizeMask()
            .build(),
    ) else null

    private val interval = if (landmarks) 110L else 250L
    private var lastFace = 0L
    @Volatile private var closed = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (closed || media == null) {
            image.close(); return
        }
        val rot = image.imageInfo.rotationDegrees
        val bw = image.width
        val bh = image.height
        val input = InputImage.fromMediaImage(media, rot)
        val tasks = ArrayList<Task<*>>(2)
        val now = SystemClock.elapsedRealtime()
        if (faceDetector != null && now - lastFace > interval) {
            lastFace = now
            val ts = image.imageInfo.timestamp
            tasks += faceDetector.process(input).addOnSuccessListener { list ->
                val centers = list.map { f ->
                    val b = f.boundingBox
                    val c = uprightToBuffer(b.exactCenterX(), b.exactCenterY(), rot, bw, bh)
                    floatArrayOf(c[0], c[1], (b.width() * b.height()).toFloat())
                }
                onFaces(centers, bw, bh)
                processor?.submitFaces(
                    PreviewFaceSet(
                        list.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.take(2).map { previewFace(it, rot, bw, bh) },
                        bh.toFloat() / bw, ts,
                    ),
                )
            }
        }
        if (segmenter != null) {
            tasks += segmenter.process(input).addOnSuccessListener { mask ->
                val w = mask.width
                val h = mask.height
                val src = mask.buffer
                src.rewind()
                val fb = src.order(ByteOrder.nativeOrder()).asFloatBuffer()
                val bytes = ByteBuffer.allocateDirect(w * h)
                for (i in 0 until w * h) bytes.put((fb.get(i).coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte())
                bytes.position(0)
                processor?.submitMask(NaturalPreviewProcessor.MaskFrame(w, h, bytes, rot, image.imageInfo.timestamp))
            }
        }
        if (tasks.isEmpty()) image.close()
        else Tasks.whenAllComplete(tasks).addOnCompleteListener { image.close() }
    }

    fun close() {
        closed = true
        faceDetector?.close()
        segmenter?.close()
    }

    companion object {
        /** ML Kit returns coordinates in the upright (rotated) image; the GPU and metering need buffer coordinates. */
        fun uprightToBuffer(u: Float, v: Float, rot: Int, bw: Int, bh: Int): FloatArray = when ((rot % 360 + 360) % 360) {
            90 -> floatArrayOf(v, bh - u)
            180 -> floatArrayOf(bw - u, bh - v)
            270 -> floatArrayOf(bw - v, u)
            else -> floatArrayOf(u, v)
        }

        /**
         * Converts an ML Kit face (upright coordinates) into the shader's q-space geometry,
         * using the same face proportions as the capture engine (FaceRegions).
         */
        fun previewFace(f: Face, rot: Int, bw: Int, bh: Int): PreviewFace {
            val b = f.boundingBox
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            val swap = (rot % 180 + 180) % 180 == 90
            fun q(u: Float, v: Float): FloatArray {
                val p = uprightToBuffer(u, v, rot, bw, bh)
                return floatArrayOf(p[0] / bw, p[1] / bw)
            }
            fun lm(type: Int, fu: Float, fv: Float): PointF = f.getLandmark(type)?.position ?: PointF(b.left + w * fu, b.top + h * fv)
            val c = q(b.exactCenterX(), b.exactCenterY() - h * 0.04f)
            val rxU = w * 0.62f
            val ryU = h * 0.82f
            val le = lm(FaceLandmark.LEFT_EYE, 0.68f, 0.4f)
            val re = lm(FaceLandmark.RIGHT_EYE, 0.32f, 0.4f)
            val mouthB = f.getLandmark(FaceLandmark.MOUTH_BOTTOM)?.position
            val mouthL = f.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val mouthR = f.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position
            val mouth = if (mouthL != null && mouthR != null) {
                PointF((mouthL.x + mouthR.x) / 2f, ((mouthL.y + mouthR.y) / 2f + (mouthB?.y ?: mouthL.y)) / 2f)
            } else PointF(b.exactCenterX(), b.top + h * 0.78f)
            val leQ = q(le.x, le.y)
            val reQ = q(re.x, re.y)
            val mQ = q(mouth.x, mouth.y)
            val eyeDist = max(w * 0.2f, hypot(le.x - re.x, le.y - re.y)) / bw
            val c1 = q(b.left.toFloat(), b.top.toFloat())
            val c2 = q(b.right.toFloat(), b.bottom.toFloat())
            return PreviewFace(
                c[0], c[1], (if (swap) ryU else rxU) / bw, (if (swap) rxU else ryU) / bw,
                leQ[0], leQ[1], reQ[0], reQ[1], mQ[0], mQ[1], eyeDist,
                minOf(c1[0], c2[0]), minOf(c1[1], c2[1]), maxOf(c1[0], c2[0]), maxOf(c1[1], c2[1]),
            )
        }
    }
}
