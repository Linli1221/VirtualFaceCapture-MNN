# 原生层性能审计（MediapipeFaceLandmark / MNN）

> 审计范围：`MediapipeFaceLandmark/src/main/cpp/**`、`ThirdParty/MNN` 配置与 `assets` 模型、
> 以及它们与 `PipelineFaceTracker` / `MediaPipeFaceDetector` 的调用契约。
> **只读审计**：本文件是唯一改动；`src/` 下未修改任何代码。
>
> 环境限制：本机没有 JDK / Android SDK / 真机 / adb，**无法编译、无法实测**。
> 下表中的毫秒数除特别标注「实测锚点」外都是**估算**，依据在每行「依据」列给出
> （数据量 ÷ 典型内存带宽、像素数 × 每像素指令数、或上一轮日志锚点反推）。
> 二值证据（模型输入尺寸、`libMNN*.so` 能力）来自对 `.mnn` / `.so` 的字符串与尾部元数据提取。
>
> **行号快照说明**：审计期间 `PipelineFaceTracker.kt` / `Camera2Manager.kt` 正在被并行修改，
> 这两个文件的行号可能已漂移（±20 行）；引用时请以同行的函数名 / 代码片段为准，
> `cpp/` 与 `MediaPipeFaceDetector.kt` 的行号在本次审计内稳定。

---

## 0. 摘要（先看这 6 条）

1. **相机 30fps 与原生层无关。** Camera2 回调里只做「3 个 ByteBuffer 包装 + 2 次 `trySend`」
   （`facecapture-sdk/.../PipelineFaceTracker.kt:559-561`），所有 MNN 工作都在 `fc-*` 线程。
   唯一跑在相机线程上的原生侧消费是**可选**的上半身抽头（默认关闭）：
   `PipelineFaceTracker.kt:566-577` → `app/.../UpperBodyPoseController.kt:290-311` →
   `MediapipeUpperBody/.../UpperBodyPoseDetector.kt:357-379`，它同步做整帧 YUV→Bitmap，
   注释自述「1~4ms @ 长边 256」。**若测量时开着上半身，这就是相机线程 4ms/33ms 的抖动源，
   会直接把交付帧率压到 30；关掉它再测一次相机帧率即可排除。**
   第二种可能：2 路推理的 `fc-*` 线程是 `NORM_PRIORITY+2`（`PipelineFaceTracker.kt:207-213`），
   而 Camera2 的 `CameraBackground` 线程是默认优先级（`Camera2Manager.kt:593-594`）——
   若大核被推理占满，相机回调的调度延迟可能让 ImageReader 丢帧，表现为 delivered=30。
   验证：把相机 HandlerThread 提到 `THREAD_PRIORITY_URGENT_DISPLAY`（或把 `fc-*` 降到 NORM）
   做一次 A/B，这个实验不改任何推理逻辑。
2. **60Hz 关键点每帧的原生固定开销估算 ~0.9–1.8ms（不含 MNN 推理）**，其中约 0.5–1.1ms
   是可以直接消除的浪费：整帧 YUV 打包 1.38MB（`face_landmarker.cpp:490-491`）、
   host→session 拷贝 768KB（`:503`）、每帧二次 JNI 头姿往返 + 468 点反序列化
   （`MediaPipeFaceLandmarkJNI.cpp:672-682,690`）、几何管线每帧 ~200–250KB 临时矩阵
   （`geometry_pipeline_nodep.h:249-334` + `procrustes_solver_nodep.h:226-286`）。
3. **10Hz 检测每次也整帧打包 1.38MB**（`face_detector.cpp:665-666`）再 host→session 拷
   192KB（`:729`）。检测必须全图，**不能 ROI 裁剪**；但零拷贝快路径同样适用。
   单次检测 3.20ms 实测锚点（`PERFORMANCE.md:206`）里约 0.3–0.6ms 是这部分固定开销。
4. **多路引擎的热路径只有一把全局锁** `g_head_pose_mutex`（`MediaPipeFaceLandmarkJNI.cpp:81,690`），
   持锁 0.15–0.4ms/帧 → 2 路 60fps 合计只占 1–3% CPU，**不是 60fps 的瓶颈**；
   真正的多路问题是**共享可变状态**：几何管线 / 头姿滤波是进程级静态
   （`face_landmarker.cpp:35-45`），而 landmark 平滑滤波是**每引擎一份**
   （`:94-99`），2 路各喂一半帧 → 平滑语义与单路不同，可能贡献抖动；
   `s_perf_profiler` 也是多线程无锁写同一组非原子计数（`:32`、`face_detector.cpp:34`）。
5. **60fps 的真正天花板是推理吞吐**：14.50ms（`PERFORMANCE.md:210`）意味着 60fps 下
   GPU 已被占用 87%，任何 1–2ms 的 CPU 尾巴（YUV 拷贝、JNI、GC）都会把 p95 推过 16.7ms
   ——这正是旧日志 `p95=18.9ms`（`PERFORMANCE.md:217`）的合理解释。所以本报告里
   「只省 0.5–1ms」的改动，价值在于**把 p95 拉回 16.7ms 以内**，而不是提高平均 FPS。
6. 内存/显存不是并行度瓶颈：每路额外引擎约 **15–25MB**（估算），主引擎约 25–35MB，
   4 路合计 80–120MB，12GB 手机 4GB 可用内存绰绰有余（Android 统一内存，无独立显存）。
   建议**保持默认 2 路**；int8 只在 CPU 后端有实质加速空间（Vulkan 侧 `.so` 只有
   FP16/FP32 卷积 shader），先拿检测器做低风险试点。

---

## 1. 数据流（与本次审计相关的部分）

```
Camera2 ImageReader (YUV_420_888, 1280x720)
  └─ onImageAvailable [相机线程]  Camera2Manager.kt:1392-1425
       ├─ processYuvDirect() → YuvFrameData（0 拷贝，仅包装 ByteBuffer）  Camera2Manager.kt:1651-1684
       ├─ yuvFrameChannel.trySend(...)          // 10Hz 检测用
       ├─ yuvLandmarkFrameChannel.trySend(...)  // 60Hz 关键点用
       └─ image.close()                         // 注意：帧被 close 后仍可能排在这两条通道里
  └─ [fc-yuv-detect 线程]  10Hz 检测：nativeDetectFaceFromYuv → FaceDetector::detectFromYuv
  └─ [fc-landmark-gpuN 线程 × 2]  60Hz 关键点：
        detectLandmarksFromYuvWithScore → FaceLandmarker::detectFromYuv
        → estimateHeadPose（第二次 JNI 往返）
        → landmarkMergeChannel（按 frameId 重排）→ 后处理
```

关键事实：

- landmark 传给 JNI 的是 `rotatedWidth/rotatedHeight`（`PipelineFaceTracker.kt:1084`），
  所以 `detectFromYuv` 里再做 90/270 交换（`face_landmarker.cpp:485-486`）得到
  **源 buffer 尺寸 1280×720**；检测传的是 `width/height`（`:1003`），不做交换
  （`face_detector.cpp:669-672`）。两条路径语义一致，**不是 bug**。
- 全帧打包发生在 `fc-*` 工作线程，不在相机线程 → **它不会直接拉低相机交付帧率**，
  但会抢占 CPU / 内存带宽，并在 60fps 时吃掉每帧 16.7ms 预算的 1–2%。
- `image.close()` 在回调返回后立刻执行（`Camera2Manager.kt:1423`），而帧是
  **入队后异步处理**的（`PipelineFaceTracker.kt:559-561`）。Camera2 的 buffer 在 close 后
  会被回收复用，工作线程此时读到的可能是**已被下一帧覆盖的内存**。这条是跨层风险
  （Kotlin 侧生命周期），原生这边无法自证；建议 Kotlin 审计员交叉确认。它表现为偶发
  关键点跳动而不是崩溃，容易被误判成「推理抖动」。

---

## 2. 每阶段耗时估算表

### 2.1 60Hz 关键点（`FaceLandmarker::detectFromYuv`，输入 720p → 256×256）

> 模型实际输入是 **256×256×3**（`.mnn` 尾部 shape 元数据 `[1,256,256,3]`），
> 不是 `PERFORMANCE.md:101` 写的 192。

| 阶段 | 估算 ms | 代码位置 | 每帧分配 | 每帧拷贝 | 依据 |
|---|---|---|---|---|---|
| JNI 入参 + `GetDirectBufferAddress`×3 | 0.01–0.03 | `MediaPipeFaceLandmarkJNI.cpp:1235-1266` | 否 | 否 | 3 次 JNI 调用 |
| **整帧 YUV 打包**（1,382,400 B） | **0.15–0.30** | `face_landmarker.cpp:490-491`；`yuv_utils.h:72-136` | 否（`resize` 复用） | **是，1.38MB** | 2 次 memcpy / ~10GB/s + 行循环开销 |
| 脸框→ROI（1.5×、square）+ `atan2` | <0.01 | `face_landmarker.cpp:214-236` | 否 | 否 | 几十次浮点 |
| **ImageProcess**：YUV→RGB + 旋转 + 缩放 + mean/norm → 256×256 fp32 | **0.35–0.70** | `face_landmarker.cpp:298-352` | 否（`image_process_` 缓存） | 写 786,432 B | 65,536 输出像素 × 双线性 4 tap + 6 参仿射 |
| host→session 输入拷贝（786,432 B） | 0.08–0.15（CPU）/ 0.10–0.30（Vulkan 上传） | `face_landmarker.cpp:503` | 否 | **是，768KB** | memcpy / staging upload |
| **MNN `runSession`** | **14.50（实测锚点，Vulkan）** | `face_landmarker.cpp:508`；`PERFORMANCE.md:210` | MNN 内部池 | — | 旧日志，分辨率未标注 |
| 输出拷回（478×3 float + 1） | <0.01 | `face_landmarker.cpp:531-532` | 否（复用） | 5,740 B | 5.7KB |
| 478 点解码 | 0.01–0.02 | `face_landmarker.cpp:355-387` | **是**（vector ~5.7KB） | 否 | 478 × ~20 flop，但每帧 new/delete |
| 时间 EMA 平滑 478×3 | <0.02 | `face_landmarker.cpp:545-553`；`face_pipeline_filter.h` | 否 | 否 | 478 × 3 次乘加 |
| JNI 结果数组（1435 floats） | 0.02–0.05 | `MediaPipeFaceLandmarkJNI.cpp:1289-1305` | **是**（`NewFloatArray` 5.7KB） | 5,740 B | Java 堆分配 + `SetFloatArrayRegion` |
| Kotlin 解析 478 个 `Point3D` | 0.05–0.15（Kotlin 层，仅参考） | `MediaPipeFaceDetector.kt:1044-1053` | **是**，478 对象 + ArrayList | 否 | 每帧 478 次对象构造 |
| **二次 JNI 头姿**：反序列化 + `vector`（无 reserve）+ 3×Procrustes + 滤波 | **0.15–0.40** | `MediaPipeFaceLandmarkJNI.cpp:672-692`；`face_landmarker.cpp:675-756`；`geometry_pipeline_nodep.h:249-334`；`procrustes_solver_nodep.h:226-286` | **是**，~200–250KB、9 次 realloc | 1434 floats 读入 | 3 次 Procrustes × 7 个 5.6KB 矩阵 + 多次 vector 拷贝 |
| `NewFloatArray(3)` 返回姿态 | <0.01 | `MediaPipeFaceLandmarkJNI.cpp:695-697` | 是（12B） | 12B | — |
| **合计（不含推理）** | **~0.9–1.8** | — | **每帧 ~0.2–0.3MB 堆操作** | **~2.2MB** | 可消除 0.5–1.1ms |

### 2.2 10Hz 人脸检测（`FaceDetector::detectFromYuv`，输入 720p → 128×128）

| 阶段 | 估算 ms | 代码位置 | 每帧分配 | 每帧拷贝 | 依据 |
|---|---|---|---|---|---|
| **整帧 YUV 打包** | **0.15–0.30** | `face_detector.cpp:665-666` | 否 | **1.38MB** | 同 2.1 |
| ImageProcess（letterbox 到 128×128） | 0.08–0.20 | `face_detector.cpp:685-728` | 否 | 写 196,608 B | 16,384 像素 |
| host→session 输入拷贝（196,608 B） | 0.02–0.05 | `face_detector.cpp:729` | 否 | **192KB** | memcpy/upload |
| **MNN `runSession`** | **3.20（实测锚点，含上面全部）** | `PERFORMANCE.md:206` | — | — | 反推推理 ≈2.6–2.9 |
| `getSessionOutputAll`（map+string 键） | 0.01–0.03 | `face_detector.cpp:741-754` | **是**，map + 多次 string 比较 | 否 | 每 100ms 一次 |
| 输出拷回（57KB + 3.6KB） | <0.02 | `face_detector.cpp:773-774` | 否（复用） | 60KB | — |
| 896 anchor 解码（含 896 次 `std::exp`） | 0.03–0.06 | `face_detector.cpp:331-347,776-779` | **是**，`detections` vector | 否 | 896 × Sigmoid |
| NMS + 坐标变换 + 框平滑 | <0.01 | `face_detector.cpp:779,786-826` | 否 | 否 | — |
| **合计（不含推理）** | **~0.3–0.6 / 次** | — | — | **1.57MB / 次** | 摊到 60fps ≈0.05–0.1ms/帧 |

> 计时盲区：`PERF_START(preprocess_timer)` 在整帧打包**之后**（`face_landmarker.cpp:493`、
> `face_detector.cpp:675`），所以日志里的 `Preprocess` **不含** 1.38MB 拷贝；`Total` 含。
> 对比两列就能量出拷贝时间，无需改代码。

---

## 3. 问题清单（按「收益 × 风险」排序）

### P1. 60Hz 每帧全帧 YUV 重打包 1.38MB（最大单笔固定开销）

- **现象**：每帧把 Y+UV 从 Camera2 三个 plane 拷进成员 `std::vector`，再交给
  `ImageProcess`；720p 60fps 下 ≈83MB/s 纯带宽，且完全在关键点 worker 的帧预算内。
- **证据**：`face_landmarker.cpp:490-491` → `yuv_utils.h:72-136`（`resize` + memcpy）。
  `PERFORMANCE.md:102` 断言「整帧拷贝本身无法避免」，**不成立**（见改法）。
- **改法**（两条，按落地成本排序）：
  1. **零拷贝快路径**：Camera2 的 NV21/NV12 在多数机型上 Y 与 UV 在**同一块 buffer**
     且连续，此时只要 `uvPixelStride==2 && yRowStride==srcWidth && uvRowStride==srcWidth`
     且 `uData/vData` 恰好落在 `yData + ySize` 之后，就可以把 `yData` 直接当
     `YUV_NV21/NV12` 源传给 `convert()`，整帧拷贝降为 0。代码草案见 §4.1。
  2. **ROI-only 打包**：只需要脸框 1.5× 的区域（`transformFaceRect`，`face_landmarker.cpp:230-234`）。
     把输出 256×256 的四角经 `trans` 的逆变换映射回源图取 bbox（外扩 8–16px、偶数对齐），
     只拷这块矩形并同步平移 `trans`。典型人脸 ROI 约占整帧 10–35% → 拷贝量降到
     0.14–0.48MB。**注意**：`face_rotation` 来自两眼连线（`:218-223`），ROI 在源图上是斜的，
     必须用四角 bbox 而不是轴对齐矩形，否则边缘采样会变（结果不再逐位一致）。
- **风险**：
  - 零拷贝：对 buffer 布局的假设依赖机型，必须严格判指针 + 保留 fallback，并在首帧打一条
    `fast path hit/miss` 日志（真机确认覆盖率）。
  - ROI：矩阵平移方向搞反会直接采样错位；需要一张固定图做「ROI 开/关逐像素 diff」回归。
- **收益**：**0.15–0.30ms/帧** + 省 1.38MB/帧 带宽（≈83MB/s）；ROI 再叠加约 0.1–0.25ms。

### P2. 10Hz 检测也整帧重打包（无法 ROI，但可零拷贝）

- **现象**：检测需要全图找脸，不能用 ROI；但同样每次 1.38MB + 192KB 拷贝。
- **证据**：`face_detector.cpp:665-666`、`:729`。
- **改法**：与 P1 共用同一个「YUV 零拷贝视图」工具函数（同一份布局判定），检测侧直接受益；
  另可把检测的 `getSessionOutputAll` 换成缓存的 `getSessionOutput(name)`（10Hz，收益小）。
- **风险**：同 P1。注意检测与关键点各自持有 `yuv_buffer_`（各 1.38MB 常驻），
  零拷贝后这部分内存可一并省掉。
- **收益**：**0.15–0.30ms / 次**（=0.025–0.05ms/帧@10Hz）+ 每次 1.57MB 带宽。

### P3. 多路引擎的共享状态：一把串行锁 + 两种「全局唯一」

- **现象 A（锁）**：`nativeEstimateHeadPose` 用进程级 `g_head_pose_mutex` 包住整个几何求解
  （`MediaPipeFaceLandmarkJNI.cpp:690`），因为 `s_geometry_pipeline_` / `s_head_pose_*` 是静态
  （`face_landmarker.cpp:35-45`）。2 路时两个 worker 的头姿计算完全串行。
- **现象 B（平滑语义）**：landmark 平滑滤波是**每引擎一份**（`face_landmarker.cpp:94-99`），
  2 路时同一时刻两个滤波器各自只看到一半帧，输出在两种滤波状态间交替 → 与单路
  60Hz 平滑不等价，可能表现为细微高频抖动。帧序由 `landmarkMergeChannel` 还原，
  但**平滑发生在还原之前、且在各自引擎内**。
- **现象 C（数据竞争）**：`s_perf_profiler`（`face_landmarker.cpp:32`、`face_detector.cpp:34`）
  被多线程无锁累加，计数器和日志节流都是非原子（`perf_profiler.h`）→ UB、统计值撕裂。
- **证据**：以上行号。
- **改法**：
  1. 把几何管线与头姿滤波改成 `FaceLandmarker` **实例成员**（初始化成本只是 468×3 的
     canonical mesh + 权重 ~11KB），删掉 `g_head_pose_mutex`。
  2. 平滑滤波器要么在**重排之后**用单一实例做（推荐，语义与单路一致），要么明确接受
     「每引擎独立平滑」并写进文档；当前是后者但没有说明，容易被当成推理抖动。
  3. profiler 改 `thread_local`，或只让 slot 0 统计。
- **风险**：实例化几何管线会多 ~11KB/引擎（可忽略）；平滑改到重排之后需要动 Kotlin
  管线顺序（收益是质量而非速度，需 A/B 观感）。
- **收益**：去掉 0.15–0.4ms/帧的串行等待（2 路合计最多 ~0.4ms/帧的 p95 尾巴），
  消除 UB；平滑一致性属于画质收益，无法用 ms 量化。
- **结论（回应「共享锁是否限制 60fps」）**：**不是吞吐瓶颈**（锁占用 1–3% CPU），
  但它是每个 worker 的**帧尾延迟**，在 16.7ms 预算已经很紧时会放大 p95。

### P4. 每帧堆分配（总计 ~0.2–0.3MB/帧，60fps ≈12–18MB/s）

| 分配点 | 大小/次数 | 证据 |
|---|---|---|
| `FaceLandmarksResult.landmarks`（vector，clear+reserve） | ~5.7KB/帧 | `face_landmarker.cpp:538-540,362-363` |
| `landmarksVec`（头姿入参，**无 reserve**） | 478×12B，~9 次 realloc | `MediaPipeFaceLandmarkJNI.cpp:675-682` |
| `normalized_landmarks(468)` + `faces = {…}` 拷贝 | ~11KB + 外层 vector | `face_landmarker.cpp:686-693` |
| geometry：3×Procrustes（各 ~7 个 5.6KB 矩阵）+ `ToMatrix`/`ToLandmarkList`/`applyTransform` | ~200–250KB/帧 | `geometry_pipeline_nodep.h:164-186,249-334`；`procrustes_solver_nodep.h:226-286` |
| `NewFloatArray(478*3+1)` | 5.7KB Java 堆/帧 | `MediaPipeFaceLandmarkJNI.cpp:1289` |
| `getSessionOutputAll` map | 10Hz | `face_detector.cpp:741-754` |
| `detections` vector（检测） | 10Hz | `face_detector.cpp:776` |

- **改法**：结果/中间量全部成员化或 `thread_local` + 一次性 `reserve`；几何管线只保留
  **姿态矩阵**输出（跳过 `metric_landmarks` 的 inverse + `applyTransform` + `ToLandmarkList`，
  调用方只用了 `pose_transform_matrix`，见 `face_landmarker.cpp:727-728`）；Java 侧改成
  「调用方传复用 `FloatArray`」。代码草案见 §4.3/§4.5。
- **风险**：低（都是等价改写）；跳过 metric 输出需要确认没有其他调用方
  （`estimateFaceGeometry` 只被 `estimateHeadPose` 调用，`face_landmarker.cpp:723`）。
- **收益**：**0.05–0.20ms/帧** + 减少 GC / malloc 抖动（p95 更稳），内存 churn 降 ~80%。

### P5. host→session 输入拷贝 768KB/帧（CPU 后端可完全消除）

- **现象**：`ImageProcess` 先写 `input_host_tensor_`，再 `copyFromHostTensor` 到 session 输入。
- **证据**：`face_landmarker.cpp:499-503`、`face_detector.cpp:727-729`。
  （`PERFORMANCE.md:101` 说这轮改动「省掉每帧 442KB memcpy」——实际仍有一次拷贝，
  且 256 输入是 768KB 不是 442KB，见 §7。）
- **改法**：CPU 会话的 `input_tensor_->host<float>()` 非空，可以**直接把 session 输入张量**
  作为 `ImageProcess` 的 dest，跳过 `copyFromHostTensor`；用运行时判空自动降级，Vulkan 路径
  保持原样。代码草案见 §4.4。
- **风险**：低（`host()` 判空即回退）；Vulkan 侧不要尝试（device tensor 不可直接写）。
- **收益**：CPU 后端 **0.08–0.15ms/帧**，省 768KB/帧；同时 CPU 会话可省掉
  `input_host_tensor_` 的 768KB 常驻（× 引擎数）。

### P6. 头姿的二次 JNI 往返 + 从 Java 数组反序列化

- **现象**：关键点已经在 native 里解算完（`result.landmarks`），却先序列化成 Java float[]，
  Kotlin 构造 478 个 `Point3D`，再 `estimateHeadPose` 把 1434 个 float 传回 native，
  native 再用 `push_back` 重建 478 个 `FaceLandmark`。
- **证据**：`MediaPipeFaceLandmarkJNI.cpp:1277-1305`（出）→ `MediaPipeFaceDetector.kt:1044-1053`
  → `PipelineFaceTracker.kt:1103-1105`（见 §附录）→ `MediaPipeFaceDetector.kt:1129-1136`（重建数组）
  → `MediaPipeFaceLandmarkJNI.cpp:672-682`（再解析）。
- **改法**：新增 `nativeDetectLandmarksFromYuvWithPose(..., jfloatArray out)`：一次 JNI 内完成
  检测 + 姿态，输出 `[478×3 点, presence, pitch, yaw, roll]`；Kotlin 复用同一个 `FloatArray`。
  旧函数保留一版做回退。
- **风险**：中（新增 API + Kotlin 调用点改动；数值上完全等价，因为 Java float 是精确 32 位）。
- **收益**：**0.05–0.15ms/帧** + 少一次 JNI 上下文切换 + 少一次 5.7KB Java 分配。

### P7. 非热路径但会影响稳定性/内存的问题

| 问题 | 证据 | 影响 | 建议 |
|---|---|---|---|
| blendshape 模型默认加载，但仓库内**没有任何调用 `predictBlendshapes` 的地方** | `MediaPipeFaceDetector.kt:605-624`（`enableBlendshape=true`），`MediaPipeFaceLandmarkJNI.cpp:741` 起只有定义 | 常驻 1.86MB 权重 + 一个 session；启动多一次建会话 | 默认传 `false`（`initialize(numThreads, enableBlendshape=false)`） |
| int8 模型随包发布但从未引用 | `MediaPipeFaceDetector.kt:100-102` 只有 float 路径；assets 内 `*_int8.mnn` 共 1.53MB | APK 体积；无运行时影响 | 见 §6 |
| `nativeEstimateHeadPose` 循环 `i += 3` 未截断（若传入 `478*3+1` 的数组会多读 1–2 个 float） | `MediaPipeFaceLandmarkJNI.cpp:672-682` vs `:1289` | 越界读，现行 Kotlin 传的是纯 1434 数组所以暂未触发，但契约脆弱 | `numFloats -= numFloats % 3;` |
| `NewFloatArray` 结果未判空即 `SetFloatArrayRegion` | `MediaPipeFaceLandmarkJNI.cpp:1289,1305+` | OOM 时崩溃 | 判空返回 |
| 切后端 / 释放额外引擎与正在跑的推理**没有同步**（热路径故意不加锁） | `PERFORMANCE.md:112` 契约；`MediaPipeFaceLandmarkJNI.cpp:405-441`；`PipelineFaceTracker.kt:619-644` 的 `stop()` 只关 channel、不 join worker | 极端时序下 release session 与 `runSession` 并发 → UAF 崩溃 | 原生加 in-flight 计数（`shared_mutex` 读锁或 atomic epoch），写路径等待归零；Kotlin 侧 join 协程 |
| `config.numThread` 在 Vulkan 下是 **GPU mode**（union），不是线程数 | `ThirdParty/MNN/include/MNN/Interpreter.hpp:29-32`；`MNNForwardType.h:67-85` | 传 4 恰好等于默认 `MNN_GPU_TUNING_WIDE`；核数 <4 的机器会变成 `HEAVY`（注释：usually not suggested） | Vulkan 显式传 `MNN_GPU_TUNING_WIDE`，线程数只对 CPU 生效；日志里区分 |
| 每路额外引擎各自 `createFromFile` → 各自持有一份 4.89MB 模型 buffer | `MediaPipeFaceLandmarkJNI.cpp:370-381`；`face_landmarker.cpp:133-134` | 内存随路数线性增长 | 共享一个 `Interpreter`（一次 `createFromBuffer`，每 slot 各 `createSession`），省 4.89MB/路 + 一次磁盘/解析时间 |
| `PERF_LOG_INTERVAL=60` 每引擎各打一套日志、多线程交错 | `perf_profiler.h`；`face_landmarker.cpp:555` | 日志噪音，可能干扰测量 | 统计改 thread_local，只 slot 0 打印 |

---

## 4. Top 5 可直接落地的补丁草案（函数级 / 片段级，不在本次改动源码）

> 所有片段都以「最小 diff + 保留旧路径」为原则；行号是当前代码的锚点。

### 4.1 【P1/P2】YUV 零拷贝快路径 + ROI-only 打包

新增一个共用小工具（放在 `include/utils/yuv_utils.h`，与 `prepareBuffer` 并列）：

```cpp
// 返回一个「已经是 MNN 可用的连续 YUV」的视图；失败返回 false，调用方退回 prepareBuffer。
struct YuvFastView {
    const uint8_t* data = nullptr;   // Y 起始；NV12/NV21 时 Y+UV 连续
    MNN::CV::ImageFormat format = MNN::CV::YUV_NV21;
};
inline bool tryFastYuvView(const uint8_t* y, const uint8_t* u, const uint8_t* v,
                           int yRowStride, int uvRowStride, int uvPixelStride,
                           int width, int height, YuvFastView& out) {
    if (uvPixelStride != 2) return false;              // I420 / 非交织不适用
    if (yRowStride != width || uvRowStride != width) return false;  // 有 padding 就不能直传
    const size_t ySize = static_cast<size_t>(width) * height;
    const auto yAddr = reinterpret_cast<uintptr_t>(y);
    const auto uAddr = reinterpret_cast<uintptr_t>(u);
    const auto vAddr = reinterpret_cast<uintptr_t>(v);
    // UV 必须紧挨在 Y 之后、且两 plane 相邻 1 字节（NV12: U 在前；NV21: V 在前）
    if (uAddr == yAddr + ySize && vAddr == uAddr + 1) {
        out = {y, MNN::CV::YUV_NV12}; return true;
    }
    if (vAddr == yAddr + ySize && uAddr == vAddr + 1) {
        out = {y, MNN::CV::YUV_NV21}; return true;
    }
    return false;
}
```

`FaceLandmarker::detectFromYuv`（`face_landmarker.cpp:488-503`）改成：

```cpp
YuvFastView fast;
const uint8_t* yuvSrc = nullptr;
MNN::CV::ImageFormat yuvFormat;
if (tryFastYuvView(yData, uData, vData, yRowStride, uvRowStride, uvPixelStride,
                   srcWidth, srcHeight, fast)) {
    yuvSrc = fast.data;
    yuvFormat = fast.format;
    // 一次性日志：确认机型是否命中（每进程只打一次）
} else {
    yuvFormat = yuv::detectFormat(uvPixelStride, uData, vData);
    yuv::prepareBuffer(..., yuv_buffer_);
    yuvSrc = yuv_buffer_.data();
}
PERF_START(preprocess_timer);                 // 注意：把计时提前，别再漏掉拷贝
preprocessImageFromYuv(yuvSrc, yuvFormat, ...);
```

ROI-only 是第二步（可与零拷贝并存）：用 `trans` 的逆映射求 256×256 四角在源图的 bbox，
裁剪后 `trans.postTranslate(-roiX, -roiY)` 再 `convert()`；**先用固定图做开关 diff 验证**。

- 预计：**-0.15~0.30ms/帧**、-1.38MB/帧（ROI 命中时再 -0.1~0.25ms）。
- 风险：布局判定失败必须必回退；建议首帧 `FACE_LOGI("yuv fast path: %s", hit?"hit":"pack")`。

### 4.2 【P3】去掉头姿全局锁 + 拆分共享状态

`include/face_landmarker.h`：把静态成员改成实例成员

```cpp
// 删掉：static std::unique_ptr<GeometryPipeline> s_geometry_pipeline_;
// 删掉：static bool s_geometry_initialized_;
// 删掉：static HeadPoseFilterType s_head_pose_filter_type_;
// 新增（每引擎一份，初始化 ~11KB canonical mesh + 权重）：
std::unique_ptr<MNN::FaceGeometry::GeometryPipeline> geometry_pipeline_;
bool geometry_initialized_ = false;
std::unique_ptr<TimeBasedEMAFilter3D> head_pose_filter_;
```

`face_landmarker.cpp`：`initGeometryPipeline()` / `setHeadPoseFilterType()` 去掉 `static`；
`MediaPipeFaceLandmarkJNI.cpp:688-692` 删掉 `g_head_pose_mutex`（连同 `:81` 的定义），
因为每个引擎现在有独立管线与滤波。

同时：`perf_profiler.h` 的 `LandmarkPerfProfiler/DetectionPerfProfiler` 改为每个
`FaceLandmarker/FaceDetector` 实例一个（或 `thread_local`），消除多线程无锁累加。

- 预计：去掉 0.15–0.4ms/帧 的串行等待；消除 UB 与统计撕裂。
- 风险：低。注意 `setCameraFov`（`face_landmarker.cpp:622-630`）目前重建的是**静态**管线，
  改实例后要遍历所有引擎重建（或接受 FOV 变更时重建各引擎），调用点在相机启动早期，可接受。

### 4.3 【P4】每帧零分配：结果/LandmarksVec/几何临时量

```cpp
// FaceLandmarker：成员复用，而不是每帧构造
FaceLandmarksResult result_;                     // 成员
FaceLandmarksResult& FaceLandmarker::detectFromYuv(...) {
    result_.landmarks.clear();                   // 容量已保留
    result_.presence = false; ...
    ...
    return result_;                              // 调用方原先按值接收，改为 const& / 拷贝需要处
}

// MediaPipeFaceLandmarkJNI.cpp:675-682
static thread_local std::vector<FaceLandmark> landmarksVec;
landmarksVec.clear();
landmarksVec.reserve(478);                       // 只第一次真正分配
for (jsize i = 0; i + 2 < numFloats; i += 3) { ... }
```

几何侧：给 `ScreenToMetricSpaceConverter::Convert` 增加 `bool need_metric_landmarks`，
`estimateHeadPose` 传 `false`，跳过末尾的

```cpp
// geometry_pipeline_nodep.h:328-332
Matrix4f inverse_transform = pose_transform.inverse();
metric = applyTransform(inverse_transform, metric);
metric_landmarks = ToLandmarkList(metric);
```

（`estimateHeadPose` 只读 `pose_transform_matrix`，见 `face_landmarker.cpp:727-728`。）

- 预计：**-0.05~0.15ms/帧**，堆 churn 从 ~250KB/帧 降到 ~10KB/帧。
- 风险：低；需要确认没有其他调用方依赖 `metric_landmarks`（当前没有）。

### 4.4 【P5】CPU 后端直写 session 输入，省 768KB 拷贝

```cpp
// face_landmarker.cpp:499-503（detector 的 :727-729 同理）
MNN::Tensor* dst = input_tensor_;
const bool canWriteDeviceInput = (input_tensor_->host<float>() != nullptr); // CPU 会话为真
if (!canWriteDeviceInput) dst = input_host_tensor_.get();
preprocessImageFromYuv(..., dst);
if (!canWriteDeviceInput) input_tensor_->copyFromHostTensor(input_host_tensor_.get());
```

- 预计：CPU 后端 **-0.08~0.15ms/帧**、-768KB/帧；CPU 下可再省 `input_host_tensor_` 内存。
- 风险：低（`host()` 判空自动回退）；只对 CPU 生效。

### 4.5 【P6】关键点 + 头姿合并为一次 JNI，输出复用数组

```cpp
// 新 JNI（旧 nativeDetectLandmarksFromYuv 保留）
// out 长度 >= 478*3 + 1 + 3：点、presence、pitch,yaw,roll
extern "C" JNIEXPORT jboolean JNICALL
Java_..._nativeDetectLandmarksFromYuvWithPose(JNIEnv* env, jobject, ..., jfloatArray out) {
    auto& result = g_faceLandmarker->detectFromYuv(...);
    if (!result.isValid()) return JNI_FALSE;
    HeadPose pose{};
    if (result.landmarks.size() >= 468) {
        pose = FaceLandmarker::estimateHeadPose(result.landmarks,
                 rotatedWidth, rotatedHeight);       // 直接复用 native 结果，不再反序列化
    }
    env->SetFloatArrayRegion(out, 0, n, landmarksData/*含 presence*/);
    float p[3] = {pose.pitch, pose.yaw, pose.roll};
    env->SetFloatArrayRegion(out, n, 3, p);
    return JNI_TRUE;
}
```

Kotlin：每个 worker 持有一个 `FloatArray(478*3+4)`，循环内复用；
`landmarkResult` 的 `List<Point3D>` 仍在 Kotlin 层构造（下游需要），但省掉了反向的
1434 float 数组和一次 JNI。

- 预计：**-0.05~0.15ms/帧** + 少一次 5.7KB Java 分配。
- 风险：中（API 新增，需同步改 `MediaPipeFaceDetector` 与 `PipelineFaceTracker`）。

**备选（若只想要低风险）**：不合并 JNI，仅把 Kotlin 侧 `FloatArray(1434)` 改为 worker 复用的
成员数组（`MediaPipeFaceDetector.kt:1129-1136`），省 5.7KB/帧，收益约 0.02–0.05ms。

---

## 5. 多引擎内存 / 显存估算与并行度建议

> 手机是统一内存（Vulkan buffer 也在系统 RAM），**没有独立显存**；下表是进程 RSS 增量估算。
> 波动来源：MNN 内部内存复用策略、激活峰值、Vulkan 会话/命令池/描述符。

| 项目 | 每路额外引擎（Vulkan landmark） | 依据 |
|---|---|---|
| 模型 flatbuffer（`createFromFile`，每引擎各一份） | 4.89MB | assets 文件大小 |
| 后端权重（fp16/bf16 存储，按 `Precision_Low_BF16`） | 2.4MB（fp32 则 4.9MB） | 权重数 ≈1.22M |
| `input_host_tensor_` | 0.77MB | 256×256×3×4 |
| session 输入张量 | 0.77MB | 同上 |
| `yuv_buffer_`（整帧打包） | 1.38MB | 1280×720×1.5 |
| 激活峰值（MobileNetV2 类结构，128×128×96 为主峰） | 2.5–6MB（fp16）/ 5–8MB（fp32） | 1.57M 元素/峰 |
| Vulkan 会话开销（命令池、描述符、pipeline、staging） | 3–8MB | 经验值 |
| **合计** | **≈15–25MB / 路** | ±50% |

主引擎（slot 0）额外持有：检测器（模型 0.42MB + 权重/激活 ~2–4MB + 自己的 `yuv_buffer_`
1.38MB）、blendshape（1.86MB 权重 + session）。**slot 0 ≈25–35MB**。

4 路合计 **≈80–120MB**，对着「12GB RAM / 4GB 可用」完全不是约束。

**推荐并行度**：

- **保持 2 路（当前默认，`PipelineFaceTracker.kt:80`）**。理由：60fps 下推理已占
  ~87% GPU 时间（14.5ms × 60），增加引擎不能提高 GPU 吞吐，只能隐藏 CPU 尾巴。
- **3 路仅在真机 A/B 显示 `p95` 明显改善、且机身温度可接受时**才考虑。注意每多一路
  多 ~4.9MB 模型 + 15–25MB 会话，并增加 MNN 内部线程/命令缓冲的调度开销。
- **4 路不建议**（`MAX_GPU_CHANNELS=4`，`PipelineFaceTracker.kt:83`）：收益递减，
  热降频风险上升；内存虽够，但持续满 GPU 会更快触发降频，反而恶化 p95。
- 若要省内存：先做 §4.2 的「共享 Interpreter」（-4.89MB/路），再做 blendshape 关闭
  （-1.86MB + session）。

---

## 6. int8 模型可行性（资产里已有但从未引用）

**事实**

- `face_landmark_detector_mp_int8.mnn` 1,386,232B vs float 4,887,300B；`face_detector_mp_int8.mnn`
  141,912B vs 419,032B（3.5× / 3.0× 压缩）。
- 两者 MNN 版本头一致（3.1.1）、op/tensor 名数量一致、输入 shape 与 float 完全相同
  （landmark `[1,256,256,3]`，detector `[1,128,128,3]`）→ **同一张图，权重量化**。
- 代码里没有任何引用（`MediaPipeFaceDetector.kt:100-102` 只列 float 文件名）。
- 随包 `libMNN.so` 字符串含 `DT_QINT8`、`TfQuantizedConv2D`、`QuantizedDepthwiseConv2D`、
  `Int8ToFloat`、`DynamicQuant`，以及运行时探测
  `The device supports: i8sdot:%d, fp16:%d, i8mm: %d` → **CPU 后端支持 int8 卷积**。
- 随包 `libMNN_Vulkan.so` 只含 `glsl_convolution*` / `glsl_convolutionDepthwise*` /
  `glsl_gemm16x16` 的 FP16/FP32 变体，未见 int8 卷积 shader；同时引用
  `ConvolutionCommon::getConvParameters(Int8Common**, ...)`（反量化路径）。

**判断**

- **Vulkan 上大概率没有算力加速**：int8 权重在 build session 时被反量化成 fp16/fp32，
  省的是模型体积/加载内存，不是卷积时间。**需要真机 A/B 才能确认**（MNN 也可能按
  `MNN_SUPPORT_INT8` 编译了 int8 Vulkan 路径，但当前 `.so` 里没有对应 shader 名）。
- **CPU 后端是 int8 的主场**：当前 CPU 4 线程 landmark 约 12–16ms（`MediaPipeFaceDetector.kt:113-114`
  的注释），int8 + i8mm/i8sdot 有望降到 8–12ms，对「CPU 兜底也要 60fps」的机型价值最大。
- **精度风险**：当前已经在用 `Precision_Low_BF16`（`face_landmarker.cpp:144`），精度余量本就不大；
  再叠 int8 量化噪声，风险集中在 (a) 478 点的亚像素抖动（EMA 会放大滞后）、
  (b) z 深度不稳（头姿/表情补偿用）、(c) 下游眨眼/表情阈值。
  检测器 int8 风险低得多：10Hz、产物只是一个 ROI 框，NMS 容错大。

**建议试点顺序**

1. **检测器 int8**（`face_detector_mp_int8.mnn`）：加一个 `AssetInt8` 开关，A/B `Face Detection` 日志；
   通过标准：框 IoU vs float > 0.98、掉脸次数不增加、`getSessionOutput` 名称一致（须在真机打印确认）。
2. **landmark int8 仅 CPU 后端 A/B**：通过标准建议——归一化坐标
   `mean|Δ| < 0.002`、`p99|Δ| < 0.01`（720p 下约 2px）、presence 分差 < 0.05、头姿角差 < 0.3°，
   且 `TrackingConfidenceDetector` 低置信触发率不上升。
3. 只有 1、2 都通过且 Vulkan A/B 确实更快，才考虑上线；否则 int8 只作为「CPU 兜底档位」。
4. 若确定不用，删掉两个 int8 资产可减 1.53MB APK；但建议先做 A/B 再决定。

---

## 7. 与 `PERFORMANCE.md` 的偏差修正（避免后人被误导）

| `PERFORMANCE.md` 的说法 | 实际情况 | 证据 |
|---|---|---|
| 「landmark 只吃 192 的 ROI」（:65） | 模型输入 **256×256×3** | `.mnn` 尾部 shape 元数据；`face_landmarker.cpp:163-173` 按模型 shape 读取 |
| 「省掉每帧 ~442 KB memcpy」（:101/:158） | 仍在每帧拷贝，且是 **786,432B（768KB）** | `face_landmarker.cpp:503` |
| 「landmark 预处理直接写复用 tensor，无 temp + memcpy」（:101） | 只去掉了 temp tensor，host→session 拷贝仍在 | 同上 |
| 「整帧拷贝本身无法避免」（:102） | **可以**：连续 NV12/NV21 零拷贝；ROI-only 可省 80–90% | `yuv_utils.h:72-136` 的布局假设 |
| 「Vulkan 下 numThread 基本不参与计算」（`MediaPipeFaceDetector.kt:112`） | 更准确：Vulkan 下它就是 `mode`（GPU 调优位掩码），传 4 = 默认 WIDE | `Interpreter.hpp:29-32`、`MNNForwardType.h:67-85` |
| 实测 `Landmark 14.50ms / Detection 3.20ms`（:206-210） | 仍是有效锚点，但是**相机 30fps 时代**的数字；且 `Preprocess` 统计漏掉了整帧打包 | `face_landmarker.cpp:493` 的计时起点 |
| `p95=18.9ms`（:217） | 与「推理 14.5 + 原生固定开销 1–2ms + 调度/GC」相符，原生层是 p95 的可压缩部分之一 | 本报告 §2 |

---

## 8. 真机验证清单（拿到设备后按此顺序做，10 分钟能出结论）

1. **先排除相机**：关闭上半身（默认关）→ 看 `Camera input rate` 与 `cameraDeliveredFps`
   （`Camera2Manager.kt:1478` 的 `cameraDeliveredFps`，探针见 `:1301-1333`）是否仍是 30；再打开上半身对比一次
   （`UpperBodyPoseDetector.kt:357-379` 的同步转换在相机线程）。
2. **量出拷贝时间**：不改代码，直接对比日志里 `Total` 与 `Preprocess`
   （`perf_profiler.h` 每 60 帧输出）→ 差值 ≈ 整帧打包 + 输入拷贝。
3. **零拷贝命中率**：按 §4.1 加一行首帧日志，确认机型是否连续布局；命中则 `Preprocess`
   与 `Total` 差值应下降 0.1–0.3ms。
4. **ROI 正确性**：固定一张 720p 图，ROI 开/关各跑一遍，逐点 diff（应为 0 或 <1e-6）。
5. **多路 A/B**：2 vs 3 路，看 `Frame interval p95`、`Actual output FPS`、机身温度；
   同时对比「关掉平滑/单路」的抖动，确认 §P3-B 的平滑语义影响。
6. **内存**：`adb shell dumpsys meminfo <pkg>` 看 PSS，1 路 vs 4 路差值应 ≈ 3×15–25MB；
   若远大于此，检查是否每路重复加载模型 buffer。
7. **int8**：按 §6 顺序，只做检测器；记录 `Face Detection` ms 与框 IoU。

---

## 附录：证据索引

**原生热路径**

- `MediapipeFaceLandmark/src/main/cpp/src/core/face_landmarker.cpp`
  - 32（profiler 静态）、35-45（全局几何/头姿滤波）、93-102（每引擎平滑滤波）
  - 113-178（loadModel / 256 输入 / `Precision_Low_BF16` / numThread 配置）
  - 214-236（ROI 1.5× square）、238-287（RGB 预处理）、289-353（YUV 预处理 + convert）
  - 355-387（decode 478）、468-557（detectFromYuv：pack→convert→copy→run→decode→EMA）
  - 622-630（FOV 变更重建全局管线）、675-756（头姿：468 点拷贝 + 几何 + 滤波）
- `MediapipeFaceLandmark/src/main/cpp/src/core/face_detector.cpp`
  - 34（profiler）、180-254（loadModel）、256-329（896 anchors）
  - 331-347（896×sigmoid）、641-730（detectFromYuv：pack→convert→copy）、
    741-754（getSessionOutputAll）、761-781（输出拷回 + decode + NMS）、786-830（坐标/平滑）
- `MediapipeFaceLandmark/src/main/cpp/MediaPipeFaceLandmarkJNI.cpp`
  - 36-58（EngineSlot / kMaxEngines=4 / thread_local）、81+690（g_head_pose_mutex）
  - 340-389（extra engine init）、405-441（release，无 in-flight 同步）
  - 658-700（nativeEstimateHeadPose：无 reserve + 二次解析）
  - 1235-1310（nativeDetectLandmarksFromYuv：取地址 → detect → NewFloatArray）
- `MediapipeFaceLandmark/src/main/cpp/include/utils/yuv_utils.h:35-136`（detectFormat / prepareBuffer）
- `MediapipeFaceLandmark/src/main/cpp/include/common/perf_profiler.h`（非原子统计、60 帧日志）
- `MediapipeFaceLandmark/src/main/cpp/include/face_geomentry/geometry_pipeline_nodep.h:164-186,249-334`
- `MediapipeFaceLandmark/src/main/cpp/include/face_geomentry/procrustes_solver_nodep.h:226-286`

**调用方 / 配置**

- `facecapture-sdk/src/main/java/com/facecapture/sdk/PipelineFaceTracker.kt:80-94`（默认 2 路、10Hz）、
  `:559-577`（入队 + 相机线程 body tap）、`:619-644`（stop 不 join）、`:1013-1017`（检测入参）、
  `:1081-1085`（关键点入参 rotatedWidth/Height）、`:1103-1105`（头姿调用）
- `MediapipeFaceLandmark/src/main/java/com/example/mediapipefacelandmark/MediaPipeFaceDetector.kt:97-102`
  （模型名/最大引擎）、`:605-624`（默认加载 blendshape）、`:1029-1055`（解析 478 点）、
  `:1129-1136`（头姿重建 FloatArray）
- `MediapipeFaceLandmark/src/main/java/com/example/mediapipefacelandmark/Camera2Manager.kt:1392-1425`
  （回调 + close）、`:1651-1684`（processYuvDirect）、`:1178-1194`（AE 帧率区间）、`:1478`（交付帧率）
- `app/src/main/java/com/example/virtualfacecapture/UpperBodyPoseController.kt:290-311`
  （相机线程同步 YUV→Bitmap 入口）
- `ThirdParty/MNN/include/MNN/Interpreter.hpp:23-68`、`MNN/MNNForwardType.h:67-120`
- `MediapipeFaceLandmark/build.gradle`（`abiFilters arm64-v8a`、无 `-DENABLE_PERF_PROFILING=0`）
- `MediapipeFaceLandmark/src/main/cpp/CMakeLists.txt`（链接 `libMNN/libMNN_Express`，
  `libMNN_Vulkan.so` 运行时动态加载）

**二进制证据（本次审计新提取）**

- `assets/face_landmark_detector_mp.mnn` / `..._int8.mnn`：尾部 shape `[1,256,256,3]`，MNN 3.1.1
- `assets/face_detector_mp.mnn` / `..._int8.mnn`：尾部 shape `[1,128,128,3]`，MNN 3.1.1
- `jniLibs/arm64-v8a/libMNN.so`：`DT_QINT8` / `TfQuantizedConv2D` / `QuantizedDepthwiseConv2D` /
  `Int8ToFloat` / `DynamicQuant` / `i8sdot:%d, fp16:%d, i8mm:%d`
- `jniLibs/arm64-v8a/libMNN_Vulkan.so`：`glsl_convolution*` / `glsl_convolutionDepthwise*` 的
  FP16/FP32 变体、`ConvolutionCommon::getConvParameters(Int8Common**, ...)`

---

### 一句话给决策者

原生层已经没有「大块头」可省了：推理 14.5ms 是硬成本，剩下的 1–2ms 是每帧固定浪费
（YUV 1.38MB + host 768KB + 二次 JNI + 200KB 临时矩阵）。把这几笔清掉（§4.1/4.3/4.4/4.5，
合计 **0.4–0.8ms/帧**，风险低到中），是 60fps 下把 p95 从 18.9ms 拉回 16.7ms 的最便宜路径；
再往上就只能靠 int8（仅 CPU 有效）或降模型/降频。
