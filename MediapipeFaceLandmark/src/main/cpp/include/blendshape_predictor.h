/**
 * @file blendshape_predictor.h
 * @brief BlendShape Prediction using MediaPipe model with MNN
 * 
 * Predicts 52 ARKit-compatible blendshape coefficients from facial landmarks.
 * Includes head rotation compensation for more accurate expressions.
 */

#ifndef MEDIAPIPE_FACE_BLENDSHAPE_PREDICTOR_H
#define MEDIAPIPE_FACE_BLENDSHAPE_PREDICTOR_H

#include <string>
#include <vector>
#include <memory>
#include "common/types.h"
#include "MNN/Interpreter.hpp"
#include "MNN/Tensor.hpp"

namespace mediapipe_face {

/**
 * @brief BlendShape Predictor using MediaPipe BlendShape model
 * 
 * This class handles:
 * - Loading and running the blendshape MNN model
 * - Extracting 146 landmark subset from 478 landmarks
 * - Predicting 52 ARKit-compatible blendshape coefficients
 * - Head rotation compensation
 */
class BlendShapePredictor {
public:
    BlendShapePredictor();
    ~BlendShapePredictor();
    
    // Prevent copying
    BlendShapePredictor(const BlendShapePredictor&) = delete;
    BlendShapePredictor& operator=(const BlendShapePredictor&) = delete;

    /**
     * @brief Load the blendshape prediction model
     * @param mnn_path Path to the MNN model file
     * @param num_threads Number of threads for inference
     * @param use_vulkan true = MNN_FORWARD_VULKAN, false = MNN_FORWARD_CPU
     * @return true if successful
     *
     * Can be called again on a live instance to switch the backend: the old
     * session/model is released first.
     */
    bool loadModel(const std::string& mnn_path, int num_threads = 2,
                   bool use_vulkan = true);

    /**
     * @brief Predict blendshapes from facial landmarks
     * @param landmarks 478 facial landmarks (normalized [0,1])
     * @param image_width Original image width (for denormalization)
     * @param image_height Original image height
     * @param head_pose Optional head pose for rotation compensation
     * @return BlendShape result with 52 coefficients
     */
    BlendShapeResult predict(const std::vector<FaceLandmark>& landmarks,
                              int image_width, int image_height,
                              const HeadPose* head_pose = nullptr);

    /**
     * @brief Apply head rotation compensation to blendshape values
     * Based on OpenSeeFace approach to compensate for pose-induced artifacts
     */
    static void applyHeadRotationCompensation(BlendShapeResult& result,
                                               const HeadPose& pose);

    [[nodiscard]] std::string lastError() const { return last_error_; }

    /**
     * @brief 当前 session **真实**跑在 Vulkan 上吗？
     *
     * 由 loadModel() 在 createSession 之后用 MNN getSessionInfo(BACKENDS) 探测，
     * 而不是「请求了什么」。设备上装不到 Vulkan 后端时 createSession(VULKAN)
     * 会静默退到 CPU（backupType 默认 CPU），所以必须看这个值。
     */
    [[nodiscard]] bool isVulkanActive() const { return vulkan_active_; }

private:
    // Subset of 478 landmarks required for BlendShape model (146 points)
    static constexpr int kLandmarkSubsetSize = 146;
    static const int kLandmarkSubsetIndices[146];

    // Extract landmark subset and convert to tensor format
    static void prepareLandmarkTensor(const std::vector<FaceLandmark>& landmarks,
                                int image_width, int image_height,
                                float* output);

    // MNN components
    std::shared_ptr<MNN::Interpreter> interpreter_;
    MNN::Session* session_ = nullptr;
    MNN::Tensor* input_tensor_ = nullptr;

    // ---- Reusable per-frame resources (avoid re-allocating on the hot path) ----
    // Host tensor the landmark subset is written into before uploading.
    std::unique_ptr<MNN::Tensor> input_host_tensor_;
    // Host tensor for the model output, created lazily after the first run.
    std::unique_ptr<MNN::Tensor> output_host_tensor_;

    // Input dimensions: [1, 146, 2] - 146 landmarks with X, Y coordinates
    int input_h_ = 146;
    int input_w_ = 2;

    // createSession 之后探测到的真实后端（true = 确实跑在 Vulkan 上）。
    bool vulkan_active_ = false;

    std::string last_error_;
};

} // namespace mediapipe_face

#endif // MEDIAPIPE_FACE_BLENDSHAPE_PREDICTOR_H
