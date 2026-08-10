package com.swmansion.moqkit.publish.source.internal

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch

private const val TAG = "GlFanOutRenderer"

internal class GlFanOutRenderer {
    private val thread = HandlerThread("GlFanOut")
    private lateinit var handler: Handler

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private var oesTextureId: Int = 0
    var surfaceTexture: SurfaceTexture? = null
        private set

    private var previewEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var encoderEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program: Int = 0
    private var positionHandle: Int = 0
    private var texCoordHandle: Int = 0
    private var texMatrixHandle: Int = 0
    private var scaleHandle: Int = 0
    private var contentRotationHandle: Int = 0
    private val transformMatrix = FloatArray(16)

    /**
     * Raw frame buffer size as reported by the producing source via [setSourceSize],
     * or null when unknown (the renderer then falls back to the legacy stretch).
     */
    @Volatile
    private var sourceSize: IntArray? = null

    /**
     * Display rotation in degrees (0/90/180/270). The SurfaceTexture matrix only
     * orients frames upright for the device's NATURAL orientation; when the app is
     * used in another display rotation the content must be rotated once more so
     * preview and encoder both stay world-upright. See [setDisplayRotation].
     */
    @Volatile
    private var displayRotationDegrees: Int = 0

    /** True when the source is user-facing mirrored (front camera). See [setSourceMirrored]. */
    @Volatile
    private var sourceMirrored: Boolean = false

    /** Whether the current frame's texture matrix transposes the frame axes. */
    private var frameTransposed = false

    /** Last logged aspect-map key per target, so the diagnostic line logs once per change. */
    private val loggedAspectKeys = HashMap<String, String>()

    private val quadVertices = ByteBuffer
        .allocateDirect(4 * 4 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(floatArrayOf(
                -1f, -1f,  0f, 0f,
                 1f, -1f,  1f, 0f,
                -1f,  1f,  0f, 1f,
                 1f,  1f,  1f, 1f,
            ))
            position(0)
        }

    fun initialize(): SurfaceTexture {
        thread.start()
        handler = Handler(thread.looper)
        val latch = CountDownLatch(1)
        var result: SurfaceTexture? = null
        handler.post {
            try {
                setupEgl()
                setupShaders()
                val texIds = IntArray(1)
                GLES20.glGenTextures(1, texIds, 0)
                oesTextureId = texIds[0]
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                val st = SurfaceTexture(oesTextureId)
                st.setOnFrameAvailableListener({ renderFrame() }, handler)
                surfaceTexture = st
                result = st
            } catch (e: Exception) {
                Log.e(TAG, "GL init failed: $e")
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        return result ?: error("GL initialization failed")
    }

    fun setEncoderSurface(surface: Surface?) {
        handler.post {
            // OBS-V49-005: the aspect/orient diagnostic lines dedupe on a
            // config key that survives publish generations (the camera — and
            // this renderer — is kept across Stop/Publish). A fresh encoder
            // bind with the SAME config then logged nothing, which read as
            // intermittent probe loss in the 08-07 재테스트. Every encoder
            // (re)bind starts a fresh dedupe window so each publish logs its
            // orientation ground truth exactly once per target.
            loggedAspectKeys.clear()
            if (encoderEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, encoderEglSurface)
                encoderEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null) {
                encoderEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
            }
        }
    }

    /**
     * Reports the raw frame buffer size the producing source writes into the
     * [SurfaceTexture]. For CameraX this should be `SurfaceRequest.resolution` (the
     * actual produced size, which can differ from the requested one). Used together
     * with the per-frame texture matrix to keep the rendered picture's aspect ratio;
     * when unset the renderer stretches the frame to each target as before.
     */
    fun setSourceSize(width: Int, height: Int) {
        sourceSize = if (width > 0 && height > 0) intArrayOf(width, height) else null
    }

    /**
     * Sets the current display rotation (0/90/180/270 degrees, i.e. Display.rotation
     * mapped to degrees). Camera sources must forward this so a landscape-held device
     * still renders world-upright frames to BOTH the preview and the encoder; screen
     * capture sources must leave it at 0 (their buffers are already display-oriented).
     */
    fun setDisplayRotation(degrees: Int) {
        displayRotationDegrees = degrees
    }

    /**
     * Marks the source as user-facing mirrored (front camera). Since
     * AND-V46-001 (08-04 device truth table) the rotation compensation is
     * facing-INDEPENDENT — this flag no longer alters the transform and is
     * kept for the orient[map] probe line, where per-facing ground truth is
     * what made the landscape table decidable from logs alone. Facing is
     * plumbed explicitly by the capture that owns the camera because the texture
     * matrix's own reflection sign cannot distinguish the front mirror from
     * the standard V-flip every camera matrix carries.
     */
    fun setSourceMirrored(mirrored: Boolean) {
        sourceMirrored = mirrored
    }

    fun setPreviewSurface(surface: Surface?) {
        handler.post {
            if (previewEglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, previewEglSurface)
                previewEglSurface = EGL14.EGL_NO_SURFACE
            }
            if (surface != null) {
                previewEglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0
                )
            }
        }
    }

    fun release() {
        handler.post {
            surfaceTexture?.release()
            surfaceTexture = null
            destroySurface(previewEglSurface).also { previewEglSurface = EGL14.EGL_NO_SURFACE }
            destroySurface(encoderEglSurface).also { encoderEglSurface = EGL14.EGL_NO_SURFACE }
            if (program != 0) {
                GLES20.glDeleteProgram(program)
                program = 0
            }
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
        }
        thread.quitSafely()
    }

    private fun renderFrame() {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(transformMatrix)
        frameTransposed = FrameTransform.isTransposed(transformMatrix)
        renderToSurface(previewEglSurface, "preview")
        renderToSurface(encoderEglSurface, "encoder")
    }

    private fun renderToSurface(eglSurface: EGLSurface, label: String) {
        if (eglSurface == EGL14.EGL_NO_SURFACE) return
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(eglDisplay, eglSurface, EGL14.EGL_HEIGHT, h, 0)
        GLES20.glViewport(0, 0, w[0], h[0])
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        quadVertices.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(positionHandle)

        quadVertices.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, quadVertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)

        // Draw the world-upright frame with one uniform scale per target
        // (center-crop fill), so preview and encoder see the same undistorted
        // picture in every display rotation.
        val rotation = displayRotationDegrees
        val mirrored = sourceMirrored
        val scale = targetScale(w[0], h[0], rotation)
        logAspectMap(label, w[0], h[0], rotation, scale, mirrored)
        GLES20.glUniform2f(scaleHandle, scale[0], scale[1])
        GLES20.glUniformMatrix2fv(contentRotationHandle, 1, false, FrameTransform.positionRotation(rotation), 0)
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, transformMatrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)

        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /**
     * NDC scale for drawing the current frame onto a target of [dstWidth]x[dstHeight]
     * without distortion. Computed in the device's NATURAL frame (where the
     * SurfaceTexture matrix orients content upright): a 90/270 display rotation
     * swaps the target's axes here, and the position rotation swaps them back at
     * draw time — so the rotated result covers the real target uniformly and the
     * overflow is center-cropped by the clip volume.
     */
    private fun targetScale(dstWidth: Int, dstHeight: Int, rotationDegrees: Int): FloatArray {
        val source = sourceSize ?: return floatArrayOf(1f, 1f)
        val upright = FrameTransform.uprightSize(source[0], source[1], frameTransposed)
        val dstNat = FrameTransform.rotatedTargetSize(dstWidth, dstHeight, rotationDegrees)
        return FrameTransform.fillCropScale(upright[0], upright[1], dstNat[0], dstNat[1])
    }

    /**
     * One diagnostic line per target whenever the aspect mapping changes (실기기
     * 재현 분석용 — report §9-3): source buffer, transposed flag, display rotation,
     * upright size, target size, NDC scale, and the fraction of the upright frame
     * kept after the center-crop on each axis.
     */
    private fun logAspectMap(label: String, dstWidth: Int, dstHeight: Int, rotationDegrees: Int, scale: FloatArray, mirrored: Boolean) {
        val source = sourceSize
        val key = "${source?.get(0)}x${source?.get(1)}|$frameTransposed|$rotationDegrees|$mirrored|${dstWidth}x$dstHeight"
        if (loggedAspectKeys[label] == key) return
        loggedAspectKeys[label] = key
        if (source == null) {
            Log.w(TAG, "aspect[$label]: source size unknown — legacy stretch to ${dstWidth}x$dstHeight")
            return
        }
        val upright = FrameTransform.uprightSize(source[0], source[1], frameTransposed)
        val dstNat = FrameTransform.rotatedTargetSize(dstWidth, dstHeight, rotationDegrees)
        val keptX = if (scale[0] > 0f) 100f / scale[0] else 0f
        val keptY = if (scale[1] > 0f) 100f / scale[1] else 0f
        Log.i(
            TAG,
            "aspect[$label]: source=${source[0]}x${source[1]} matrixTransposed=$frameTransposed " +
                "displayRotation=$rotationDegrees uprightNat=${upright[0]}x${upright[1]} " +
                "target=${dstWidth}x$dstHeight targetNat=${dstNat[0]}x${dstNat[1]} " +
                "scale=%.3fx%.3f keptFov=%.1f%%x%.1f%%"
                    .format(Locale.US, scale[0], scale[1], keptX, keptY),
        )
        // AND-V45-005 ground-truth probe: the full 2x2 of the texture matrix +
        // det + facing + the compensation actually applied. One line per config
        // change per target; the landscape retest matrix collects these so every
        // orientation cell is decidable from logs alone (재테스트 시나리오 §2-5).
        val m = transformMatrix
        // AND-V46-001: the compensation is facing-independent (device truth
        // table 08-04); mirrored stays in this line purely as the facing
        // diagnostic that made that table decidable.
        val applied = FrameTransform.positionRotation(rotationDegrees)
        Log.i(
            TAG,
            ("orient[$label]: texM2x2=[%.3f,%.3f;%.3f,%.3f] det=%.3f mirrored=$mirrored " +
                "displayRotation=$rotationDegrees applied=[%.0f,%.0f;%.0f,%.0f]")
                .format(
                    Locale.US, m[0], m[4], m[1], m[5], FrameTransform.matrixDet2x2(m),
                    applied[0], applied[2], applied[1], applied[3],
                ),
        )
    }

    private fun setupEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)

        // Dummy pbuffer surface so we can set up shaders before any window surface exists
        val pbAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        val dummy = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbAttribs, 0)
        EGL14.eglMakeCurrent(eglDisplay, dummy, dummy, eglContext)
    }

    private fun setupShaders() {
        val vs = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            uniform mat2 uPosRotation;
            uniform vec2 uScale;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = vec4(uPosRotation * (aPosition.xy * uScale), 0.0, 1.0);
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """.trimIndent()

        val fs = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(sTexture, vTexCoord);
            }
        """.trimIndent()

        val vert = compileShader(GLES20.GL_VERTEX_SHADER, vs)
        val frag = compileShader(GLES20.GL_FRAGMENT_SHADER, fs)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vert)
        GLES20.glAttachShader(program, frag)
        GLES20.glLinkProgram(program)
        GLES20.glDeleteShader(vert)
        GLES20.glDeleteShader(frag)

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix")
        contentRotationHandle = GLES20.glGetUniformLocation(program, "uPosRotation")
        scaleHandle = GLES20.glGetUniformLocation(program, "uScale")
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: ${GLES20.glGetShaderInfoLog(shader)}")
        }
        return shader
    }

    private fun destroySurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(eglDisplay, surface)
        }
    }
}
