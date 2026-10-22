#include <jni.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <android/log.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

// Use new modular implementation
#include "mediapipe_face.h"

#define LOG_TAG "MediaPipeFaceJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace mediapipe_face;

// ---------------------------------------------------------------------------
// Multi-engine (GPU 多路并行) support
//
// N 个互相独立的引擎可以共存于同一个进程：
//
//   slot 0 — 主引擎。跑 Vulkan，负责人脸检测 + 一路关键点推理。
//   slot 1..3 — 额外的关键点引擎。同样跑 Vulkan（GPU 并行），只加载 landmarker
//               模型，从同一个帧队列里抢帧，让 GPU 一直有活儿干，把利用率顶满。
//
// 每个引擎的 MNN session、时序滤波状态完全独立，只有模型路径和线程数共享；
// 帧的顺序由 Kotlin 侧的重排阶段（在 CPU 线程上）负责还原。
//
// 引擎按**线程**选择（thread_local）：每个推理线程在启动时 bind 一次自己的槽位，
// 之后的 per-frame JNI 调用并不需要改签名，宏会把 g_faceDetector 之流落到当前
// 线程绑定的槽位上。默认槽位是 0，所以单引擎行为与历史版本完全一致。
// ---------------------------------------------------------------------------
struct EngineSlot {
    std::unique_ptr<FaceDetector> detector;
    std::unique_ptr<FaceLandmarker> landmarker;
    std::unique_ptr<BlendShapePredictor> blendshape;
    // 该槽位当前生效的 MNN 后端：true = MNN_FORWARD_VULKAN, false = MNN_FORWARD_CPU
    std::atomic<bool> useVulkan{true};
    HeadPose lastHeadPose;
    bool hasHeadPose = false;
};

// slot 0 + 最多 3 路并行关键点引擎。与 Kotlin 侧
// MediaPipeFaceDetector.MAX_EXTRA_ENGINES 保持一致。
static constexpr int kMaxEngines = 4;
static EngineSlot g_engines[kMaxEngines];
static thread_local int g_engine_index = 0;

// 兼容旧代码：所有 per-frame 实现都用这几个宏，落到当前线程绑定的槽位。
#define g_faceDetector        (g_engines[g_engine_index].detector)
#define g_faceLandmarker      (g_engines[g_engine_index].landmarker)
#define g_blendshapePredictor (g_engines[g_engine_index].blendshape)
#define g_use_vulkan          (g_engines[g_engine_index].useVulkan)
#define g_lastHeadPose        (g_engines[g_engine_index].lastHeadPose)
#define g_hasHeadPose         (g_engines[g_engine_index].hasHeadPose)

// Model paths / thread count captured in nativeInit so the MNN backend can be
// rebuilt at runtime without going back to Kotlin for the model files again.
// NOTE: std::string copies — never keep the GetStringUTFChars pointers.
static std::string g_detector_model_path;
static std::string g_landmarker_model_path;
static std::string g_blendshape_model_path;  // empty => blendshape disabled
static int g_num_threads = 2;

// Serializes model creation. Per-frame inference does NOT take this lock:
// callers must not switch the backend while the capture pipeline is running
// (MediaPipeFaceDetector.setBackend() documents and enforces that contract).
static std::mutex g_model_mutex;

/**
 * 头部姿态解算用的是**全局**几何管线 + 全局时序滤波（FaceLandmarker 里的
 * s_geometry_pipeline_ / s_head_pose_*_filter）。多路并行时几条推理线程会
 * 同时算同一帧的姿态，必须串行化，否则既是数据竞争、也会把滤波状态搅乱。
 *
 * 这把锁只覆盖「几何解算 + 滤波」这段（远小于一次推理），不会拖慢各路的
 * 并行推理。
 */
static std::mutex g_head_pose_mutex;

// Creates the three models on the requested backend using the cached paths.
// Caller must hold g_model_mutex. Returns false only when a critical model
// (detector / landmarker) fails; the blendshape model stays optional.
static bool createModelsLocked(bool use_vulkan) {
    g_faceDetector.reset();
    g_faceLandmarker.reset();
    g_blendshapePredictor.reset();
    
    auto detector = std::make_unique<FaceDetector>();
    if (!detector->loadModel(g_detector_model_path, g_num_threads, use_vulkan)) {
        LOGE("Failed to load face detector: %s", detector->lastError().c_str());
        return false;
    }
    LOGI("Face detector loaded successfully");
    
    auto landmarker = std::make_unique<FaceLandmarker>();
    if (!landmarker->loadModel(g_landmarker_model_path, g_num_threads, use_vulkan)) {
        LOGE("Failed to load face landmarker: %s", landmarker->lastError().c_str());
        return false;
    }
    LOGI("Face landmarker loaded successfully");
    
    std::unique_ptr<BlendShapePredictor> blendshape;
    if (!g_blendshape_model_path.empty()) {
        blendshape = std::make_unique<BlendShapePredictor>();
        if (!blendshape->loadModel(g_blendshape_model_path, g_num_threads, use_vulkan)) {
            LOGE("Failed to load blendshape predictor: %s",
                 blendshape->lastError().c_str());
            blendshape.reset();
            LOGI("BlendShape prediction will be disabled");
        } else {
            LOGI("BlendShape predictor loaded successfully");
        }
    }
    
    g_faceDetector = std::move(detector);
    g_faceLandmarker = std::move(landmarker);
    g_blendshapePredictor = std::move(blendshape);
    return true;
}

// Cached head pose for compensation lives per engine slot (see EngineSlot).

/**
 * Initialize face detector, landmarker, and blendshape predictor models
 * @param assetManager Android AssetManager
 * @param detectorModelPath Path to face detector model in assets
 * @param landmarkerModelPath Path to face landmarker model in assets
 * @param blendshapeModelPath Path to blendshape model in assets (optional, can be null)
 * @param numThreads Number of threads for inference
 * @return true if successful
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeInit(
        JNIEnv* env,
        jobject /* this */,
        jobject assetManager,
        jstring detectorModelPath,
        jstring landmarkerModelPath,
        jstring blendshapeModelPath,
        jint numThreads) {
    
    LOGI("Initializing MediaPipe Face Detection with BlendShape support");
    
    if (detectorModelPath == nullptr || landmarkerModelPath == nullptr) {
        LOGE("Model paths must not be null");
        return JNI_FALSE;
    }
    
    const char* detectorPath = env->GetStringUTFChars(detectorModelPath, nullptr);
    const char* landmarkerPath = env->GetStringUTFChars(landmarkerModelPath, nullptr);
    const char* blendshapePath = blendshapeModelPath ?
                                  env->GetStringUTFChars(blendshapeModelPath, nullptr) : nullptr;
    
    // Keep std::string copies of the paths so the backend can be switched later.
    const std::string detectorPathCopy = (detectorPath != nullptr) ? detectorPath : "";
    const std::string landmarkerPathCopy = (landmarkerPath != nullptr) ? landmarkerPath : "";
    const std::string blendshapePathCopy = (blendshapePath != nullptr) ? blendshapePath : "";
    
    if (detectorPath != nullptr) {
        env->ReleaseStringUTFChars(detectorModelPath, detectorPath);
    }
    if (landmarkerPath != nullptr) {
        env->ReleaseStringUTFChars(landmarkerModelPath, landmarkerPath);
    }
    if (blendshapePath != nullptr) {
        env->ReleaseStringUTFChars(blendshapeModelPath, blendshapePath);
    }
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    g_detector_model_path = detectorPathCopy;
    g_landmarker_model_path = landmarkerPathCopy;
    g_blendshape_model_path = blendshapePathCopy;
    g_num_threads = static_cast<int>(numThreads);
    g_hasHeadPose = false;
    
    // Create detector + landmarker (+ optional blendshape) on the current backend.
    // If Vulkan cannot be created on this device, fall back to the CPU backend
    // instead of failing initialization outright.
    bool ok = createModelsLocked(g_use_vulkan.load());
    if (!ok && g_use_vulkan.load()) {
        LOGI("Vulkan backend unavailable, falling back to MNN CPU backend");
        ok = createModelsLocked(false);
        if (ok) {
            g_use_vulkan.store(false);
        }
    }
    
    if (!ok) {
        LOGE("Failed to initialize MediaPipe face models");
        return JNI_FALSE;
    }
    
    LOGI("MediaPipe face models initialized (backend=%s, threads=%d)",
         g_use_vulkan.load() ? "Vulkan" : "CPU", g_num_threads);
    return JNI_TRUE;
}

/**
 * Switch the MNN inference backend of all three models at runtime.
 *
 * The models are reloaded in place from the paths cached by nativeInit, so the
 * smoothing filter configuration and state are preserved. On failure the old
 * backend is restored (best effort) and JNI_FALSE is returned.
 *
 * @param useVulkan true = MNN_FORWARD_VULKAN, false = MNN_FORWARD_CPU
 * @return true if the requested backend is active after the call
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetBackend(
        JNIEnv* env,
        jobject /* this */,
        jboolean useVulkan) {
    
    const bool want_vulkan = (useVulkan == JNI_TRUE);
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    if (!g_faceDetector || !g_faceLandmarker) {
        LOGE("Cannot switch MNN backend: models not initialized");
        return JNI_FALSE;
    }
    if (g_detector_model_path.empty() || g_landmarker_model_path.empty()) {
        LOGE("Cannot switch MNN backend: model paths were not cached");
        return JNI_FALSE;
    }
    
    const bool previous_vulkan = g_use_vulkan.load();
    if (want_vulkan == previous_vulkan) {
        LOGI("MNN backend already %s", want_vulkan ? "Vulkan" : "CPU");
        return JNI_TRUE;
    }
    
    LOGI("Switching MNN backend to %s (reloading models)...",
         want_vulkan ? "Vulkan" : "CPU");
    
    // Critical model #1: face detector.
    if (!g_faceDetector->loadModel(g_detector_model_path, g_num_threads, want_vulkan)) {
        LOGE("Face detector reload on %s failed: %s",
             want_vulkan ? "Vulkan" : "CPU", g_faceDetector->lastError().c_str());
        g_faceDetector->loadModel(g_detector_model_path, g_num_threads, previous_vulkan);
        LOGI("Rolled back to %s", previous_vulkan ? "Vulkan" : "CPU");
        return JNI_FALSE;
    }
    
    // Critical model #2: face landmarker.
    if (!g_faceLandmarker->loadModel(g_landmarker_model_path, g_num_threads, want_vulkan)) {
        LOGE("Face landmarker reload on %s failed: %s",
             want_vulkan ? "Vulkan" : "CPU", g_faceLandmarker->lastError().c_str());
        // Roll back both critical models so the pipeline stays usable.
        g_faceLandmarker->loadModel(g_landmarker_model_path, g_num_threads, previous_vulkan);
        g_faceDetector->loadModel(g_detector_model_path, g_num_threads, previous_vulkan);
        LOGI("Rolled back to %s", previous_vulkan ? "Vulkan" : "CPU");
        return JNI_FALSE;
    }
    
    // Optional model: blendshape predictor.
    if (g_blendshapePredictor && !g_blendshape_model_path.empty()) {
        if (!g_blendshapePredictor->loadModel(g_blendshape_model_path, g_num_threads,
                                              want_vulkan)) {
            LOGE("BlendShape reload on %s failed: %s",
                 want_vulkan ? "Vulkan" : "CPU",
                 g_blendshapePredictor->lastError().c_str());
            if (!g_blendshapePredictor->loadModel(g_blendshape_model_path, g_num_threads,
                                                  previous_vulkan)) {
                LOGE("BlendShape rollback failed, blendshape prediction disabled");
                g_blendshapePredictor.reset();
            }
        }
    }
    
    g_use_vulkan.store(want_vulkan);
    LOGI("MNN backend switched to %s", want_vulkan ? "Vulkan" : "CPU");
    return JNI_TRUE;
}

/**
 * Get the currently active MNN inference backend.
 * @return true if running on Vulkan, false if running on the CPU backend
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetBackend(
        JNIEnv* env,
        jobject /* this */) {
    
    return g_use_vulkan.load() ? JNI_TRUE : JNI_FALSE;
}

// ===========================================================================
// GPU 多路并行：引擎槽位管理
//
// 调用方（推理线程）在启动时调用一次 nativeSelectEngine(i)，之后这个线程上的
// 所有 per-frame 调用都会落到槽位 i 的引擎上；主线程 / 检测线程保持默认的槽位 0。
// ===========================================================================

/**
 * 把**当前线程**绑定到指定引擎槽位（0 = 主引擎，1..kMaxEngines-1 = 并行关键点引擎）。
 * 线程本地状态，不影响其它线程。
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSelectEngine(
        JNIEnv* env,
        jobject /* this */,
        jint index) {

    if (index <= 0) {
        g_engine_index = 0;
    } else if (index >= kMaxEngines) {
        g_engine_index = kMaxEngines - 1;
    } else {
        g_engine_index = static_cast<int>(index);
    }
}

/** 当前线程绑定的引擎槽位，仅用于日志 / 断言。 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetEngineIndex(
        JNIEnv* env,
        jobject /* this */) {

    return static_cast<jint>(g_engine_index);
}

/**
 * 初始化一个额外的关键点引擎（槽位 >= 1）。
 *
 * 只加载 landmarker：这些引擎唯一的职责是从帧队列里抢下关键点推理，
 * 不需要检测 / blendshape 模型，可以省掉两份模型的常驻内存。
 * 必须在 nativeInit 之后调用（模型路径已缓存）。
 *
 * @param slotIndex 引擎槽位，1 .. kMaxEngines-1
 * @param useVulkan true = 再开一路 Vulkan（GPU 并行，默认），false = CPU 后端
 * @param numThreads MNN 线程数；<=0 时沿用主引擎的线程数
 * @return true 表示该槽位可用
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeInitEngine(
        JNIEnv* env,
        jobject /* this */,
        jint slotIndex,
        jboolean useVulkan,
        jint numThreads) {

    std::lock_guard<std::mutex> lock(g_model_mutex);

    if (slotIndex < 1 || slotIndex >= kMaxEngines) {
        LOGE("Cannot init engine: slot %d out of range (1..%d)",
             static_cast<int>(slotIndex), kMaxEngines - 1);
        return JNI_FALSE;
    }

    EngineSlot& slot = g_engines[slotIndex];
    slot.detector.reset();
    slot.landmarker.reset();
    slot.blendshape.reset();
    slot.hasHeadPose = false;

    if (g_landmarker_model_path.empty()) {
        LOGE("Cannot init extra engine %d: landmarker model path not cached",
             static_cast<int>(slotIndex));
        return JNI_FALSE;
    }

    const int threads = (numThreads > 0) ? static_cast<int>(numThreads) : g_num_threads;
    const bool want_vulkan = (useVulkan == JNI_TRUE);

    auto landmarker = std::make_unique<FaceLandmarker>();
    bool backend = want_vulkan;
    if (!landmarker->loadModel(g_landmarker_model_path, threads, backend)) {
        // 不做「Vulkan 失败就退 CPU」的回退：这一路存在的意义就是吃 GPU，
        // 退成 CPU 会去和重排 / 后处理抢核。交给 Kotlin 侧把并行度降一级即可。
        LOGE("Extra engine %d landmarker load failed (want=%s): %s",
             static_cast<int>(slotIndex), want_vulkan ? "Vulkan" : "CPU",
             landmarker->lastError().c_str());
        return JNI_FALSE;
    }

    slot.landmarker = std::move(landmarker);
    slot.useVulkan.store(backend);

    // 相机 FOV / 滤波配置在 FaceLandmarker 里是静态（全局）状态，额外引擎自动共享，
    // 不需要像 session 那样再复制一份。
    LOGI("Extra engine %d ready: backend=%s, threads=%d",
         static_cast<int>(slotIndex), backend ? "Vulkan" : "CPU", threads);
    return JNI_TRUE;
}

/** 指定槽位的引擎是否可用（landmarker 已加载）。 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeIsEngineReady(
        JNIEnv* env,
        jobject /* this */,
        jint slotIndex) {

    if (slotIndex < 0 || slotIndex >= kMaxEngines) {
        return JNI_FALSE;
    }
    return g_engines[slotIndex].landmarker ? JNI_TRUE : JNI_FALSE;
}

/** 释放指定槽位的额外引擎（槽位 0 = 主引擎，不允许通过这里释放）。 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeReleaseEngine(
        JNIEnv* env,
        jobject /* this */,
        jint slotIndex) {

    if (slotIndex < 1 || slotIndex >= kMaxEngines) {
        return;
    }

    std::lock_guard<std::mutex> lock(g_model_mutex);

    EngineSlot& slot = g_engines[slotIndex];
    slot.landmarker.reset();
    slot.detector.reset();
    slot.blendshape.reset();
    slot.hasHeadPose = false;
    LOGI("Extra engine %d released", static_cast<int>(slotIndex));
}

/** 释放全部额外引擎（切回单路 / 切到 CPU 后端 / release 时调用）。 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeReleaseExtraEngines(
        JNIEnv* env,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_model_mutex);

    for (int i = 1; i < kMaxEngines; ++i) {
        EngineSlot& slot = g_engines[i];
        slot.landmarker.reset();
        slot.detector.reset();
        slot.blendshape.reset();
        slot.hasHeadPose = false;
    }
    LOGI("All extra engines released");
}

/**
 * Set dump directory for debug frame capture
 * The first frame passed to detector will be saved as PPM image
 * @param dumpDir Directory path where to save the debug frame
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetDumpDirectory(
        JNIEnv* env,
        jobject /* this */,
        jstring dumpDir) {
    
    if (dumpDir == nullptr) {
        LOGI("Dump directory set to null, disabling frame dump");
        FaceDetector::setDumpDirectory("");
        return;
    }
    
    const char* dir = env->GetStringUTFChars(dumpDir, nullptr);
    LOGI("Setting dump directory to: %s", dir);
    FaceDetector::setDumpDirectory(dir);
    env->ReleaseStringUTFChars(dumpDir, dir);
}

/**
 * Detect face landmarks from RGB image data
 * @param imageData RGB image data (width * height * 3 bytes)
 * @param width Image width
 * @param height Image height
 * @param scoreThreshold Face detection score threshold
 * @param iouThreshold Face detection NMS IOU threshold
 * @param presenceThreshold Landmark presence threshold
 * @return Float array of landmarks [x1,y1,z1, x2,y2,z2, ...] or null if no face detected
 *         Coordinates are normalized to [0, 1] relative to image size
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeDetectLandmarks(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray imageData,
        jint width,
        jint height,
        jfloat scoreThreshold,
        jfloat iouThreshold,
        jfloat presenceThreshold) {
    
    if (!g_faceDetector || !g_faceLandmarker) {
        LOGE("Models not initialized. Call nativeInit first.");
        return nullptr;
    }
    
    // Get image data
    jbyte* imgBytes = env->GetByteArrayElements(imageData, nullptr);
    uint8_t* imgData = reinterpret_cast<uint8_t*>(imgBytes);
    
    // Step 1: Detect faces
    auto faces = g_faceDetector->detect(imgData, width, height, scoreThreshold, iouThreshold);
    
    if (faces.empty()) {
        LOGI("No face detected");
        env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
        return nullptr;
    }
    
    LOGI("Detected %zu face(s), processing first face", faces.size());
    
    // Step 2: Get landmarks for first face
    FaceDetection& face = faces[0];
    auto result = g_faceLandmarker->detect(imgData, width, height, face, presenceThreshold);
    
    env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
    
    if (!result.isValid()) {
        LOGI("Failed to detect landmarks (presence score: %.3f)", result.presence_score);
        return nullptr;
    }
    
    LOGI("Detected %zu landmarks with presence score %.3f", 
         result.landmarks.size(), result.presence_score);
    
    // Step 3: Convert landmarks to float array [x,y,z, x,y,z, ...]
    // Landmarks are already normalized to [0, 1] by the detector
    size_t numLandmarks = result.landmarks.size();
    jfloatArray landmarksArray = env->NewFloatArray(numLandmarks * 3);
    
    // Per-thread scratch buffer: clear() keeps the capacity, so after the first
    // frame there is no heap allocation left in this per-frame copy path.
    static thread_local std::vector<float> landmarksData;
    landmarksData.clear();
    landmarksData.reserve(numLandmarks * 3);
    
    for (const auto& lm : result.landmarks) {
        landmarksData.push_back(lm.x);
        landmarksData.push_back(lm.y);
        landmarksData.push_back(lm.z);
    }
    
    env->SetFloatArrayRegion(landmarksArray, 0, numLandmarks * 3, landmarksData.data());
    
    return landmarksArray;
}

/**
 * Stage 1: Detect face bounding box only (fast, ~4ms)
 * Separate from landmark detection for pipeline parallelism
 * @return Float array [x1, y1, x2, y2, score] or null if no face
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeDetectFace(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray imageData,
        jint width,
        jint height,
        jfloat scoreThreshold,
        jfloat iouThreshold) {
    
    if (!g_faceDetector) {
        LOGE("Face detector not initialized");
        return nullptr;
    }
    
    jbyte* imgBytes = env->GetByteArrayElements(imageData, nullptr);
    uint8_t* imgData = reinterpret_cast<uint8_t*>(imgBytes);
    
    // Detect faces
    auto faces = g_faceDetector->detect(imgData, width, height, scoreThreshold, iouThreshold);
    
    env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
    
    if (faces.empty()) {
        return nullptr;
    }
    
    // Return first face as [x1, y1, x2, y2, score]
    FaceDetection& face = faces[0];
    jfloatArray result = env->NewFloatArray(5);
    float boxData[5] = { face.x1, face.y1, face.x2, face.y2, face.score };
    env->SetFloatArrayRegion(result, 0, 5, boxData);
    
    return result;
}

/**
 * Stage 2: Detect landmarks using pre-computed bounding box (slower, ~24ms)
 * Uses provided bounding box instead of running face detection
 * @param boxX1, boxY1, boxX2, boxY2 Normalized bounding box coordinates [0,1]
 * @return Float array of landmarks [x,y,z, ...] or null on failure
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeDetectLandmarksWithBox(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray imageData,
        jint width,
        jint height,
        jfloat boxX1,
        jfloat boxY1,
        jfloat boxX2,
        jfloat boxY2,
        jfloat presenceThreshold) {
    
    if (!g_faceLandmarker) {
        LOGE("Face landmarker not initialized");
        return nullptr;
    }
    
    jbyte* imgBytes = env->GetByteArrayElements(imageData, nullptr);
    uint8_t* imgData = reinterpret_cast<uint8_t*>(imgBytes);
    
    // Create FaceDetection from provided box
    FaceDetection face;
    face.x1 = boxX1;
    face.y1 = boxY1;
    face.x2 = boxX2;
    face.y2 = boxY2;
    face.score = 1.0f;  // Assume valid box
    
    // Run landmark detection
    auto result = g_faceLandmarker->detect(imgData, width, height, face, presenceThreshold);
    
    env->ReleaseByteArrayElements(imageData, imgBytes, JNI_ABORT);
    
    if (!result.isValid()) {
        LOGI("Landmark detection failed with provided box");
        return nullptr;
    }
    
    // Convert landmarks to float array
    size_t numLandmarks = result.landmarks.size();
    jfloatArray landmarksArray = env->NewFloatArray(numLandmarks * 3);
    
    // Per-thread scratch buffer: clear() keeps the capacity, so after the first
    // frame there is no heap allocation left in this per-frame copy path.
    static thread_local std::vector<float> landmarksData;
    landmarksData.clear();
    landmarksData.reserve(numLandmarks * 3);
    
    for (const auto& lm : result.landmarks) {
        landmarksData.push_back(lm.x);
        landmarksData.push_back(lm.y);
        landmarksData.push_back(lm.z);
    }
    
    env->SetFloatArrayRegion(landmarksArray, 0, numLandmarks * 3, landmarksData.data());
    
    return landmarksArray;
}

/**
 * Estimate head pose from landmarks
 * @param landmarks Float array of landmarks [x,y,z, ...]
 * @param imageWidth Image width
 * @param imageHeight Image height
 * @return Float array [pitch, yaw, roll] in degrees
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeEstimateHeadPose(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray landmarks,
        jint imageWidth,
        jint imageHeight) {
    
    if (!g_faceLandmarker) {
        LOGE("Landmarker not initialized");
        return nullptr;
    }
    
    // Convert landmarks array to vector
    jsize numFloats = env->GetArrayLength(landmarks);
    jfloat* landmarksData = env->GetFloatArrayElements(landmarks, nullptr);
    
    std::vector<FaceLandmark> landmarksVec;
    for (jsize i = 0; i < numFloats; i += 3) {
        FaceLandmark lm;
        lm.x = landmarksData[i];
        lm.y = landmarksData[i + 1];
        lm.z = landmarksData[i + 2];
        landmarksVec.push_back(lm);
    }
    
    env->ReleaseFloatArrayElements(landmarks, landmarksData, JNI_ABORT);
    
    // Estimate head pose (geometry pipeline + temporal filter are process-global,
    // so the CPU / GPU channels must not run this concurrently).
    HeadPose pose;
    {
        std::lock_guard<std::mutex> lock(g_head_pose_mutex);
        pose = FaceLandmarker::estimateHeadPose(landmarksVec, imageWidth, imageHeight);
    }
    
    // Return [pitch, yaw, roll]
    jfloatArray poseArray = env->NewFloatArray(3);
    float poseData[3] = { pose.pitch, pose.yaw, pose.roll };
    env->SetFloatArrayRegion(poseArray, 0, 3, poseData);
    
    return poseArray;
}

/**
 * Release native resources
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeRelease(
        JNIEnv* env,
        jobject /* this */) {
    
    LOGI("Releasing MediaPipe Face Detection resources");
    
    std::lock_guard<std::mutex> lock(g_model_mutex);
    
    // 释放全部引擎槽位（主引擎 + 可选的并行关键点引擎），而不只是当前线程绑定的那个。
    for (auto& engine : g_engines) {
        engine.detector.reset();
        engine.landmarker.reset();
        engine.blendshape.reset();
        engine.hasHeadPose = false;
    }
}

/**
 * Predict blendshapes from face landmarks
 * @param landmarks Float array of landmarks [x,y,z, ...] (478 landmarks * 3 = 1434 floats)
 * @param imageWidth Image width (for denormalization)
 * @param imageHeight Image height (for denormalization)
 * @param useHeadPoseCompensation Whether to apply head rotation compensation
 * @return Float array of 52 blendshape values [0-1], or null if prediction failed
 *         Values are ordered according to MediaPipe's BlendShape indices (ARKit compatible)
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativePredictBlendshapes(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray landmarks,
        jint imageWidth,
        jint imageHeight,
        jboolean useHeadPoseCompensation) {
    
    if (!g_blendshapePredictor) {
        LOGE("BlendShape predictor not initialized");
        return nullptr;
    }
    
    // Convert landmarks array to vector
    jsize numFloats = env->GetArrayLength(landmarks);
    if (numFloats < 1404) {  // 468 * 3 minimum
        LOGE("Insufficient landmarks for BlendShape prediction: %d floats", numFloats);
        return nullptr;
    }
    
    jfloat* landmarksData = env->GetFloatArrayElements(landmarks, nullptr);
    
    std::vector<FaceLandmark> landmarksVec;
    landmarksVec.reserve(numFloats / 3);
    for (jsize i = 0; i < numFloats; i += 3) {
        FaceLandmark lm;
        lm.x = landmarksData[i];
        lm.y = landmarksData[i + 1];
        lm.z = landmarksData[i + 2];
        landmarksVec.push_back(lm);
    }
    
    // Update head pose if compensation is enabled
    HeadPose* posePtr = nullptr;
    if (useHeadPoseCompensation && g_faceLandmarker) {
        // 同 nativeEstimateHeadPose：全局几何管线 + 全局滤波，需要串行化。
        std::lock_guard<std::mutex> poseLock(g_head_pose_mutex);
        g_lastHeadPose = FaceLandmarker::estimateHeadPose(landmarksVec, imageWidth, imageHeight);
        g_hasHeadPose = g_lastHeadPose.isValid();
        if (g_hasHeadPose) {
            posePtr = &g_lastHeadPose;
            LOGI("Head pose: pitch=%.2f, yaw=%.2f, roll=%.2f", 
                 g_lastHeadPose.pitch, g_lastHeadPose.yaw, g_lastHeadPose.roll);
        }
    }
    
    env->ReleaseFloatArrayElements(landmarks, landmarksData, JNI_ABORT);
    
    // Predict blendshapes
    BlendShapeResult result = g_blendshapePredictor->predict(
        landmarksVec, imageWidth, imageHeight, posePtr);
    
    if (!result.valid) {
        LOGE("BlendShape prediction failed: %s", g_blendshapePredictor->lastError().c_str());
        return nullptr;
    }
    
    // Return compensated blendshape values
    jfloatArray blendshapeArray = env->NewFloatArray(BS_COUNT);
    env->SetFloatArrayRegion(blendshapeArray, 0, BS_COUNT, result.compensated);
    
    LOGI("BlendShape prediction successful: eyeBlink L=%.2f R=%.2f, jawOpen=%.2f", 
         result.compensated[BS_EYE_BLINK_LEFT], 
         result.compensated[BS_EYE_BLINK_RIGHT],
         result.compensated[BS_JAW_OPEN]);
    
    return blendshapeArray;
}

/**
 * Get raw blendshapes without head pose compensation
 * @param landmarks Float array of landmarks [x,y,z, ...]
 * @param imageWidth Image width
 * @param imageHeight Image height
 * @return Float array of 52 raw blendshape values [0-1]
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativePredictBlendshapesRaw(
        JNIEnv* env,
        jobject /* this */,
        jfloatArray landmarks,
        jint imageWidth,
        jint imageHeight) {
    
    if (!g_blendshapePredictor) {
        LOGE("BlendShape predictor not initialized");
        return nullptr;
    }
    
    jsize numFloats = env->GetArrayLength(landmarks);
    if (numFloats < 1404) {
        LOGE("Insufficient landmarks for BlendShape prediction");
        return nullptr;
    }
    
    jfloat* landmarksData = env->GetFloatArrayElements(landmarks, nullptr);
    
    std::vector<FaceLandmark> landmarksVec;
    landmarksVec.reserve(numFloats / 3);
    for (jsize i = 0; i < numFloats; i += 3) {
        FaceLandmark lm;
        lm.x = landmarksData[i];
        lm.y = landmarksData[i + 1];
        lm.z = landmarksData[i + 2];
        landmarksVec.push_back(lm);
    }
    
    env->ReleaseFloatArrayElements(landmarks, landmarksData, JNI_ABORT);
    
    // Predict without head pose compensation
    BlendShapeResult result = g_blendshapePredictor->predict(
        landmarksVec, imageWidth, imageHeight, nullptr);
    
    if (!result.valid) {
        LOGE("BlendShape prediction failed");
        return nullptr;
    }
    
    // Return raw (uncompensated) values
    jfloatArray blendshapeArray = env->NewFloatArray(BS_COUNT);
    env->SetFloatArrayRegion(blendshapeArray, 0, BS_COUNT, result.values);
    
    return blendshapeArray;
}

/**
 * Get the last estimated head pose
 * @return Float array [pitch, yaw, roll] in degrees, or null if no pose available
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetLastHeadPose(
        JNIEnv* env,
        jobject /* this */) {
    
    if (!g_hasHeadPose) {
        return nullptr;
    }
    
    jfloatArray poseArray = env->NewFloatArray(6);
    float poseData[6] = {
        g_lastHeadPose.pitch, 
        g_lastHeadPose.yaw, 
        g_lastHeadPose.roll,
        g_lastHeadPose.translation_x,
        g_lastHeadPose.translation_y,
        g_lastHeadPose.translation_z
    };
    env->SetFloatArrayRegion(poseArray, 0, 6, poseData);
    
    return poseArray;
}

/**
 * Get BlendShape name by index
 * @param index BlendShape index (0-51)
 * @return BlendShape name string
 */
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetBlendshapeName(
        JNIEnv* env,
        jobject /* this */,
        jint index) {
    
    if (index < 0 || index >= BS_COUNT) {
        return env->NewStringUTF("unknown");
    }
    
    return env->NewStringUTF(getBlendShapeName(static_cast<BlendShapeIndex>(index)));
}

/**
 * Check if BlendShape prediction is available
 * @return true if BlendShape model is loaded
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeIsBlendshapeAvailable(
        JNIEnv* env,
        jobject /* this */) {
    
    return g_blendshapePredictor != nullptr ? JNI_TRUE : JNI_FALSE;
}

/**
 * Enable/disable landmark smoothing
 * @param enabled true to enable smoothing, false to disable
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetSmoothingEnabled(
        JNIEnv* env,
        jobject /* this */,
        jboolean enabled) {
    
    // 多路并行时每个引擎各有一份滤波状态，配置必须一起改，否则各路的平滑行为不一致。
    for (auto& engine : g_engines) {
        if (engine.landmarker) {
            engine.landmarker->setSmoothingEnabled(enabled);
        }
    }
    if (g_faceLandmarker) {
        LOGI("Landmark smoothing %s", enabled ? "enabled" : "disabled");
    }
}

/**
 * Reset landmark smoothing filter state
 * Call this when face is lost or tracking needs to be reset
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeResetSmoothing(
        JNIEnv* env,
        jobject /* this */) {
    
    // 同上：两路的滤波状态都要清，否则重置基线后两路会短暂「不同步」。
    for (auto& engine : g_engines) {
        if (engine.landmarker) {
            engine.landmarker->resetSmoothing();
        }
    }
    if (g_faceLandmarker) {
        LOGI("Landmark smoothing filter reset");
    }
    if (g_faceDetector) {
        g_faceDetector->resetSmoothing();
        LOGI("Detection smoothing filter reset");
    }
}

/**
 * Set camera FOV for accurate head pose estimation
 * @param fovDegrees Vertical field of view in degrees
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetCameraFov(
        JNIEnv* env,
        jobject /* this */,
        jfloat fovDegrees) {

    FaceLandmarker::setCameraFov(fovDegrees);
    LOGI("Camera FOV set to %.1f degrees", fovDegrees);
}

/**
 * Get current camera FOV
 * @return Current vertical FOV in degrees
 */
extern "C" JNIEXPORT jfloat JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetCameraFov(
        JNIEnv* env,
        jobject /* this */) {

    return FaceLandmarker::getCameraFov();
}

// ============================================================================
// Filter Configuration JNI Methods
// ============================================================================

/**
 * Set landmark filter type
 * @param filterType 0=ONE_EURO, 1=TIME_BASED_EMA, 2=GAUSSIAN, 3=EMA, 4=NONE
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetLandmarkFilterType(
        JNIEnv* env,
        jobject /* this */,
        jint filterType) {

    auto type = static_cast<mnncv::SmoothingFilterType>(filterType);
    // 多路并行：各引擎的滤波类型保持一致。
    for (auto& engine : g_engines) {
        if (engine.landmarker) {
            engine.landmarker->setFilterType(type);
        }
    }
    LOGI("Landmark filter type set to %d", filterType);
}

/**
 * Get current landmark filter type
 * @return Filter type: 0=ONE_EURO, 1=TIME_BASED_EMA, 2=GAUSSIAN, 3=EMA, 4=NONE
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetLandmarkFilterType(
        JNIEnv* env,
        jobject /* this */) {

    if (g_faceLandmarker) {
        return static_cast<jint>(g_faceLandmarker->getFilterType());
    }
    return 4; // NONE
}

/**
 * Configure landmark filter with detailed parameters
 * @param filterType Filter type (0-4)
 * @param oneEuroMinCutoff One Euro min_cutoff parameter
 * @param oneEuroBeta One Euro beta parameter
 * @param oneEuroDerivCutoff One Euro derivate_cutoff parameter
 * @param timeEmaTau Time-Based EMA tau parameter (seconds)
 * @param gaussianWindowSize Gaussian window size
 * @param gaussianSigma Gaussian sigma
 * @param emaAlpha Simple EMA alpha
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeConfigureLandmarkFilter(
        JNIEnv* env,
        jobject /* this */,
        jint filterType,
        jfloat oneEuroMinCutoff,
        jfloat oneEuroBeta,
        jfloat oneEuroDerivCutoff,
        jfloat timeEmaTau,
        jint gaussianWindowSize,
        jfloat gaussianSigma,
        jfloat emaAlpha) {

    mnncv::FilterConfig config;
    config.type = static_cast<mnncv::SmoothingFilterType>(filterType);
    config.one_euro_min_cutoff = oneEuroMinCutoff;
    config.one_euro_beta = oneEuroBeta;
    config.one_euro_derivate_cutoff = oneEuroDerivCutoff;
    config.time_ema_tau = timeEmaTau;
    config.gaussian_window_size = gaussianWindowSize;
    config.gaussian_sigma = gaussianSigma;
    config.ema_alpha = emaAlpha;

    // 多路并行：各引擎用同一套滤波参数。
    for (auto& engine : g_engines) {
        if (engine.landmarker) {
            engine.landmarker->setFilterConfig(config);
        }
    }
    LOGI("Landmark filter configured: type=%d, tau=%.3f", filterType, timeEmaTau);
}

/**
 * Set detection filter type
 * @param filterType 0=ONE_EURO, 1=TIME_BASED_EMA, 2=GAUSSIAN, 3=EMA, 4=NONE
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetDetectionFilterType(
        JNIEnv* env,
        jobject /* this */,
        jint filterType) {

    if (g_faceDetector) {
        auto type = static_cast<mnncv::SmoothingFilterType>(filterType);
        g_faceDetector->setFilterType(type);
        LOGI("Detection filter type set to %d", filterType);
    }
}

/**
 * Get current detection filter type
 * @return Filter type: 0=ONE_EURO, 1=TIME_BASED_EMA, 2=GAUSSIAN, 3=EMA, 4=NONE
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetDetectionFilterType(
        JNIEnv* env,
        jobject /* this */) {

    if (g_faceDetector) {
        return static_cast<jint>(g_faceDetector->getFilterType());
    }
    return 4; // NONE
}

/**
 * Configure detection filter with detailed parameters
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeConfigureDetectionFilter(
        JNIEnv* env,
        jobject /* this */,
        jint filterType,
        jfloat oneEuroMinCutoff,
        jfloat oneEuroBeta,
        jfloat oneEuroDerivCutoff,
        jfloat timeEmaTau,
        jint gaussianWindowSize,
        jfloat gaussianSigma,
        jfloat emaAlpha) {

    if (g_faceDetector) {
        mnncv::FilterConfig config;
        config.type = static_cast<mnncv::SmoothingFilterType>(filterType);
        config.one_euro_min_cutoff = oneEuroMinCutoff;
        config.one_euro_beta = oneEuroBeta;
        config.one_euro_derivate_cutoff = oneEuroDerivCutoff;
        config.time_ema_tau = timeEmaTau;
        config.gaussian_window_size = gaussianWindowSize;
        config.gaussian_sigma = gaussianSigma;
        config.ema_alpha = emaAlpha;

        g_faceDetector->setFilterConfig(config);
        LOGI("Detection filter configured: type=%d, tau=%.3f", filterType, timeEmaTau);
    }
}

/**
 * Set head pose filter type (for estimateHeadPose smoothing)
 * @param filterType 0=ONE_EURO, 1=GAUSSIAN, 2=EMA, 3=TIME_BASED_EMA
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetHeadPoseFilterType(
        JNIEnv* env,
        jobject /* this */,
        jint filterType) {

    FaceLandmarker::setHeadPoseFilterTypeInt(filterType);
    LOGI("Head pose filter type set to %d", filterType);
}

// ============================================================================
// YUV Direct Input Support - Zero-copy pipeline optimization
// ============================================================================

/**
 * Detect face directly from YUV data with rotation
 * This integrates YUV->RGB conversion into the preprocessing pipeline
 * 
 * @param yBuffer Y plane ByteBuffer (direct)
 * @param uBuffer U plane ByteBuffer (direct)
 * @param vBuffer V plane ByteBuffer (direct)
 * @param yRowStride Y plane row stride
 * @param uvRowStride UV plane row stride
 * @param uvPixelStride UV pixel stride (1=I420, 2=NV12/NV21)
 * @param width Source image width
 * @param height Source image height
 * @param rotation Rotation in degrees (0, 90, 180, 270)
 * @param scoreThreshold Detection score threshold
 * @param iouThreshold NMS IOU threshold
 * @return Float array [x1, y1, x2, y2, score] or null if no face
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeDetectFaceFromYuv(
        JNIEnv* env,
        jobject /* this */,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint yRowStride,
        jint uvRowStride,
        jint uvPixelStride,
        jint width,
        jint height,
        jint rotation,
        jfloat scoreThreshold,
        jfloat iouThreshold) {
    
    if (!g_faceDetector) {
        LOGE("Face detector not initialized");
        return nullptr;
    }
    
    // Get direct buffer addresses
    auto yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    
    if (!yData || !uData || !vData) {
        LOGE("Failed to get direct buffer addresses");
        return nullptr;
    }
    
    // Detect faces directly from YUV
    auto faces = g_faceDetector->detectFromYuv(
        yData, uData, vData,
        yRowStride, uvRowStride, uvPixelStride,
        width, height, rotation,
        scoreThreshold, iouThreshold);
    
    if (faces.empty()) {
        return nullptr;
    }
    
    // Return first face as [x1, y1, x2, y2, score]
    FaceDetection& face = faces[0];
    jfloatArray result = env->NewFloatArray(5);
    float boxData[5] = { face.x1, face.y1, face.x2, face.y2, face.score };
    env->SetFloatArrayRegion(result, 0, 5, boxData);
    
    return result;
}

/**
 * Detect landmarks directly from YUV data with pre-computed bounding box
 * 
 * @param yBuffer Y plane ByteBuffer (direct)
 * @param uBuffer U plane ByteBuffer (direct)
 * @param vBuffer V plane ByteBuffer (direct)
 * @param yRowStride Y plane row stride
 * @param uvRowStride UV plane row stride
 * @param uvPixelStride UV pixel stride
 * @param width Image width (after rotation)
 * @param height Image height (after rotation)
 * @param rotation Rotation in degrees
 * @param boxX1, boxY1, boxX2, boxY2 Face bounding box (normalized, in rotated coords)
 * @param presenceThreshold Presence threshold
 * @return Float array of landmarks [x,y,z, ..., presence_score] or null on failure
 *         The last element is presence_score
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeDetectLandmarksFromYuv(
        JNIEnv* env,
        jobject /* this */,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint yRowStride,
        jint uvRowStride,
        jint uvPixelStride,
        jint width,
        jint height,
        jint rotation,
        jfloat boxX1,
        jfloat boxY1,
        jfloat boxX2,
        jfloat boxY2,
        jfloat presenceThreshold) {
    
    if (!g_faceLandmarker) {
        LOGE("Face landmarker not initialized");
        return nullptr;
    }
    
    auto yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    
    if (!yData || !uData || !vData) {
        LOGE("Failed to get direct buffer addresses");
        return nullptr;
    }
    
    // Create FaceDetection from provided box
    FaceDetection face;
    face.x1 = boxX1;
    face.y1 = boxY1;
    face.x2 = boxX2;
    face.y2 = boxY2;
    face.score = 1.0f;
    
    // Run landmark detection directly from YUV
    auto result = g_faceLandmarker->detectFromYuv(
        yData, uData, vData,
        yRowStride, uvRowStride, uvPixelStride,
        width, height, rotation,
        face, presenceThreshold);
    
    if (!result.isValid()) {
        return nullptr;
    }
    
    // Convert landmarks to float array with presence_score appended
    size_t numLandmarks = result.landmarks.size();
    jfloatArray landmarksArray = env->NewFloatArray(numLandmarks * 3 + 1);
    
    // Per-thread scratch buffer: clear() keeps the capacity, so after the first
    // frame there is no heap allocation left in this per-frame copy path.
    static thread_local std::vector<float> landmarksData;
    landmarksData.clear();
    landmarksData.reserve(numLandmarks * 3 + 1);
    
    for (const auto& lm : result.landmarks) {
        landmarksData.push_back(lm.x);
        landmarksData.push_back(lm.y);
        landmarksData.push_back(lm.z);
    }
    
    // Append presence_score as the last element
    landmarksData.push_back(result.presence_score);
    
    env->SetFloatArrayRegion(landmarksArray, 0, numLandmarks * 3 + 1, landmarksData.data());
    
    return landmarksArray;
}

/**
 * Full pipeline: Detect face and landmarks from YUV in one call
 * Combines face detection and landmark detection using YUV input
 * 
 * @return Float array of landmarks [x,y,z, ...] or null if no face
 */
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeDetectAllFromYuv(
        JNIEnv* env,
        jobject /* this */,
        jobject yBuffer,
        jobject uBuffer,
        jobject vBuffer,
        jint yRowStride,
        jint uvRowStride,
        jint uvPixelStride,
        jint width,
        jint height,
        jint rotation,
        jfloat scoreThreshold,
        jfloat iouThreshold,
        jfloat presenceThreshold) {
    
    if (!g_faceDetector || !g_faceLandmarker) {
        LOGE("Models not initialized");
        return nullptr;
    }
    
    auto yData = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuffer));
    auto uData = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuffer));
    auto vData = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuffer));
    
    if (!yData || !uData || !vData) {
        LOGE("Failed to get direct buffer addresses");
        return nullptr;
    }
    
    // Step 1: Detect face from YUV
    auto faces = g_faceDetector->detectFromYuv(
        yData, uData, vData,
        yRowStride, uvRowStride, uvPixelStride,
        width, height, rotation,
        scoreThreshold, iouThreshold);
    
    if (faces.empty()) {
        return nullptr;
    }
    
    // Step 2: Detect landmarks from YUV using the detected face
    auto result = g_faceLandmarker->detectFromYuv(
        yData, uData, vData,
        yRowStride, uvRowStride, uvPixelStride,
        width, height, rotation,
        faces[0], presenceThreshold);
    
    if (!result.isValid()) {
        return nullptr;
    }
    
    // Convert landmarks to float array
    size_t numLandmarks = result.landmarks.size();
    jfloatArray landmarksArray = env->NewFloatArray(numLandmarks * 3);
    
    // Per-thread scratch buffer: clear() keeps the capacity, so after the first
    // frame there is no heap allocation left in this per-frame copy path.
    static thread_local std::vector<float> landmarksData;
    landmarksData.clear();
    landmarksData.reserve(numLandmarks * 3);
    
    for (const auto& lm : result.landmarks) {
        landmarksData.push_back(lm.x);
        landmarksData.push_back(lm.y);
        landmarksData.push_back(lm.z);
    }
    
    env->SetFloatArrayRegion(landmarksArray, 0, numLandmarks * 3, landmarksData.data());
    
    return landmarksArray;
}

//=============================================================================
// NEON Optimization Test JNI Methods
//=============================================================================

#include "face_geomentry/test_neon_optimization.h"

/**
 * Run NEON optimization tests and benchmarks
 * @return true if all tests passed
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_NeonOptimizationTest_runTests(
        JNIEnv* env,
        jclass /* clazz */) {
    
    LOGI("Running NEON Optimization Tests...");
    
    try {
        MNN::FaceGeometry::runNeonTests();
        LOGI("NEON Optimization Tests completed successfully");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("NEON Optimization Tests failed: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("NEON Optimization Tests failed with unknown exception");
        return JNI_FALSE;
    }
}

/**
 * Check if NEON is enabled on this device
 * @return true if NEON is available
 */
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mediapipefacelandmark_NeonOptimizationTest_isNeonEnabled(
        JNIEnv* env,
        jclass /* clazz */) {
    
#if MNN_USE_NEON
    LOGI("NEON is ENABLED");
    return JNI_TRUE;
#else
    LOGI("NEON is DISABLED");
    return JNI_FALSE;
#endif
}

/**
 * Run only benchmarks (skip correctness tests)
 */
extern "C" JNIEXPORT void JNICALL
Java_com_example_mediapipefacelandmark_NeonOptimizationTest_runBenchmarksOnly(
        JNIEnv* env,
        jclass /* clazz */) {
    
    LOGI("Running NEON Benchmarks Only...");
    
    try {
        srand(42);  // Fixed seed for reproducibility
        MNN::FaceGeometry::NeonTestRunner::printHeader("Performance Benchmarks (Direct)");
        MNN::FaceGeometry::NeonTestRunner::runBenchmarks();
        LOGI("NEON Benchmarks completed");
    } catch (const std::exception& e) {
        LOGE("NEON Benchmarks failed: %s", e.what());
    }
}
