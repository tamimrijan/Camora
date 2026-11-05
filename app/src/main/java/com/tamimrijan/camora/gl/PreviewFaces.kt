package com.tamimrijan.camora.gl

/**
 * A face as the viewfinder shader needs it, in "q-space": camera-buffer pixels divided by
 * the buffer width (so both axes use the same unit and circles stay round).
 */
class PreviewFace(
    val cx: Float, val cy: Float,      // face ellipse centre
    val rx: Float, val ry: Float,      // face ellipse radii
    val lx: Float, val ly: Float,      // left eye
    val rx2: Float, val ry2: Float,    // right eye
    val mx: Float, val my: Float,      // mouth
    val eyeDist: Float,
    val boxL: Float, val boxT: Float, val boxR: Float, val boxB: Float,
) {
    val width get() = boxR - boxL

    fun toArray(): FloatArray = floatArrayOf(cx, cy, rx, ry, lx, ly, rx2, ry2, mx, my, eyeDist, boxL, boxT, boxR, boxB)

    companion object {
        const val FIELDS = 15
        fun fromArray(a: FloatArray, o: Int) = PreviewFace(
            a[o], a[o + 1], a[o + 2], a[o + 3], a[o + 4], a[o + 5], a[o + 6], a[o + 7], a[o + 8], a[o + 9], a[o + 10],
            a[o + 11], a[o + 12], a[o + 13], a[o + 14],
        )
    }
}

/** Faces from one analysis frame. [qy] = buffer height / width. */
class PreviewFaceSet(val faces: List<PreviewFace>, val qy: Float, val timeNs: Long)
