package com.tamimrijan.camora.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitFaceTest {
    @Test fun faceHoleInPersonMaskIsFilled() {
        // 100x100 mask (scale 4 => 400x400 photo). Person body fills the lower half, but the
        // segmenter missed the face completely (a hole), e.g. sunglasses or a hat.
        val mask = FloatPlane(100, 100)
        for (y in 55 until 100) for (x in 30 until 70) mask[x, y] = 1f
        val face = FaceInfo(160f, 80f, 240f, 200f) // full res: centre (200,140) => mask (50,35)
        assertEquals(0f, mask[50, 35], 0f)

        FaceRegions.protectFaces(mask, listOf(face), scale = 4f)

        assertTrue("face centre must be kept sharp", mask[50, 35] > 0.95f)
        assertEquals("body untouched", 1f, mask[50, 80], 0f)
        assertEquals("background untouched", 0f, mask[5, 5], 0f)
        assertEquals("background beside the face untouched", 0f, mask[90, 35], 0f)
    }

    @Test fun noFacesLeavesMaskAlone() {
        val mask = FloatPlane(10, 10)
        mask[3, 3] = 0.4f
        FaceRegions.protectFaces(mask, emptyList(), 4f)
        assertEquals(0.4f, mask[3, 3], 0f)
    }
}