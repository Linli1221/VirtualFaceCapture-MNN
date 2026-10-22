package com.example.virtualfacecapture.web

import android.graphics.Bitmap
import android.os.Build
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一张已经编码好的输出帧。
 * WebSocket 通道用 [webp]（带 alpha），MJPEG 通道用 [jpeg]（无 alpha）。
 */
data class EncodedFrame(
    val webp: ByteArray?,
    val jpeg: ByteArray?,
)

/**
 * RGBA 帧 -> WebP / JPEG。
 *
 * 输入是 GPU 抓帧得到的 **预乘 alpha（premultiplied）** RGBA 数据（Live2D Cubism 的
 * 着色器无论是否开启 PREMULTIPLIED_ALPHA_ENABLE 都会输出 premultiplied 颜色），
 * 而 WebP/PNG 存的是直通 alpha，所以编码前必须做一次 un-premultiply，否则
 * 半透明边缘会发黑。
 *
 * 整个对象只被单个编码线程使用，内部的 Bitmap / IntArray 复用，避免每帧分配。
 */
class FrameEncoder {

    private var bitmap: Bitmap? = null
    private var pixels: IntArray? = null
    private val output = ByteArrayOutputStream(1 shl 16)

    /** 256 项倒数表：un-premultiply 时把除法换成乘+移位。 */
    private val unpremultiply = IntArray(256) { alpha ->
        if (alpha == 0) 0 else (255 * 256 + alpha - 1) / alpha
    }

    fun encode(
        rgba: ByteBuffer,
        width: Int,
        height: Int,
        needWebp: Boolean,
        needJpeg: Boolean,
        quality: Int,
    ): EncodedFrame {
        if (width <= 0 || height <= 0 || (!needWebp && !needJpeg)) {
            return EncodedFrame(null, null)
        }

        val count = width * height
        val cachedPixels = pixels
        val targetPixels = if (cachedPixels == null || cachedPixels.size != count) {
            IntArray(count).also { pixels = it }
        } else {
            cachedPixels
        }

        // 坑：ByteBuffer.duplicate() 会把自己的字节序重置成 BIG_ENDIAN（JDK-4715166，
        // 官方以「非 bug」关闭，只提供 workaround），asIntBuffer() 又会继承那个字节序。
        // 不重新指定的话，读出来的 int 是 R<<24|G<<16|B<<8|A：alpha 被当成红色、
        // 红色被当成 alpha、G/B 互换 —— 表现就是 OBS 里整体偏灰偏粉、蓝紫色全丢、
        // 红色分量接近 0 的暗部直接变透明（手机预览走 GL，不经过这里，所以看着正常）。
        // glReadPixels 按 R,G,B,A 逐字节写入，arm64/x86_64 都是小端，所以显式按小端
        // 组装：低 8 位 = R，高 8 位 = A。
        val source = rgba.duplicate()
        source.rewind()
        source.order(ByteOrder.LITTLE_ENDIAN)
        val ints = source.asIntBuffer().order(ByteOrder.LITTLE_ENDIAN)
        if (ints.capacity() < count) {
            return EncodedFrame(null, null)
        }

        // GL 侧已经把画面翻正（抓帧时 V 翻转），这里只需 RGBA->ARGB + un-premultiply。
        // glReadPixels 写入的字节序为 R,G,B,A，小端读成 Int 是 0xAABBGGRR。
        var index = 0
        while (index < count) {
            val value = ints.get(index)
            val alpha = value ushr 24
            if (alpha == 0) {
                targetPixels[index] = 0
            } else if (alpha == 0xFF) {
                targetPixels[index] = (0xFF shl 24) or
                    ((value and 0xFF) shl 16) or
                    (((value ushr 8) and 0xFF) shl 8) or
                    ((value ushr 16) and 0xFF)
            } else {
                val factor = unpremultiply[alpha]
                var red = (value and 0xFF) * factor shr 8
                var green = ((value ushr 8) and 0xFF) * factor shr 8
                var blue = ((value ushr 16) and 0xFF) * factor shr 8
                if (red > 0xFF) red = 0xFF
                if (green > 0xFF) green = 0xFF
                if (blue > 0xFF) blue = 0xFF
                targetPixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
            index++
        }

        val target = bitmap?.takeIf { it.width == width && it.height == height }
            ?: run {
                bitmap?.recycle()
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
            }
        target.setPixels(targetPixels, 0, width, 0, 0, width, height)

        var webpBytes: ByteArray? = null
        if (needWebp) {
            output.reset()
            if (target.compress(webpFormat(), quality.coerceIn(1, 100), output)) {
                webpBytes = output.toByteArray()
            }
        }

        var jpegBytes: ByteArray? = null
        if (needJpeg) {
            output.reset()
            if (target.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)) {
                jpegBytes = output.toByteArray()
            }
        }

        return EncodedFrame(webpBytes, jpegBytes)
    }

    fun release() {
        bitmap?.recycle()
        bitmap = null
        pixels = null
    }

    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }
    }
}
