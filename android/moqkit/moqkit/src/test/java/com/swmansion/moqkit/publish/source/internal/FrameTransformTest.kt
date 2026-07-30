package com.swmansion.moqkit.publish.source.internal

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameTransformTest {

    private val identityMatrix = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    // 90° rotation (with the usual translation), as SurfaceTexture reports for a
    // portrait-held camera.
    private val rotation90Matrix = floatArrayOf(
        0f, 1f, 0f, 0f,
        -1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        1f, 0f, 0f, 1f,
    )

    private val rotation270Matrix = floatArrayOf(
        0f, -1f, 0f, 0f,
        1f, 0f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 1f, 0f, 1f,
    )

    @Test
    fun fillCropScale_portraitSourceIntoLandscapeTarget_cropsVertically() {
        // The reported bug: an upright portrait frame (1080x1920) encoded to 1280x720.
        // Uniform scale => width fits exactly, height overflows and is cropped.
        val scale = FrameTransform.fillCropScale(srcWidth = 1080, srcHeight = 1920, dstWidth = 1280, dstHeight = 720)
        assertEquals(1f, scale[0], EPS)
        assertTrue("height must overflow the target for center-crop", scale[1] > 1f)
        assertUniformScale(scale, srcW = 1080, srcH = 1920, dstW = 1280, dstH = 720)
    }

    @Test
    fun fillCropScale_landscapeSourceIntoPortraitTarget_cropsHorizontally() {
        val scale = FrameTransform.fillCropScale(srcWidth = 1920, srcHeight = 1080, dstWidth = 1080, dstHeight = 1920)
        assertEquals(1f, scale[1], EPS)
        assertTrue("width must overflow the target for center-crop", scale[0] > 1f)
        assertUniformScale(scale, srcW = 1920, srcH = 1080, dstW = 1080, dstH = 1920)
    }

    @Test
    fun fillCropScale_sameAspectRatio_drawsFullscreen() {
        val scale = FrameTransform.fillCropScale(srcWidth = 1920, srcHeight = 1080, dstWidth = 1280, dstHeight = 720)
        assertArrayEquals(floatArrayOf(1f, 1f), scale, EPS)
    }

    @Test
    fun fillCropScale_degenerateSizes_fallsBackToStretch() {
        assertArrayEquals(
            floatArrayOf(1f, 1f),
            FrameTransform.fillCropScale(srcWidth = 0, srcHeight = 1920, dstWidth = 1280, dstHeight = 720),
            EPS,
        )
        assertArrayEquals(
            floatArrayOf(1f, 1f),
            FrameTransform.fillCropScale(srcWidth = 1080, srcHeight = 1920, dstWidth = 0, dstHeight = 720),
            EPS,
        )
    }

    @Test
    fun isTransposed_detectsAxisSwap() {
        assertFalse(FrameTransform.isTransposed(identityMatrix))
        assertTrue(FrameTransform.isTransposed(rotation90Matrix))
        assertTrue(FrameTransform.isTransposed(rotation270Matrix))
        assertFalse(FrameTransform.isTransposed(FloatArray(4)))
    }

    @Test
    fun uprightSize_swapsOnlyWhenTransposed() {
        assertArrayEquals(intArrayOf(1920, 1080), FrameTransform.uprightSize(1920, 1080, transposed = false))
        assertArrayEquals(intArrayOf(1080, 1920), FrameTransform.uprightSize(1920, 1080, transposed = true))
    }

    // AND-V42-002 orientation-aware fixes -------------------------------------

    @Test
    fun fillCropScale_portraitSourceIntoPortraitTarget_keepsFullFov() {
        // The v4.3 fix's goal: portrait phone (upright 1080x1920) encoded to a
        // portrait 720x1280 target must fill the frame with NO crop and NO zoom.
        val scale = FrameTransform.fillCropScale(srcWidth = 1080, srcHeight = 1920, dstWidth = 720, dstHeight = 1280)
        assertArrayEquals(floatArrayOf(1f, 1f), scale, EPS)
    }

    @Test
    fun isTransposed_frontCameraMirrorPlusRotation() {
        // Front cameras combine the 90° rotation with a mirror; the first
        // column still points along V, so the transposition is detected.
        val mirroredRotation = floatArrayOf(
            0f, 1f, 0f, 0f,
            1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        assertTrue(FrameTransform.isTransposed(mirroredRotation))
    }

    @Test
    fun isTransposed_cropInsetOnlyMatrixIsNotTransposed() {
        // A typical HAL crop-inset (shrink + translate, no rotation) must not
        // register as transposed.
        val cropInset = floatArrayOf(
            0.97f, 0f, 0f, 0f,
            0f, -0.97f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0.015f, 0.985f, 0f, 1f,
        )
        assertFalse(FrameTransform.isTransposed(cropInset))
    }

    @Test
    fun positionRotation_mapsNdcAxesPerDisplayRotation() {
        // Column-major 2x2: (x,y) -> (m[0]x + m[2]y, m[1]x + m[3]y).
        fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
            Pair(m[0] * x + m[2] * y, m[1] * x + m[3] * y)

        val identity = FrameTransform.positionRotation(0)
        assertEquals(Pair(1f, 0f), apply(identity, 1f, 0f))
        assertEquals(Pair(0f, 1f), apply(identity, 0f, 1f))

        // ROTATION_90 (device turned CCW): content top must move from NDC +y
        // (device top, now pointing world-left) to NDC +x (device right, now
        // pointing world-up) => clockwise quad rotation.
        val rot90 = FrameTransform.positionRotation(90)
        assertEquals(Pair(1f, 0f), apply(rot90, 0f, 1f))
        assertEquals(Pair(0f, -1f), apply(rot90, 1f, 0f))

        val rot180 = FrameTransform.positionRotation(180)
        assertEquals(Pair(0f, -1f), apply(rot180, 0f, 1f))
        assertEquals(Pair(-1f, 0f), apply(rot180, 1f, 0f))

        // ROTATION_270 (device turned CW): content top moves to NDC -x
        // (device left, now pointing world-up) => counter-clockwise rotation.
        val rot270 = FrameTransform.positionRotation(270)
        assertEquals(Pair(-1f, 0f), apply(rot270, 0f, 1f))
        assertEquals(Pair(0f, 1f), apply(rot270, 1f, 0f))

        // Normalization: negative and >=360 inputs reuse the same quadrant.
        assertArrayEquals(rot270, FrameTransform.positionRotation(-90), EPS)
        assertArrayEquals(rot90, FrameTransform.positionRotation(450), EPS)
    }

    @Test
    fun positionRotation_roundTripsThroughInverseRotations() {
        fun apply(m: FloatArray, p: Pair<Float, Float>): Pair<Float, Float> =
            Pair(m[0] * p.first + m[2] * p.second, m[1] * p.first + m[3] * p.second)

        val corners = listOf(Pair(1f, 1f), Pair(-1f, 1f), Pair(1f, -1f), Pair(-1f, -1f))
        for ((a, b) in listOf(Pair(90, 270), Pair(180, 180), Pair(270, 90))) {
            val ma = FrameTransform.positionRotation(a)
            val mb = FrameTransform.positionRotation(b)
            for (corner in corners) {
                assertEquals("rot$a then rot$b must round-trip $corner", corner, apply(mb, apply(ma, corner)))
            }
        }
    }

    @Test
    fun rotatedTargetSize_swapsAxesForQuarterTurns() {
        assertArrayEquals(intArrayOf(1280, 720), FrameTransform.rotatedTargetSize(1280, 720, 0))
        assertArrayEquals(intArrayOf(720, 1280), FrameTransform.rotatedTargetSize(1280, 720, 90))
        assertArrayEquals(intArrayOf(1280, 720), FrameTransform.rotatedTargetSize(1280, 720, 180))
        assertArrayEquals(intArrayOf(720, 1280), FrameTransform.rotatedTargetSize(1280, 720, 270))
        assertArrayEquals(intArrayOf(720, 1280), FrameTransform.rotatedTargetSize(1280, 720, -90))
    }

    /**
     * Characterization of the FULL renderer composition (targetScale +
     * positionRotation as GlFanOutRenderer applies them) for the four retest
     * cells: the final drawn NDC box must cover the whole target exactly for
     * orientation-matched cells, and the crop must land on the expected axis
     * for the mismatch cell. Guards the composition, not just the leaves.
     */
    @Test
    fun composition_drawnBoxPerRetestCell() {
        fun drawnBox(srcW: Int, srcH: Int, transposed: Boolean, rotation: Int, dstW: Int, dstH: Int): FloatArray {
            val upright = FrameTransform.uprightSize(srcW, srcH, transposed)
            val dstNat = FrameTransform.rotatedTargetSize(dstW, dstH, rotation)
            val s = FrameTransform.fillCropScale(upright[0], upright[1], dstNat[0], dstNat[1])
            val r = FrameTransform.positionRotation(rotation)
            // Half-extents of the rotated scaled quad: |R * diag(s) * (1,1)|.
            val x = kotlin.math.abs(r[0] * s[0] + r[2] * s[1])
            val y = kotlin.math.abs(r[1] * s[0] + r[3] * s[1])
            return floatArrayOf(x, y)
        }

        // Portrait phone, portrait encode (the AND-V42-002 fix): exact cover.
        assertArrayEquals(floatArrayOf(1f, 1f), drawnBox(1920, 1080, true, 0, 720, 1280), EPS)
        // Landscape-held phone (ROTATION_90), landscape encode: exact cover.
        assertArrayEquals(floatArrayOf(1f, 1f), drawnBox(1920, 1080, true, 90, 1280, 720), EPS)
        // Reverse landscape (ROTATION_270): same exact cover.
        assertArrayEquals(floatArrayOf(1f, 1f), drawnBox(1920, 1080, true, 270, 1280, 720), EPS)
        // Orientation mismatch (portrait source forced into a landscape
        // encode, the v4.2 defect shape): width fits exactly, the vertical
        // overflow (~3.16x) is what the clip volume crops.
        val mismatch = drawnBox(1920, 1080, true, 0, 1280, 720)
        assertEquals(1f, mismatch[0], EPS)
        assertEquals(1920f * (1280f / 1080f) / 720f, mismatch[1], 1e-3f)
    }

    @Test
    fun landscapeHeldPhone_fullFovThroughNaturalFrameScale() {
        // Galaxy S24 held landscape (ROTATION_90), orientation-aware encode
        // 1280x720: scale computed in the NATURAL frame must be [1,1] — full
        // FOV, no crop — because the rotated target matches the upright source.
        val uprightNat = FrameTransform.uprightSize(1920, 1080, transposed = true) // 1080x1920
        val dstNat = FrameTransform.rotatedTargetSize(1280, 720, 90) // 720x1280
        val scale = FrameTransform.fillCropScale(uprightNat[0], uprightNat[1], dstNat[0], dstNat[1])
        assertArrayEquals(floatArrayOf(1f, 1f), scale, EPS)
    }

    /**
     * The drawn content (scale * target) must have the source's aspect ratio:
     * a single uniform scale factor is applied on both axes.
     */
    private fun assertUniformScale(scale: FloatArray, srcW: Int, srcH: Int, dstW: Int, dstH: Int) {
        val drawnAspect = (scale[0] * dstW) / (scale[1] * dstH)
        val srcAspect = srcW.toFloat() / srcH
        assertEquals("drawn content must keep the source aspect ratio", srcAspect, drawnAspect, EPS)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
