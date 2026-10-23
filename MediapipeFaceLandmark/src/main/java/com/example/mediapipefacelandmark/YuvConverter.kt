package com.example.mediapipefacelandmark

import com.vfc.log.VfcLog as Log
import java.nio.ByteBuffer

/**
 * Native YUV to RGB converter using C++ with optional NEON SIMD optimization
 * Much faster than pure Kotlin implementation
 */
object YuvConverter {

    private const val TAG = "YuvConverter"

    private var isNativeLoaded = false

    init {
        try {
            System.loadLibrary("mediapipefacelandmark")
            isNativeLoaded = true
            Log.i(TAG, "Native library loaded. NEON support: ${nativeHasNeonSupport()}")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
            isNativeLoaded = false
        }
    }

    /**
     * Check if native conversion is available
     */
    fun isNativeAvailable(): Boolean = isNativeLoaded

    /**
     * Convert YUV420 to RGB with rotation using native code
     *
     * @param yBuffer Y plane ByteBuffer (must be direct)
     * @param uBuffer U plane ByteBuffer (must be direct)
     * @param vBuffer V plane ByteBuffer (must be direct)
     * @param yRowStride Y plane row stride
     * @param uvRowStride UV plane row stride
     * @param uvPixelStride UV plane pixel stride
     * @param srcWidth Source image width
     * @param srcHeight Source image height
     * @param rotation Rotation in degrees (0, 90, 180, 270)
     * @param rgbBuffer Output RGB buffer (must be direct, pre-allocated with size outWidth * outHeight * 3)
     * @param outWidth Output width after rotation
     * @param outHeight Output height after rotation
     */
    fun convertYuvToRgb(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        rgbBuffer: ByteBuffer,
        outWidth: Int,
        outHeight: Int
    ) {
        if (!isNativeLoaded) {
            throw IllegalStateException("Native library not loaded")
        }

        nativeConvertYuvToRgb(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            srcWidth, srcHeight, rotation,
            rgbBuffer, outWidth, outHeight
        )
    }

    /**
     * Convert YUV420 to BGRA (ARGB_8888 little-endian) with rotation using native code
     *
     * 与 [convertYuvToRgb] 的旋转/采样映射逐像素一致，区别是：
     * - 输出 4 字节 BGRA（B,G,R,A，A 固定 255），可直接喂给 Bitmap / ImageReader；
     * - 允许最近邻降采样：dst 像素先映射回 rotated 空间，再按同样规则反查 sensor 坐标。
     *   outWidth/outHeight 等于旋转后全尺寸时与 convertYuvToRgb 的采样完全等价。
     *
     * 这条路径的意义是把预览的逐像素转换从 Kotlin（720p 约 92 万次循环）搬到 C++。
     *
     * @param yBuffer Y plane ByteBuffer (must be direct)
     * @param uBuffer U plane ByteBuffer (must be direct)
     * @param vBuffer V plane ByteBuffer (must be direct)
     * @param yRowStride Y plane row stride
     * @param uvRowStride UV plane row stride
     * @param uvPixelStride UV plane pixel stride
     * @param srcWidth Source image width
     * @param srcHeight Source image height
     * @param rotation Rotation in degrees (0, 90, 180, 270)
     * @param bgraBuffer Output BGRA buffer (must be direct, size >= outWidth * outHeight * 4)
     * @param outWidth Output width after rotation
     * @param outHeight Output height after rotation
     */
    fun convertYuvToBgra(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        bgraBuffer: ByteBuffer,
        outWidth: Int,
        outHeight: Int
    ) {
        if (!isNativeLoaded) {
            throw IllegalStateException("Native library not loaded")
        }

        nativeConvertYuvToBgra(
            yBuffer, uBuffer, vBuffer,
            yRowStride, uvRowStride, uvPixelStride,
            srcWidth, srcHeight, rotation,
            bgraBuffer, outWidth, outHeight
        )
    }

    // Native methods
    private external fun nativeConvertYuvToRgb(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        rgbBuffer: ByteBuffer,
        outWidth: Int,
        outHeight: Int
    )

    private external fun nativeConvertYuvToBgra(
        yBuffer: ByteBuffer,
        uBuffer: ByteBuffer,
        vBuffer: ByteBuffer,
        yRowStride: Int,
        uvRowStride: Int,
        uvPixelStride: Int,
        srcWidth: Int,
        srcHeight: Int,
        rotation: Int,
        bgraBuffer: ByteBuffer,
        outWidth: Int,
        outHeight: Int
    )

    external fun nativeHasNeonSupport(): Boolean
}

