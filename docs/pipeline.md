# 动捕流水线架构：一帧数据是怎么变成虚拟形象动作的

> **这份文档回答一个问题：动捕是怎么完成的。**
> 它自顶向下把「相机 → 原生推理 → Kotlin 流水线 → 协议 → PC 渲染」串一遍，说明每一段
> *做了什么、为什么这么做、花多少时间*。
>
> 它**不是契约**，不重复已有文档的内容：
>
> | 想查什么 | 看哪里 |
> |---|---|
> | 报文格式、字段语义、映射参考实现 | [`tracking-protocol.md`](./tracking-protocol.md) |
> | 上半身模块的 API / 验收 / 模型获取 | [`upper-body.md`](./upper-body.md) |
> | 各阶段 ms 级审计与补丁草案 | [`perf/audit-kotlin.md`](./perf/audit-kotlin.md)、[`perf/audit-native.md`](./perf/audit-native.md) |
> | 本轮 ROI 分支改造的设计/数学/验证 | [`perf/round2-roi.md`](./perf/round2-roi.md) |
> | 历次性能改造记录 | [`../PERFORMANCE.md`](../PERFORMANCE.md) |
>
> 文中 `file:line` 是 `feat/mediapipe-upper-body` 分支上的锚点，用来「顺藤摸瓜」定位代码；
> 函数名比行号更稳定，行号漂移时以函数名为准。

---

## 1. 一句话概括

**手机只做「采集 + 推理 + 解算」，把每帧结果压成一条 ≤1 KB 的 JSON 走 WebSocket 发出去；
PC 只做「映射 + 渲染」。** 两端之间除了这份 JSON 没有任何耦合——不传图像，不传音频，
手机不知道 PC 的地址，也不需要知道。

动捕能力由**两路数据源**喂给同一个 `track` 报文：

| 通道 | 数据源 | 产出 | 频率 | 是否默认开 |
|---|---|---|---|---|
| **脸部** | MNN + `.mnn` 模型（自研 C++ 推理） | ARKit-52 BlendShape + 头部姿态 `pitch/yaw/roll` | 受推理限制，约 60 fps | ✅ 开 |
| **上半身** | 官方 MediaPipe Tasks `PoseLandmarker` | 13 个上半身关键点 + 相对躯干角 `[roll, yaw]` | 15 fps（抽帧） | ❌ 关 |

两条通道**完全解耦**：上半身关掉时相机线程零额外开销，也不加载 MediaPipe Tasks 运行时；
面捕的「不依赖 MediaPipe 运行时」这个设计没有被破坏（`upper-body.md` §1）。

---

## 2. 总览

```
Camera2 ImageReader  (YUV_420_888 · 1280×720 · 60fps 目标)
   │
   │  相机线程 <16.7ms 预算：只搬指针，不做任何像素转换
   │
   ├──► yuvFrameChannel(2) ────────► fc-face-detect   ──── 10Hz ───► lastFaceBox
   │                                   face_detector 128×128 → 框 + 6 关键点
   │                                   （整帧零拷贝快路径，命中时不再重排 1.38MB）
   │
   ├──► yuvLandmarkFrameChannel(3~5) ─► fc-face-landmark-gpu0..3
   │                                   face_landmarker 256×256 ROI → 478 点
   │                                   （只打包 720p 源帧里的脸 ROI，约 0.15–0.4MB）
   │                                   → estimateHeadPose → Procrustes → 欧拉角
   │                                        │ landmarkMergeChannel(8)
   │                                        ▼
   │                                   fc-face-landmark-merge
   │                                   FrameReorderBuffer（按 frameId 还原顺序）
   │                                        │ landmarkChannel(4)
   │                                        ▼
   │                                   fc-face-process
   │                                   FaceCapturePipeline：52 BS 解算 + 自标定 + 眨眼曲线
   │                                        │ outputChannel(1)
   │                                        ▼
   │                                   fc-face-output ──► 5 个回调
   │
   └──► onYuvFrameForBodyWithHint  (抽帧 tap · 66ms = 15fps)
            │  带同帧脸框（一级扩充 1.15×，TTL 500ms）；duplicate() 不拷像素
            ▼
        UpperBodyPoseDetector
            │  ① 脸框 → 上半身 ROI（4.5×/5.5× 脸宽高）→ 只采样 ROI 到 320p
            │     （同步；无脸框 / ROI 太小时整帧 320p）
            │  ② PoseLandmarker.detectAsync（异步）
            │  ③ 33 点回映整帧 → 13 点 → One-Euro → 躯干角 → 减基线
            ▼
        UpperBodyPoseFrame ──► TrackingServer 最新快照

        两条通道在 TrackingOutputManager 汇合
                   │
                   ▼
        TrackingServer.submitTrack
          • 分配 seq
          • 校准边界补发 calib
          • 无客户端 → 连 JSON 都不生成
          • buildTrack 手写 JSON（≤1 KB）
                   │
                   ▼
        TrackingFrameBuffer  (500ms 播放缓冲)
          • 攒够 0.5s 起播
          • 按生产节奏匀速放行
          • 深度比例调节补齐垫子
                   │
                   ▼
        每客户端 AtomicReference「最新帧」覆盖写入  ──► WS 文本帧
                                                         │
                        ┌────────────────────────────────┘
                        ▼
        Windows 客户端  handleMessage → 丢旧 seq → feedTrack
          • applyPoseOffset（本地姿态校准）
          • mapper.apply(bs, pose) → 10 个头/脸参数
          • updateBodyState → readUpperBodyTrack → mapBody → smoothBodyParams
          • stage.pushTrackParams / setBodyParams
                        │
                        ▼
        live2d.js  beforeModelUpdate 钩子
          • ParamTimeline 时间轴插值（≤120ms 自适应延迟）
          • 头/脸一阶低通（τ 10ms 或 0.6 系数）
          • 上半身一阶低通（τ 160ms）+ 头姿与躯干相加 clamp
                        │
                        ▼
        core.setParameterValueById  ──►  Cubism 渲染
```

---

## 3. 第一段：采集 —— 相机线程只搬指针

### 3.1 尺寸与帧率的选择策略

固定目标 **1280×720 @ 60fps**（`Camera2Manager.kt:176-180`），但选择顺序是
**帧率优先于清晰度**：`chooseCaptureSize`（`Camera2Manager.kt:822`）只在 HAL 明确证明
能跑满 60 fps 的尺寸里挑，挑不到才退让。同时有两道兜底：

- `MIN_SIZE = 192`（`:186`）—— 再小就没法做人脸检测了；
- `MAX_PIXEL_COUNT = 1280×720`（`:195`）—— 不接受更高分辨率，因为整帧处理和 AE 都会被拖垮。

AE 侧还会把 `SENSOR_FRAME_DURATION` 钉在区间上限（`Camera2Manager.kt:1154-1165`，
只在要求 ≥60 fps 时才钉），并把 AE 目标区间选成 `(30, 60)`（`:1188`）。这一步是必需的：

> **AE 区间是「允许范围」，不是「目标帧率」。** 选到 `(30,60)` 这种可变区间后如果不管它，
> Camera2 的 AE 在室内灯光下会从区间下端（33 ms）开始跑——为了拿更长曝光时间，
> 代价是帧率直接砍半。现象就是「CPU 和 GPU 都不忙，但只有 30 帧」：整条管线都在等相机喂帧。

钉帧率有 HAL 兼容兜底：个别 HAL 拒绝显式的 `SENSOR_FRAME_DURATION`，就退回只设 AE 区间
（`:1051`），并在下一轮重试（`:550`）。

### 3.2 关键设计：不做 YUV → RGB

这是整条链路能跑 60 fps 的**前提**。

`onImageAvailableListener`（`Camera2Manager.kt:1401`）→ `acquireLatestImage()` →
`processYuvDirect(image)`（`:1663`）→ `image.close()`。`processYuvDirect` 只做一件事：
把 3 个 plane 的 **direct ByteBuffer**、三个 stride、旋转角取出来，包成 `YuvFrameData`。

| 做法 | 每帧代价 |
|---|---|
| **现状：传 plane 指针** | 0 像素拷贝，约 0.05–0.25 ms（`getRotationDegrees()` 走 DisplayManager IPC 时占大头） |
| 假设：先转 RGB 再推理 | 写 2.7 MB + 92 万次逐像素循环，约 2–5 ms，正好吃掉 1/4 的相机预算 |

代价是下游（原生侧）必须自己吃 YUV，所以 `face_detector` / `face_landmarker` 都有
`detectFromYuv` 这一套入口，而 MNN 的 `ImageProcess` 原生支持 YUV 源格式。

### 3.3 工作区里这一轮新增的三件事

| 机制 | 作用 | 锚点 |
|---|---|---|
| **预览限速 30 fps** | 预览只是给人看的，不必跟满 60；把省下的预算让给推理 | `PREVIEW_INTERVAL_NS`（`:208`）、门控在 `maybeConvertPreviewFrame`（`:1502`） |
| **相机线程提到 `THREAD_PRIORITY_DISPLAY`** | 相机回调预算是 16.7 ms 硬上限，而推理线程是 `NORM+2`；默认优先级会被推理线程压后，表现为「画面转着转着就一顿一顿」 | `:598` |
| **交付帧率自检 + 自适应降档** | 起播 2.5 s 后测两次交付帧率，低于 50 fps 就判因 | `scheduleFpsProbe`（`:1522`）、`restartAtSmallerSize`（`:1614`） |

降档逻辑值得单独说，因为它**先判因再动手**：

```
交付帧率 < 50 fps ?
   ├─ HAL 报的 SENSOR_FRAME_DURATION = 33.3 ms
   │     → 传感器本身就是 30fps，降档没用 → 直接放弃，不改任何东西
   └─ SENSOR_FRAME_DURATION = 16.7 ms
         → 传感器能跑 60，是我们回调太慢 → 降到「更小但仍支持 60fps」的尺寸重试
```

降档还有 `captureLifecycleLock` + `fpsProbeGeneration` 两道并发防护：重配置期间到达的
旧回调会因为 generation 不匹配被丢弃，不会去关掉新会话的 `ImageReader`。

---

## 4. 第二段：原生推理（C++）

单 so：`libmediapipefacelandmark.so`，由 `MediapipeFaceLandmark/src/main/cpp/CMakeLists.txt`
作为**根** CMake 工程构建（不是子模块分支），JNI + core + 两个 YUV 转换器全部编进去。

没有 `JNI_OnLoad` / `RegisterNatives`，全靠 `Java_com_example_mediapipefacelandmark_*`
静态名字绑定；Kotlin 侧对应函数都是 `private external`。

### 4.1 人脸检测：BlazeFace 128×128

`nativeDetectFaceFromYuv`（`MediaPipeFaceLandmarkJNI.cpp:1168`）
→ `FaceDetector::detectFromYuv`（`face_detector.cpp:641`）：

```
yuv::detectFormat          自动识别 I420 / NV12 / NV21（看 uvPixelStride + U/V 指针先后）
yuv::tryFastYuvView        连续 NV12/NV21 时直接引用相机缓冲（零拷贝）
yuv::prepareBuffer         否则把带 stride 的三平面打包成连续缓冲（fallback）
letterbox 缩放 + 居中 pad  mean 127.5 / norm 1/127.5，BILINEAR
runSession                 推理（Vulkan 或 CPU + Precision_Low_BF16）
decodeBoxes                896 个 anchor → 框 + 6 个关键点
nms                        加权 NMS（WBF 风格）
```

**输出驱动采样**：BlazeFace 的输入固定 128×128，MNN 只采 16,384 个输出像素，源图再大也
不会多读；所以「128p 检测」在本仓库就是「检测输入 128×128」，不需要再额外做一次
YUV→128p 的整帧下采样（那样反而多读一遍源图）。

**零拷贝快路径**：`tryFastYuvView` 只接受 `uvPixelStride==2` + 两行 stride 都等于 width +
U/V 指针恰好紧接 Y 平面的布局；任何不匹配都退回 repack，不做机型假设。命中时检测侧
每 100ms 少一次 1.38MB 重排。

**896 个 anchor**：4 层，strides `{8,16,16,16}`，`min_scale = 0.1484375`、
`max_scale = 0.75`、offset 0.5（`face_detector.cpp:261-263`）。同 stride 的层合并尺度
（层 0 单独、层 1–3 共 6 个尺度），128×128 下正好 896 个。

**加权 NMS** 不是硬抑制：重叠框按权重合并成加权平均框与加权平均关键点，`iou > 阈值`
的被打上 `suppressed` 标记但不丢弃——这对侧脸、遮挡时的框稳定性帮助很大。

输出张量按名字匹配（含 `"regressor"`/`"boxes"` 和 `"classificator"`/`"scores"`），
不依赖张量顺序。

**节流到 10 Hz**：稳定追踪时脸框 100 ms 内几乎不动，所以
`DETECTION_INTERVAL_MS_STABLE = 100L`（`PipelineFaceTracker.kt:94`）让每 6 帧才跑一次检测。
这一级单次 3.2 ms，均摊后只有 0.53 ms。

### 4.2 关键点：256×256 ROI（720p 源帧裁切）

`FaceLandmarker::detectFromYuv`（`face_landmarker.cpp:501`）：

**① ROI 参数**（`transformFaceRect`）
中心 = 框中心，宽高各 **×1.5**，取 `max(w,h)` 做正方形；再叠加**两眼连线的 roll 角**
`atan2(dy, dx)`。正方形化是因为 landmark 模型只吃方形输入；贴 roll 角是为了让模型看到的
永远是「正脸朝上」，否则歪头时关键点会漂。

这是**二级扩充**：检测框在 SDK 侧已经先做过一级扩充（1.15×，抵消 10Hz 框的滞后），
两级叠加约 1.725×，脸顶点/下巴在大角度时不容易被裁掉。

> 现状说明：当前生产路径（YUV 直通）的 `FaceBox` 只带框 + 分数，不带检测器的 6 个关键点，
> 所以 `transformFaceRect` 里的**两眼连线 roll 实际为 0**；代码保留 roll 分支，供以后把 6 点
> 一路传下来时直接启用。

**② 仿射矩阵**（`buildYuvTransform`）

```
I → postScale(1/input_w) → postTranslate(-0.5, -0.5)
  → postRotate(roll) → postScale(w_px, h_px) → postTranslate(cx_px, cy_px)
  → postTranslate(-cx_rot,-cy_rot) → postRotate(-img_rotation) → postTranslate(cx_src,cy_src)
```

注意最后三步是**相机旋转的逆映射**：相机传感器方向 ≠ 显示方向，这里是唯一处理它的地方。

**②.5 只打包脸 ROI**（`detectFromYuv`）：用 `trans.mapXY` 把 256×256 的 4 个角映射回
720p 源帧，取 bbox、外扩 8px、偶数对齐、clamp。ROI 面积 < 85% 整帧且边长 ≥64px 时，
`prepareBufferRect` 只拷这块（典型 0.15–0.4MB，替代整帧 1.38MB），再用
`trans.postTranslate(-roiX,-roiY)` 把矩阵换算到 ROI 局部坐标；否则退回整帧
（整帧优先走零拷贝快路径）。

**③ 预处理直接写进复用的 host tensor**（`input_host_tensor_`），跳过「临时 tensor + memcpy」
这一步，每帧省一次 256×256×3 = 192 KB 的拷贝。

**④ 输出**：按名字取 `"Identity"`（478×3）和 `"Identity_1"`（presence logit），
`sigmoid` 后与阈值 0.5 比较。低于阈值直接判失败，不进解码。

**⑤ 反解码**（`decodeLandmarks`，`:355`）：减 0.5 居中 → 按 ROI 的 `cos/sin(roll)` 旋转 →
映射回全图归一化坐标。`z` 只乘 `rect_width`，所以它是**相对深度不是米制**——
这一点在 PC 端映射时很重要（不能直接当角度用）。

**⑥ 平滑**：`UnifiedLandmarksSmoothingFilter`，Time-Based EMA，`tau = 0.12 s`
（`face_landmarker.h:213` 默认开启、`:214` 持有实例）。presence 丢失的帧会 `reset()`，
避免掉脸再回来时被旧状态拖住。

### 4.3 头姿：Procrustes + 正交投影

这是整条链路数学上最重的一段。Kotlin 侧单独发一次 JNI 调用
`nativeEstimateHeadPose`（`MediaPipeFaceLandmarkJNI.cpp:659`）。

**为什么不能只用 6 个检测关键点算角度？** 因为 6 个点只能拟合一个平面，无法区分
「真的转了头」和「表情把嘴角拉歪了」。要用 468 个点做刚体配准。

```
estimateFaceGeometry            face_landmarker.cpp:675
  └ 只取前 468 点（iris 的 10 点不参与）
  └ PerspectiveCameraFrustum(fov = 相机实测垂直 FOV, near = 1, far = 10000)
  └ 硬编码 468 顶点 canonical 网格 + 33 个 Procrustes 权重基
       │
       ▼  ScreenToMetricSpaceConverter::Convert   geometry_pipeline_nodep.h:249
  ① ProjectXY         xy 投到 near 平面；origin_is_top_left 时 y 取反
  ② 深度偏移           z 减均值
  ③ ChangeHandedness  z 取反（屏幕坐标 → 右手系）
  ④ 第一次 Procrustes  canonical ↔ intermediate，scale = 变换矩阵第 0 列范数
  ⑤ MoveAndRescaleZ + UnprojectXY → 第二次 scale
  ⑥ 第二次 Procrustes  用最终 metric 点解出 4×4 pose_transform_matrix
  ⑦ metric = pose.inverse() @ metric   把点对齐回 canonical 坐标系
```

Procrustes 内部是**加权正交 Procrustes + Jacobi 迭代 SVD**（`mini_linalg.h`）：
`R = U·Vᵀ`，行列式为负时翻 U 的第 3 列抑制反射（否则会解出一个「镜面人」）。

**欧拉角提取**（`extractEulerAnglesFromMatrix4f`，`face_landmarker.cpp:52`）：
约定 **ZYX 内旋**（Tait-Bryan），`R = Rz(roll)·Ry(yaw)·Rx(pitch)`。

```
先用第一列范数归一化 → 去掉 Procrustes 带出来的 scale
sy = sqrt(r21² + r22²)
非奇异：pitch = atan2(r21, r22)   yaw = atan2(-r20, sy)   roll = atan2(r10, r00)
万向锁（sy < 1e-6）：pitch = atan2(-r12, r11)   yaw = atan2(-r20, sy)   roll = 0
弧度 → 度
```

**符号约定**（`common/types.h:71-85`）：pitch 上正下负 `[-90,90]`、yaw 左正右负 `[-90,90]`、
roll 左倾正右倾负 `[-180,180]`。

**FOV 从哪来**：`calculateFovDegrees`（`Camera2Manager.kt`）用
`SENSOR_INFO_PHYSICAL_SIZE` 和 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` 真算，算不出来才回退 63°。
这个值经 `PipelineFaceTracker` 传给 `setCameraFov`，`setCameraFov` 会**销毁并重建**
几何管线（`face_landmarker.cpp:622-631`）——所以它必须在推理线程启动之后再调。

**最后再过一道 Time-EMA**（tau = 0.3 s），比 landmark 的 0.12 s 更慢——因为角度噪声
在视觉上比位置噪声更刺眼（一点点角度抖动就是脸的抽搐）。

### 4.4 多路并行：`EngineSlot[4]` + `thread_local`

```
struct EngineSlot { detector; landmarker; blendshape; useVulkan; lastHeadPose; }
static EngineSlot g_engines[4];                 MediaPipeFaceLandmarkJNI.cpp:36-49
static thread_local int g_engine_index = 0;     :50
#define g_faceDetector (g_engines[g_engine_index].detector)   :53-58
```

每个推理线程启动时 `nativeSelectEngine(i)` 绑一次自己的槽位，之后所有 per-frame 调用
通过宏落到本线程槽位——**per-frame 的 JNI 签名一个都不用改**。

| | 隔离粒度 |
|---|---|
| **每路独立** | detector / landmarker / blendshape 三组 MNN session、landmark 滤波器、检测框滤波器、`useVulkan`、`lastHeadPose` |
| **跨路共享** | 模型路径、几何管线、4 个头姿滤波器、perf profiler |

**锁只有两把**：

| 锁 | 范围 | 为什么 |
|---|---|---|
| `g_model_mutex`（`:71`） | 只保护模型创建 / 重建 / 释放 | **per-frame 路径完全不取**——单帧推理靠 session 隔离，不需要锁 |
| `g_head_pose_mutex`（`:81`） | 只包「几何解算 + 头姿滤波」 | 几何管线和头姿滤波器是进程级静态，多路并发会踩 |

> 副作用：多路并行时，头姿滤波的**采样顺序 = worker 到达顺序，不是 frameId 顺序**。
> 因为锁在重排之前。对 60 fps 的 EMA 影响很小，但确实不是「严格时间序」。
> 这条记在 §11。

**额外引擎**（slot 1..3）**只加载 landmarker**，不加载 detector / blendshape，也不做
Vulkan→CPU 回退（`nativeInitEngine`，`:340`）——它们只负责关键点这一段，检测框是共享的。

---

## 5. 第三段：Kotlin 并行流水线

5 类阶段各一条常驻单线程（其中 landmark 阶段最多再开 4 路 worker），加一条相机线程。
线程全部 `Executors.newSingleThreadExecutor` + 名字 `fc-<stage>` +
`Thread.NORM_PRIORITY + 2`（`PipelineFaceTracker.kt:207-213`），
即「比普通后台/默认线程高，但仍低于 UI」——推理不能抢 UI 的帧，也不该被默认优先级压后。

### 5.1 拓扑

| 线程 | 职责 | 上游通道（容量 / 策略） |
|---|---|---|
| `CameraBackground` | 采集、预览、扇出到两条 YUV 通道 | — |
| `fc-face-detect` | 10 Hz 检测，产出 `lastFaceBox` | `yuvFrameChannel`（2 / DROP_OLDEST） |
| `fc-face-landmark-gpu0..3` | 478 点 + 头姿，**多路抢帧** | `yuvLandmarkFrameChannel`（3~5 / DROP_OLDEST） |
| `fc-face-landmark-merge` | 按 `frameId` 重排 | `landmarkMergeChannel`（8 / DROP_OLDEST） |
| `fc-face-process` | 52 BS 解算 + 自标定 + 眨眼曲线 | `landmarkChannel`（4 / DROP_OLDEST） |
| `fc-face-output` | 5 个回调派发 | `outputChannel`（1 / DROP_OLDEST） |

**全部 `DROP_OLDEST`**——下游慢了就丢最旧的，绝不让延迟堆积。`outputChannel` 容量只有 1，
因为下游是「最新状态」语义，中间帧没有保留价值。

### 5.2 为什么需要重排

多路并行下，第 5 帧可能比第 4 帧先算完。`FrameReorderBuffer`（`FrameReorderBuffer.kt:25`）
用 `TreeMap` 按 `frameId` 排序：

- 首帧立即放行（不等待，避免起播延迟）；
- 顺序对齐时逐个放行，清掉缺口标记；
- 出现缺口时最多等 **`gapTimeoutMs = 60`**（`:27`）或 `pending > maxPending = 8`（`:26`）；
- 超时就接受丢帧、放行手里最旧的一帧。**绝不为了等某一帧把整条链路拖慢。**

单路时退化为纯转发。下游（校准基线、眨眼曲线、发给 PC 的 `seq`）全都依赖
「帧序严格递增」这个前提，所以重排是必需的而不是优化。

### 5.3 并行度不是想开就开

`resolveActiveChannelCount()`（`PipelineFaceTracker.kt:851`）有三重门限：

```
非 YUV 直通模式          → 固定 1 路
主引擎不是 Vulkan        → 固定 1 路（CPU 后端开多路只会互相抢核）
否则取 期望值 ∩ 「已就绪槽位前缀」
```

「已就绪前缀」的意思是：请求 ×3 但第 2 路创建失败，就停在 2 路——**前缀里的照常工作，
并行度降一级**，不会因为一个引擎失败让整条链路不可用。

默认 2 路（`GPU_CHANNEL_DEFAULT`），上限 4（`MAX_GPU_CHANNELS`，与原生 `kMaxEngines` 对齐）。
代价是每路多一份 landmark 模型常驻显存。

### 5.4 帧龄闸门

即使通道是 DROP_OLDEST，也还有一道 `MAX_FRAME_AGE_MS = 300`——处理阶段会丢弃超过
300 ms 的陈旧帧。原因是丢掉它的代价（一帧丢动捕）远小于让形象「演一帧 300 ms 前的动作」。

---

## 6. 第四段：478 点 → ARKit-52

`FaceCapturePipeline.processWithPose`（`FaceCapturePipeline.kt:84`）四步：

### ① 距离估计

由归一化 faceW/H 推 `faceRatio`，做 0.9/0.1 EMA。它用来估「脸离相机多远」，
进而推透视强度——远距离时眼睛的几何变化被压缩，需要更强的补偿增益。

### ② 关键点提取

`FaceLandmarkIndices.extractKeyPoints`（`FaceLandmarkIndices.kt:275`）：归一化 → 像素，
切出眼（上下睑各 7 点 + 内外眼角 + 虹膜中心）、眉、鼻、嘴外 20、嘴内 20、轮廓 36，
同时给出两个归一化基准：

- `faceWidth` = 右眼外眼角 ↔ 左眼外眼角
- `faceHeight` = 鼻根 ↔ 下巴

后面所有的几何量都除以这两个之一，这是**尺度不变**的关键——用户离远一点、近一点，
blendshape 值不应该变。

### ③ 姿态自标定

首 **1 s 且 ≥10 帧**（`FaceLandmarkIndices.kt:377` / `:385`）的窗口取均值做基线，
`relativeHeadPose = raw - baseline`（`FaceCapturePipeline.kt:136-138`）。

点位累积用 3 个 `DoubleArray(478)` **常数内存**，不是保存点云——内存与窗口长度无关。

存储的基线：`baselinePitch/Yaw/Roll`（`:119-122`）、478 点均值 → `extractKeyPoints` →
`extractor.calibrate`（`:173`）、再 `setCalibrationPose`（`:176`）。子提取器各有独立窗口
（嘴、笑、眼各一份），因为它们需要的信息类型不同（嘴要 MAR 范围、眼要 EAR 范围）。

会话边界 = `resetAdaptiveBaseline()` 或切摄像头。会话内基线不变。

### ④ 四路解算 + 统一 clamp

```
extractFullBlendShapes（BlendShapeExtractor.kt）
   眼（blink / wide）
   → 注视（虹膜偏移）
   → 嘴（jawOpen）
   → 微笑
   → advancedMode 分支（当前不执行，见下）
   → 统一 clamp
```

**三个核心公式**：

**jawOpen** —— 嘴部纵横比 MAR

```
MAR = dist(嘴内上唇中心, 嘴内下唇中心) / dist(左嘴角, 右嘴角)
    → 俯仰补偿：>5° 才补，低头 ×1.3，系数 0.008/度
    → 校准区间内分段线性
```

校准值（`SimpleMouthExtractor.kt:40-41` 是未校准默认）：`closed = mean + 2σ` 钳 `[0.05, 0.25]`，
`open = closed × 3.5` 钳 `[0.25, 0.70]`；未校准时 `0.10 / 0.45`。

用 `mean + 2σ` 而不是 `mean`，是为了让「说话时嘴自然微张」不被当成张开——它把闭口阈值
推到分布的上边缘。

**eyeBlink** —— 眼部纵横比 EAR

```
EAR = mean(上睑每点到下睑 7 点的最近距离) / dist(内眼角, 外眼角)
```

这是经典的 Soukupová–Čech EAR。之后还要过两道补偿：

- **pitch 动态范围回映射**：低头时眼裂本来就变窄，`×1.2` 补偿，下限 0.4；
- **yaw 左右不对称补偿**：转头时远侧眼睛被压缩，远侧乘 0.4、近侧乘增益，并按时间衰减。

最后 `ear ≤ closed → 1`、`ear ≥ open → 0`，中间线性。未校准默认
`closed = 0.10`、`open = 0.18`、`wide = 0.26`（`SimpleEyeExtractor.kt:21-23`）。

**mouthSmile**

```
feature = (均值(嘴角上下方外轮廓点).y - 嘴角.y) / faceHeight
```

注意用**外**轮廓（jawOpen 用的是内轮廓），因为有嘴唇闭合时内轮廓点会重合。
带 pitch 0.0008/度、roll 0.001/度补偿；`observedSmileMax` 只增不减（上限 0.15），
所以笑得越大，后续检测越灵敏。

### ⚠️ 现状：52 维里只有 17 维有值

`BlendShapeExtractor` 的 `advancedMode` 默认 `false`（`BlendShapeExtractor.kt:19-20`），
`FaceCapturePipeline` 用默认构造函数（`FaceCapturePipeline.kt:20`），**全仓库没有
`advancedMode = true` 的构造**。

于是 `AdvancedBlendShapeExtractor`（眉 / 颊 / jaw 左右 / 鼻 / 嘴高级 / eyeSquint，
536 行完整实现）**当前不运行**。有值的 17 维是：

- eyeBlink L/R、eyeWide L/R —— 4
- eyeLookIn/Out/Up/Down × 2 —— 8
- jawOpen —— 1
- mouthSmile L/R —— 2
- mouthFrown L/R —— 2

其余 35 维恒 0。这对当前 Live2D 映射**没有影响**（映射只用到这 17 维里的 9 个），
但如果以后要驱动模型的眉、鼻、脸颊参数，得先把 `advancedMode` 打开。

### ⚠️ 现状：native blendshape 模型加载了但从未被调用

`MediaPipeFaceDetector.kt` 里定义了 `predictBlendshapes` / `predictBlendshapesRaw` /
`isBlendshapeAvailable`，`face_blendshape_mp.mnn`（1.86 MB）也会随
`initialize(enableBlendshape = true)` 复制到 cache 并加载——但**全仓库没有任何调用点**。

52 维**全部来自上面这套 Kotlin 几何解算**，不走 MNN blendshape 模型。
这是一个可以做但没做的优化方向：模型推理会更快，但可解释性和可调性不如几何解算。

---

## 7. 第五段：眨眼曲线（为什么需要它）

`BlinkCurveProcessor` 存在的理由有三个，缺一不可：

1. 478 点的眼皮抖动经 EAR→blink 线性映射会 **1:1 变成眼皮颤**；
2. 线性映射做不到「1~2 帧内完成 0→1→0」，观感很机械（人的眨眼约 100~150 ms）；
3. 需要区分「眨眼」和「长时半闭」（眯眼）——两者的 EAR 曲线完全不同。

所以用**状态机 + 余弦曲线**替掉原始值：

| 参数 | 值 | 锚点 |
|---|---|---|
| 闭眼曲线 | `(1 - cos(tπ)) / 2`，80 ms | `BlinkCurveProcessor.kt:28` |
| 保持 | 30 ms | `:29` |
| 睁眼曲线 | `(1 + cos(tπ)) / 2`，120 ms | `:30` |
| 速度判据 | 跌幅率 > 0.12 | `:36` |
| 幅度判据 | 总跌幅 > 0.35 | `:38` |
| 眯眼判据 | EAR < 0.65 × baseline 持续 > 300 ms | `:42` |
| 眯眼锁值 | `SQUINT_HOLD = 0.5` | `:91` |

余弦曲线两端导数为 0，所以闭眼/睁眼的起止是「软」的，不会出现角速度突变。

**6 态机**：`TRACKING` / `BLINK_DETECT` / `CLOSING` / `BLINK_CLOSED` / `BLINK_OPENING` /
`SQUINT_HOLD`。前两态**恒输出 0**——这是设计选择：只有确认要眨眼了才开始画曲线。

**双眼同步**：同处眨眼态且进入时间差 ≤150 ms 时取较大值；一眼 `CLOSING` 另一眼还在
`BLINK_DETECT` 且差距够小时强制跟随。否则会出现「一只眼先闭一只眼后闭」的鬼畜效果。

**代价**：低于判据的微小开合被完全丢弃。所以「慢慢闭上眼」这种动作是捕不到的——
这在虚拟主播场景可接受（没有主播会慢慢闭眼），但做睡眠/昏迷表情就不行。

---

## 8. 第六段：上半身（可选旁路）

### 8.1 接入点：面捕之后

```kotlin
// PipelineFaceTracker.kt（相机线程）
yuvFrameChannel.trySend(data)
yuvLandmarkFrameChannel.trySend(data.copy())

val bodyTapWithHint = onYuvFrameForBodyWithHint
val bodyTapLegacy = onYuvFrameForBody
if (bodyTapWithHint != null || bodyTapLegacy != null) {
    if (intervalMs <= 0L || nowMs - lastBodyTapMs >= intervalMs) {
        lastBodyTapMs = nowMs
        try {
            if (bodyTapWithHint != null) {
                // 第二参数 = 最近脸框（一级扩充 1.15×），超过 500ms 没刷新则 null
                bodyTapWithHint(yuvData, currentFaceBoxHint(nowMs))
            } else {
                bodyTapLegacy?.invoke(yuvData)  // 旧接口，整帧 320p
            }
        } catch (t: Throwable) { ... }
    }
}
```

四个细节：

1. **面捕两条通道先喂完**，保证面捕优先级；
2. 脸框只在 66ms 的 tap 时刻取一次，锁内只有两次字段读；
3. `try/catch(Throwable)` 是必需的——**相机线程一旦被抛出去，面捕也会跟着停**；
4. 关闭时两个 tap 都置 null，相机回调里只多一次判空（`MainActivity.kt`）。

### 8.2 为什么不能直接把帧丢给后台线程

MediaPipe 需要 `Bitmap`，而 `Bitmap` 必须当场构造——**YUV 的 ByteBuffer 在相机回调返回后
就随 `image.close()` 失效了**。所以像素转换必须同步做，只有推理能异步。

`YuvBitmapConverter.convert` 现在做两件事：

1. **脸框 → 上半身 ROI**（`UpperBodyRoi.fromFace`）：bodyW = faceW×4.5、bodyH = faceH×5.5，
   水平以脸中心为中心、顶部留 0.35×脸高；没有可信脸框 / 脸太小 / ROI <48px 时用整帧。
2. **只采样 ROI**（最近邻反查源坐标）并缩放到**长边上限 320**（只缩不放）。整帧 720p→320p 也只采约
   5.8 万像素；带 ROI 时输出 10 万像素上下，15fps 下约 1~4ms，仍在相机线程预算内。

结果点先在 ROI 局部坐标，**回映到整帧预览坐标之后**再做 13 点提取 / 躯干角 / 平滑，
所以协议 `ub[]` 的语义与改造前完全一致。

### 8.3 限帧 + 单飞

```kotlin
// UpperBodyPoseDetector.pushFrame
if (nowMs - lastSubmit < 1000/targetFps) { dropped++; return }   // 15fps 门限
if (!inFlight.compareAndSet(false, true)) { dropped++; return }  // 上一帧没回调就丢
```

两道门限缺一不可：限帧控制吞吐，单飞**防止对同一个 `landmarker` 并发 `detectAsync`**
（MediaPipe LIVE_STREAM 不是线程安全的）。

还带 `generation` 计数：转换期间如果主线程重建了 `landmarker`，本帧必须作废，
且不能去清理「新实例」的单飞标记。

### 8.4 后处理链

```
PoseLandmarkerResult（33 点，ROI 局部归一化）
  → UpperBodyRoi.mapLocalToPreview   回映到整帧预览坐标（镜像/ROI 局部翻转）
  → UpperBodyGeometry.extract        抽 13 个上半身点
  → isTracking                       可见度 > 0.5 的至少 7 个
  → UpperBodySmoother.smooth         One-Euro 逐点 xyz
  → UpperBodyGeometry.torso          躯干角
  → UpperBodySmoother.smoothTorso    更慢的 One-Euro
  → TorsoBaseline.apply              减基线
```

回映公式（`sx=roi.w/W`、`sy=roi.h/H`、`left=roi.x0/W`、`top=roi.y0/H`）：

```
非镜像  px = left + lx·sx
镜像    px = (1 - left - sx) + lx·sx
        py = top + ly·sy
        z  = lz·sx
```

`z` 必须一起按 `sx` 缩放：MediaPipe 的 z 与 x 同尺度（以输入图宽度为单位），不缩放会让
躯干 yaw 随 ROI 大小漂移。满帧 ROI 时公式退化为恒等，旧行为与协议语义都不变。

**躯干角定义**（`UpperBodyGeometry.kt:113-131`）：

```kotlin
roll = degrees(atan2(right.y - left.y, horizontal))   // >0 = 右肩下沉
yaw  = degrees(atan2(-dz,         horizontal))        // >0 = 向本人左侧转
horizontal = max(|right.x - left.x|, 0.02)            // 下限防止两肩重合时 atan2 爆掉
```

用 **0.02 下限**而不是直接除，是因为这个尺度下 `atan2` 对分母极其敏感——两肩在画面里
几乎重合时（正面、肩窄、距离远），分母趋 0 会产生几十度的假角度。

yaw 用 `atan2(-dz, |dx|)` 而不是契约里写的 `atan2(z_R - z_L, x_R - x_L)`，是**等价变形**：
把方向信息全部收到 dz 上、水平基准取绝对值，避免基线相减后因 `atan2` 的 ±180 回绕突跳。

**两级 One-Euro**（`OneEuroFilter.kt:83-84` 默认 `minCutoff 1.2` / `beta 0.02`）：

| 对象 | minCutoff | beta | 理由 |
|---|---|---|---|
| 13 个点 × xyz | 1.2 | 0.02 | 点位置需要跟手，快动作时降延迟（beta 项） |
| 躯干 roll / yaw | 0.6 | 0.01 | 角度需要稳，宁可慢一点也不要抖 |

**基线**（`TorsoBaseline.kt`）：10 个有效帧均值（`:78`），10 帧内权重按
`collected / 10` **渐入**（`:44-66`）——这样起播时不会突然从 0 跳到基线值。

---

## 9. 第七段：打包发送

### 9.1 `submitTrack` 的五件事

`TrackingServer.submitTrack`（`TrackingServer.kt:319`）：

```
① 分配 seq（AtomicLong.getAndIncrement）
② calibrating 变化时先发 calib
   ← 顺序要求：calib 必须和 track 走同一个缓冲，否则会比 track 早 0.5s 送达
③ if (wsClients.isEmpty()) return
   ← 没有客户端时连 JSON 都不生成（JSON 序列化不是免费的）
④ 取上半身最新快照，判 ubOk = tracking && now - timestamp <= 1500ms
⑤ buildTrack 手写 JSON → dispatch
```

### 9.2 手写 JSON 而不是用库

`TrackingProtocol.buildTrack`（`TrackingProtocol.kt:231`）用 `StringBuilder` 拼：
预留 640 / 1024 容量，数字统一 `appendDecimal4`（最多 4 位小数、去尾随 0、NaN/Inf 写 0）。

理由：
- 反射方案（Gson / Moshi）每帧会有 `String`、`Map`、`Iterator` 分配，60 fps 下是 GC 压力；
- 4 位小数是人眼和 Live2D 参数都分辨不出的精度，多写就是浪费带宽；
- 手写可以把「`ubOk=false` 时发 52 个 0」这种结构稳定性要求写死。

实测大小：未开上半身 **493 B**，开了 **933 B**（`docs/tracking-protocol.md` §1 的
「1.1 KB」是含 `ub` 的同量级估计）。

### 9.3 报文形态

```json
{"type":"track","seq":128,"t":1730000000123,
 "bs":[/* 52 项 */],"pose":[3.5321,-8.2044,0.4123],
 "face":true,"score":0.9821,"fps":59.1,"calibrating":false,
 "ubOk":true,"ub":[/* 13×4 = 52 项 */],"ubT":1730000000100,"body":[-2.1,5.4]}
```

上半身四个字段是**全有或全无**：
- 没开 → 一个都不出现（老客户端按「未知字段忽略」）；
- 开了但没结果 → `ubOk:false` + 52 个 0 + `body:[0,0]`（结构稳定，客户端不用特判）。

### 9.4 为什么要有 500 ms 的缓冲

手机是「算完一帧发一帧」，而 MNN 推理耗时会抖、系统调度会抖、Wi-Fi 会突发。
PC 每收到一帧就直写 Live2D，帧间隔一抖形象就「一卡一卡」。

`TrackingFrameBuffer`（`TrackingFrameBuffer.kt:26`）的四条机制：

| 机制 | 作用 | 锚点 |
|---|---|---|
| **起播预缓冲** | 队头攒够 500 ms 才 `primed = true` | `:130-133` |
| **匀速放行** | 间隔取相邻入队间隔的 EMA，**与某帧何时算完无关** | `nextDueNanos` `:173` |
| **深度调节** | 死区 = `max(2, target/10)` 帧，每差 1 帧修正节奏 25‰，平滑 1/4，限幅 900‰~1150‰ | `:158-170`、`PACING_*` `:210-217` |
| **溢出保护** | 120 条上限，丢最旧 | `maxQueued` `:28`、`:100` |

第三点容易被忽略但很重要：**如果不做深度调节，跑久了延迟会悄悄变短**——因为每次卡顿
都吃掉一点缓冲垫，而放行节奏是按「历史平均间隔」定的，补不回来。几分钟后 0.5 s 变成 0.2 s，
再卡一下就直接断流。而且调节必须是**双向**的：垫子变厚了要加快放行，否则延迟会自己长胖。

断流还有一条：队空且 `now - lastEnqueue > bufferMs/2` 时 `primed = false` 重新攒
（`:92-95`）——避免网络断了一下之后用旧节奏放行。

> **`FRAME_BUFFER_MS = 0` 可以完全关掉它**，退化成「算完就发」，端到端从 0.58 s 降到 <80 ms。
> 代价是网络抖动直接可见。

### 9.5 每客户端「最新帧」槽位

`pendingTrack` 是 `AtomicReference`，`offerTrack` **覆盖式写入**（`TrackingServer.kt:908`）。
慢客户端只会丢帧，绝不拖慢推理线程。

这里有一个已修的坑：原来 writer 用 `controlQueue.poll(20ms)` 等消息，
**把发送帧率硬钉在约 50 fps**（`:874` 的注释记录了这件事）。现在用
`Semaphore writerWakeup` 主动唤醒，上限回到推理帧率。

---

## 10. 第八段：PC 端消费与映射

### 10.1 每帧顺序

```js
// app.js handleMessage → feedTrack
if (track.seq <= lastSeq) { state.dropped++; return }   // 丢旧帧
applyPoseOffset(track.pose, state.poseOffset)           // 叠本地姿态校准
const result = mapper.apply(track.bs ?? [], pose)       // 10 个头/脸参数
const body = updateBodyState(track, now)                // 上半身三连
stage.pushTrackParams(result.params, now, track.t)      // 带手机采集时间戳
stage.setBodyParams(body.current)
```

`lastSeq` 在 `onopen` 和 `onHello` **双重**重置为 -1——因为 App 重启后 `seq` 会归零。

### 10.2 头/脸映射：只有 3 个是直通

| Live2D 参数 | 公式 | 类型 |
|---|---|---|
| `ParamEyeLOpen` | `1 - bs[9]` | 直通 |
| `ParamEyeROpen` | `1 - bs[10]` | 直通 |
| `ParamMouthOpenY` | `bs[25]` | 直通 |
| `ParamMouthForm` | `(bs[44]+bs[45])/2 - (bs[30]+bs[31])/2`，clamp ±1 | 组合 |
| `ParamEyeBallX` | `((bs[15]-bs[13]) + (bs[14]-bs[16]))/2` | 组合（左右眼符号相反合并） |
| `ParamEyeBallY` | `((bs[17]-bs[11]) + (bs[18]-bs[12]))/2` | 组合 |
| `ParamAngleX` | `-yawNorm × 30` | 归一化后乘（取负：手机 yaw>0 左转 → 模型 X<0） |
| `ParamAngleY` | `-pitchNorm × 30` | 同上 |
| `ParamAngleZ` | `rollNorm × 30` | 同上 |
| `ParamBodyAngleX` | `-yawNorm × 10` | 头姿分量，后面要与躯干**相加** |

注意 `ParamEyeBallX/Y`：Live2D 的「眼球左右」是一个参数，但左右眼的虹膜偏移方向
在**同一转向下符号相反**（往左看时，左眼虹膜往内、右眼虹膜往外）。所以是
`(左眼内-外) + (右眼外-内)` 这种交叉组合，不是简单平均。

### 10.3 自适应归一化：向外快、向内慢

`AdaptiveAngleNormalizer`（`mapping.js:104`）不是滑动分位数，而是
**非对称单边峰值跟踪**：

```
负/正两向各维护一个 pct
向外扩张：pct += (a - pct) * 0.1          ← 快
向内收缩：pct += (a - pct) * alpha * 0.1  ← 极慢，alpha = 0.05（前 100 帧）/ 0.01（之后）
有效量程 = 默认量程 ⊕ 观测量程  按 samples/100 线性混合
        再 max(..., minRange)
```

默认量程（对应协议 §8.2）：pitch `(20, 40, min 10)`、yaw `(45, 45, min 15)`、
roll `(30, 30, min 10)`。

**为什么不对称**：向外快是为了几秒内摸到用户的真实转头幅度；向内慢是防抖主力——
如果收缩也快，用户只是「回到中位」，量程就被压回去，下次转头就会瞬间打满、看起来像抽搐。
代价是**没有中值/分位滤波**，抖动抑制完全靠这个不对称 + 后续 `live2d.js` 的时间常数缓动。

**冷启动行为**：因为要与默认量程线性混合，前几帧不给满量程。`pitch = +20°` 时
`ParamAngleY` 得到 `-15.14` 而不是 `-30`。这是有意的——先用保守量程，等观测到真实幅度
再放开。

### 10.4 上半身映射

13 点先换算成**上半身运动骨骼**（`buildUpperBodySkeleton()`：脊柱 → 锁骨 → 上臂 → 前臂，
每根骨的角度都是相对父骨的旋转），再映射到模型参数：

```js
ParamBodyAngleX = clamp(-yaw_body × 0.3, ±30)   // 与头姿的 -yaw×10 相加后 clamp，不覆盖
ParamBodyAngleY = clamp(-roll × 0.2,  ±30)
ParamBodyAngleZ = clamp( roll × 0.5,  ±30)
ParamArmLA/RA   = clamp(肩→腕与竖直向下的夹角 / 150°, 0, 1) × 30   // 上臂抬起
ParamArmLB/RB   = clamp(肘屈曲 / 150°, -1, 1) × 30                 // 前臂相对上臂，正 = 屈肘
ParamShoulderL/R = clamp(锁骨差动 / 25°, -1, 1) × 30               // 肩线相对髋线，正 = 该侧肩抬起（左右反号）
```

手臂角度：0° = 自然下垂，90° = 平举，180° = 过头。取**肩 → 腕**而不是肩 → 肘，
因为手肘常被躯干挡住。左右对称取 `|dx|`，镜像不影响这个角。
肘屈曲与锁骨差动都是**父骨相对**角：躯干跟着倾斜时手臂不会被误读成「抬起」，
整体侧倾（肩髋同倾）也不会重复计数进肩膀参数。

**可见度门控**：该侧肩/肘/腕三者最小可见度 < **0.3** 时**不输出**该侧手臂参数
（`ParamArm{L,R}A/B` 四个都算；`ParamShoulderL/R` 要求双肩 + 双胯都可信）。宁可不动，
也不要拿坏关键点硬驱动——坏点的角度可能是任意值。

`ParamHandL/R` 刻意不输出：13 点里没有手部关键点，腕关节朝向无从测量，硬凑只会更假。

### 10.5 三道回退，保证不冻结在最后一帧

这是整个上半身设计里最容易做错的地方。如果不做，一旦掉线，形象会**永远举着手**。

| 层 | 机制 | 锚点 |
|---|---|---|
| **过期判定** | `ubT` 只判「是不是新样本」（`ubT !== last.t` 才刷新 `receivedAt`），过期用**本地到达时间** `age > 1500 ms` | `readUpperBodyTrack` `mapping.js:870` |
| **按帧衰减** | 没目标时在上一帧值上 **×0.85/帧**；`\|v\| < 0.01` 归 0；全部归零后**从输出删除** | `smoothBodyParams` `:933`、`BODY_DECAY_PER_FRAME` `:507` |
| **渲染层释放** | `setBodyParams` 对本帧没给的参数**删掉缓存** | `live2d.js:297` |

「从输出删除」这一步是关键：如果每帧写死 0，模型的 idle motion（呼吸、摆手）
永远起不来。删掉之后参数交回模型自己管。

### 10.6 时间轴插值

`ParamTimeline`（`interp.js:131`）维护一条播放时间轴，渲染时取
「当前时刻 − 自适应延迟」处做**线性插值**。

**解决什么问题**：输入 20~30 fps 且到达间隔不齐（推理耗时波动、Wi-Fi、PC GC）。
如果「收到即设目标 + 每帧缓动」，一次输入要撑 2~3 个渲染帧，表现为
「冲一大步再拖尾」，或者两帧挤在一个渲染帧里跳过去。

| 设计点 | 做法 | 为什么 |
|---|---|---|
| **时间基准** | 优先用手机 `track.t`（采集时间），不是到达时间 | WS/TCP 有序，但**到达**时间会被批量推迟；用采集时间才能剔除传输抖动 |
| **时钟偏移** | 最近 12 帧「到达−采集」的**最小值**，每帧最多跟 1 ms，突变 >120 ms 就重锚并清样本 | 单调性优先，**绝不让时间轴倒退**（倒退 = 画面倒带） |
| **延迟** | 输入间隔 `p85` + 传输滞后 `p90` + 8 ms 余量，上限 120 ms；输入够快时为 0 | 用分位数而不是最大值：单次 200 ms 抽风不应永久抬高延迟 |
| **变延迟** | 快升慢降（attack 120 ms / release 600 ms）+ 限速 | 保证 `sampleAt` 单调递增 |
| **见底** | 最多外推 40 ms，且不超过 0.75 个输入间隔 | 外推太多就是编造动作 |

**输入够快时延迟为 0**——这意味着 60 fps 输入、60 fps 渲染时，PC 侧几乎不额外加延迟。

---

## 11. 延迟预算与吞吐上界

### 11.1 单帧延迟（实测锚点见 `PERFORMANCE.md`）

| 环节 | 耗时 | 备注 |
|---|---|---|
| 相机曝光 → 回调 | 0–16.7 ms | 受帧率限制 |
| 人脸检测 | 3.2 ms | 10 Hz，**均摊 0.53 ms** |
| 关键点推理 | **14.5 ms** | **真正的瓶颈** |
| 重排 | 0.03 ms | 缺口时最多 60 ms |
| 后处理 | 5.2 ms | 其中 BlendShape 4.1 ms |
| 派发 + JSON | 0.2 ms | |
| socket + Wi-Fi | 0.5–1 ms | |
| **手机端串行延迟** | **≈22–56 ms** | |
| 0.5 s 发送缓冲 | **+500 ms** | 可关（`FRAME_BUFFER_MS = 0`） |
| PC 插值 | +0–120 ms | 输入够快时为 0 |
| **端到端** | **≈0.58 s** | 关缓冲后 <80 ms |

帧间隔分布：`p50 = 16.7 ms`、`p95 = 18.9 ms`、`max = 34.2 ms`（`PERFORMANCE.md:217`）。

### 11.2 吞吐上界

| 阶段 | 单帧 | 理论上界 |
|---|---|---|
| 关键点推理 | 14.5 ms | 2 路 ≈ **138 fps** |
| 后处理 | 5.2 ms | ≈ **192 fps** |

所以 60 fps 的瓶颈是**单帧 14.5 ms 的 landmark 推理**，不是后处理。这也解释了为什么
要开多路 GPU 并行——不是提升单帧速度，而是提高 GPU 利用率（单路时 GPU 在等 CPU 段：
YUV 预处理、命令录制、结果回读）。

---

## 12. 已知问题与设计取舍

以下都是**代码现状**，不是待办清单；每条都在 `perf/` 的审计里有详细推导。

### 12.1 值得注意的正确性 / 稳定性问题

| # | 问题 | 影响 | 锚点 |
|---|---|---|---|
| 1 | **YUV plane 生命周期**：`image.close()` 在回调返回即执行，但帧已 `trySend` 进通道；`ImageReader` 只有 3 块 buffer，60 fps 下 16.7–50 ms 就被复用 | 检测通道排队 1–2 帧时，读到的可能是**已被覆盖的画面** | `Camera2Manager.kt:1401`、`PipelineFaceTracker.kt` 通道定义 |
| 2 | **重排缺口 60 ms**：永久丢一帧会让输出定格约 67 ms 再突发放放 | 偶发一次顿挫 | `FrameReorderBuffer.kt:27` |
| 3 | **上半身抽帧在相机线程同步转换**（ROI→320p，约 1~4 ms / 66 ms） | duty 很低；ROI 已把采样限制在人身上，但转换期间仍占相机线程 | `UpperBodyPoseDetector.pushFrame` |
| 4 | **多路并行时头姿滤波采样顺序 = worker 到达顺序** | 不是严格时间序；60 fps 的 EMA 影响很小 | `g_head_pose_mutex`，`MediaPipeFaceLandmarkJNI.cpp:81` |
| 5 | **未加锁的静态 perf profiler** 在 4 路并行时并发写 | 统计层数据竞争，不影响结果 | `perf_profiler.h` |

### 12.2 设计取舍（不是 bug）

| 取舍 | 选择 | 代价 |
|---|---|---|
| 眨眼曲线 vs 原始 EAR | 曲线 | 低于判据的微小开合被完全丢弃 |
| 0.5 s 播放缓冲 vs 低延迟 | 缓冲 | +500 ms；可关 |
| 检测 10 Hz vs 每帧 | 节流 | 脸快速移动时框可能滞后（靠重检兜底） |
| 头姿 tau 0.3 s vs 跟手 | 稳 | 快速甩头时角度略滞后 |
| 上半身 15 fps vs 60 fps | 15 fps | 手臂动作有明显延迟感（PC 侧 160 ms 低通再加一点） |
| MediaPipe Pose vs 自研 | 官方 Tasks | 引入 MediaPipe 运行时，但**只在开上半身时加载** |

### 12.3 闲置能力

| 能力 | 状态 |
|---|---|
| `advancedMode`（眉 / 颊 / jaw 左右 / 鼻 / 嘴高级 / eyeSquint） | 实现完整（536 行），但默认 `false` 且无调用点 |
| native blendshape 模型（`face_blendshape_mp.mnn`，1.86 MB） | 加载但不推理，52 维全部来自 Kotlin 几何解算 |
| `FaceCaptureConfig.smoothing` / `numThreads` / `calibrationDurationMs` | 只声明，`initialize` 只读 `enableBlinkCurve` |
| `nativeConfigureLandmarkFilter` / `configureForHighFps` | 定义但从未调用，所以 native 滤波参数就是头文件默认值 |
| `ParamShoulderL/R`、`ParamHandL/R` | 无可靠数据源，刻意不输出 |

---

## 13. 术语与符号约定

| 概念 | 含义 |
|---|---|
| `bs[52]` | ARKit-52 BlendShape，顺序见 `tracking-protocol.md` §5.1，每项 `[0,1]` |
| `pose[3]` | `[pitch, yaw, roll]`，**已减首帧基线**，度 |
| `ub[52]` | 13 点 × `[x, y, z, visibility]`，x/y ∈ `[0,1]`，**已镜像**（与预览方向一致） |
| `body[2]` | `[roll, yaw]`，相对基线躯干角，度 |
| `left` / `right` | **被拍者本人**的左右，不是画面左右 |
| pitch | 抬头为正，`[-90, 90]` |
| yaw | 向左转（本人视角）为正，`[-90, 90]` |
| roll | 左倾为正，`[-180, 180]` |
| 躯干 roll | **右肩下沉**为正 |
| 躯干 yaw | 向**本人左侧**转为正 |
| 手臂角 | 相对「竖直向下」的夹角：0° 下垂、90° 平举、180° 过头 |

---

## 14. 这份文档是怎么来的

- 分支 `feat/mediapipe-upper-body`，**纯静态读码**，三路并行调研（原生 C++ / Kotlin
  流水线 / 协议与 PC 端）后汇总。
- 环境限制：**无 JDK、无 Android SDK、无 adb、无网络**，因此
  **没有跑过 gradle 构建，也没有跑过单元测试**。文中所有 ms 数字来自
  `PERFORMANCE.md` 的实测锚点或 `perf/` 审计的静态推导，不是本次测量。
- 工作区存在未提交的性能改造（`Camera2Manager.kt`、`PipelineFaceTracker.kt`、
  `TrackingServer.kt`、`mnn_yuv_converter.cpp` 等），文中把「已修」和「仍是问题」
  分开标注了。
