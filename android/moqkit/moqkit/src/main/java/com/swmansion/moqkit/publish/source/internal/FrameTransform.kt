package com.swmansion.moqkit.publish.source.internal

import kotlin.math.abs
import kotlin.math.max

/**
 * Aspect-ratio math for mapping a captured frame onto a render target.
 *
 * The GL fan-out renderer draws the camera/screen texture as a fullscreen quad. The
 * SurfaceTexture transform matrix already rotates the frame upright (e.g. 90° for a
 * portrait-held camera), so the content arriving at a target can have a very different
 * aspect ratio than the target itself. Stretching that quad independently in X and Y
 * distorts the picture (the "horizontally stretched" portrait video bug); instead the
 * quad is scaled by a single uniform factor so the source fills the target and the
 * overflow is center-cropped by the clip volume.
 *
 * Preview and encoder targets both use these helpers, so they always apply the same
 * crop and rotation. Pure functions, unit-tested on the JVM.
 */
internal object FrameTransform {

    /**
     * True when a SurfaceTexture transform matrix swaps the frame axes (a 90°/270°
     * rotation). The first column of the matrix is the image of the texture U axis;
     * when it points mostly along V, the axes are transposed.
     */
    fun isTransposed(matrix: FloatArray): Boolean {
        if (matrix.size < 16) return false
        return abs(matrix[1]) > abs(matrix[0])
    }

    /**
     * Frame size as displayed upright, given the raw buffer size and whether the
     * transform matrix transposes it.
     */
    fun uprightSize(bufferWidth: Int, bufferHeight: Int, transposed: Boolean): IntArray =
        if (transposed) {
            intArrayOf(bufferHeight, bufferWidth)
        } else {
            intArrayOf(bufferWidth, bufferHeight)
        }

    /**
     * NDC scale factors `[sx, sy]` that draw the upright source over the target with
     * one uniform scale factor (aspect preserved), covering the target fully and
     * center-cropping the overflow. Each factor is >= 1; the axis whose scaled content
     * exactly matches the target is 1, the other overflows and gets clipped.
     *
     * Returns `[1, 1]` (the legacy stretch) for degenerate sizes.
     */
    fun fillCropScale(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): FloatArray {
        if (srcWidth <= 0 || srcHeight <= 0 || dstWidth <= 0 || dstHeight <= 0) {
            return floatArrayOf(1f, 1f)
        }
        val scale = max(
            dstWidth.toFloat() / srcWidth,
            dstHeight.toFloat() / srcHeight,
        )
        return floatArrayOf(
            srcWidth * scale / dstWidth,
            srcHeight * scale / dstHeight,
        )
    }

    /**
     * Column-major 2x2 NDC POSITION rotation compensating a display rotation.
     *
     * Rotating vertex POSITIONS (after texturing) rather than texture
     * coordinates is deliberate: position space is downstream of the texture
     * matrix, so the front camera's in-matrix mirror cannot silently reverse a
     * texture-space rotation.
     *
     * DIRECTION IS DEVICE-VERIFIED, NOT DERIVED (AND-V46-001, 08-04 실기기).
     * The v4.6 orient[map] probe captured the full landscape truth table on
     * SM-S921N — all four cells, both facings, fresh publishes:
     *
     *   rear/90   applied CW90  -> 180° flipped   => correct is CCW90
     *   rear/270  applied CCW90 -> 180° flipped   => correct is CW90
     *   front/90  applied CCW90 -> world-upright  => CCW90 confirmed
     *   front/270 applied CW90  -> world-upright  => CW90 confirmed
     *
     * So the correct compensation is FACING-INDEPENDENT: 90 => CCW90,
     * 270 => CW90 — the v4.3-derived base table simply had the direction
     * inverted, and v4.6's front-only negation had landed the front cells on
     * the correct values by construction. This table now IS those verified
     * values for every source; the earlier facing negation is gone (the 08-03
     * front observations that motivated it were mislabeled cells). 180 is its
     * own inverse, so only the quarter turns changed.
     */
    fun positionRotation(displayRotationDegrees: Int): FloatArray =
        when (normalizedRotation(displayRotationDegrees)) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f) // (x,y) -> (-y, x): counter-clockwise 90
            180 -> floatArrayOf(-1f, 0f, 0f, -1f) // (x,y) -> (-x, -y)
            270 -> floatArrayOf(0f, -1f, 1f, 0f) // (x,y) -> (y, -x): clockwise 90
            else -> floatArrayOf(1f, 0f, 0f, 1f)
        }

    /**
     * Determinant of the texture matrix's 2x2 linear part (column-major 4x4:
     * indices 0,1 = image of U, 4,5 = image of V). Negative = the matrix
     * contains a reflection. Diagnostic only (the orient[map] probe): the
     * standard camera SurfaceTexture matrix already contains a V-flip (GL
     * bottom-left origin vs image top-left), so the SIGN alone does not
     * identify the front camera's user-facing mirror — facing is plumbed
     * explicitly instead of inferred from this.
     */
    fun matrixDet2x2(matrix: FloatArray): Float {
        if (matrix.size < 16) return 0f
        return matrix[0] * matrix[5] - matrix[1] * matrix[4]
    }

    /**
     * Rotation-compensated target size: the fill-crop scale is computed in the
     * device's NATURAL frame (where the SurfaceTexture matrix orients content
     * upright), so a 90/270 display rotation swaps the target's axes first and
     * the position rotation swaps them back when drawing.
     */
    fun rotatedTargetSize(dstWidth: Int, dstHeight: Int, displayRotationDegrees: Int): IntArray =
        if (normalizedRotation(displayRotationDegrees) % 180 == 90) {
            intArrayOf(dstHeight, dstWidth)
        } else {
            intArrayOf(dstWidth, dstHeight)
        }

    private fun normalizedRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360
}
