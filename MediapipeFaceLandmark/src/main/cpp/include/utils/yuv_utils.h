/**
 * @file yuv_utils.h
 * @brief YUV image processing utilities
 * 
 * Provides utilities for handling YUV image data from Android Camera2 API,
 * including format detection, buffer preparation, and rotation matrices.
 */

#ifndef MEDIAPIPE_FACE_YUV_UTILS_H
#define MEDIAPIPE_FACE_YUV_UTILS_H

#include <cstdint>
#include <vector>
#include <cstring>
#include "MNN/ImageProcess.hpp"
#include "MNN/Matrix.h"
#include "common/logging.h"

namespace mediapipe_face {
namespace yuv {

/**
 * @brief Determine MNN ImageFormat from YUV_420_888 parameters
 * 
 * Android YUV_420_888 is a flexible format that can be:
 * - NV21 (Y + VU interleaved): uvPixelStride=2, V before U
 * - NV12 (Y + UV interleaved): uvPixelStride=2, U before V
 * - I420 (Y + U + V separate): uvPixelStride=1
 * 
 * @param uvPixelStride The pixel stride of U/V planes
 * @param uBuffer Pointer to U plane
 * @param vBuffer Pointer to V plane
 * @return MNN ImageFormat
 */
inline MNN::CV::ImageFormat detectFormat(int uvPixelStride, 
                                         const uint8_t* uBuffer, 
                                         const uint8_t* vBuffer) {
    if (uvPixelStride == 1) {
        return MNN::CV::YUV_I420;
    } else if (uvPixelStride == 2) {
        // Check if U comes before V in memory
        if (uBuffer < vBuffer) {
            return MNN::CV::YUV_NV12;
        } else {
            return MNN::CV::YUV_NV21;
        }
    }
    
    FACE_LOGW("Unknown uvPixelStride=%d, defaulting to NV21", uvPixelStride);
    return MNN::CV::YUV_NV21;
}

/**
 * @brief Prepare YUV data into contiguous buffer for MNN ImageProcess
 * 
 * MNN expects YUV data in a contiguous buffer:
 * - For NV21/NV12: Y plane followed by interleaved UV/VU
 * - For I420: Y plane, U plane, V plane
 * 
 * @param yData Y plane data
 * @param uData U plane data  
 * @param vData V plane data
 * @param yRowStride Y plane row stride
 * @param uvRowStride UV plane row stride
 * @param uvPixelStride UV pixel stride
 * @param width Image width
 * @param height Image height
 * @param format Detected YUV format
 * @param outBuffer Output buffer (will be resized)
 * @return Size of data written to outBuffer
 */
inline size_t prepareBuffer(const uint8_t* yData,
                            const uint8_t* uData,
                            const uint8_t* vData,
                            int yRowStride,
                            int uvRowStride,
                            int uvPixelStride,
                            int width, int height,
                            MNN::CV::ImageFormat format,
                            std::vector<uint8_t>& outBuffer) {
    // Calculate buffer size: Y plane + UV plane (half height)
    size_t ySize = width * height;
    size_t uvSize = width * height / 2;
    outBuffer.resize(ySize + uvSize);
    
    size_t offset = 0;
    
    // Copy Y plane (handle stride)
    if (yRowStride == width) {
        memcpy(outBuffer.data(), yData, ySize);
        offset = ySize;
    } else {
        for (int y = 0; y < height; y++) {
            memcpy(outBuffer.data() + y * width, yData + y * yRowStride, width);
        }
        offset = ySize;
    }
    
    int uvHeight = height / 2;
    int uvWidth = width / 2;
    
    if (format == MNN::CV::YUV_I420) {
        // I420: Copy U plane, then V plane
        if (uvPixelStride == 1 && uvRowStride == uvWidth) {
            memcpy(outBuffer.data() + offset, uData, uvWidth * uvHeight);
            offset += uvWidth * uvHeight;
            memcpy(outBuffer.data() + offset, vData, uvWidth * uvHeight);
            offset += uvWidth * uvHeight;
        } else {
            for (int y = 0; y < uvHeight; y++) {
                for (int x = 0; x < uvWidth; x++) {
                    outBuffer[offset++] = uData[y * uvRowStride + x * uvPixelStride];
                }
            }
            for (int y = 0; y < uvHeight; y++) {
                for (int x = 0; x < uvWidth; x++) {
                    outBuffer[offset++] = vData[y * uvRowStride + x * uvPixelStride];
                }
            }
        }
    } else {
        // NV12 or NV21: Interleaved UV plane
        const uint8_t* uvStart = (format == MNN::CV::YUV_NV12) ? uData : vData;
        if (uvRowStride == width) {
            memcpy(outBuffer.data() + offset, uvStart, width * uvHeight);
            offset += width * uvHeight;
        } else {
            for (int y = 0; y < uvHeight; y++) {
                memcpy(outBuffer.data() + offset + y * width, uvStart + y * uvRowStride, width);
            }
            offset += width * uvHeight;
        }
    }
    
    return offset;
}

/**
 * @brief Zero-copy view of a camera YUV buffer that MNN can consume directly
 *
 * Only contiguous NV12/NV21 layouts (Y plane immediately followed by the
 * interleaved UV plane, row stride == width) qualify; everything else must be
 * repacked with prepareBuffer()/prepareBufferRect().
 */
struct YuvFastView {
    const uint8_t* data = nullptr;                  // Y origin; Y + UV are contiguous
    MNN::CV::ImageFormat format = MNN::CV::YUV_NV21;
};

/**
 * @brief Try to describe the camera YUV planes as one contiguous MNN buffer
 *
 * Strictly validates the layout: interleaved UV (pixel stride 2), row strides
 * equal to the frame width, and the UV pointer pair placed exactly one byte
 * after the Y plane (NV12: U then V, NV21: V then U). Returns false instead of
 * guessing whenever anything does not match, so callers must keep a repack
 * fallback.
 *
 * @return true when \p out is a valid view, false otherwise
 */
inline bool tryFastYuvView(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                           int yRowStride, int uvRowStride, int uvPixelStride,
                           int width, int height, YuvFastView& out) {
    if (y == nullptr || u == nullptr || v == nullptr) {
        return false;
    }
    if (width <= 0 || height <= 0) {
        return false;
    }
    if (uvPixelStride != 2) {
        return false;                               // I420 / non-interleaved: must repack
    }
    if (yRowStride != width || uvRowStride != width) {
        return false;                               // padded rows: must repack
    }

    const size_t ySize = static_cast<size_t>(width) * static_cast<size_t>(height);
    const uintptr_t yAddr = reinterpret_cast<uintptr_t>(y);
    const uintptr_t uAddr = reinterpret_cast<uintptr_t>(u);
    const uintptr_t vAddr = reinterpret_cast<uintptr_t>(v);

    // UV must start right after Y and the two samples must be adjacent bytes.
    if (uAddr == yAddr + ySize && vAddr == uAddr + 1) {
        out.data = y;
        out.format = MNN::CV::YUV_NV12;
        return true;
    }
    if (vAddr == yAddr + ySize && uAddr == vAddr + 1) {
        out.data = y;
        out.format = MNN::CV::YUV_NV21;
        return true;
    }
    return false;
}

/**
 * @brief Repack only a rectangular ROI of a YUV frame into a contiguous buffer
 *
 * Produces exactly the layout MNN expects for a (roiW x roiH) image, so the
 * caller can call convert() with the ROI dimensions instead of the full frame.
 * Y is copied row by row from (roiX, roiY); the chroma planes start at
 * (roiX/2, roiY/2). Supports I420 (separate U and V planes) and NV12/NV21
 * (interleaved UV, uvPixelStride == 2).
 *
 * roiX/roiY must be even (UV subsampling); they are defensively rounded down.
 * Callers that sample with the original (roiW, roiH) must request an even,
 * in-frame rectangle; out-of-range input is clamped here only as a memory-safety
 * net and the returned size is authoritative for the packed result. Returns 0
 * (and leaves the buffer unusable) when the layout cannot be honoured, so the
 * caller can fall back to a full-frame repack.
 *
 * @return Number of bytes written to outBuffer, or 0 on failure
 */
inline size_t prepareBufferRect(const uint8_t* yData,
                                const uint8_t* uData,
                                const uint8_t* vData,
                                int yRowStride,
                                int uvRowStride,
                                int uvPixelStride,
                                int width, int height,
                                int roiX, int roiY,
                                int roiW, int roiH,
                                MNN::CV::ImageFormat format,
                                std::vector<uint8_t>& outBuffer) {
    if (yData == nullptr || uData == nullptr || vData == nullptr) {
        return 0;
    }
    if (width <= 0 || height <= 0 || yRowStride <= 0 || uvRowStride <= 0 ||
        uvPixelStride <= 0) {
        return 0;
    }
    // 4:2:0 chroma subsampling requires even frame dimensions; odd sizes fall
    // back to the whole-frame repack (which the camera never produces anyway).
    if ((width & 1) != 0 || (height & 1) != 0) {
        return 0;
    }
    if (yRowStride < width) {
        return 0;
    }
    if (format == MNN::CV::YUV_I420) {
        if (uvRowStride < width / 2) {
            return 0;
        }
    } else {
        // NV12/NV21 require interleaved UV samples.
        if (uvPixelStride != 2 || uvRowStride < width) {
            return 0;
        }
    }

    // UV subsampling needs an even origin and an even extent.
    if (roiX < 0) {
        roiX = 0;
    }
    if (roiY < 0) {
        roiY = 0;
    }
    roiX &= ~1;
    roiY &= ~1;
    if (roiX >= width - 1 || roiY >= height - 1 || roiW <= 0 || roiH <= 0) {
        return 0;
    }
    if (roiX + roiW > width) {
        roiW = width - roiX;
    }
    if (roiY + roiH > height) {
        roiH = height - roiY;
    }
    roiW &= ~1;
    roiH &= ~1;
    if (roiW <= 0 || roiH <= 0) {
        return 0;
    }

    const int uvW = roiW / 2;
    const int uvH = roiH / 2;
    if (roiX + roiW > yRowStride) {
        return 0;
    }
    if (format == MNN::CV::YUV_I420) {
        // Conservative bound for strided I420 planes: the last sampled byte is
        // ((roiX/2) + uvW - 1) * uvPixelStride, so require the one-past-end
        // position (uvX + uvW) * uvPixelStride to fit as well.
        if ((roiX / 2 + uvW) * uvPixelStride > uvRowStride) {
            return 0;
        }
    } else {
        if (roiX + roiW > uvRowStride) {
            return 0;
        }
    }

    const size_t ySize = static_cast<size_t>(roiW) * static_cast<size_t>(roiH);
    // Chroma is half resolution in both directions (2x2 subsampling): for both
    // I420 and NV12/NV21 this is exactly roiW * roiH / 2 bytes.
    outBuffer.resize(ySize + static_cast<size_t>(roiW) * static_cast<size_t>(roiH) / 2);

    // ---- Y plane ----
    uint8_t* dst = outBuffer.data();
    if (yRowStride == width && roiW == width) {
        // Full-width horizontal band: one contiguous copy.
        memcpy(dst, yData + static_cast<size_t>(roiY) * static_cast<size_t>(yRowStride), ySize);
    } else {
        for (int row = 0; row < roiH; ++row) {
            memcpy(dst + static_cast<size_t>(row) * static_cast<size_t>(roiW),
                   yData + static_cast<size_t>(roiY + row) * static_cast<size_t>(yRowStride) + roiX,
                   static_cast<size_t>(roiW));
        }
    }

    // ---- Chroma planes ----
    uint8_t* uvDst = dst + ySize;
    const int uvX = roiX / 2;
    const int uvY = roiY / 2;

    if (format == MNN::CV::YUV_I420) {
        // I420: separate half-resolution U plane followed by the V plane.
        const uint8_t* planes[2] = {uData, vData};
        size_t offset = 0;
        for (int plane = 0; plane < 2; ++plane) {
            const uint8_t* src = planes[plane];
            for (int row = 0; row < uvH; ++row) {
                const uint8_t* srcRow =
                    src + static_cast<size_t>(uvY + row) * static_cast<size_t>(uvRowStride);
                if (uvPixelStride == 1) {
                    memcpy(uvDst + offset, srcRow + uvX, static_cast<size_t>(uvW));
                    offset += static_cast<size_t>(uvW);
                } else {
                    for (int col = 0; col < uvW; ++col) {
                        uvDst[offset++] = srcRow[(uvX + col) * uvPixelStride];
                    }
                }
            }
        }
    } else {
        // NV12/NV21: interleaved UV pairs. Each UV row covers roiW bytes and
        // starts at byte roiX (== uvX * 2) of the source row.
        const uint8_t* uvSrc = (format == MNN::CV::YUV_NV12) ? uData : vData;
        size_t offset = 0;
        for (int row = 0; row < uvH; ++row) {
            memcpy(uvDst + offset,
                   uvSrc + static_cast<size_t>(uvY + row) * static_cast<size_t>(uvRowStride) + roiX,
                   static_cast<size_t>(roiW));
            offset += static_cast<size_t>(roiW);
        }
    }

    return ySize + static_cast<size_t>(roiW) * static_cast<size_t>(roiH) / 2;
}

/**
 * @brief Create rotation matrix for MNN ImageProcess
 * 
 * MNN Matrix uses destination-to-source convention:
 * For each destination pixel, the matrix tells where to sample from source.
 * 
 * @param rotation Rotation in degrees (0, 90, 180, 270) - desired OUTPUT rotation
 * @param srcWidth Source image width
 * @param srcHeight Source image height
 * @param dstWidth Destination image width  
 * @param dstHeight Destination image height
 * @return MNN Matrix for the transformation
 */
inline MNN::CV::Matrix createRotationMatrix(int rotation,
                                             int srcWidth, int srcHeight,
                                             int dstWidth, int dstHeight) {
    MNN::CV::Matrix matrix;
    matrix.setIdentity();
    
    float cx_dst = static_cast<float>(dstWidth) / 2.0f;
    float cy_dst = static_cast<float>(dstHeight) / 2.0f;
    float cx_src = static_cast<float>(srcWidth) / 2.0f;
    float cy_src = static_cast<float>(srcHeight) / 2.0f;
    
    // For dst->src convention, we apply inverse rotation
    switch (rotation) {
        case 0:
            break;
            
        case 90:
            matrix.postTranslate(-cx_dst, -cy_dst);
            matrix.postRotate(-90.0f);
            matrix.postTranslate(cx_src, cy_src);
            break;
            
        case 180:
            matrix.postTranslate(-cx_dst, -cy_dst);
            matrix.postRotate(180.0f);
            matrix.postTranslate(cx_src, cy_src);
            break;
            
        case 270:
            matrix.postTranslate(-cx_dst, -cy_dst);
            matrix.postRotate(90.0f);
            matrix.postTranslate(cx_src, cy_src);
            break;
            
        default:
            FACE_LOGE("Unsupported rotation: %d, using 0", rotation);
            break;
    }
    
    return matrix;
}

/**
 * @brief Get output dimensions after rotation
 * 
 * @param srcWidth Source width
 * @param srcHeight Source height
 * @param rotation Rotation in degrees
 * @param outWidth Output width (will be set)
 * @param outHeight Output height (will be set)
 */
inline void getRotatedDimensions(int srcWidth, int srcHeight, int rotation,
                                  int& outWidth, int& outHeight) {
    if (rotation == 90 || rotation == 270) {
        outWidth = srcHeight;
        outHeight = srcWidth;
    } else {
        outWidth = srcWidth;
        outHeight = srcHeight;
    }
}

} // namespace yuv
} // namespace mediapipe_face

#endif // MEDIAPIPE_FACE_YUV_UTILS_H
