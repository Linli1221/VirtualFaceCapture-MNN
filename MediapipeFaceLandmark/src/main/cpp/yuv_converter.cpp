/**
 * Fast YUV to RGB conversion with rotation using NEON SIMD
 * This is much faster than pure Kotlin/Java implementation
 */

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <algorithm>
#include <vector>

#ifdef __ARM_NEON__
#include <arm_neon.h>
#endif

#define LOG_TAG "YuvConverter"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Clamp value to [0, 255]
static inline uint8_t clamp(int value) {
    return static_cast<uint8_t>(std::max(0, std::min(255, value)));
}

/**
 * Convert YUV420 to RGB with rotation
 * Uses integer math for speed, with optional NEON optimization
 *
 * @param yData Y plane data
 * @param uData U plane data
 * @param vData V plane data
 * @param yRowStride Y plane row stride
 * @param uvRowStride UV plane row stride
 * @param uvPixelStride UV plane pixel stride
 * @param srcWidth Source image width
 * @param srcHeight Source image height
 * @param rotation Rotation in degrees (0, 90, 180, 270)
 * @param rgbOut Output RGB buffer (must be pre-allocated)
 * @param outWidth Output width after rotation
 * @param outHeight Output height after rotation
 */
extern "C" void convertYuvToRgbRotated(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    int srcWidth,
    int srcHeight,
    int rotation,
    uint8_t* rgbOut,
    int outWidth,
    int outHeight
) {
    // Process each output pixel
    for (int dstY = 0; dstY < outHeight; dstY++) {
        for (int dstX = 0; dstX < outWidth; dstX++) {
            // Calculate source coordinates based on rotation
            int srcX, srcY;

            switch (rotation) {
                case 90:
                    srcX = dstY;
                    srcY = outWidth - 1 - dstX;
                    break;
                case 180:
                    srcX = srcWidth - 1 - dstX;
                    srcY = srcHeight - 1 - dstY;
                    break;
                case 270:
                    srcX = outHeight - 1 - dstY;
                    srcY = dstX;
                    break;
                default: // 0
                    srcX = dstX;
                    srcY = dstY;
                    break;
            }

            // Get Y value
            int yIndex = srcY * yRowStride + srcX;
            int y = yData[yIndex];

            // Get UV values (subsampled by 2)
            int uvIndex = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixelStride;
            int u = uData[uvIndex] - 128;
            int v = vData[uvIndex] - 128;

            // YUV to RGB conversion using integer math (faster than float)
            // Using fixed-point arithmetic: multiply by 256 then shift right by 8
            int r = y + ((351 * v) >> 8);
            int g = y - ((86 * u + 179 * v) >> 8);
            int b = y + ((443 * u) >> 8);

            // Write to output
            int outIndex = (dstY * outWidth + dstX) * 3;
            rgbOut[outIndex + 0] = clamp(r);
            rgbOut[outIndex + 1] = clamp(g);
            rgbOut[outIndex + 2] = clamp(b);
        }
    }
}

/**
 * YUV420 -> **BGRA** (little-endian ARGB_8888: B,G,R,A) with rotation and
 * optional nearest-neighbour down-scale.
 *
 * 语义与 convertYuvToRgbRotated() 逐像素一致：旋转映射关系完全相同，只是
 * 1) 输出改成 4 字节 BGRA（A 固定 255）；
 * 2) 允许输出尺寸小于旋转后的全尺寸：dst 像素 (x,y) 先映射回 rotated 空间
 *    (x*sx, y*sy)，sx = rotatedW/outWidth、sy = rotatedH/outHeight，再做同样的
 *    旋转反查。outW/outH 等于 rotated 尺寸时与旧函数完全等价。
 *
 * 用途：预览路径。Kotlin 那份逐像素实现（Camera2Manager.yuv420ToRgbRotatedKotlin）
 * 在 720p 上要跑 92 万次循环 + 3 次 ByteBuffer.get，这里换成一趟 C++ 循环，
 * 并且把每个 dstX / dstY 的源坐标预计算成表，内层循环只剩查表 + 整数乘加。
 */
extern "C" void convertYuvToBgraRotated(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    int srcWidth,
    int srcHeight,
    int rotation,
    uint8_t* bgraOut,
    int outWidth,
    int outHeight
) {
    if (yData == nullptr || uData == nullptr || vData == nullptr || bgraOut == nullptr) {
        return;
    }
    if (srcWidth <= 0 || srcHeight <= 0 || outWidth <= 0 || outHeight <= 0) {
        return;
    }

    const bool swapSides = (rotation == 90 || rotation == 270);
    // 旋转后（全尺寸）的画布尺寸。
    const int rotatedW = swapSides ? srcHeight : srcWidth;
    const int rotatedH = swapSides ? srcWidth : srcHeight;

    // 每个 dstX / dstY 只需要算一次源坐标，内层循环查表。
    static thread_local std::vector<int32_t> s_mapX;
    static thread_local std::vector<int32_t> s_mapY;
    s_mapX.resize(static_cast<size_t>(outWidth));
    s_mapY.resize(static_cast<size_t>(outHeight));

    for (int x = 0; x < outWidth; ++x) {
        // rotated 空间的最近邻坐标（整数运算，避免浮点）。
        int rx = static_cast<int>((static_cast<int64_t>(x) * rotatedW) / outWidth);
        if (rx >= rotatedW) rx = rotatedW - 1;
        if (rx < 0) rx = 0;

        int coord;
        switch (rotation) {
            case 90:
                coord = rotatedW - 1 - rx;        // = srcY
                break;
            case 180:
                coord = srcWidth - 1 - rx;        // = srcX
                break;
            case 270:
                coord = rx;                       // = srcY
                break;
            default: // 0
                coord = rx;                       // = srcX
                break;
        }
        s_mapX[x] = coord;
    }

    for (int y = 0; y < outHeight; ++y) {
        int ry = static_cast<int>((static_cast<int64_t>(y) * rotatedH) / outHeight);
        if (ry >= rotatedH) ry = rotatedH - 1;
        if (ry < 0) ry = 0;

        int coord;
        switch (rotation) {
            case 90:
                coord = ry;                       // = srcX
                break;
            case 180:
                coord = srcHeight - 1 - ry;       // = srcY
                break;
            case 270:
                coord = rotatedH - 1 - ry;        // = srcX
                break;
            default: // 0
                coord = ry;                       // = srcY
                break;
        }
        s_mapY[y] = coord;
    }

    for (int dstY = 0; dstY < outHeight; ++dstY) {
        // 90/270 时 x 轴贡献的是 srcY、y 轴贡献的是 srcX，这里统一成 swap。
        const int srcXBase = swapSides ? s_mapY[dstY] : 0;
        const int srcYBase = swapSides ? 0 : s_mapY[dstY];

        for (int dstX = 0; dstX < outWidth; ++dstX) {
            int srcX = swapSides ? srcXBase : s_mapX[dstX];
            int srcY = swapSides ? s_mapX[dstX] : srcYBase;

            // 表已经保证在 rotated 范围内，这里只做一次防御性 clamp。
            if (srcX < 0) srcX = 0;
            if (srcX >= srcWidth) srcX = srcWidth - 1;
            if (srcY < 0) srcY = 0;
            if (srcY >= srcHeight) srcY = srcHeight - 1;

            const int yIndex = srcY * yRowStride + srcX;
            const int yVal = yData[yIndex];

            const int uvIndex = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixelStride;
            const int u = uData[uvIndex] - 128;
            const int v = vData[uvIndex] - 128;

            // 与 convertYuvToRgbRotated 相同的定点系数。
            const int r = yVal + ((351 * v) >> 8);
            const int g = yVal - ((86 * u + 179 * v) >> 8);
            const int b = yVal + ((443 * u) >> 8);

            const int outIndex = (dstY * outWidth + dstX) * 4;
            bgraOut[outIndex + 0] = clamp(b);
            bgraOut[outIndex + 1] = clamp(g);
            bgraOut[outIndex + 2] = clamp(r);
            bgraOut[outIndex + 3] = 255;
        }
    }
}


#if defined(__ARM_NEON__) || defined(__ARM_NEON)
#include <arm_neon.h>
/**
 * NEON-optimized YUV to RGB conversion (no rotation)
 * Processes 8 pixels at a time
 */
static void convertYuvToRgbNeon(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    int width,
    int height,
    uint8_t* rgbOut
) {
    for (int y = 0; y < height; y++) {
        int x = 0;

        // Process 8 pixels at a time with NEON
        for (; x + 8 <= width; x += 8) {
            // Load 8 Y values
            uint8x8_t yVec = vld1_u8(&yData[y * yRowStride + x]);

            // Load 4 UV values (subsampled) and duplicate for 8 pixels
            uint8_t u0 = uData[(y/2) * uvRowStride + (x/2) * uvPixelStride];
            uint8_t u1 = uData[(y/2) * uvRowStride + ((x+2)/2) * uvPixelStride];
            uint8_t u2 = uData[(y/2) * uvRowStride + ((x+4)/2) * uvPixelStride];
            uint8_t u3 = uData[(y/2) * uvRowStride + ((x+6)/2) * uvPixelStride];

            uint8_t v0 = vData[(y/2) * uvRowStride + (x/2) * uvPixelStride];
            uint8_t v1 = vData[(y/2) * uvRowStride + ((x+2)/2) * uvPixelStride];
            uint8_t v2 = vData[(y/2) * uvRowStride + ((x+4)/2) * uvPixelStride];
            uint8_t v3 = vData[(y/2) * uvRowStride + ((x+6)/2) * uvPixelStride];

            // Convert Y to 16-bit for math
            int16x8_t yWide = vreinterpretq_s16_u16(vmovl_u8(yVec));

            // Create UV vectors (duplicated for each pair of pixels)
            int16_t uArr[8] = {(int16_t)(u0-128), (int16_t)(u0-128), (int16_t)(u1-128), (int16_t)(u1-128),
                              (int16_t)(u2-128), (int16_t)(u2-128), (int16_t)(u3-128), (int16_t)(u3-128)};
            int16_t vArr[8] = {(int16_t)(v0-128), (int16_t)(v0-128), (int16_t)(v1-128), (int16_t)(v1-128),
                              (int16_t)(v2-128), (int16_t)(v2-128), (int16_t)(v3-128), (int16_t)(v3-128)};
            int16x8_t uVec = vld1q_s16(uArr);
            int16x8_t vVec = vld1q_s16(vArr);

            // R = Y + 1.370705 * V  (using 351/256)
            int16x8_t rVec = vaddq_s16(yWide, vshrq_n_s16(vmulq_n_s16(vVec, 351), 8));

            // G = Y - 0.337633 * U - 0.698001 * V (using 86/256, 179/256)
            int16x8_t gVec = vsubq_s16(yWide,
                vshrq_n_s16(vaddq_s16(vmulq_n_s16(uVec, 86), vmulq_n_s16(vVec, 179)), 8));

            // B = Y + 1.732446 * U (using 443/256)
            int16x8_t bVec = vaddq_s16(yWide, vshrq_n_s16(vmulq_n_s16(uVec, 443), 8));

            // Clamp to [0, 255] and convert to uint8
            uint8x8_t rOut = vqmovun_s16(vmaxq_s16(rVec, vdupq_n_s16(0)));
            uint8x8_t gOut = vqmovun_s16(vmaxq_s16(gVec, vdupq_n_s16(0)));
            uint8x8_t bOut = vqmovun_s16(vmaxq_s16(bVec, vdupq_n_s16(0)));

            // Interleave R, G, B and store
            uint8x8x3_t rgb;
            rgb.val[0] = rOut;
            rgb.val[1] = gOut;
            rgb.val[2] = bOut;
            vst3_u8(&rgbOut[(y * width + x) * 3], rgb);
        }

        // Process remaining pixels
        for (; x < width; x++) {
            int yVal = yData[y * yRowStride + x];
            int uvIdx = (y/2) * uvRowStride + (x/2) * uvPixelStride;
            int u = uData[uvIdx] - 128;
            int v = vData[uvIdx] - 128;

            int r = yVal + ((351 * v) >> 8);
            int g = yVal - ((86 * u + 179 * v) >> 8);
            int b = yVal + ((443 * u) >> 8);

            int outIdx = (y * width + x) * 3;
            rgbOut[outIdx + 0] = clamp(r);
            rgbOut[outIdx + 1] = clamp(g);
            rgbOut[outIdx + 2] = clamp(b);
        }
    }
}

/**
 * NEON-optimized YUV to RGB conversion with 270 degree rotation
 * For 270 rotation: srcX = outHeight - 1 - dstY; srcY = dstX
 * Output dimensions are swapped: outWidth = srcHeight, outHeight = srcWidth
 */
static void convertYuvToRgbNeon270(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    int srcWidth,
    int srcHeight,
    uint8_t* rgbOut,
    int outWidth,
    int outHeight
) {
    // For 270 rotation: outWidth = srcHeight, outHeight = srcWidth
    // srcX = outHeight - 1 - dstY = srcWidth - 1 - dstY
    // srcY = dstX

    for (int dstY = 0; dstY < outHeight; dstY++) {
        int srcX = outHeight - 1 - dstY;  // This is srcWidth - 1 - dstY

        int dstX = 0;
        // Process 8 pixels at a time with NEON
        for (; dstX + 8 <= outWidth; dstX += 8) {
            // For 270 rotation, 8 consecutive dst pixels come from 8 consecutive src rows
            // at the same srcX column
            int srcY0 = dstX;
            int srcY1 = dstX + 1;
            int srcY2 = dstX + 2;
            int srcY3 = dstX + 3;
            int srcY4 = dstX + 4;
            int srcY5 = dstX + 5;
            int srcY6 = dstX + 6;
            int srcY7 = dstX + 7;

            // Load 8 Y values from column srcX, rows srcY0-srcY7
            uint8_t yArr[8];
            yArr[0] = yData[srcY0 * yRowStride + srcX];
            yArr[1] = yData[srcY1 * yRowStride + srcX];
            yArr[2] = yData[srcY2 * yRowStride + srcX];
            yArr[3] = yData[srcY3 * yRowStride + srcX];
            yArr[4] = yData[srcY4 * yRowStride + srcX];
            yArr[5] = yData[srcY5 * yRowStride + srcX];
            yArr[6] = yData[srcY6 * yRowStride + srcX];
            yArr[7] = yData[srcY7 * yRowStride + srcX];
            uint8x8_t yVec = vld1_u8(yArr);

            // Load UV values (subsampled by 2)
            int uvX = srcX / 2;
            uint8_t uArr8[8], vArr8[8];
            for (int i = 0; i < 8; i++) {
                int uvY = (dstX + i) / 2;
                int uvIdx = uvY * uvRowStride + uvX * uvPixelStride;
                uArr8[i] = uData[uvIdx];
                vArr8[i] = vData[uvIdx];
            }

            // Convert Y to 16-bit for math
            int16x8_t yWide = vreinterpretq_s16_u16(vmovl_u8(yVec));

            // Create UV vectors
            int16_t uArr[8], vArr[8];
            for (int i = 0; i < 8; i++) {
                uArr[i] = (int16_t)(uArr8[i] - 128);
                vArr[i] = (int16_t)(vArr8[i] - 128);
            }
            int16x8_t uVec = vld1q_s16(uArr);
            int16x8_t vVec = vld1q_s16(vArr);

            // R = Y + 1.370705 * V  (using 351/256)
            int16x8_t rVec = vaddq_s16(yWide, vshrq_n_s16(vmulq_n_s16(vVec, 351), 8));

            // G = Y - 0.337633 * U - 0.698001 * V (using 86/256, 179/256)
            int16x8_t gVec = vsubq_s16(yWide,
                vshrq_n_s16(vaddq_s16(vmulq_n_s16(uVec, 86), vmulq_n_s16(vVec, 179)), 8));

            // B = Y + 1.732446 * U (using 443/256)
            int16x8_t bVec = vaddq_s16(yWide, vshrq_n_s16(vmulq_n_s16(uVec, 443), 8));

            // Clamp to [0, 255] and convert to uint8
            uint8x8_t rOut = vqmovun_s16(vmaxq_s16(rVec, vdupq_n_s16(0)));
            uint8x8_t gOut = vqmovun_s16(vmaxq_s16(gVec, vdupq_n_s16(0)));
            uint8x8_t bOut = vqmovun_s16(vmaxq_s16(bVec, vdupq_n_s16(0)));

            // Interleave R, G, B and store
            uint8x8x3_t rgb;
            rgb.val[0] = rOut;
            rgb.val[1] = gOut;
            rgb.val[2] = bOut;
            vst3_u8(&rgbOut[(dstY * outWidth + dstX) * 3], rgb);
        }

        // Process remaining pixels
        for (; dstX < outWidth; dstX++) {
            int srcY = dstX;

            int yVal = yData[srcY * yRowStride + srcX];
            int uvIdx = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixelStride;
            int u = uData[uvIdx] - 128;
            int v = vData[uvIdx] - 128;

            int r = yVal + ((351 * v) >> 8);
            int g = yVal - ((86 * u + 179 * v) >> 8);
            int b = yVal + ((443 * u) >> 8);

            int outIdx = (dstY * outWidth + dstX) * 3;
            rgbOut[outIdx + 0] = clamp(r);
            rgbOut[outIdx + 1] = clamp(g);
            rgbOut[outIdx + 2] = clamp(b);
        }
    }
}

/**
 * NEON-optimized YUV to RGB conversion with 90 degree rotation
 * For 90 rotation: srcX = dstY; srcY = outWidth - 1 - dstX
 */
static void convertYuvToRgbNeon90(
    const uint8_t* yData,
    const uint8_t* uData,
    const uint8_t* vData,
    int yRowStride,
    int uvRowStride,
    int uvPixelStride,
    int srcWidth,
    int srcHeight,
    uint8_t* rgbOut,
    int outWidth,
    int outHeight
) {
    for (int dstY = 0; dstY < outHeight; dstY++) {
        int srcX = dstY;

        int dstX = 0;
        for (; dstX + 8 <= outWidth; dstX += 8) {
            // For 90 rotation, srcY = outWidth - 1 - dstX
            uint8_t yArr[8];
            for (int i = 0; i < 8; i++) {
                int srcY = outWidth - 1 - (dstX + i);
                yArr[i] = yData[srcY * yRowStride + srcX];
            }
            uint8x8_t yVec = vld1_u8(yArr);

            // Load UV values
            int uvX = srcX / 2;
            uint8_t uArr8[8], vArr8[8];
            for (int i = 0; i < 8; i++) {
                int srcY = outWidth - 1 - (dstX + i);
                int uvY = srcY / 2;
                int uvIdx = uvY * uvRowStride + uvX * uvPixelStride;
                uArr8[i] = uData[uvIdx];
                vArr8[i] = vData[uvIdx];
            }

            int16x8_t yWide = vreinterpretq_s16_u16(vmovl_u8(yVec));

            int16_t uArr[8], vArr[8];
            for (int i = 0; i < 8; i++) {
                uArr[i] = (int16_t)(uArr8[i] - 128);
                vArr[i] = (int16_t)(vArr8[i] - 128);
            }
            int16x8_t uVec = vld1q_s16(uArr);
            int16x8_t vVec = vld1q_s16(vArr);

            int16x8_t rVec = vaddq_s16(yWide, vshrq_n_s16(vmulq_n_s16(vVec, 351), 8));
            int16x8_t gVec = vsubq_s16(yWide,
                vshrq_n_s16(vaddq_s16(vmulq_n_s16(uVec, 86), vmulq_n_s16(vVec, 179)), 8));
            int16x8_t bVec = vaddq_s16(yWide, vshrq_n_s16(vmulq_n_s16(uVec, 443), 8));

            uint8x8_t rOut = vqmovun_s16(vmaxq_s16(rVec, vdupq_n_s16(0)));
            uint8x8_t gOut = vqmovun_s16(vmaxq_s16(gVec, vdupq_n_s16(0)));
            uint8x8_t bOut = vqmovun_s16(vmaxq_s16(bVec, vdupq_n_s16(0)));

            uint8x8x3_t rgb;
            rgb.val[0] = rOut;
            rgb.val[1] = gOut;
            rgb.val[2] = bOut;
            vst3_u8(&rgbOut[(dstY * outWidth + dstX) * 3], rgb);
        }

        for (; dstX < outWidth; dstX++) {
            int srcY = outWidth - 1 - dstX;

            int yVal = yData[srcY * yRowStride + srcX];
            int uvIdx = (srcY / 2) * uvRowStride + (srcX / 2) * uvPixelStride;
            int u = uData[uvIdx] - 128;
            int v = vData[uvIdx] - 128;

            int r = yVal + ((351 * v) >> 8);
            int g = yVal - ((86 * u + 179 * v) >> 8);
            int b = yVal + ((443 * u) >> 8);

            int outIdx = (dstY * outWidth + dstX) * 3;
            rgbOut[outIdx + 0] = clamp(r);
            rgbOut[outIdx + 1] = clamp(g);
            rgbOut[outIdx + 2] = clamp(b);
        }
    }
}
#endif

/**
 * JNI method for YUV to RGB conversion with rotation
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_YuvConverter_nativeConvertYuvToRgb(
    JNIEnv* env,
    jobject clazz,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint srcWidth,
    jint srcHeight,
    jint rotation,
    jobject rgbBuffer,
    jint outWidth,
    jint outHeight
) {
    auto* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* rgbOut = static_cast<uint8_t*>(env->GetDirectBufferAddress(rgbBuffer));

    if (!yData || !uData || !vData || !rgbOut) {
        LOGE("Failed to get buffer addresses");
        return;
    }

#if defined(__ARM_NEON__) || defined(__ARM_NEON)
    // Use NEON optimized versions based on rotation
    if (rotation == 0) {
        convertYuvToRgbNeon(yData, uData, vData, yRowStride, uvRowStride, uvPixelStride,
                           srcWidth, srcHeight, rgbOut);
        return;
    } else if (rotation == 270) {
        convertYuvToRgbNeon270(yData, uData, vData, yRowStride, uvRowStride, uvPixelStride,
                              srcWidth, srcHeight, rgbOut, outWidth, outHeight);
        return;
    } else if (rotation == 90) {
        convertYuvToRgbNeon90(yData, uData, vData, yRowStride, uvRowStride, uvPixelStride,
                             srcWidth, srcHeight, rgbOut, outWidth, outHeight);
        return;
    }
#endif

    // Fallback to scalar with rotation
    convertYuvToRgbRotated(yData, uData, vData, yRowStride, uvRowStride, uvPixelStride,
                          srcWidth, srcHeight, rotation, rgbOut, outWidth, outHeight);
}

/**
 * JNI method for YUV to BGRA conversion with rotation (preview path)
 *
 * 对应 Kotlin: YuvConverter.nativeConvertYuvToBgra(...)
 * 输出缓冲必须是 direct ByteBuffer，容量 >= outWidth * outHeight * 4。
 * 小端 ARGB_8888 的内存布局即 B,G,R,A。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_YuvConverter_nativeConvertYuvToBgra(
    JNIEnv* env,
    jobject clazz,
    jobject yBuffer,
    jobject uBuffer,
    jobject vBuffer,
    jint yRowStride,
    jint uvRowStride,
    jint uvPixelStride,
    jint srcWidth,
    jint srcHeight,
    jint rotation,
    jobject bgraBuffer,
    jint outWidth,
    jint outHeight
) {
    auto* yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto* uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto* vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    auto* bgraOut = static_cast<uint8_t*>(env->GetDirectBufferAddress(bgraBuffer));

    if (!yData || !uData || !vData || !bgraOut) {
        LOGE("Failed to get buffer addresses (BGRA)");
        return;
    }

    if (outWidth <= 0 || outHeight <= 0 || srcWidth <= 0 || srcHeight <= 0) {
        LOGE("Invalid dimensions (BGRA): src=%dx%d out=%dx%d",
             srcWidth, srcHeight, outWidth, outHeight);
        return;
    }

    // 输出容量兜底：容量不足时直接返回，避免写越界（真机上这类越界极难定位）。
    const jlong capacity = env->GetDirectBufferCapacity(bgraBuffer);
    const jlong required = static_cast<jlong>(outWidth) * static_cast<jlong>(outHeight) * 4;
    if (capacity >= 0 && capacity < required) {
        LOGE("BGRA output buffer too small: capacity=%lld < required=%lld",
             static_cast<long long>(capacity), static_cast<long long>(required));
        return;
    }

    convertYuvToBgraRotated(yData, uData, vData, yRowStride, uvRowStride, uvPixelStride,
                            srcWidth, srcHeight, rotation, bgraOut, outWidth, outHeight);
}

/**
 * JNI method to get library capabilities
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_YuvConverter_nativeHasNeonSupport(
    JNIEnv* env,
    jobject clazz
) {
#if defined(__ARM_NEON__) || defined(__ARM_NEON)
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

