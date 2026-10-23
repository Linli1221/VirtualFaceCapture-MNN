# 第三轮 · 原生面捕热路径 CPU 成本审计（MediapipeFaceLandmark / MNN Vulkan）

> 审计对象：`MediapipeFaceLandmark/src/main/cpp/**` 与它们的实际调用点。
> **只读审计**：`src/` 下一行未改，本文是唯一写入。
> **环境限制**：本机没有 JDK / Android SDK / NDK / 真机，**没有编译、没有实测毫秒**。
> 本文所有数字分两类：
> - **可算的**：给出公式 + 算式 + 行号（字节数、像素数、循环次数、分配次数、flops 量级）；
> - **算不出的**：明确写「待实测」，并给出现成可用的日志/计数器去测（§8）。
> 若某个数字是估算，行内会标「≈」。

---

## 0. 现象与结论先行

用户反馈三件事：**面捕帧率低 / CPU 占用极高 / GPU 显存与算力都空闲**。
把这三件事放回代码上，结论是：

### 0.1 一句话结论

> 每帧真正跑在 GPU 上的只有 `runSession()` 里那一次 256×256 前向；
> 而**它前面排着一条全 CPU、全同步、单线程的串行队列**：
> YUV ROI 打包（0.13~0.78 MB memcpy）→ MNN `ImageProcess` 用 CPU 把 ROI 采样成
> **786 432 B float tensor**（65 536 px × 40~60 ops）→ `copyFromHostTensor` 全量上传
> → 推理 → **2 次阻塞式 device→host 回读** → 478 点循环解码 → 1 434 次 `exp` 平滑
> → 再走**一次独立 JNI** 把 478 点重新序列化到 Kotlin、再回来跑纯 CPU 的
> Procrustes 几何解算。
> GPU 每帧只有一次活儿可干，干完就被回读强制排空；CPU 则在等 GPU 和搬数据之间来回。

### 0.2 三个最大的 CPU 热点（按可算工作量排序）

| 排名 | 热点 | 每帧工作量（可算） | 证据 |
|---|---|---|---|
| 1 | **MNN `ImageProcess` 把 YUV ROI 采样成 float 输入** | 65 536 px × 3 ch；写出 **786 432 B** float；按每像素 YUV→RGB(9 乘) + 双线性 4 抽头 ×3 通道 + 归一化，**≈ 2.6~4 MFLOP/帧**（CPU，非 GPU） | `face_landmarker.cpp:635`、`face_landmarker.cpp:367`、`ImageProcess.hpp:111` |
| 2 | **host→device 输入上传（float 形态）** | **786 432 B/帧**；60Hz ≈ **47 MB/s**（若 MNN 内部还走 staging buffer，则是 2×） | `face_landmarker.cpp:642` |
| 3 | **头姿几何解算：3 次 Procrustes + 每帧 ~163 KB 堆 churn** | 3 × `SolveWeightedOrthogonalProblem`；每次 ≈ 30 K 乘加 + **37 440 B 临时矩阵**；`Convert` 再加 28 KB；JNI 侧 478 次 `push_back`（无 `reserve`）。合计 **≈ 100 K 乘加 + ~163 KB alloc+free / 帧**，60Hz ≈ **9.8 MB/s 内存抖动** | `geometry_pipeline_nodep.h:274-307`、`procrustes_solver_nodep.h:226-286`、`mini_linalg.h:402-418`、`MediaPipeFaceLandmarkJNI.cpp:675-682` |

外加一个**测量盲区**，它正是「所有性能数字都是静态估算」的根因：

- `PERF_START(preprocess_timer)` 在 **YUV 打包之后**（`face_landmarker.cpp:628`，打包在 586~626；
  `face_detector.cpp:686`，打包在 666~677）。所以日志里的 `Preprocess` **不含**打包，
  `Total` 又混进了打包 + 上传 + 回读。**现有日志在结构上就无法回答「CPU 花在哪」。**

### 0.3 GPU 为什么可能「空着」（原生侧的 2+ 条硬证据）

1. **每帧 2 次阻塞回读，GPU 队列被反复排空。** `copyToHostTensor` 是同步语义
   （`Tensor.hpp:148-153`：for DEVICE tensor 拷到 host），关键点 5 736 B + flag 4 B
   （`face_landmarker.cpp:670-671`），检测 57 344 B + 3 584 B（`face_detector.cpp:784-785`）。
   没有双缓冲、没有跨帧流水 → GPU 的忙闲比 = 单帧 GPU 计算时间 ÷ 整帧周期。
2. **GPU 在等 CPU 上传**：786 432 B 的 float 输入是 CPU 逐像素算出来的
   （`face_landmarker.cpp:635` → `:642`），这段时间 GPU 无事可做。
3. **`numThread` 被写进了 Vulkan 的 `mode` 联合体**：`ScheduleConfig` 里
   `numThread` 与 `mode` 是 **union**（`Interpreter.hpp:29-32`），Vulkan 下解释为
   `MNN_GPU_TUNING_*` 位标志（`MNNForwardType.h:69-84`）。`threads = cores.coerceIn(2,4)`
   （`PipelineFaceTracker.kt:500`），于是：
   - 4 核 → `mode=4` = `MNN_GPU_TUNING_WIDE`（默认，碰巧正确）；
   - **2 核 → `mode=2` = `MNN_GPU_TUNING_HEAVY`（头文件原话 "usually not suggested"）**；
   - **3 核 → `mode=3` = `TUNING_NONE|TUNING_HEAVY`（互相矛盾）**。
   证据：`face_detector.cpp:208`、`face_landmarker.cpp:141`、`blendshape_predictor.cpp:85`。
4. **未开 `MNN_GPU_RECORD_BATCH`**（`MNNForwardType.h:84`：让所有 op 共用一个 commandBuffer）。
   不开启时每个 op 各自录一次命令并提交；256×256 的小模型有上百个 op，
   提交/屏障开销会盖过计算，表现为「GPU 利用率低、CPU 忙」。
5. **`backupType` 默认 CPU**（`Interpreter.hpp:64`），且代码从未设置、也从未检查
   `runSession` 的返回码（`face_landmarker.cpp:647`、`face_detector.cpp:746`）。任何一个
   op 不被 Vulkan 支持，就会**静默落到 CPU**，每帧多一次 GPU↔CPU 同步，且**没有日志**。
6. （旁证）**第 4 个 Vulkan session 是死的**：blendshape 模型默认加载
   （`MediaPipeFaceDetector.kt:605` 默认 `enableBlendshape=true`，`PipelineFaceTracker.kt:501`
   只用默认值），但全仓库**没有任何 `predictBlendshapes` 调用点** → 建了 session、占了内存，
   一帧都没跑过。详见 §7。

---

## 1. 先钉死「生产路径到底走哪几个 JNI」

第三轮和第一轮（`docs/perf/audit-native.md`）最大的差别：第一轮按「函数」审计，
本轮按**实际被调用的那条路径**审计。生产路径（YUV 直通 + GPU 多路）是：

| 调用点 | 频率 | 说明 |
|---|---|---|
| `nativeDetectFaceFromYuv` | **10 Hz**（节流） | `PipelineFaceTracker.kt:1116-1121`，`DETECTION_INTERVAL_MS_STABLE=100`（`:94`）；掉脸时立即重检 |
| `nativeDetectLandmarksFromYuv` | **每帧** | `PipelineFaceTracker.kt:1199-1203`（worker 内），带 presence_score |
| `nativeEstimateHeadPose` | **每帧** | `PipelineFaceTracker.kt:1224-1226`，独立 JNI 往返 |
| `nativePredictBlendshapes` / `…Raw` | **0 次** | 只有定义，无调用点 → 死路径 |
| `nativePredictBlendshapes` 里的 E2E 补偿 | 0 次 | 同上 |

并行度：`GPU_CHANNEL_DEFAULT = 2`（`PipelineFaceTracker.kt:80`），即**主引擎 + 1 路额外
landmarker 引擎**；每路一条独立单线程（`:213-216`），线程启动时 `bindEngine(slot)`
绑定 `thread_local g_engine_index`（`MediaPipeFaceLandmarkJNI.cpp:50,303-316`）。
→ **常驻 4 个 MNN session**（detector / landmarker×2 / blendshape），全部建在 Vulkan 上
（`createModelsLocked`，`MediaPipeFaceLandmarkJNI.cpp:86-122`）。

> ⚠️ 关键结构事实：`FaceLandmarker` 的几何管线与头姿滤波是**进程级 static**
> （`face_landmarker.cpp:35-45`），所以 2 路并行时**几何解算完全不并行**，
> 而是被 `g_head_pose_mutex`（`MediaPipeFaceLandmarkJNI.cpp:81,689-692`）串起来。
> 多开一路只多花 CPU，不减少几何时间。这也是「GPU 并行但帧率不涨」的一个解释。

---

## 2. 每帧时序表（720p → 256×256 关键点帧，逐步骤）

> 基本参数：源 `1280×720` YUV_420_888，`rotation=90`（所以旋转后 `720×1280`）；
> 模型输入 `256×256×3` float（`face_landmarker.cpp:163-173` 读 shape）。
> 「ROI」= `round2-roi.md` 引入的只打包脸区。表里 ROI 打包字节数用
> `roiW × roiH × 1.5`（Y 全分辨率 + UV 各 1/4）。

| # | 步骤 | CPU 工作量（可算） | 证据 file:line | 可优化性 |
|---|---|---|---|---|
| 1 | JNI 取 3 个 direct buffer 地址 | 3 次 `GetDirectBufferAddress`，**0 拷贝** | `MediaPipeFaceLandmarkJNI.cpp:1259-1261` | 已最优 |
| 2 | 格式探测 | 1 次 `uvPixelStride` 比较 | `face_landmarker.cpp:518`、`yuv_utils.h:35-51` | 已最优 |
| 3 | 脸框 → 中心/边长/roll | 15 次浮点运算；`transformFaceRect` 的 1.5× 扩充 | `face_landmarker.cpp:214-236`、`:521` | — |
| 4 | 构造 dst→src 矩阵 | 8 次 `Matrix::post*`（4×4 浮点乘） | `face_landmarker.cpp:289-328`、`:527` | — |
| 5 | 算 ROI 四角 bbox | 4 次 `mapXY` + 8 次 `floor/ceil` + 6 次 clamp | `face_landmarker.cpp:542-579` | — |
| 6 | **YUV ROI 打包（memcpy）** | `roiW×roiH×1.5` B。典型脸高 300 px → 侧边 450 px → **303 750 B**；脸高 400 → **540 000 B**；脸高 500 → **777 600 B**（均 ≤ 整帧 1 382 400 B） | `face_landmarker.cpp:586`、`yuv_utils.h:214-349`（Y 按行 memcpy `:302-308`，UV 按行 `:340-345`） | ⚠️ 只在 `roiW,roiH≥64 且 area<85%` 时才走；否则整帧 → 命中率待实测（已有日志 `:597-601`） |
| 7 | **`ImageProcess::convert` → float tensor** | **65 536 px**；每 px：YUV→RGB 9 乘 + 3 通道双线性 4 抽头 + `×normal+mean` 6 次 → **≈ 2.6~4 MFLOP**；**写出 786 432 B**（256×256×3×4） | `face_landmarker.cpp:635` → `:367` → `:286`；`ImageProcess.hpp:111`；float 由 `input_host_tensor_` 的 dtype 决定（`face_landmarker.cpp:160-161`） | ⚠️ 这是**最大的单笔 CPU 工作量**；`filterType=BILINEAR`、`wrap=ZERO`（`:343-344`） |
| 8 | **host→device 上传** | **786 432 B**；60 Hz ≈ **47.2 MB/s** | `face_landmarker.cpp:642`（`copyFromHostTensor`，`Tensor.hpp:146`） | ⚠️ 是否有 staging 二次拷贝「待实测」 |
| 9 | `runSession` | GPU 前向（CPU 侧仅提交/等待）；返回码**被忽略** | `face_landmarker.cpp:647`、`Interpreter.hpp:405` | ⚠️ 应检查 `ErrorCode` |
| 10 | 取 2 个输出 tensor | 2 次 `std::map` 名字查找（**返回 const 引用，不分配**） | `face_landmarker.cpp:652-653`、`Interpreter.hpp:442,480` | 已可接受 |
| 11 | **2 次阻塞回读** | 5 736 B + 4 B；**2 个同步点** | `face_landmarker.cpp:670-671` | ✅ **可减到 1 次**（见 §5.2 / P1-1） |
| 12 | presence sigmoid | 1 次 `exp` | `face_landmarker.cpp:673` | — |
| 13 | 478 点解码 | 478 次迭代 × ≈ 12 flop；`reserve(478)` 是**每次调用都新建 vector**（5 736 B alloc/free） | `face_landmarker.cpp:678-679`、`:396`、`decodeLandmarks:388-420` | ✅ 可复用成员 vector |
| 14 | **时域平滑** | 478 × 3 = **1 434 次 `std::exp`** + 1 次 `steady_clock::now` | `face_landmarker.cpp:687`、`face_pipeline_filter.h:456-483`（`exp` 在 `:479`）、`:647-657` | ⚠️ 可改 `expf`/查表；量级 ~10 µs |
| 15 | 打包 jfloatArray | `NewFloatArray(1437)` **5 748 B Java 堆** + `SetFloatArrayRegion` 拷贝 5 748 B（JNI 内部再拷一次） | `MediaPipeFaceLandmarkJNI.cpp:1289-1306` | ⚠️ 可用 `ByteBuffer`/复用数组 |
| 16 | **Kotlin 反序列化 478 个对象** | `ArrayList<Point3D>(478)` + **478 个 `Point3D` 对象分配** + 1 434 次数组读 | `MediaPipeFaceDetector.kt:1042-1053` | ✅ 可整体消除（P0-2 的建议） |
| 17 | **再次序列化回 FloatArray** | `FloatArray(1434)` **5 736 B** + 1 434 次写 | `MediaPipeFaceDetector.kt:1129-1134` | ✅ 同上 |
| 18 | JNI 反序列化到头姿 | `GetFloatArrayElements` + `std::vector<FaceLandmark>` **无 reserve** 的 478 次 `push_back`（≈10 次 realloc，最终 5 736 B） | `MediaPipeFaceLandmarkJNI.cpp:673-684` | ✅ 可复用 thread_local 缓冲 |
| 19 | **取全局互斥锁** | 1 次 `lock_guard`（多路并行时是**真竞争点**） | `MediaPipeFaceLandmarkJNI.cpp:689-692` | ⚠️ 见 §5.3 |
| 20 | 紧凑度检查 | 2 × 468 次读 + 1 次 `sqrt` | `geometry_pipeline_nodep.h:408-432`、`:455` | — |
| 21 | `ToMatrix` | 468 列 → `Matrix3Xf` **5 616 B alloc** | `geometry_pipeline_nodep.h:164-174`、`:259` | ✅ 可复用 |
| 22 | `ProjectXY` + 深度均值 | 468 × (6 乘加) + 468 累加 | `geometry_pipeline_nodep.h:188-207`、`:264-268` | — |
| 23 | 2 次 `intermediate = landmarks` | 2 × 5 616 B 拷贝 | `geometry_pipeline_nodep.h:271,277` | ✅ 可复用 |
| 24 | **3 次 Procrustes** | 每次 ≈ 30 K 乘加 + **37 440 B** 临时矩阵（6 × 5 616 + 2 × 1 872）+ 468 次 `sqrt`（`ExtractSquareRoot`）+ 468 次权重校验；3× = **≈ 90 K 乘加 + 112 320 B** | `geometry_pipeline_nodep.h:274,294,306` → `procrustes_solver_nodep.h:288-311`、`:226-286`；`mini_linalg.h:402-418`（`Matrix3Xf` 内部是 `std::vector<float>`，**每个临时对象都是一次堆分配**） | ✅✅ **零分配化收益最大**（P1-2） |
| 25 | SVD 3×3 | Jacobi ≤ 50 次迭代（典型 3~6） | `mini_linalg.h:676-795` | — |
| 26 | `pose_transform.inverse()` + `applyTransform` | 4×4 高斯消元 + 468 × 12 乘加 = 5 616 B alloc | `geometry_pipeline_nodep.h:329-330`、`mini_linalg.h:344-396,803-819` | ✅ 可复用 |
| 27 | `ToLandmarkList` | 468 × 3 写 + 5 616 B `std::vector<Landmark3D>` | `geometry_pipeline_nodep.h:176-186`、`:332` | ✅ 可复用 |
| 28 | 欧拉角 + 头姿滤波 | 1 次 `sqrt` + 4 次 `atan2` + 1 次 `exp` | `face_landmarker.cpp:52-89`、`:866-891` | — |
| 29 | 返回 `[pitch,yaw,roll]` | `NewFloatArray(3)` | `MediaPipeFaceLandmarkJNI.cpp:695-697` | — |

**检测帧（10 Hz，另一条线程）** 同样走 1→15 的形态，只是尺寸小 4 倍、但**回读量大 10 倍**：

| # | 步骤 | CPU 工作量 | 证据 |
|---|---|---|---|
| D1 | YUV 打包 | 命中 `tryFastYuvView` → **0 拷贝**（相机缓冲连续 NV12/NV21 时）；否则 **1 382 400 B** memcpy | `face_detector.cpp:669-677`、`yuv_utils.h:161-194` |
| D2 | `ImageProcess` → float | 16 384 px → **196 608 B** 写出；≈ 0.65~1 MFLOP | `face_detector.cpp:738` |
| D3 | 上传 | **196 608 B** | `face_detector.cpp:740` |
| D4 | 回读 | **57 344 B**（regressor 896×16×4）+ **3 584 B**（scores 896×4）；**2 个同步点** | `face_detector.cpp:784-785`；896 个 anchor 由 `generateAnchors:256-329` 推出（见 §9 复算） |
| D5 | `decodeBoxes` | **896 次 `std::exp`**（`:345`）+ 幸存框 × 6 个关键点 | `face_detector.cpp:331-392` |
| D6 | `nms` | `std::sort` + `O(n²)` IoU（n = 过阈框数）+ `std::vector<bool>(n)` | `face_detector.cpp:410-464`、`:421` |
| D7 | 首帧 PPM dump | **921 600 次像素循环** + `std::vector<uint8_t>(2 764 800)` + 写文件（**一次**，但在检测线程上） | `face_detector.cpp:656-661`、`dumpYuvFrameToPPM:56-123`；dump 目录在生产中被无条件设置：`PipelineFaceTracker.kt:509` |

> 均摊到帧：检测成本 × 0.1。但因为它在**另一条线程**上和关键点抢核/抢 GPU，
> 它的抖峰会直接打在高帧率的 p95 上。

---

## 3. 每帧「分配 / 拷贝 / 建对象 / 加锁」清单

> 只列**每帧**仍存在的项。`byte` 数按 64 位 ARM 估算，`alloc` 指堆分配次数（含 malloc 扩容）。

### 3.1 拷贝（memcpy / 大块写）

| 拷贝 | 字节/帧 | 频率 | file:line | 备注 |
|---|---|---|---|---|
| YUV ROI 打包 | 135 000 ~ 777 600 | 每帧 | `face_landmarker.cpp:586`、`yuv_utils.h:302-308,340-345` | 命中 ROI 才有；否则 1 382 400 |
| `ImageProcess` 写 float 输入 | **786 432** | 每帧 | `face_landmarker.cpp:367` | CPU 计算 + 写内存 |
| 输入上传 host→device | **786 432** | 每帧 | `face_landmarker.cpp:642` | 可能 ×2（staging，待实测） |
| 输出回读 device→host | 5 740 | 每帧 | `face_landmarker.cpp:670-671` | 2 次同步 |
| JNI `SetFloatArrayRegion` | 5 748 | 每帧 | `MediaPipeFaceLandmarkJNI.cpp:1306` | 又一次同样的数据 |
| Kotlin → jfloatArray | 5 736 | 每帧 | `MediaPipeFaceDetector.kt:1129` | **同一份 478 点第 3 次搬家** |
| `GetFloatArrayElements` | 5 736 | 每帧 | `MediaPipeFaceLandmarkJNI.cpp:673` | 第 4 次 |
| 几何中间拷贝 | ≈ 163 000 | 每帧 | §2 #21~27 | alloc 与 free 成对 |
| 检测帧（10 Hz） | 196 608 + 60 928 | 10 Hz | `face_detector.cpp:738,740,784-785` | 均摊 1/10 |

> 每帧纯搬运算术：`0.3 + 0.786 + 0.786 + 0.006 + … ≈ 1.9 MB`（ROI 命中典型值）。
> 60 Hz → **≈ 114 MB/s** 的内存/总线流量，**其中 ≈ 1.57 MB 来自 float 形态的模型输入
>（CPU 写 + 上传）**；若换成 uint8 输入模型，这一项可压到 1/4（≈ 0.39 MB）。
> 这就是「GPU 空闲但 CPU 高」在数字上的样子。

### 3.2 堆分配（每帧）

| 分配 | 次数/帧 | 字节 | file:line | 能否消除 |
|---|---|---|---|---|
| `FaceLandmarksResult::landmarks.reserve(478)` | 1 | 5 736 | `face_landmarker.cpp:396` | ✅ 成员复用 |
| `jfloatArray` | 1 | 5 748 | `MediaPipeFaceLandmarkJNI.cpp:1289` | ⚠️ 需换返回契约 |
| Kotlin `ArrayList` + 478 `Point3D` | **479** | ≈ 17 000~20 000 | `MediaPipeFaceDetector.kt:1044-1053` | ✅✅ |
| Kotlin `FloatArray(1434)` | 1 | 5 736 | `MediaPipeFaceDetector.kt:1129` | ✅✅ |
| `std::vector<FaceLandmark> landmarksVec`（无 reserve） | ≈ 10（realloc 链） | 5 736 终值 | `MediaPipeFaceLandmarkJNI.cpp:675` | ✅ thread_local 复用 |
| `std::vector<NormalizedLandmark>(468)` | 1 | 5 616 | `face_landmarker.cpp:825` | ✅ |
| `faces = {normalized_landmarks}`（拷贝构造） | 1 | 5 616 | `face_landmarker.cpp:832` | ✅ |
| `std::vector<FaceGeometry> results` | 1~2 | 小 | `face_landmarker.cpp:834` | ✅ |
| `Matrix3Xf` / `VectorXf` 临时量（几何） | **≈ 28~30** | ≈ 163 000 | `geometry_pipeline_nodep.h:259,271,277,330,332`、`procrustes_solver_nodep.h:306,233,234,241,245,253,275,276,279`、`mini_linalg.h:409,490,542` | ✅✅ 预分配 scratch |
| `std::vector<bool> suppressed`（nms，检测帧） | 1 | n bits | `face_detector.cpp:421` | 10 Hz |
| `FaceDetection::keypoints`（6 × pair，检测帧） | 6/候选框 | 48/框 | `face_detector.cpp:387` | 10 Hz，可换 `std::array<...,6>` |
| `PerfTimer`（栈上） | 5 | 0 | `perf_profiler.h:215` | 无堆 |

**合计（关键点帧）：≈ 490 次 JVM/原生堆分配、≈ 190 KB 分配量。60 Hz → ≈ 29 400 alloc/s、≈ 11 MB/s。** 这部分是 GC 压力与 malloc 竞争的来源。

### 3.3 加锁

| 锁 | 频次 | file:line | 影响 |
|---|---|---|---|
| `g_head_pose_mutex` | **每帧** | `MediaPipeFaceLandmarkJNI.cpp:689-692`（`nativeEstimateHeadPose`）、`:769-777`（blendshape 路径，死代码） | 2 路并行时几何解算被串行化；且几何状态是全局 static（`face_landmarker.cpp:35-45`），**多路之间共享一份滤波状态** → 既是性能瓶颈也是时序语义问题 |
| `g_model_mutex` | 仅 init / 切后端 | `:172,220,347,415,431,712` | 不在热路径 ✅ |
| Kotlin `faceBoxLock` | 2 次/帧 + 2 次/检测 | `PipelineFaceTracker.kt:989-992,1115,1134,1192` | 短临界区，可接受 |
| Kotlin `perfLock` | **4~5 次/帧**（`ENABLE_PERF_PROFILING`） | `PipelineFaceTracker.kt:1045,1092,1119,1141,1250` | 建议改 `AtomicLong`/`LongAdder` |
| MNN session 内部 | 每 session 单调用者 | — | 无额外争用 |

### 3.4 「建对象」但非堆

- `MNN::CV::ImageProcess::Config`（栈，`face_landmarker.cpp:340-349`）+ 2 次 `memcpy`（`mean`/`normal` 各 16 B）：**已缓存 ImageProcess**（`:352-357`），只在格式变化时重建 ✅。
- `MNN::CV::Matrix`（栈，4×4）：每帧 8 次 `post*` ✅。
- `MNN::Tensor`：输入/输出 host tensor **均已复用**（`face_landmarker.cpp:160,462-471`；`face_detector.cpp:227,566-575`；`blendshape_predictor.cpp:104,187`）✅ —— 这一点前两轮已经做对，本轮无需再动。
- `std::string` 拼接：热路径**没有**（检测里的 `kv.first.find(...)` 只是比较；`s_dump_directory + "/…"` 只在首帧）。

---

## 4. MNN 配置审计（逐条 vs Vulkan 最佳实践）

当前三处配置**完全同构**（detector `:207-214`、landmarker `:140-147`、blendshape `:84-91`）：

```cpp
MNN::ScheduleConfig config;                    // :207
config.numThread = num_threads;                // :208  ← Vulkan 下这是 mode！
config.type = use_vulkan ? MNN_FORWARD_VULKAN : MNN_FORWARD_CPU;
MNN::BackendConfig backendCfg;
backendCfg.precision = MNN::BackendConfig::Precision_Low_BF16;   // :211
config.backendConfig = &backendCfg;
session_ = interpreter_->createSession(config);
```

| # | 项目 | 现状 | 与最佳实践的偏差 | 证据 |
|---|---|---|---|---|
| M1 | **`numThread` vs `mode`** | 统一写 `numThread` | `numThread`/`mode` 是 **union**；Vulkan 下 `mode` 是 `MNN_GPU_TUNING_*` 位标志。2 核 → `HEAVY`；3 核 → `NONE\|HEAVY`（矛盾） | `Interpreter.hpp:29-32`、`MNNForwardType.h:69-84`、`face_landmarker.cpp:141` |
| M2 | `MNN_GPU_RECORD_BATCH` | **未设置** | 官方注释：开启后所有 op 共用一个 commandBuffer；不开则每 op 一次提交 → 小模型上提交开销 > 计算，GPU 利用率低 | `MNNForwardType.h:84` |
| M3 | `backupType` | 未设置（默认 CPU） | 不支持 Vulkan 的 op 会**静默**落 CPU；每命中一次就多一次同步，且无日志 | `Interpreter.hpp:64` |
| M4 | `precision` | `Precision_Low_BF16` | 4 档枚举里最激进的一档；移动 Vulkan 常见无 bf16 扩展 → 实际可能退化成 fp32/fp16（行为不确定）。且低精度会直接伤 478 点的 z → 头姿 yaw/pitch 抖动 | `MNNForwardType.h:111`、`face_landmarker.cpp:144` |
| M5 | `power` / `memory` | 均默认 `Normal` | 60 fps 长跑场景通常 `Power_High` 更稳（避免 DVFS 把 GPU 降频）；`Memory_Low` 对 256×256 无必要。**需真机 A/B，不要盲改** | `MNNForwardType.h:103-109` |
| M6 | `setCacheFile` / `updateCacheFile` | **从未调用** | 不持久化 kernel/shader/pipeline cache → 每次冷启动后的前若干十帧都要重新编译、tuning（`MNN_GPU_TUNING_WIDE` 本身就是「广泛 tuning」）。用户感知的「刚打开时卡几秒」通常来自这里 | `Interpreter.hpp:189,205` |
| M7 | `setSessionMode` | 未调用（默认 `Session_Debug`） | `Session_Debug` 保留 op 级回调信息；`Session_Release` 更省。收益小但零风险 | `Interpreter.hpp:129-139,180` |
| M8 | `setSessionHint` | 未调用 | `WINOGRAD_MEMORY_LEVEL` 等 hint 全默认；winograd 的 workspace 档位与 conv 是否启用强相关，值得试 | `Interpreter.hpp:207-309` |
| M9 | **session 复用** | ✅ 三个模型各建一次，每帧只 `runSession` | 正确。但**共 4 个 session**（含 2 个 landmarker + 1 个死 blendshape），是否共享同一个 Vulkan Runtime/`sharedContext` **未知**（`BackendConfig::sharedContext` 从未设置） | `MediaPipeFaceLandmarkJNI.cpp:86-122,339-389`、`MNNForwardType.h:117` |
| M10 | `runSession` 返回码 | 3 处全部忽略 | MNN 会返回 `ErrorCode`；忽略意味着 Vulkan OOM / 后端不可用都被当成「有结果」，日志里只看到 `presence_score=0` | `face_landmarker.cpp:449,647`、`face_detector.cpp:538,746`、`blendshape_predictor.cpp:170` |
| M11 | `setDumpDirectory` | 生产里**无条件设置**为 cacheDir | 首帧会跑 921 600 次像素循环 + 写 2.7 MB PPM（**在检测线程上**），并引入一个无保护的 `static bool s_first_frame_dumped` 跨引擎数据竞争 | `PipelineFaceTracker.kt:509`、`face_detector.cpp:30,474-478,656-661` |

> M9 的验证方法（不需要改代码）：`adb logcat | grep -i "MNN\|vulkan"`，看初始化阶段
> 出现几次 backend/context 创建；或在 init 之后 dump `/proc/<pid>/maps` 里
> `libMNN.so` 的 `VkDevice` 分配数。**这两项本机做不了，标「待实测」。**

---

## 5. GPU→CPU 回读：位置、代价、能不能少

### 5.1 现状

| 回读点 | 字节 | 同步语义 | file:line |
|---|---|---|---|
| landmarker `Identity`（478×3） | 5 736 | `copyToHostTensor`（DEVICE→HOST，阻塞） | `face_landmarker.cpp:670`、`Tensor.hpp:148-153` |
| landmarker `Identity_1`（presence） | 4 | 同上（**第二个同步点**） | `face_landmarker.cpp:671` |
| detector `regressor`（896×16） | 57 344 | 同上 | `face_detector.cpp:784` |
| detector `classificator`（896） | 3 584 | 同上 | `face_detector.cpp:785` |
| blendshape | — | 死路径，从不执行 | — |

**每关键点帧 2 个同步点；每检测帧 2 个；2 路并行 → 每秒 120 个同步点 + 10 Hz 的 20 个。**
每个同步点意味着：等待该 session 的所有 GPU 命令完成 + 一次映射/拷贝。
单次开销「待实测」，但从结构上它把 GPU 的并行度压成了 1 帧 —— 这是 GPU 利用率低的结构性原因。

### 5.2 能减少吗？能，且都是低风险

1. **关键点：先读 flag，再决定要不要读 478 点。**
   当前两行无条件执行。改成先 `copyToHostTensor(flag)` → 算 sigmoid → 若 `presence` 为 false
   **直接 return**（现在也 return，但已经白读了 5 736 B 并多做一次同步）。
   掉脸/侧脸时省一次同步 + 5.7 KB。
2. **检测：先读 scores（3 584 B），全低于阈值就不读 regressor（57 344 B）。**
   896 个 anchor 的分数是独立的，`max(score)` 可先判定。无脸场景直接砍掉 94% 的回读量。
3. **合并成一次拷贝（可行但有前提）**：MNN 的 `saveTensors` + `getSessionOutputAll` 只能减少
   *查找*，不能减少同步次数；真要 1 次同步，需要模型把 presence 作为第 1435 个输出
   （改模型），或改用 `MNN::Tensor::createHostTensorFromDevice` 一次性读**同一块**连续内存 ——
   当前两个输出在 device 上是分开的，做不到「一次拷完」。**结论：不改模型就只能从 2 次降到 1 次（方案 1）。**
4. **真正的结构性解法（本轮不建议落地）**：把「关键点 + 姿态 + 52 BlendShape」全部搬到同一个
   GPU 图里，CPU 每帧只取 55 个数（≈ 220 B）。这需要模型工程配合，属于第四轮。

### 5.3 一个被忽略的放大效应

2 路并行时，几何解算被 `g_head_pose_mutex` 串行化（`MediaPipeFaceLandmarkJNI.cpp:689-692`）。
如果单帧几何是 0.1 ms，那么 2 路并行下几何的**吞吐上限仍是 10 000 次/秒**（不是瓶颈），
但**两路 worker 会在同一把锁上排队**，把关键点输出的 p99 拉长；
而且两路共用一份 One-Euro/EMA 状态（`face_landmarker.cpp:42-45`），
滤波器看到的 dt 变成了一半 → 相同 tau 下平滑更强 → **延迟变大、观感「更粘」**。
**这可能解释「多开一路后帧率没涨、手感反而更钝」——是假设，需用 §8 的
`try_lock` 失败计数 + 逐帧 pitch/yaw/roll 对比来证实。**

---

## 6. BlendShape：在 GPU 上？不，它根本没跑

这是本轮最反直觉的一条：

| 问题 | 事实 | 证据 |
|---|---|---|
| MNN blendshape 模型在跑吗？ | **没有**。`nativePredictBlendshapes` / `…Raw` 只有定义，全仓库无调用点 | `MediaPipeFaceLandmarkJNI.cpp:732-800`、`MediaPipeFaceDetector.kt:1153-1210`；`grep -r "predictBlendshapes("` 只命中声明 |
| 它被加载了吗？ | **加载了**，而且建在 Vulkan 上（第 4 个 session），常驻内存 | `MediaPipeFaceLandmarkJNI.cpp:105-116`、`PipelineFaceTracker.kt:501`（默认 `enableBlendshape=true`） |
| 那 52 维表情从哪来？ | **Kotlin 纯 CPU 几何启发式**：`BlendShapeExtractor` → `extractFullBlendShapes`（眼部/嘴部/gaze/高级表情，逐帧做点集运算 + 若干对象分配） | `OpenSeeFaceProcess/.../FaceCapturePipeline.kt:19-20,143-147`、`BlendShapeExtractor.kt:78-104` |
| 对「GPU 空闲」的意义 | 4 个 Vulkan session 里**有 1 个永远不提交任何命令**；同时 CPU 上多了一份本可以交给 GPU 的 52 维回归 | 同上 |

所以第 5 问的答案是：**既不「在 GPU 上」，也不是「被每帧拉回 CPU 算」——它是两条都没走：
MNN 的 blendshape 会话是死的，真正干活的是 Kotlin 的 CPU 启发式。**
（`docs/perf/audit-native.md:243` 已经指出「模型白加载」，本轮补上的是
「它还是第 4 个 Vulkan session」与「真正的 52 维来自 Kotlin」这两点。）

---

## 7. 补丁建议（按「收益 / 风险」排序）

> 排序原则：先能**测量**，再**减法**，最后才是**调参**。
> 每条给：改什么 / 怎么改 / 文件 / 预期收益 / 风险 / 如何验证。

### P0-1 仪器化：把 4 个阶段拆开计时（收益：把「猜」变「测」，风险≈0）

- **改什么**：`perf_profiler.h` 的 `LandmarkPerfProfiler` 增加 `pack`、`upload`、`readback`、`geometry`
  四个 `PerfStats`；在 `face_landmarker.cpp` 的 586~626 前后包 `PERF_START(pack_timer)`，
  在 642 前后包 `upload_timer`，在 670~671 前后包 `readback_timer`，
  在 `face_landmarker.cpp:846` 的 `estimateHeadPose` 里包 `geometry_timer`（复用 `LandmarkPerfProfiler`）。
- **文件**：`include/common/perf_profiler.h`、`src/core/face_landmarker.cpp`。
- **预期收益**：0 ms（不改变行为），但从此每次 A/B 都有 5 个可比的毫秒数。
- **风险**：极低。`PERF_START` 是栈上计时器（`perf_profiler.h:215`），每帧多 8 次
  `steady_clock::now()`，量级 <1 µs。
- **验证**：logcat 每 60 帧出现 `=== Face Landmarks Perf ===`，新增 4 行且
  `pack + preprocess + upload + inference + readback + postprocess + smoothing ≈ total`。

### P0-2 头姿合并进关键点 JNI（消除 1 次往返 + 478 个对象/帧）

- **改什么**：`nativeDetectLandmarksFromYuv` 的返回数组从 `478×3 + 1` 扩成 `478×3 + 1 + 3`
  （末尾追加 pitch/yaw/roll）；内部在拿到 landmarks 后直接调用
  `FaceLandmarker::estimateHeadPose`（同线程、同一份数据），Kotlin 侧删掉
  `estimateHeadPose()` 的 `FloatArray` 重建与 `nativeEstimateHeadPose` 调用。
- **文件**：`MediaPipeFaceLandmarkJNI.cpp:1287-1308`、`face_landmarker.cpp`、
  `MediaPipeFaceDetector.kt:1011-1056,1118-1140`、`PipelineFaceTracker.kt:1204-1232`。
- **预期收益**：每帧省 **1 次 JNI 往返 + 5 736 B 数组分配 + 1 434 次写 + 5 736 B 入参拷贝
  + 478 次 `push_back` 与 ~10 次 realloc**；按 §3.2 约占每帧 Java/native 堆分配的 **>90%**。
  CPU 绝对收益「待实测」，预期 0.05~0.2 ms/帧 + 明显的 GC 抖动下降。
- **风险**：中。返回数组长度变化是**契约变更**，所有读该数组的地方都要改
  （目前只有 `detectLandmarksFromYuvWithScore`）。建议保留旧函数、新增
  `nativeDetectLandmarksAndPoseFromYuv` 并灰度。
- **验证**：同一批帧上，新路径的 `[pitch,yaw,roll]` 与旧路径逐帧 diff（因为滤波状态共享，
  需先 `resetSmoothing`，且允许 <1e-3 的浮点差）；Java 对象分配数用 `Debug.startAllocCounting()`
  或 Profiler 看 GC 次数对比。

### P1-1 条件回读（省同步 + 省 57 KB/检测帧）

- **改什么**：
  1) `face_landmarker.cpp:670-673` 改成先读 `face_flag_tensor` → sigmoid → 若 `!presence`
     直接返回（不读 landmarks）；
  2) `face_detector.cpp:784-789` 改成先读 `score_host_tensor_`，求 `max`（896 次比较），
     低于 `score_threshold` 就跳过 `bbox_tensor->copyToHostTensor`。
- **文件**：`src/core/face_landmarker.cpp`、`src/core/face_detector.cpp`。
- **预期收益**：无脸/低置信度帧省 1 次同步；检测帧省 57 344 B 回读（无脸时 94%）。
  稳态有脸时收益 ≈ 0，**属于健壮性 + 掉脸恢复期收益**。
- **风险**：低（只要 `decodeLandmarks` 确实只在 presence 时被调用，现在就是这样）。
- **验证**：日志加 `readback_bytes` 计数器；A/B 看掉脸时的恢复帧率。

### P1-2 几何解算零分配化（停掉 ≈ 9.8 MB/s 的堆抖动）

- **改什么**：给 `ScreenToMetricSpaceConverter` 与 `ProcrustesSolver` 加
  「scratch 复用」：把 `weighted_sources / weighted_targets / twice_weighted_sources /
  centered_weighted_sources / rotated / rotated_sources / pointwise_diffs / weighted_diffs /
  sqrt_weights` 改成 `convert()` 内 **一次性 `resize` 的成员**（或传入的 workspace 结构体），
  `InternalSolveWeightedOrthogonalProblem` 接收 workspace 引用而不是自己造临时量。
  同时把 `Matrix3Xf::rowwiseProduct/leftMultiply/operator-` 增加 `void out(…, Matrix3Xf&)` 的
  「写进已有缓冲」重载，避免返回值语义的隐式分配。
- **文件**：`include/face_geomentry/procrustes_solver_nodep.h`、`geometry_pipeline_nodep.h`、
  `mini_linalg.h`（只加重载，不改现有语义）。
- **预期收益**：每帧少 **~28~30 次 malloc/free、~163 KB 分配量**（§3.2），60 Hz ≈ 9.8 MB/s；
  同时提高缓存命中（每块 5.6 KB 的临时矩阵反复换地址，会持续挤掉 L1 里的热数据，
  而 L1 通常只有 32~64 KB）。
- **风险**：中。别名（同一个 workspace 被同时当输入和输出）是最容易写错的地方；
  必须在每个 `Solve*` 调用点确认输入/输出不重叠。建议先只做 `ExtractSquareRoot` 与
  `sqrt_weights` 的复用（零别名风险，占 3 744 B × 3），再逐步推进。
- **验证**：先在 PC 上用同一组 478 点跑 1 000 次，比较旧/新实现的 `pose_transform` 最大绝对差
  （应 = 0，因为只是不再分配）；真机看 `malloc` 计数（`adb shell dumpsys meminfo` 的
  native heap 增速）。

### P1-3 Vulkan 配置修正（最可能改善 GPU 利用率）

- **改什么**（`face_detector.cpp:207-214`、`face_landmarker.cpp:140-147`、`blendshape_predictor.cpp:84-91`）：

```
if (use_vulkan) {
    config.mode = MNN_GPU_TUNING_WIDE | MNN_GPU_RECORD_BATCH;  // 不再写 numThread
} else {
    config.numThread = num_threads;
}
backendCfg.power = MNN::BackendConfig::Power_High;   // A/B 后决定
```

另外补三件小事：

1) `interpreter_->setSessionMode(MNN::Interpreter::Session_Release);`（每模型建 session 前调用一次）
2) `interpreter_->setCacheFile(cachePath.c_str());` + 首次 `createSession` 后
   `updateCacheFile(session_)`（路径用 Android cacheDir，同 dump 目录）
3) 检查 `runSession` 返回码并 `LOGE`（`ErrorCode != NO_ERROR` 时把 op 名/后端打出来）
- **文件**：上述三个 `.cpp`；`MediaPipeFaceLandmarkJNI.cpp`（把 cacheDir 传进来，新增
  `nativeSetCacheDirectory`）。
- **预期收益**：`RECORD_BATCH` 把上百次提交压成一次 → 直接减少驱动开销与 CPU 占用；
  `TUNING_WIDE` 修掉 2/3 核机型的错误档位；cache 持久化改善冷启动。**具体毫秒「待实测」**，
  这是本轮最值得先上真机 A/B 的一条。
- **风险**：中。`MNN_GPU_RECORD_BATCH` 在某些驱动上会引入状态切换 bug（MNN 文档标注为
  「Vulkan 有效」但非默认）；`Power_High` 可能增加发热。**必须 A/B，不要直接替换。**
- **验证**：logcat 里 `Face Landmarks Perf` 的 `Inference` 与 `Total`；
  同时用 `adb shell dumpsys gpu` / Snapdragon Profiler 看 GPU busy%；以及
  frame p50/p99（Kotlin 侧已有 `perfLandmarkTotal`）。

### P1-4 关掉死掉的 blendshape 模型

- **改什么**：`PipelineFaceTracker.kt:501` 显式传 `enableBlendshape = false`
  （或把 `MediaPipeFaceDetector.kt:605` 的默认值改 false，并让需要的人显式打开）。
- **文件**：`PipelineFaceTracker.kt`、`MediaPipeFaceDetector.kt`。
- **预期收益**：少 1 个 Vulkan session + 1 份模型常驻（启动更快、内存更省；
  若 4 个 session 不共享 Vulkan Runtime，还少一套 device 资源）。对帧率**无**直接收益。
- **风险**：极低（确认无调用点即可）。
- **验证**：init 日志里不再出现 `BlendShape predictor loaded successfully`；
  `nativeIsBlendshapeAvailable()` 返回 false 后 `MediaPipeFaceDetector` 的
  `predictBlendshapes` 分支不会被走到（本来也没人走）。

### P2-1 关掉首帧 PPM dump（或在 Release 里默认不设 dumpDir）

- **改什么**：`PipelineFaceTracker.kt:509` 的 `faceDetector.setDumpDirectory(cacheDir.absolutePath)`
  包一层 `if (BuildConfig.DEBUG && DEBUG_DUMP_FRAME)`；同时把
  `s_first_frame_dumped`（`face_detector.cpp:30`）改成 `std::atomic<bool>`。
- **文件**：`PipelineFaceTracker.kt`、`face_detector.cpp`。
- **预期收益**：去掉首帧的 **921 600 次像素循环 + 2 764 800 B 分配 + 2.7 MB 写盘**
  （发生在检测线程，用户可见为「第一帧卡一下」）；顺带修掉一个数据竞争。
- **风险**：极低。
- **验证**：冷启动后 cacheDir 里不再出现 `detector_input_frame*.ppm`；首帧时间下降。

### P2-2 检测后处理的临时分配与 896 次 `exp`

- **改什么**：`FaceDetection::keypoints` 从 `std::vector<std::pair<float,float>>` 换成
  `std::array<std::pair<float,float>, 6>`（`face_detector.cpp:387` 处 6 次 `emplace_back`
  → 0 次分配）；`decodeBoxes` 里 896 次 `std::exp` 可换 `expf` 或按 MediaPipe 的
  「先用 raw score 比较阈值再 sigmoid」提前剪枝（把 exp 从 896 次降到「过阈值候选数」次）。
- **文件**：`include/common/types.h`、`src/core/face_detector.cpp:337-391`。
- **预期收益**：10 Hz 路径，均摊很小；主要改善检测线程的抖动。
- **风险**：低（阈值语义要小心：`score = sigmoid(clamp(raw))`，剪枝要用 `clamp(raw)` 反推）。
- **验证**：检测结果与旧实现逐帧 diff（同一帧应给出相同框）。

### P2-3 平滑器的 `exp` 与几何的 `sqrt`

- **改什么**：`TimeBasedEMAFilter::filter` 的 `1 - exp(-dt/tau)` 在 dt 恒定时可缓存；
  478 个滤波器的 `tau` 相同 → 同一帧内 `alpha` 只算 1 次（现在是 1 434 次）。
- **文件**：`include/face_pipeline_filter.h:441-540,636-678`。
- **预期收益**：1 434 `exp` → 1 `exp`/帧。量级 ~10 µs，属于「顺手省」。
- **风险**：低（`dt` 需要在整帧内固定一次）。
- **验证**：输出逐帧 diff。

### P2-4 顺手修：析构顺序

- **改什么**：`FaceDetector::~FaceDetector`（`face_detector.cpp:140-147`）、
  `FaceLandmarker::~FaceLandmarker`（`face_landmarker.cpp:104-111`）、
  `BlendShapePredictor::~BlendShapePredictor`（`blendshape_predictor.cpp:49-56`）都是
  **先 `releaseModel()` 再 `releaseSession()`**，而 `loadModel` 里的顺序是对的
  （先 session 后 model，`face_detector.cpp:187-192`）。析构里反了。
- **文件**：三个 `.cpp`。
- **预期收益**：0（热路径无关），但切后端/释放时可能崩/泄漏，会影响真机 A/B 的可靠性。
- **风险**：极低。
- **验证**：反复 `nativeSetBackend(true/false)` 100 次不崩、native heap 不涨。

---

## 8. 待实测清单（用什么日志/计数器去测）

> 本机没有设备，以下每一条都是「拿到机器后 10 分钟内可出结论」的动作。
> 前提：先落地 **P0-1** 的埋点。

| 要回答的问题 | 现有可直接用的证据 | 需要新加的最小埋点 |
|---|---|---|
| 每帧时间到底怎么分？ | `PERF_CHECK_LOG`(每 60 帧，`perf_profiler.h:27-29`)：`Total/Preprocess/Inference/Postproc/Smoothing` | P0-1 的 `Pack/Upload/Readback/Geometry` 四行 |
| ROI 命中率与节省量？ | 已有一次性日志：`YUV ROI path: first hit, roi=…%`（`face_landmarker.cpp:597-601`）、`YUV full-frame path: first use (zero-copy/repacked)`（`:621-625`） | 把「一次性」改成每 300 帧统计一次命中率与平均 ROI 面积 |
| 检测被节流了多少？ | Kotlin `detectionSkipCount` / `detectionRunCount`（`PipelineFaceTracker.kt:1119,1141`） | 无需新增 |
| GPU 上传/回读各花多久？ | 无（这是完全的黑盒） | 在 `copyFromHostTensor` / `copyToHostTensor` 两侧加 `PerfTimer`，与 `runSession` 分开 |
| Inference 里是否含 GPU 等待？ | 无 | 对比 `runSession` 墙钟 vs `readback` 墙钟：若两者接近，说明 `runSession` 是同步的；若 `readback` 占大头，说明 MNN 是异步提交 |
| 有多少 op 落在 CPU（backupType）？ | 无 | 用 `runSessionWithCallBack`（`Interpreter.hpp:415`）在 debug 版里统计「每个 op 的 backend 名」，或打开 MNN 的 verbose 日志；M10 落地后错误码也会暴露 |
| 4 个 session 是否共享 Vulkan Runtime？ | 无 | init 后打 logcat 里 MNN 的 `create runtime` / `Vulkan` 相关行；或 `dumpsys meminfo` 的 native heap 增量对比「开/关 extra engine」 |
| 每帧到底分配多少？ | 无 | `Debug.startAllocCounting()` 包住 1 秒的 worker 循环；native 侧用 `mallinfo2`/自定义计数宏包住 `operator new` |
| 头姿是否是多路并行的瓶颈？ | 无 | 在 `g_head_pose_mutex` 上加 `try_lock` + 失败计数（只统计，不改行为） |
| 冷启动后前 N 帧有多慢？ | 无 | P1-3 的 cache 落地前后，对比第一秒的 `Inference` 均值 |

### 已有但需要修正的「盲区」

1. **打包时间没有归属**：`face_landmarker.cpp:586-626` 在 `PERF_START(preprocess_timer)`（`:628`）
   之前；`face_detector.cpp:666-677` 同理（`:686` 才开始计时）。→ P0-1 修掉。
2. **`Total` 混入了不该算的**：`detectFromYuv` 的 `PERF_START(total_timer)` 在函数最开头，
   包含格式探测、ROI 计算、打包、上传、回读——**这正是我们想要的分母**，但必须先拆开
   才解释得了它。
3. **perf 日志是「每 60 帧」的均值**，对 60 fps 抖动（p99）不敏感。建议同时记 `max_ms`
   （`PerfStats` 已经有 `max_ms`，只是 `log()` 里打出来了——`perf_profiler.h:143`）。

---

## 9. 数字复算（可追溯）

本轮所有字节数都是下面这几条算式，可以直接复现：

```
# 1) 关键点模型输入（float 形态）
256 × 256 × 3 × 4  = 786 432 B          # 每帧写出 + 每帧上传

# 2) 检测模型输入
128 × 128 × 3 × 4  = 196 608 B

# 3) BlazeFace 896 anchors（generateAnchors, face_detector.cpp:256-329）
stride 8 : (128/8)^2  = 16×16 = 256 格 × 2 anchor = 512
stride 16: (128/16)^2 =  8×8  =  64 格 × 6 anchor = 384   # 3 层同 stride，每层 2 个
合计 896 anchors
regressor: 896 × 16 × 4 = 57 344 B
scores   : 896 ×  1 × 4 =  3 584 B

# 4) 关键点输出
478 × 3 × 4 = 5 736 B        # Identity
                    4 B      # Identity_1（presence）

# 5) YUV ROI 打包（rotation=90，源 1280×720，ROI 侧边 s = 1.5 × 脸高）
bytes = s × s × 1.5
脸高 200 px → s=300 → (300²  + 300²/2) = 135 000 B   （整帧 1 382 400 B 的  9.8%）
脸高 300 px → s=450 → (450²  + 450²/2) = 303 750 B   （                  22.0%）
脸高 400 px → s=600 → (600²  + 600²/2) = 540 000 B   （                  39.1%）
脸高 500 px → s=720 → (720²  + 720²/2) = 777 600 B   （                  56.2%）
整帧 = 1280 × 720 × 1.5 = 1 382 400 B

# 6) 几何：每次 Procrustes 的临时分配
每次 Solve: 6 × (3 × 468 × 4 = 5 616 B) + 2 × (468 × 4 = 1 872 B) = 37 440 B
× 3 次 Solve = 112 320 B
Convert 里再加 ToMatrix(5 616) + 2×intermediate(11 232) + applyTransform(5 616)
          + ToLandmarkList(5 616) ≈ 28 080 B
estimateFaceGeometry 再加 ≈ 17 KB
→ 每帧 ≈ 163 KB

# 7) 每帧流量（ROI 命中典型值，脸高 300 px）
0.304 MB(pack) + 0.786 MB(float 写) + 0.786 MB(上传) ≈ 1.88 MB
60 Hz → 113 MB/s

# 8) 每帧分配次数
原生 ≈ 1 + 1 + 10 + 28 = 40；JVM ≈ 479 + 1 = 480
→ ≈ 520 次/帧，60 Hz ≈ 31 200 次/秒
```

**不能算、必须实测的**：ImageProcess 每像素真实指令数（MNN 实现相关）、
Vulkan 提交/回读的固定开销（驱动相关）、`copyFromHostTensor` 是否二次 staging、
Procrustes 的真实微秒数（取决于 AArch64 上 `std::vector` 临时量的分配器表现）。

---

## 10. 与前两轮的关系 / 本轮没做的事

| 内容 | 轮次 | 状态 |
|---|---|---|
| 全帧 1.38 MB 重打包、零拷贝 fast view、ROI-only 打包 | 第 1/2 轮 | 已落地（`yuv_utils.h:161-194,214-349`、`face_landmarker.cpp:585-626`） |
| `MNN::Tensor` 每帧 create、ImageProcess 每帧重建 | 第 1 轮 P4 | **已修**（`face_landmarker.cpp:160,462-471`、`face_detector.cpp:510-516,566-575`） |
| 头姿二次 JNI 往返 | 第 1 轮 P6 | **未修**，本轮升级为 **P0-2** |
| CPU 后端直写 session 输入 | 第 1 轮 P5 | 未做（当前跑 Vulkan，优先级下降） |
| Vulkan 配置（`mode`/`RECORD_BATCH`/cache/hint） | 前两轮**未审计** | 本轮新增 **§4 + P1-3** |
| 几何解算的分配账 | 前两轮未量化 | 本轮新增 **§3.2 + P1-2** |
| Blendshape 死会话 | 第 1 轮提过「白加载」 | 本轮补齐「第 4 个 Vulkan session + 真正来源是 Kotlin」 |
| **实测** | 三轮皆无 | §8 给出可执行清单 |

**本轮明确没做**：不改任何源码、不编译、不跑真机、不评估上半身链路
（`MediapipeUpperBody` 是另一条 CPU 线程，属于 Kotlin 审计范围）。
`yuv_converter.cpp` / `mnn_yuv_converter.cpp` 虽然编进了 `libmediapipefacelandmark.so`
（`CMakeLists.txt:16-17`），但唯一调用方是 legacy RGB 预览路径
（`Camera2Manager.kt:1701-1731` → `yuv420ToRgbRotated`），**不在 YUV 面捕热路径上**，
本轮不计入成本；若将来把预览也切回 RGB，它每次会整帧写 `1280×720×3 = 2 764 800 B`。

---

## 11. 给下一步的一句话

> 先做 **P0-1（埋点）**，再拿一台 2~4 核机的 `pack / preprocess / upload / inference /
> readback / geometry` 六个数回来；同时把 **P0-2（头姿合并）** 和 **P1-3（Vulkan 配置）**
> 准备成可 A/B 的分支。
> 如果 `preprocess + upload` 加起来 ≥ `inference`，那么「GPU 空闲」的根因就是
> **CPU 预处理 + 同步式上传**，下一轮该做的是把预处理搬进 GPU（或换 uint8 输入模型），
> 而不是继续调 MNN 线程数。
