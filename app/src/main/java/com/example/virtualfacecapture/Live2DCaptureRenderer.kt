package com.example.virtualfacecapture

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import com.example.virtualfacecapture.web.GlCaptureSink
import com.live2d.demo.JniBridgeJava
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Live2D 的 GL 渲染器，代替原来的 [com.live2d.demo.GLRenderer]。
 *
 * 需要抓帧（浏览器输出）时走离屏路径：
 *
 * ```
 * 场景 -> 离屏 FBO(RGBA8, 透明背景) -> [按需] 缩小到输出尺寸 + glReadPixels
 *                                 \-> 全屏贴回窗口
 * ```
 *
 * 这样做有两个目的：
 * 1. 离屏 FBO 的 alpha 通道是确定有效的（窗口 surface 的 alpha 在部分机型上不可靠），
 *    浏览器端才能拿到真正的透明背景；
 * 2. 抓帧时可以先用 GPU 缩放到输出尺寸，CPU 只需要读一小块像素，编码压力可控。
 *
 * 没有任何人抓帧时（默认状态）直接画窗口，省掉整趟离屏渲染 + 贴回；
 * 高刷屏（>75Hz）还会把 native 绘制压到 60fps 附近，见 [minNativeDrawIntervalNanos]。
 *
 * 如果 FBO / shader 创建失败，会自动退回「直接画窗口」的原行为（只是没有透明通道）。
 */
class Live2DCaptureRenderer(
    private val sinkProvider: () -> GlCaptureSink?,
    /** 屏幕刷新率（Hz）。≤75Hz 时完全不做帧率节流，行为与改动前一致。 */
    refreshRate: Float = 60f,
) : GLSurfaceView.Renderer {

    companion object {
        private const val TAG = "Live2DCaptureRenderer"

        private const val VERTEX_SHADER = """
            attribute vec2 a_position;
            attribute vec2 a_texCoord;
            uniform float u_flip;
            varying vec2 v_texCoord;
            void main() {
                gl_Position = vec4(a_position, 0.0, 1.0);
                v_texCoord = vec2(a_texCoord.x, u_flip > 0.5 ? 1.0 - a_texCoord.y : a_texCoord.y);
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec2 v_texCoord;
            uniform sampler2D u_texture;
            void main() {
                gl_FragColor = texture2D(u_texture, v_texCoord);
            }
        """

        /** 无人抓帧持续 2 秒后释放抓帧 FBO。 */
        private const val CAPTURE_IDLE_RELEASE_NANOS = 2_000_000_000L

        /** Live2D 动画按 60fps 设计，高刷屏没必要把每个 vsync 都重画一遍。 */
        private const val TARGET_FPS = 60

        /** 只有明显高于 60Hz 的屏幕才节流；60Hz 屏保持原有节奏，避免误伤。 */
        private const val THROTTLE_REFRESH_THRESHOLD_HZ = 75f

        /** 间隔容差：抵消 vsync 抖动，保证 120Hz 屏稳定地「每两帧画一次」。 */
        private const val THROTTLE_TOLERANCE = 0.85

        /**
         * 计算两次 nativeOnDrawFrame() 之间的最小间隔（纳秒）。
         *
         * - ≤75Hz（绝大多数手机）：返回 0，表示不节流，调用节奏与改动前完全一致；
         * - 120Hz：每 2 个 vsync 画一帧 → 60fps；
         * - 144Hz：每 2 个 vsync 画一帧 → 72fps（再降就要掉到 48fps，反而更不稳）。
         */
        private fun computeMinNativeDrawInterval(refreshRate: Float): Long {
            if (!refreshRate.isFinite() || refreshRate <= THROTTLE_REFRESH_THRESHOLD_HZ) {
                return 0L
            }
            val divisor = (refreshRate / TARGET_FPS).toInt().coerceAtLeast(1)
            val frameIntervalNanos = 1_000_000_000.0 / refreshRate.toDouble()
            return (frameIntervalNanos * divisor * THROTTLE_TOLERANCE).toLong()
        }
    }

    /**
     * 允许调用 nativeOnDrawFrame() 的最小间隔；0 表示不节流。
     *
     * 注意：节流只减少「画」的次数，不改变 native 调用的约定（native 内部仍按自己的
     * 时钟推进动画），所以不会出现动画变慢，只是帧率上限被压到 60fps 附近。
     */
    private val minNativeDrawIntervalNanos: Long = computeMinNativeDrawInterval(refreshRate)

    /** 上一次真正调用 nativeOnDrawFrame() 的时间戳（System.nanoTime）。 */
    private var lastNativeDrawNanos = 0L

    // 全屏 blit
    private var blitProgram = 0
    private var attrPosition = -1
    private var attrTexCoord = -1
    private var uniformTexture = -1
    private var uniformFlip = -1
    private var quadBuffer: FloatBuffer? = null

    // 场景离屏目标（与屏幕同尺寸）
    private var sceneTexture = 0
    private var sceneFramebuffer = 0
    private var sceneWidth = 0
    private var sceneHeight = 0
    private var sceneReady = false

    // 抓帧目标（输出尺寸，可为屏幕的下采样）
    private var captureTexture = 0
    private var captureFramebuffer = 0
    private var captureWidth = 0
    private var captureHeight = 0

    private var lastCaptureNanos = 0L
    private var captureLastWantedNanos = 0L
    private var glReady = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.i(TAG, "onSurfaceCreated")

        // 新的 EGL context：之前创建的 GL 对象全部失效
        blitProgram = 0
        quadBuffer = null
        sceneTexture = 0
        sceneFramebuffer = 0
        captureTexture = 0
        captureFramebuffer = 0
        sceneReady = false
        captureWidth = 0
        captureHeight = 0
        lastCaptureNanos = 0L
        captureLastWantedNanos = 0L

        // 和原 GLRenderer 完全一致的调用顺序
        JniBridgeJava.nativeOnSurfaceCreated()
        glReady = true

        createBlitResources()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.i(TAG, "onSurfaceChanged: ${width}x$height")
        JniBridgeJava.nativeOnSurfaceChanged(width, height)

        sceneWidth = width
        sceneHeight = height
        createSceneTarget(width, height)

        // 新尺寸的离屏目标还没有内容：让下一帧一定要真正渲染（否则高刷屏上可能
        // 把一张尚未渲染的纹理贴到窗口上）
        lastNativeDrawNanos = 0L
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!glReady || !sceneReady || blitProgram == 0 || quadBuffer == null) {
            // FBO 不可用：保持原行为，直接画窗口
            JniBridgeJava.nativeOnDrawFrame()
            lastNativeDrawNanos = System.nanoTime()
            return
        }

        val sink = sinkProvider()
        val captureWanted = sink != null && sink.isCaptureWanted()
        val now = System.nanoTime()

        // 0) 高刷屏节流：还没到下一帧的绘制时间，就放过这次 native 绘制。
        //
        // 不做任何 native 调用、但必须把上一帧的离屏结果重贴一次窗口 —— EGL 交换后的
        // 后备缓冲内容未定义，完全不画在部分机型上会闪黑/花屏。
        if (minNativeDrawIntervalNanos > 0L &&
            now - lastNativeDrawNanos < minNativeDrawIntervalNanos
        ) {
            if (!captureWanted) {
                releaseCaptureTargetIfIdle(now)
            }
            blitSceneToWindow()
            return
        }
        lastNativeDrawNanos = now

        // 1) 没人抓帧 + 60Hz 屏：直接画窗口。
        //    这时离屏 FBO 与全屏贴回纯属浪费（每个 vsync 多一整趟离屏渲染 + 一次
        //    全屏贴图），视觉结果与 FBO 方案完全一致。
        if (!captureWanted && minNativeDrawIntervalNanos == 0L) {
            releaseCaptureTargetIfIdle(now)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, sceneWidth, sceneHeight)
            JniBridgeJava.nativeOnDrawFrame()
            return
        }

        // 2) 画到离屏 FBO（透明背景）
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glViewport(0, 0, sceneWidth, sceneHeight)
        JniBridgeJava.nativeOnDrawFrame()

        // 3) 按需抓帧（GPU 缩放到输出尺寸，CPU 只读小块）
        if (sink != null && captureWanted) {
            captureLastWantedNanos = now
            tryCapture(sink)
        } else {
            // 浏览器输出关闭 2 秒后释放抓帧用的 FBO/纹理，别白占显存
            releaseCaptureTargetIfIdle(now)
        }

        // 4) 贴回窗口，看到的内容与原来一致
        blitSceneToWindow()
    }

    /** 浏览器输出关闭、抓帧目标闲置超过 2 秒后释放。 */
    private fun releaseCaptureTargetIfIdle(now: Long) {
        if (captureFramebuffer == 0) return
        if (now - captureLastWantedNanos <= CAPTURE_IDLE_RELEASE_NANOS) return
        destroyTarget(captureTexture, captureFramebuffer)
        captureTexture = 0
        captureFramebuffer = 0
        captureWidth = 0
        captureHeight = 0
        Log.i(TAG, "抓帧目标已释放")
    }

    /** 把离屏场景贴回窗口（透明背景在离屏 FBO 里，窗口这层铺黑底）。 */
    private fun blitSceneToWindow() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, sceneWidth, sceneHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawQuad(sceneTexture, flipVertically = false)
    }

    // ------------------------------------------------------------------ 抓帧

    private fun tryCapture(sink: GlCaptureSink) {
        val now = System.nanoTime()
        val fps = sink.requestedFps().coerceIn(1, 120)
        val interval = 1_000_000_000L / fps
        if (now - lastCaptureNanos < interval) return
        if (sceneWidth <= 0 || sceneHeight <= 0) return

        val requestedHeight = sink.requestedHeight()
        val targetHeight = if (requestedHeight <= 0) {
            sceneHeight
        } else {
            minOf(requestedHeight, sceneHeight)
        }
        if (targetHeight <= 0) return
        val targetWidth = maxOf(2, (sceneWidth.toLong() * targetHeight / sceneHeight).toInt())

        if (targetWidth != captureWidth || targetHeight != captureHeight) {
            createCaptureTarget(targetWidth, targetHeight)
        }
        if (captureFramebuffer == 0) return

        // 上一帧还没编码完就直接丢这一帧，保证延迟
        val buffer = sink.acquireBuffer(targetWidth, targetHeight) ?: return
        buffer.clear()

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureFramebuffer)
        GLES20.glViewport(0, 0, targetWidth, targetHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glColorMask(true, true, true, true)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        // 抓帧时 V 翻转，读回来的行序就是「从上到下」
        drawQuad(sceneTexture, flipVertically = true)

        GLES20.glReadPixels(
            0,
            0,
            targetWidth,
            targetHeight,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            buffer,
        )

        lastCaptureNanos = now
        sink.submitFrame(buffer, targetWidth, targetHeight)

        // 恢复窗口 framebuffer，交给 onDrawFrame 继续
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sceneFramebuffer)
        GLES20.glViewport(0, 0, sceneWidth, sceneHeight)
    }

    // --------------------------------------------------------------- 离屏目标

    private fun createSceneTarget(width: Int, height: Int) {
        destroyTarget(sceneTexture, sceneFramebuffer)
        sceneTexture = 0
        sceneFramebuffer = 0
        sceneReady = false

        val target = createTarget(width, height) ?: return
        sceneTexture = target[0]
        sceneFramebuffer = target[1]
        sceneReady = true
        Log.i(TAG, "场景 FBO 就绪: ${width}x$height")
    }

    private fun createCaptureTarget(width: Int, height: Int) {
        destroyTarget(captureTexture, captureFramebuffer)
        captureTexture = 0
        captureFramebuffer = 0
        captureWidth = 0
        captureHeight = 0

        val target = createTarget(width, height) ?: return
        captureTexture = target[0]
        captureFramebuffer = target[1]
        captureWidth = width
        captureHeight = height
        Log.i(TAG, "抓帧 FBO 就绪: ${width}x$height")
    }

    /** @return [texture, framebuffer]，失败返回 null。 */
    private fun createTarget(width: Int, height: Int): IntArray? {
        if (width <= 0 || height <= 0) return null

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        if (textures[0] == 0) return null

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val framebuffers = IntArray(1)
        GLES20.glGenFramebuffers(1, framebuffers, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffers[0])
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER,
            GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D,
            textures[0],
            0,
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.w(TAG, "FBO 不完整 (0x${Integer.toHexString(status)})，退化为直接渲染")
            GLES20.glDeleteFramebuffers(1, framebuffers, 0)
            GLES20.glDeleteTextures(1, textures, 0)
            return null
        }

        return intArrayOf(textures[0], framebuffers[0])
    }

    private fun destroyTarget(texture: Int, framebuffer: Int) {
        if (framebuffer != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        }
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    // ------------------------------------------------------------------ blit

    private fun createBlitResources() {
        val program = createProgram() ?: return
        blitProgram = program
        attrPosition = GLES20.glGetAttribLocation(program, "a_position")
        attrTexCoord = GLES20.glGetAttribLocation(program, "a_texCoord")
        uniformTexture = GLES20.glGetUniformLocation(program, "u_texture")
        uniformFlip = GLES20.glGetUniformLocation(program, "u_flip")

        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        val buffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(vertices)
        buffer.position(0)
        quadBuffer = buffer

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
    }

    private fun drawQuad(texture: Int, flipVertically: Boolean) {
        val program = blitProgram
        val quad = quadBuffer ?: return
        if (program == 0) return

        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uniformTexture, 0)
        GLES20.glUniform1f(uniformFlip, if (flipVertically) 1f else 0f)

        quad.position(0)
        GLES20.glEnableVertexAttribArray(attrPosition)
        GLES20.glVertexAttribPointer(attrPosition, 2, GLES20.GL_FLOAT, false, 4 * 4, quad)

        quad.position(2)
        GLES20.glEnableVertexAttribArray(attrTexCoord)
        GLES20.glVertexAttribPointer(attrTexCoord, 2, GLES20.GL_FLOAT, false, 4 * 4, quad)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(attrPosition)
        GLES20.glDisableVertexAttribArray(attrTexCoord)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun createProgram(): Int? {
        val vertex = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER) ?: return null
        val fragment = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER) ?: return null

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)

        val result = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)

        if (result[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "blit program 链接失败: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return null
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int? {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) return null
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val result = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0)
        if (result[0] != GLES20.GL_TRUE) {
            Log.e(TAG, "shader 编译失败: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return null
        }
        return shader
    }
}
