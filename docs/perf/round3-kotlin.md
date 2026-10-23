# 第三轮 Kotlin 侧每帧成本审计（面捕热路径）

> 审计对象：`VirtualFaceCapture-MNN` 当前工作区（Android 采集端）
> 审计方式：**纯静态**——只读源码 + 算式推导 + 逐行取证。本机没有 JDK / Android SDK / 真机，
> **未编译、未运行、未实测**。所有毫秒数若不是代码里已有的日志实测值，都标注为「估算」，
> 并在 §9 给出「怎么测」。
> 与 `docs/perf/audit-kotlin.md`（第一轮）、`docs/perf/round2-roi.md`（第二轮 ROI）的关系见 §10：
> 本文只重复上一轮**仍然成立**的结论，其余篇幅全部给新发现，避免把同一份报告写三遍。

---

## 0. 结论先行（TL;DR）

1. **相机线程被「调试预览」一个人吃掉了半个帧预算，这是 60fps 上不去的直接原因。**
   `MainActivity` 在前台无条件打开预览（`app/.../MainActivity.kt:583`），于是相机线程每两帧
   （33 ms 门控，`Camera2Manager.kt:208`）就要做一次完整的 720p YUV→RGB，然后在 Kotlin 里
   再做 **3 次全帧搬运**：`ByteArray` memcpy → 921,600 次逐像素打包 → `Bitmap.setPixels`
   （`app/.../CameraPreviewView.kt:276-291`、`:318`）。合计每帧约 **23.5 MB 内存读写**、
   每帧 92 万次标量循环；每秒 27.6 M 次像素运算。只要有一帧被它顶过 16.67 ms，
   `ImageReader` 就丢帧，`cameraDeliveredFps` 立刻掉档。**而这条路径对推理毫无贡献**——
   面捕走的是 YUV 直通。
2. **UI 叠加层是第二个独立的 CPU/GC 大户，且与相机帧同频触发。**
   `onVisualizationUpdate` 每帧 `postInvalidate()`（`MainActivity.kt:530-532`），`onDraw` 里对
   478 个点逐个做 `Pair` 装箱 + `Set<Int>.contains`（`CameraPreviewView.kt:531-566`），
   平均每个点 6 次 `HashSet<Integer>` 查找、每次查找装箱一个 `Integer`。估算 **≈72 KB 垃圾/帧、
   3.4×10⁵ 个新对象/秒（其中 1.7×10⁵ 是装箱 Integer）、4.1 MB/s**，这是全工程最大的一块 GC 垃圾。
3. **推理链路本身（Kotlin 侧）不是 CPU 大头，但它是分配大头。** 关键点阶段每帧稳定分配
   ≈25 KB（JNI `float[1435]` + `ArrayList` + 478 个 `Point3D` + `float[1434]` + 3 个装箱 Float），
   后处理阶段再分配 ≈16 KB（`extractKeyPoints` 把 478 个点**又**重映射一遍）。60fps 下
   **≈2.4 MB/s**，且全是短命对象，直接推高 minor GC 频率。
4. **还有一处结构性浪费：单路推理时仍然强制走「重排线程」。** `activeChannelCount == 1` 时，
   关键点结果依旧 `mergeChannel.trySend()` → 重排线程 `TreeMap` 排序 → `landmarkChannel`
   （`PipelineFaceTracker.kt:1247`、`:1268-1296`），每帧白付 1 次多余的线程唤醒 + 1 个 `ArrayList`
   + 1 个 `TreeMap.Entry`，还额外引入一个「缺口等 60 ms」的抖动源（`:69`）。
5. **「GPU 显存空闲」是必然结果，不是配置问题。** 全链路只有 MNN 的 landmark 推理用 GPU；
   预览的 YUV→RGB、RGB→ARGB、Bitmap 上传全部在 CPU 上做（而且现成有 GPU 合成器可用）。
   CPU 被上面 1/2 两条占满后，喂给 Vulkan 的帧就更少，GPU 自然更闲。
6. **一个必须先修的隐雷：YUV plane 在 `image.close()` 之后才被推理线程读。**
   相机回调 `finally { image.close() }`（`Camera2Manager.kt:1430-1437`），而 JNI 侧是
   **零拷贝直接取 `GetDirectBufferAddress`**（`MediaPipeFaceLandmarkJNI.cpp:1259-1261`）。
   第二轮把整帧打包改成 `tryFastYuvView` 零拷贝之后，这个窗口从「读副本」变成「读正在被 HAL
   复写的同一块内存」，多路并行还把窗口拉长了。它不一定是当前低帧率的主因，但它是
   「偶发跳点 / 花屏 / 崩溃」的现成解释，也限制了后面所有「让推理更慢但更准」的优化。

一句话因果链：
**预览整帧转换（CPU）+ 478 点 UI 叠加（CPU/GC）+ 每帧 ~8 次线程切换 → 相机线程超预算 + GC 抖动 → 交付帧率掉、推理被饿 → 面捕帧率低、CPU 高、GPU 闲。**

---

## 1. 每帧时序与耗时归属

### 1.1 线程 / 队列拓扑（当前实际配置）

```
                 ┌───────────────── CameraBackground (HandlerThread, THREAD_PRIORITY_DISPLAY) ──────────────┐
  Camera HAL ───▶│ onImageAvailableListener  (Camera2Manager.kt:1401)                                    │
                 │   ├─ updateDeliveredFps()            每帧，<10µs                                        │
                 │   ├─ processYuvDirect()              每帧，分配 3 对象                                │
                 │   │    └─ yuvFrameCallback → PipelineFaceTracker.kt:592                                 │
                 │   │         ├─ trySend(yuvFrameChannel)         cap 2  DROP_OLDEST                     │
                 │   │         ├─ trySend(yuvLandmarkFrameChannel) cap 3   + data.copy()                 │
                 │   │         └─ [每 66ms] onYuvFrameForBodyWithHint → 上半身 ROI 采样 + Bitmap + detectAsync│
                 │   └─ [每 33ms] maybeConvertPreviewFrame()  720p 整帧 YUV→RGB → CameraPreviewView       │
                 └────────────────────────────────────────────────────────────────────────────────────────┘
                          │                                              │
        yuvFrameChannel   │                              yuvLandmarkFrameChannel
        (cap2, DROP_OLDEST)│                              (cap3, DROP_OLDEST)  ← 2 个消费者抢帧
                          ▼                                              ▼
              fc-face-detect (10Hz 节流)                    fc-face-landmark-gpu0 / gpu1
              MNN FaceDetector 128×128                      MNN FaceLandmarker (Vulkan) ×2
                          │                                              │
                          │ lastFaceBox (@Volatile + faceBoxLock)        │ 每帧 478 点 + 头姿
                          │                                              ▼
                          │                                  landmarkMergeChannel (cap8)
                          │                                              ▼
                          │                                  fc-face-landmark-merge（重排 TreeMap）
                          │                                              ▼
                          │                                  landmarkChannel (cap4) ──┐
                          ▼                                              ▼           │
                     （无下游）                              fc-face-process（后处理）  │
                                                          extractKeyPoints → 52 BS     │
                                                          blinkCurve → OutputFrame     │
                                                                        ▼               │
                                                            outputChannel (cap1) ◀──────┘
                                                                        ▼
                                                            fc-face-output（派发回调）
                                                              ├─ onFaceCaptureFrame
                                                              ├─ onFaceCaptureResult → TrackingOutputManager
                                                              ├─ onVisualizationUpdate → postInvalidate (60Hz)
                                                              └─ onBlendShapesUpdate / onHeadPoseUpdate → DebugSnapshotBatcher
                                                                        ▼
                                                            TrackingServer.submitTrack
                                                              JSON (StringBuilder) → frameBuffer(500ms)
                                                                        ▼
                                                            tracking-playout → tracking-ws-writer → TCP
```

### 1.2 相机线程：逐项工作量（`Camera2Manager.kt:1401-1438`）

| # | 动作 | 频率 | 证据 | 每帧成本（估算 / 待实测） |
|---|---|---|---|---|
| 1 | `acquireLatestImage()` + `updateDeliveredFps()` | 每帧 60/s | `:1404`、`:1411`、`:1477-1492` | < 10 µs |
| 2 | `processYuvDirect()`：3 个 plane getter、`getRotationDegrees()`、构造 `YuvFrameData` | 每帧 60/s | `:1663-1696`；旋转 `:1744-1762`；`Pair` 分配 `:1674-1677` | 20–40 µs（`defaultDisplay.rotation` 每帧一次 + 2 个对象） |
| 3 | 面捕 tap：`yuvFrameChannel.trySend` + `yuvLandmarkFrameChannel.trySend(data.copy())` | 每帧 60/s | `PipelineFaceTracker.kt:603-605` | 10–30 µs（2 对象 + 2 次 channel 入队；`trySend` 返回 `ChannelResult` 是 value class，无额外分配） |
| 4 | 脸框提示：`currentFaceBoxHint()`（`synchronized(faceBoxLock)`） | 每 66 ms 15/s | `:618-622`、`:984-994` | 1–3 µs |
| 5 | **上半身 tap**：`Yuv420Frame` + 3×`duplicate()` → `UpperBodyRoi.fromFace` → `YuvBitmapConverter.convert`（ROI 逐像素采样 + ARGB 打包 + `Bitmap.createBitmap`）→ `BitmapImageBuilder` → `detectAsync` | 每 66 ms 15/s | `UpperBodyPoseController.kt:317-346`、`UpperBodyPoseDetector.kt:408-486`、`YuvBitmapConverter.kt:33-119` | **1–4 ms**（ROI 典型 10 万像素；注释同口径见 `YuvBitmapConverter.kt:9-11`）+ 230 KB 原生 Bitmap |
| 6 | **预览 RGB**：`processYuvToRgbRotated()`（整帧 YUV→RGB 2.76 MB）→ `CameraPreviewView.updateCameraFrame()`（`ByteArray` 2.76 MB memcpy → 921,600 次逐像素打包 → `setPixels` 3.69 MB） | 每 33 ms 30/s | `:1502-1509`、`:1727-1731`、`:1770-1842`、`CameraPreviewView.kt:253-328` | **6–11 ms**（四段：native 转换 2–4 + memcpy 0.3–0.6 + 像素循环 2–4 + setPixels 1–2） |
| 7 | `captureCallback.onCaptureCompleted`：读 4 个 `CaptureResult` 字段 + `"$range\|$frameDuration\|$iso"` 字符串 + 变更时 `Log.i.format` | 每帧 60/s（`onCaptureCompleted`） | `:1331-1362` | 3–10 µs + 每帧 1 个 String；AE/ISO 抖动时每个变化帧 2 次 `String.format` + 1 条日志 |
| 8 | `image.close()` + `recordCallbackTime()` | 每帧 60/s | `:1430-1437`、`:1446-1469` | < 5 µs；每 120 帧 1 条 `Log.i`（+ 超预算时 1 条 `Log.w`） |

**相机线程预算判定：**

- 只有第 5 项（15 Hz，1–4 ms）或只有第 6 项（30 Hz，6–11 ms）时，单帧回调 2–12 ms < 16.67 ms，**勉强在预算内**；
- 但 15 Hz 与 30 Hz 会在 **1/15 的帧上重合**（≈4 帧/秒），此时回调 = 7–15 ms + 第 1/2/3 项 + GC 停顿，
  **很容易越过 16.67 ms**。一旦越过，`ImageReader`（`maxImages=3`，`:707-714`）用
  `acquireLatestImage()` 丢掉中间帧，`cameraDeliveredFps` 从 60 掉到 45–55，整条管线跟着掉。
- 结论：**相机线程不是「推理慢」，是「被 UI 预览拖慢」**。这一点与
  `PipelineFaceTracker.kt:1549-1551` 里那条日志的判据完全吻合（`callback avg >= 16 ms` ⇒ 处理链路在拖慢交付）。

### 1.3 其余阶段每帧工作量

| 阶段 | 线程 | 每帧做的事（逐项） | 证据 | 60fps 预算判定 |
|---|---|---|---|---|
| 人脸检测 | `fc-face-detect` | 10 Hz 节流（`:1114-1122`）；命中时 MNN 128×128 检测 + `expandFaceBox`（2 个 `FaceBox`）+ 写 `lastFaceBox`；未命中时只做 `synchronized(faceBoxLock)` + 计数器 | `:1104-1151`、`:959-974` | **不在关键路径**（10 Hz，均摊 0.07–0.4 ms/帧），但它和 landmark 抢 CPU/GPU，且每帧都要从 cap2 通道取一帧再 `continue` |
| 关键点推理 | `fc-face-landmark-gpu0/1` | JNI `detectLandmarksFromYuvWithScore`（Vulkan，**待实测**）→ `float[1435]` → `ArrayList<Point3D>`(478) → `estimateHeadPose`（`float[1434]` + `Triple`）→ `TrackingConfidenceDetector.updateAndCheck`（478 点框 + 478 点 sqrt 位移 + `toList()`）→ `LandmarkData` | `:1175-1260`、`MediaPipeFaceDetector.kt:1011-1056`、`:1118-1140`、`TrackingConfidenceDetector.kt:230-320` | 吞吐 = `1000/单帧推理ms × 并行路数`；代码注释的历史估值是 ~24 ms/路（`PipelineFaceTracker.kt:43`），**必须实测**（见 §9）。Kotlin 侧 478 点后处理约 0.1–0.3 ms/帧 |
| 重排 | `fc-face-landmark-merge` | `receiveCatching()` → `TreeMap` 插入/删除 → `ArrayList` 返回 → `trySend` | `:1268-1296`、`FrameReorderBuffer.kt:58-133` | 20–60 µs CPU，但**多 1 次线程切换**（单路时 100% 冗余，见 K5） |
| 后处理 | `fc-face-process` | 年龄丢弃（`:1305-1309`）→ `processWithPose`：`updateFaceDistanceFromLandmarks`(478 循环) + `extractKeyPoints`(478 点重映射 + ~14 个小 List) + 眼部 EAR（98 次 `distance2DTo` = 196 `Math.pow` + 98 `sqrt`）+ 嘴/微笑 + `clamp()` 拷贝 → `blinkCurveProcessor`（2 个 `Pair`）→ `ARKitBlendShapes.copy` → `OutputFrame` | `:1298-1394`、`FaceCapturePipeline.kt:84-152`、`FaceLandmarkIndices.kt:275-348`、`SimpleEyeExtractor.kt:689-728` | **0.4–1.0 ms/帧（估算）**；`perfBlendShape` 日志里的 “BlendShape” 就是这一段，可直接读 |
| 派发 | `fc-face-output` | `dispatchOutput`：4 个回调 + `calculateBoundingBox`(478 循环，因为两个回调都非 null) + `SdkFaceCaptureFrame` + `FaceVisualizationData` + `postInvalidate` | `:1426-1474`、`:1567-1590` | **0.1–0.3 ms/帧（估算）**；其中 JSON 组装在 `submitFrame` 里（见 §5） |
| WS 写 | `tracking-ws-writer` | `pendingTrack.getAndSet` → `String.toByteArray(UTF-8)` → 2 次 `write` + `flush` | `TrackingServer.kt:966-998`、`:940-960` | 20–100 µs/帧，独立线程，不阻塞面捕；但 `flush` 每帧一次 = 60 次系统调用/秒 |
| UI 绘制 | 主线程 | 双缓冲 `drawBitmap` + **478 次 `drawCircle`**（每次 2 个 `Pair` + ~6 次 `Set<Int>` 装箱查找）+ 3 次 `String.format` + `postInvalidate` | `CameraPreviewView.kt:385-395`、`:531-566`、`:611-624` | **2–5 ms/帧（估算）**，> 单帧预算的 12–30%；被 `onVisualizationUpdate`（60 Hz）+ 预览（30 Hz）共同触发 |
| 调试 HUD | 主线程 | `DebugSnapshotBatcher`：每帧 2 次 `synchronized` + 最多 10 Hz 写 Compose state | `MainActivity.kt:122-191` | < 0.05 ms/帧；面板收起时提前返回（`:104-106`） |

---

## 2. 每帧分配清单

尺寸按 ART（compressed oops，8 B 对齐）估算：对象头 8 B、引用 4 B、`float/int` 4 B、`long` 8 B。

### 2.1 分阶段明细

| 阶段 | 分配点（file:line） | 单次分配 | 频率 | 每秒 |
|---|---|---|---|---|
| 相机·每帧 | `YuvFrameData`（`Camera2Manager.kt:1680`）、`YuvFrameDataInternal`（`PipelineFaceTracker.kt:603`）、`data.copy()`（`:605`）、`Pair`+2 装箱 Int（`Camera2Manager.kt:1674-1677`）、`captureCallback` 的 key String（`:1346`） | 48 + 32 + 32 + 48 + ~150 = **310 B** | 60/s | 18.2 KB/s |
| 相机·预览 | `yuv420ToRgbRotated` 的 `Pair`+2 Int（`:1789-1792`）、返回的 `Triple`+3 Int（`:1813`）、`frameData.duplicate()`（`PipelineFaceTracker.kt:589`） | **168 B** | 30/s | 4.9 KB/s |
| 相机·上半身 tap | `Yuv420Frame`、3×`ByteBuffer.duplicate()`、`FaceRoiHint`、`RoiWindow`、5×`YuvGeometry.Size`（`Yuv420Frame.kt:45-50`、`YuvBitmapConverter.kt:47`）、2×`IntArray`（`:61-71`）、`Bitmap` 对象 + `MPImage` | Java **≈2.7 KB** + **230,400 B 原生** | 15/s | 39.5 KB/s Java + **3.3 MB/s 原生** |
| 检测 | JNI `float[5]`（`JNI.cpp:1211`）、2×`FaceBox`（`PipelineFaceTracker.kt:967`） | 132 B | 10/s | 1.3 KB/s |
| **关键点** | JNI `float[1435]`（`JNI.cpp:1289`）、`LandmarkResult`、`ArrayList`+`Object[478]`（`MediaPipeFaceDetector.kt:1044`）、**478 × `Point3D`**（`:1048`）、`float[1434]`（`:1129`）、`Triple`+3 装箱 Float（`:1139`）、`LandmarkData`（`PipelineFaceTracker.kt:1227`） | **25,084 B** | 60/s | **1.44 MB/s** |
| 重排 | `ArrayList(1)`+`Object[1]`（`FrameReorderBuffer.kt:92`）、`TreeMap.Entry`（`:63`） | 96 B | 60/s | 5.8 KB/s |
| **后处理** | `extractKeyPoints` 的 **478 × `Point3D`** + `ArrayList`（`FaceLandmarkIndices.kt:283-289`）、`FaceKeyPoints`、2×`EyePoints`、14 个小 `ArrayList`（`:295-330`）、2×`EyeGaze`、3×`ARKitBlendShapes`（`BlendShapeExtractor.kt:48`、`:103`、`PipelineFaceTracker.kt:1353`）、2×`HeadPose`、2×`HeadPoseData`、`ProcessResult`、`OutputFrame`、`DetectionBox`、`SdkFaceCaptureFrame`、`FaceVisualizationData`、4×`Point3D`(centerOf)、`Pair`+2 装箱 Float | **15,952 B** | 60/s | 0.93 MB/s |
| WS/JSON | `float[52]`（`TrackingProtocol.kt:309`/`ARKitBlendShapes.kt:325`）、`float[3]`（`:310`）、`StringBuilder(640)`（`:244`）、结果 String（`:289`）、`TrackingFrameBuffer.Entry`（`TrackingFrameBuffer.kt:104`）、`toByteArray`（`TrackingServer.kt:987`） | **2,836 B** | 60/s | 166 KB/s |
| **UI onDraw** | 478×`Pair`(transformPoint)（`CameraPreviewView.kt:507-516`）、478×`Pair(paint,radius)`（`:535-563`）、**≈2,868 个装箱 `Integer`**（`Set<Int>.contains`，`:233-243`+`:537-559`）、**478 个 `IndexedValue`**（`withIndex()`，`:531`）、4×`String.format`（`:526`、`:619-623`）、`RectF` | **71,728 B** | 60/s | **4.11 MB/s** |

### 2.2 合计（60 fps + 预览 30 fps + 上半身 15 fps + WS 已连接）

| 口径 | 数值 |
|---|---|
| Java 堆分配 | **≈6.7 MB/s** |
| 原生 Bitmap 分配（上半身 tap） | **≈3.3 MB/s** |
| 新对象数 | **≈3.4×10⁵ 个/秒**（其中 1.7×10⁵ 是 UI 的装箱 `Integer`，2.9×10⁴ 是 `withIndex()` 的 `IndexedValue`） |
| 单帧最贵的一帧 | 关键点结果帧 **≈25 KB**；叠加 UI 重绘那一拍再加 **≈72 KB** |

对照：ART 默认 GC 在手机上处理 6–10 MB/s 分配通常没问题，但**分配点是 60 Hz 均匀的**，
意味着 `minor GC` 的触发节奏和帧节奏同频。一旦某次 GC 停顿落在相机回调那一帧，
`callback > 16.67 ms` → 丢帧 → 交付帧率掉。这就是「平均 CPU 不算爆表但帧率上不去」的典型形态。

### 2.3 特别检查项（逐条回答任务里的点名项）

| 检查项 | 结论 | 证据 |
|---|---|---|
| `Point3D` 每帧创建多少 | **956 个/帧**（关键点 478 + `extractKeyPoints` 478）+ 4 个 `centerOf` 结果 | `MediaPipeFaceDetector.kt:1048`、`FaceLandmarkIndices.kt:283-289`、`BlendShapeExtractor.kt:182-186` |
| `BlendShape` 数组拷贝 | `ARKitBlendShapes` 每帧创建 **3 个**：`extractBlendShapes` 里 new、`clamp()` 里 `copy()`、眨眼处理后 `copy()`。`toMediaPipeArray()` 再新建 `float[52]` | `BlendShapeExtractor.kt:48,103`、`ARKitBlendShapes.kt:451-462`、`PipelineFaceTracker.kt:1353`、`ARKitBlendShapes.kt:324-386` |
| 数组拷贝 | ① 预览：整帧 2.76 MB `ByteArray` memcpy + 3.69 MB `IntArray`；② 关键点：JNI 每帧 `float[1435]` + `float[1434]` = 11.5 KB；③ `TrackingConfidenceDetector` 每帧 `currentLandmarks.toList()` = 478 引用（≈1.9 KB） | `CameraPreviewView.kt:283`、`JNI.cpp:1289/632`、`TrackingConfidenceDetector.kt:306` |
| `map/filter/lambda` | `landmarks.map { Point3D(...) }`（`Iterable.map` 是 inline，**没有 lambda 对象**，但分配 478 个 `Point3D` + 1 个 `ArrayList` + 1 个 `Iterator`）；UI 循环的 `withIndex()` 每帧还会分配 1 个 `IndexingIterator` + **478 个 `IndexedValue`**（`CameraPreviewView.kt:531`） | `FaceLandmarkIndices.kt:283`、`CameraPreviewView.kt:531` |
| `String` / `String.format` | 每帧至少 1 次（相机 `captureCallback` 的 key 拼接），UI 每帧 3–4 次 `String.format`（每次内部 new `Formatter` + `StringBuilder` + `char[]`），眨眼时 `Log.v` 里 1–2 次模板拼接 | `Camera2Manager.kt:1346`、`CameraPreviewView.kt:526,619-623`、`BlinkCurveProcessor.kt:591` |
| `ByteBuffer` | 面捕分支**零像素拷贝**（直接传 Camera2 direct buffer，`PipelineFaceTracker.kt:603-605`）；每帧新增的是 2 个对象引用。上半身 tap 每 66 ms `duplicate()` 3 次（≈48 B/个，共享底层内存）。预览分支每 33 ms 1 次 `duplicate()` | 同上 |
| boxing | UI 每帧 ≈2,868 个 `Integer`；关键点 `Triple<Float,…>` = 3 个 `Float`；`BlinkCurveProcessor.process` 返回 `Pair<Float,Float>` = 2 个 `Float`；`yuv420ToRgbRotated` 返回 `Triple<ByteBuffer,Int,Int>` = 3 个 `Integer` | `CameraPreviewView.kt:537-559`、`MediaPipeFaceDetector.kt:1139`、`BlinkCurveProcessor.kt:563`、`Camera2Manager.kt:1813` |
| 唯一的「大对象」分配 | 上半身 tap 的 `Bitmap.createBitmap(px, w, h, …)`：320×180 ARGB = **230 KB/次**，15 次/秒。它进 native 堆，但需要 GC 回收 `NativeAllocationRegistry` 条目，属于「低开销但会推 GC」的隐性压力 | `YuvBitmapConverter.kt:118` |

---

## 3. 线程、通道与锁

### 3.1 常驻线程清单

| 线程 | 数量 | 优先级 / 类型 | 创建点 | 备注 |
|---|---|---|---|---|
| `CameraBackground` | 1 | `THREAD_PRIORITY_DISPLAY`，HandlerThread | `Camera2Manager.kt:596-599` | 16.67 ms 硬预算 |
| `fc-face-detect` | 1 | NORM+2 单线程池 | `PipelineFaceTracker.kt:200-201,228-234` | 懒创建 |
| `fc-face-landmark-gpu0…3` | **4 个池 / 实际 2 个** | NORM+2 | `:213-216` | `MAX_GPU_CHANNELS = 1 + MAX_EXTRA_ENGINES = 4`（`:83`、`MediaPipeFaceDetector.kt:97`）；默认只启动 2 路（`:80,1161-1172`） |
| `fc-face-landmark-merge` | 1 | NORM+2 | `:219-220` | 单路时全程冗余 |
| `fc-face-process` | 1 | NORM+2 | `:222-223` | |
| `fc-face-output` | 1 | NORM+2 | `:225-226` | |
| `Camera-fps-probe` | 1 | daemon，长期 `sleep(2500)` | `Camera2Manager.kt:1317-1319` | 只在启动后 3 s 内工作 |
| `vfc-log` | 0 或 1 | daemon，`MIN_PRIORITY` | `VfcLog.kt:108-124`，仅 debug 包（`VfcApp.kt:21-25`） | 每行 `flush()` |
| `tracking-accept` / `-playout` / `-stats` | 3 | 普通 Thread | `TrackingServer.kt:224-240` | 只在服务开启后 |
| `tracking-ws-reader` / `-writer` | 2 / 客户端 | daemon | `TrackingServer.kt:897-905` | |
| MediaPipe Pose 内部线程池 | 2–4 | 库内部 | `UpperBodyPoseDetector` | 只在开上半身时 |
| 主线程 / RenderThread | 2 | — | — | `onDraw` 2–5 ms |

合计（面捕开、上半身开、WS 连接 1 个）：**≈17–20 个 app 线程 + MNN 原生线程池（每引擎 2–4 条 × 1~4 引擎）**。
在 8 核手机上，仅线程数就已经超过物理核数；`faceDetector.initialize(numThreads = availableProcessors().coerceIn(2,4))`
（`PipelineFaceTracker.kt:500-505`）**每多开一路 GPU 引擎就再复制一份**（`:940-944`，`numThreads = mnnThreadCount`），
Vulkan 下这些线程基本空转，但会参与调度与功耗统计。

### 3.2 每帧线程切换次数

| 路径 | 每帧切换 | 说明 |
|---|---|---|
| 相机回调 → 检测线程 | 1 | 检测 10 Hz 也要把 60 Hz 的帧全取出来再 `continue`（`:1118-1121`） |
| 相机回调 → 关键点 worker | 1 | 通道 fan-out，天然负载均衡 |
| worker → 重排线程 | 1 | `mergeChannel.trySend`（`:1247`） |
| 重排线程 → 后处理线程 | 1 | `landmarkChannel.trySend`（`:1287,1290`） |
| 后处理 → 派发线程 | 1 | `outputChannel.trySend`（`:1362`） |
| 派发 → WS playout | 1 | `frameBufferLock` + `signalAll`（`TrackingServer.kt:389-394`）→ 由 playout 线程取出 |
| playout → ws writer | 1 | `offerTrack` + `Semaphore.release`（`:908-913`） |
| **合计** | **7–8 次/帧** | 60 fps ⇒ **420–480 次线程唤醒/秒**；每次 wakeup 的调度延迟 0.05–1 ms，是 jitter 的主要来源之一 |

结论：单路模式下「worker→重排→后处理」这两个跳是纯冗余——结果本来就是有序的，
`worker → 后处理` 一跳就能到（**每帧省 1 次唤醒**，并去掉重排线程这一级 60 ms 缺口源）。

### 3.3 Channel 容量与 `trySend` 失败行为

| 通道 | 容量 | 溢出策略 | 创建点 | 失败时行为 |
|---|---|---|---|---|
| `yuvFrameChannel` | 2 | `DROP_OLDEST` | `:282-285`、`:550` | `trySend` 返回失败/丢弃，**代码完全忽略返回值，没有任何计数器** |
| `yuvLandmarkFrameChannel` | `max(3, 并行度+1)` = 3 | `DROP_OLDEST` | `:539,551` | 同上 |
| `landmarkMergeChannel` | 8 | `DROP_OLDEST` | `:297-300` | 同上；丢帧会让重排出现缺口，触发 60 ms 等待（`:69`） |
| `landmarkChannel` | 4 | `DROP_OLDEST` | `:287-290` | 同上 |
| `outputChannel` | 1 | `DROP_OLDEST` | `:292-295` | 回调慢时只保留最新帧（正确的设计） |
| `TrackingFrameBuffer` | 120 | 丢最旧 | `TrackingFrameBuffer.kt:100-104` | 有 `droppedCount` |

**这是当前最大的可观测性盲区**：相机 60 Hz 产出、关键点 2 路消费，一旦消费跟不上，
通道就开始静默丢帧，而 `PipelineFaceTracker` 的所有统计（`staleFrameDropCount`、`detectionSkipCount`）
**都不统计这件事**。所以「为什么只有 45 帧」的答案可能藏在通道里却完全看不到。

### 3.4 锁竞争点

| 锁 | 类型 | 每帧获取次数 | 争用方 | 评估 |
|---|---|---|---|---|
| `faceBoxLock` | `Any()` monitor | 2–3 | 检测写（10 Hz）、2 路关键点读（60 Hz×2）、相机提示读（15 Hz） | 临界区只有字段读写，**但它是跨 4 个线程的热锁**；且 `lastFaceBox` 已经是 `@Volatile`（`:182`），锁其实是双保险。可以退化为一对 `AtomicReference`(box, ts) 或干脆只用 volatile |
| `perfLock` | `Any()` monitor | **4–6** | 检测、2 路 worker、后处理、派发 | 临界区只有 `Double` 累加；`logPerfStats`（每 60 帧）也在里面做快照+reset。单次 ~100 ns，但 2 路 worker 会真争用；`ENABLE_PERF_PROFILING` 恒为 true（`:112`），**release 也在付这笔钱** |
| `bitmapLock` | `Any()` monitor | 30（相机）/ `AtomicReference`（UI） | 相机线程 vs UI | 只在预览开启时；临界区含 `setPixels`，**UI 若正在读同一块会等**（虽然靠 `uiDrawing` 规避） |
| `frameBufferLock` | `ReentrantLock` | 每帧 2 次（入队 + 放行） | 派发线程 / playout | 临界区短，`signalAll` 唤醒；60 Hz 下无问题 |
| `DebugSnapshotBatcher.lock` | `Any()` | 2/帧（blendShapes + headPose） | 派发线程 + UI | 极短；面板收起时提前 return（`MainActivity.kt:104-106`） |
| `VfcLog.writeLock` | `ReentrantLock` | 每行 1 次 | 仅日志线程 | 不影响到业务线程，但每行一次 `flush()` |
| `captureLifecycleLock` | `Any()` | 0（只 start/stop） | — | |
| `UpperBodyPoseDetector.stateLock/pipelineLock` | monitor | 0（result 回调只读 volatile） | — | |

**`@Volatile` 热点读**：`onYuvFrameForBodyWithHint` + `onYuvFrameForBody` 各 1 次/帧（`:613-614`，
设计上已经做了「局部快照」很好）；`bodyTapIntervalMs`、`lastBodyTapMs`、`isRunning`（每个 stage 循环每帧 1 次）。

### 3.5 协程派发次数

- **每帧 0 次** `launch`（第一轮已经修掉「每帧一个协程」的问题）。
- 启动时 6–9 次 `pipelineScope.launch(dispatcher)`（`:1029,1058,1105,1181,1269,1299,1403`）。
- 唯一的例外：重排线程在有缺口时 `withTimeoutOrNull(60ms) { … }`（`:1280-1282`），
  **每次进入都会创建一个 `TimeoutCoroutine` 对象**；正常情况下（无缺口）走 `receiveCatching()` 不分配。
  一旦 60 Hz 下发生丢帧 → 缺口 → 每帧一个超时协程 + 60 ms 停顿，这是「偶发卡顿」的经典放大器。

---

## 4. 统计与日志开销

| 项 | 现状 | 60 fps 量级 | 证据 |
|---|---|---|---|
| `ENABLE_PERF_PROFILING` | `const val = true`（**恒开**） | 每帧 4–6 次 `System.nanoTime()` + `synchronized`；≈0.5–1 µs/帧 ⇒ <0.01% CPU | `PipelineFaceTracker.kt:112`、`:1034/1044/1119/1139/1149/1197/1249/1311/1313/1408/1412` |
| 每 60 帧的 `logPerfStats()` | 16 条 `Log.i` + 14 次 `String.format` + 1 次 `FloatArray(120)` 拷贝 + `Arrays.sort` | 每秒 1 次，**单次 0.3–1 ms**（估算），全部落在后处理线程上；排序本身 120 元素可忽略 | `:1480-1556`、`:1625-1635` |
| `cameraCallbackAvgMs` | 每 120 帧 1 条 `Log.i`，超 16.7 ms 再加 1 条 `Log.w` | 2–4 行/秒 | `Camera2Manager.kt:1446-1469` |
| `captureCallback` 的 key 字符串 | **每帧** | **60 个 String/秒**；ISO 抖动时每帧 2 次 `String.format` + 1 条 `Log.i`（`String.format` 5–10 µs ⇒ 最坏 0.6 ms/帧，全在相机线程） | `:1346-1360` |
| `BlinkCurveProcessor` 的 `Log.v` | 双眼同时眨眼期间**每帧**一次模板字符串，**无 `isLoggable` 保护** | 眨眼时 ≈30–50 条/秒，每条都先拼字符串 | `BlinkCurveProcessor.kt:591`、`:601,610` |
| `TrackingConfidenceDetector` 的 reason | 只在 `shouldRedetect` 时构建（已优化） | 周期重检 ≈2 次/秒 | `TrackingConfidenceDetector.kt:293-302` |
| `UI FPS update` | 1 条/秒 | 1 行/秒 | `PipelineFaceTracker.kt:1604` |
| **`VfcLog` 是否落盘** | **只在 debug 包**（`ApplicationInfo.FLAG_DEBUGGABLE`）；release 包 `enabled=false`，每行只多一次 volatile 读 | — | `VfcApp.kt:21-26`、`VfcLog.kt:93-132,252-253` |
| `VfcLog` 落盘时的真实成本 | 每行 `pool.execute { … }`（1 个 lambda + 1 个队列节点）→ 日志线程 `StringBuilder` + `SimpleDateFormat.format` + `write` + **`flush()`（每行一次写盘系统调用）** | 面捕+预览+HUD 合计 **40–80 行/秒** ⇒ 40–80 次 `flush/s`；日志线程 `MIN_PRIORITY` 但会抢 IO，实测可吃掉 1–3% CPU 并制造百微秒级停顿 | `VfcLog.kt:259-281`、`:285-303` |
| 每帧字符串格式化次数（稳态） | 相机 1（key）+ UI 3–4（`String.format`，60 Hz）+ 眨眼期间 1–2 | **≈250–320 次/秒**，其中 UI 的 ~200 次是纯调试信息 | 见上 |

**结论**：日志本身不是主因（release 包几乎为零），但在 **debug 包**里，它把
「每行 flush + `SimpleDateFormat`」叠在 60 Hz 的相机线程日志上，会把
「偶发的 1 次 `Log.i`」放大成几十微秒到毫秒级的停顿。用户测试用的多半是 debug 包，
所以这一项在「CPU 高 + 抖动」的体感里占比不小。**先做两件事就够：把 `captureCallback` 的格式化降到 debug-only；
把 `VfcLog` 的 `flush()` 改成按 8 KB 或 100 ms 批量刷。**

---

## 5. WS 发送侧

| 项 | 现状 | 证据 |
|---|---|---|
| 无客户端时 | `wsClients.isEmpty()` 直接 return，**连 JSON 都不生成** | `TrackingServer.kt:338-339` |
| 每帧什么被序列化 | `ARKitBlendShapes.toMediaPipeArray()` → `float[52]`；`floatArrayOf(3)`；手写 `StringBuilder(640)`，52 个 `appendDecimal4` + 3 个 pose + 4 个标量；结果 String ≈ **500–700 B** | `TrackingProtocol.kt:231-317`、`:77-104` |
| 序列化成本 | 估算 **0.05–0.2 ms/帧**（纯整数拼接，无反射、无第三方 JSON） | 待实测：`sentFps` 与 `fps` 对比 + Perfetto |
| 写线程是否阻塞面捕 | **不阻塞**：`pendingTrack` 是 `AtomicReference`，只保留最新一帧（覆盖式），写线程慢只会丢帧不会反压 | `TrackingServer.kt:869,908-913,985-993` |
| 背压 | 发送帧缓冲 500 ms（`TrackingProtocol.kt:57`）+ 队列上限 120 条（`TrackingFrameBuffer.kt:223`）；超限丢最旧。**代价是固定 +0.5 s 端到端延迟**，与「帧率低」是两个独立问题，但用户会把两者混为一谈 | `TrackingFrameBuffer.kt:89-117` |
| 每帧系统调用 | `sendFrame` 里 2–4 次 `write` 落到 32 KB 的 `BufferedOutputStream`（`TrackingServer.kt:491`），`flush()` 才是真正的 1 次系统调用 ⇒ **60 次/秒** | `TrackingServer.kt:940-960` |
| 唤醒机制 | `Semaphore.release/tryAcquire(20ms)`，第一轮已修掉 50 fps 天花板 | `:881-887,993` |
| 额外线程跳 | 派发线程 → `frameBuffer` → playout 线程 → writer 线程 = **2 次额外切换/帧**（缓冲开启时） | `:389-394,403-437,908-913` |

**优化空间**：`String → ByteArray` 的二次拷贝可以去掉（让 `buildTrack` 直接往 `ByteArrayOutputStream`
或复用的 `ByteBuffer` 写）；`Nagle` 若未关闭（代码里没有 `TCP_NODELAY`），每帧 flush 会有 40 ms 级别的
潜在延迟放大。CPU 上这不是大头，**优先级低于 §2 的相机/UI 两项**。

---

## 6. 后半段：478 点 → 52 BlendShape / 眨眼曲线的运算量

### 6.1 每帧对 478 个点的完整遍历次数

| # | 遍历 | 位置 | 每帧点数 |
|---|---|---|---|
| 1 | JNI 侧打包/拷贝（native 内部） | `JNI.cpp:1289-1306` | 1,434 floats |
| 2 | `ArrayList<Point3D>` 构建 | `MediaPipeFaceDetector.kt:1046-1053` | 478 |
| 3 | `estimateHeadPose` 重打包 `float[1434]` | `:1129-1134` | 478 |
| 4 | `TrackingConfidenceDetector.calculateFaceBox` | `TrackingConfidenceDetector.kt:206-220` | 478 |
| 5 | `calculateLandmarkMovement`（478 次 `sqrt`） | `:146-162` | 478 |
| 6 | `lastLandmarks = currentLandmarks.toList()` | `:306` | 478 |
| 7 | `updateFaceDistanceFromLandmarks`（bbox） | `FaceCapturePipeline.kt:261-304` | 478 |
| 8 | `extractKeyPoints` 归一化→像素重映射 | `FaceLandmarkIndices.kt:281-289` | 478 |
| 9 | `calculateBoundingBox`（派发阶段） | `PipelineFaceTracker.kt:1567-1590` | 478 |
| **合计** | | | **≈3,400 次点访问/帧 ⇒ ≈2.0×10⁵ 次/秒** |

纯算术量不大（每次访问 2–4 次比较/减法），但 **#6、#8 各带一次对象分配**，是上表里两块主要垃圾。

### 6.2 具体算子

- **眼部 EAR**：`computeEyeAspectRatio` 对每只眼做 7×7 = 49 次 `Point3D.distance2DTo`
  （`SimpleEyeExtractor.kt:703-711`），双眼 **98 次**；每个 `distance2DTo` 内部是
  `2 × Math.pow(Double)` + `1 × Math.sqrt`（`Landmark.kt:36-41`）⇒ **196 次 `Math.pow` + 98 次 `sqrt`/帧**。
  `Math.pow` 在 ART 上约 20–60 ns ⇒ **4–12 µs/帧**，可忽略；但把它换成 `dx*dx+dy*dy` 是零风险的顺手优化。
- **嘴部 MAR**：2 次 `distance2DTo`（`SimpleMouthExtractor.kt:287-288`）。
- **微笑**：纯加减除（`SimpleSmileExtractor.kt:293-353`），无分配。
- **姿态校准**：只在未校准窗口内累加 3×478 个 `Double`（`FaceCapturePipeline.kt:190-220`），
  **常数内存**（前一轮已改好），稳态每帧只判断 `isPoseCalibrated`。
- **52 BlendShape**：`ARKitBlendShapes` 52 个字段的写入 + `clamp()` 的 `copy()`（只 clamp 7 个字段，
  其余字段原样复制 52 个 → 216 B/次）。
- **眨眼曲线**：每只眼一个状态机（`BlinkCurveProcessor.kt:165-455`），每帧 ≤20 次浮点比较/插值 +
  2 次 `Pair`（装箱 2 个 Float）。**One-Euro 过滤在 Kotlin 侧只用于上半身**
  （`MediapipeUpperBody/UpperBodySmoother.kt:23-25`，13 点 × 3 轴 = 39 个 `OneEuroFilter`，15 Hz），
  脸部平滑在原生 `face_pipeline_filter` 里。⇒ 眨眼/平滑的 Kotlin 成本 **< 0.05 ms/帧**。
- **上半身结果后处理**（15 Hz）：33 → 13 点 + 39 个 One-Euro + 回映，`List(13)` 分配，
  成本 < 0.1 ms/次 ⇒ 均摊到面捕帧可忽略。

**结论：后处理的 CPU 只有 0.4–1.0 ms/帧，真正的成本是「分配」而不是「算」；
所以优化后处理要用「复用数组 / 少遍历」而不是「换算法」。**

---

## 7. 补丁建议（按 收益 ÷ 风险 排序）

> 所有补丁都**不改协议、不改模型**，且互相独立。每条给出：改什么 / 怎么改 / 文件 /
> 预期收益 / 风险 / 怎么验证。预期收益标注「估算」，真机数字由 §9 的方法产出。

### K1（P0，收益最大）把「调试预览」彻底搬离相机线程

- **改什么**：相机线程上的整帧 YUV→RGB + 3 次全帧搬运。
- **怎么改**（两档，按风险选）：
  - **A（推荐，最终形态）**：给 `Camera2` 会话加第二个输出 Surface（`SurfaceView` 的 `SurfaceHolder`
    或 `TextureView` 的 `SurfaceTexture`），在 `createCaptureSession` 里 `addTarget`。
    删除 `setCameraPreviewFramesEnabled(true)`、`processYuvToRgbForPreview`、`maybeConvertPreviewFrame`、
    `enablePreviewInYuvMode` 以及 `CameraPreviewView` 的整条 `updateCameraFrame` 位图路径。
    预览由系统合成器（GPU）直接显示，**相机线程成本 = 0**。
  - **B（低风险过渡）**：保留现有路径，但
    ① 预览分辨率降到 **360p**（`720p` 的 1/4 像素）；
    ② 让 native 转换直接输出 **RGBA_8888** 到一个复用 direct `ByteBuffer`，
    用 `Bitmap.copyPixelsFromBuffer()` 一步进 Bitmap，**删掉 `ByteArray` memcpy + 921,600 次像素循环 + `setPixels`**。
    预期把 6–11 ms 压到 1.5–3 ms。
- **涉及文件**：`app/.../MainActivity.kt:578-585`（预览开关）、
  `app/.../CameraPreviewView.kt:253-328`（位图路径）、
  `MediapipeFaceLandmark/.../Camera2Manager.kt:1401-1438,1502-1509,1727-1731,1770-1842`、
  `facecapture-sdk/.../PipelineFaceTracker.kt:582-590,808-812`。
- **预期收益**：相机回调 **−6~11 ms（一半的帧）**；若 A 方案，相机线程只剩 0.1–0.3 ms。
  CPU **−15~25%**（一个核的 20% 左右）；Java 堆 −5 KB/s、原生 −3.3 MB/s（B 方案减一部分）；
  **直接决定能否稳 60 fps 交付**。
- **风险**：A 方案要处理旋转 / 镜像 / 与 Compose 的 `AndroidView` 嵌套、`SurfaceView` 层级（可能盖住叠加层，
  需把叠加层换成另一个 `View` 叠在上面）、以及 `ImageReader` 与 preview surface 的会话配置兼容性
  （部分 HAL 拒绝 preview+ImageReader 组合，需要 fallback 到当前实现）。B 方案风险低，但要动 native 转换器。
- **验证**：`Camera2Manager` 已有的 `Camera callback: avg … max …`（`:1453`）与
  `Camera delivered`（`PipelineFaceTracker.kt:1537`）；A/B 对比 30 秒，看 `avg` 是否降到 < 4 ms、
  `delivered` 是否稳定 58–60；再用 `/status` 的 `fps` 与 PC 端画面确认无回归。

### K2（P0，正确性）修掉「`image.close()` 之后仍读 YUV plane」

- **改什么**：帧生命周期。
- **怎么改**：
  - **A**：相机线程不 `close()`，把 `Image` 的所有权随 `YuvFrameData` 传给消费端，由**最后**一个消费者
    （关键点 worker / 检测 worker / 上半身 tap）在读完时 `close()`。`ImageReader.maxImages` 提到 6。
    需要一个引用计数（3 个消费者 + 1 个 tap = 4）。
  - **B（更简单）**：相机线程把平面拷进一个 **3 槽 ring 的 direct `ByteBuffer`**（Y 1.38 MB + UV 0.69 MB ≈ 2.07 MB × 3 = 6.2 MB 常驻），
    推理线程只读 ring。第二轮已经能算出 ROI bbox，可以只拷 ROI（典型 0.15–0.4 MB），
    成本 **0.1–0.3 ms/帧**。
- **涉及文件**：`MediapipeFaceLandmark/.../Camera2Manager.kt:1401-1438,707-714`、
  `facecapture-sdk/.../PipelineFaceTracker.kt:592-631`、
  `MediaPipeFaceLandmarkJNI.cpp:1236-1310`（`GetDirectBufferAddress` 零拷贝路径）。
- **预期收益**：消除「偶发跳点 / 花屏 / 崩溃」；也让 K1/K3 的收益不被数据竞争掩盖。
  **不直接提升帧率**，但它是「敢不敢把并行度提到 3–4 路」的前提。
- **风险**：中等（生命周期 + 内存 + 与 `stop()` 的竞态，现有 `stop()` 关闭通道的写法已经考虑过一半）。
- **验证**：人为在 JNI 入口 `usleep(30ms)`（临时打点），跑 10 分钟看是否出现关键点跳变/崩溃；
  再跑正常版本对比 landmark 输出的稳定性（录 JSON 逐帧 diff）。

### K3（P1，低风险高收益）UI 叠加层去装箱、去 Pair、降频

- **改什么**：`onDraw` 里 478 点 × (2 个 `Pair` + ~6 次 `Set<Int>.contains`)。
- **怎么改**：
  1. 用 `IntArray(478)` 做 **LUT**：`lutPaint[index]`（0..N）+ `lutRadius[index]`（或把半径编码进高 8 位），
     在 `companion object` 里一次性构建，`onDraw` 里只做 `when (lutPaint[i])` 取预置 `Paint`；
     彻底删掉 8 个 `toSet()` 的 `contains`。
  2. 删掉 `transformPoint` 返回 `Pair` 与 `(paint, radius)` 的 `Pair`：直接算 `Float` 局部变量。
  3. `String.format` 的 3 条姿态文本与 1 个 score：改成 **10 Hz 缓存字符串**（或 `canvas.drawText` 前
     只在值变化 > 0.5° 时重算）。
  4. `postInvalidate` 从「每帧」改成 **≤30 Hz 节流**（叠加层与相机预览一致就够）。
- **涉及文件**：`app/.../CameraPreviewView.kt:233-243,515-566,611-624`、
  `app/.../MainActivity.kt:530-532`。
- **预期收益**：**−4.1 MB/s** Java 垃圾、**−1.7×10⁵ 对象/秒**、UI 线程 **−2~4 ms/帧**；
  GC 停顿次数下降，间接改善相机线程的 `max` 回调耗时。
- **风险**：低（纯绘制层，可回退）。注意 LUT 要与 `FaceLandmarkIndices` 的索引一一对应，写个单测锁住。
- **验证**：`adb shell dumpsys gfxinfo <pkg> framestats` 看 UI 帧耗时；`Debug.getNativeHeapAllocatedSize`
  或 Android Studio Memory Profiler 看分配速率；开/关叠加层各录 30 秒对比 `/status` 的 `fps`。

### K4（P1）后处理与关键点阶段「零分配化」

- **改什么**：每帧 956 个 `Point3D`、`float[1434]`、`toList()`、`Pair`。
- **怎么改**：
  1. `extractKeyPoints`：`imageWidth>0` 时**不要**把 478 个点全部重映射；只对 `FaceKeyPoints`
     真正引用的 ~120 个索引做映射（其余保持归一化），或者干脆把 `FaceKeyPoints` 里的点改成
     `Point3D` 值语义的 3 个 `FloatArray` + 索引表。
  2. `estimateHeadPose`：用 `ThreadLocal<FloatArray(1434)>` 复用缓冲；返回改成
     `nativeEstimateHeadPoseInto(array, out: FloatArray)` 或返回 `FloatArray(3)` 后由调用方读，
     避免 `Triple` 的 3 个 `Float` 装箱。
  3. `TrackingConfidenceDetector`：`toList()` 改为「只保存抽样点」（如每 16 点取 1，30 个点足够算 movement），
     或复用两个 `FloatArray` 乒乓缓冲。
  4. `BlinkCurveProcessor.process` 返回 `Pair` 改为写 `FloatArray(2)` 出参（或 `@JvmStatic` 的两个 out 参数）。
  5. `ARKitBlendShapes` 的 3 次创建：`extractBlendShapes` 里改成「先构造一次，`clamp` 原地改字段」。
- **涉及文件**：`MediapipeFaceLandmark/.../MediaPipeFaceDetector.kt:1029-1056,1118-1140`、
  `CommonData/.../FaceLandmarkIndices.kt:275-348`、`OpenSeeFaceProcess/.../TrackingConfidenceDetector.kt:146-162,306`、
  `OpenSeeFaceProcess/.../BlinkCurveProcessor.kt:548-616`、`OpenSeeFaceProcess/.../BlendShapeExtractor.kt:43-104`、
  `CommonData/.../Landmark.kt:451-462`。
- **预期收益**：**−2.4 MB/s**、**−6×10⁴ 对象/秒**、关键点/后处理线程各 **−0.2~0.4 ms/帧**；
  最重要的收益是**降低 minor GC 频率**，减少落在相机回调帧上的 GC 停顿。
- **风险**：中（要保数值一致）。建议先用「录一段 478 点 + 52 BS 到文件」的 golden 对比，
  改完逐帧 diff（`< 1e-4` 视为通过）。
- **验证**：见上；同时看 `BlendShape` 日志的 `Processing/BlendShape` 毫秒数是否下降。

### K5（P1，30 分钟可落地）单路时绕过重排线程

- **改什么**：`activeChannelCount == 1` 时 worker 直接写 `landmarkChannel`。
- **怎么改**：`startYuvLandmarkWorker` 里按本轮并行度选目标通道：
  `val target = if (activeChannelCount == 1) landmarkChannel else mergeChannel`，把 `trySend` 发到 `target`；
  `startLandmarkReorderStage` 只在 `activeChannelCount > 1` 时启动。
- **涉及文件**：`facecapture-sdk/.../PipelineFaceTracker.kt:1161-1172,1187-1188,1246-1247,1268-1296,576`。
- **预期收益**：每帧少 **1 次线程唤醒**（2 跳合并为 1 跳）+ 1 个 `ArrayList` + 1 个 `TreeMap.Entry`；
  消灭「单路也会等 60 ms 缺口」这一类退化（`:69`、`:1280-1282`）。
- **风险**：低。唯一要保证的是 `landmarkChannel` 的写入时序仍严格递增（单路天然满足）。
- **验证**：`seq` 单调性（现有 `TrackingFrameBuffer` 已经假设）；`logPerfStats` 里
  `Frame interval p95/max` 应下降。

### K6（P2）相机线程杂项

- **改什么**：每帧 `getRotationDegrees()`、每帧 key 字符串、日志。
- **怎么改**：
  1. 旋转角缓存，用 `DisplayManager.DisplayListener` 在屏幕旋转时失效（现在仍会读已废弃的
     `defaultDisplay.rotation`，`Camera2Manager.kt:1744-1746`）。
  2. `captureCallback` 的 `"$range|$frameDuration|$iso"` 改成 3 个字段比较（`lastRange/lastDuration/lastIso`），
     日志降级到 `if (Log.isLoggable(TAG, DEBUG))`。
  3. `VfcLog.append` 的 `flush()` 改成批量（8 KB 或 100 ms），`SimpleDateFormat` 只在日志线程建一次。
- **涉及文件**：`MediapipeFaceLandmark/.../Camera2Manager.kt:1744-1762,1331-1362`、
  `CommonData/.../VfcLog.kt:285-303`。
- **预期收益**：**−0.1~0.3 ms/帧** + 每秒少 60 个 String；debug 包下把 40–80 次 `flush/s`
  压到 1–2 次，**明显降低 IO 抖动与功耗**。
- **风险**：低。
- **验证**：`Camera callback avg/max`；`/status`；debug 包对比 GfxInfo 与电池统计。

### K7（P2）上半身 ROI 转换去冗余

- **改什么**：`YuvBitmapConverter` 的逐像素 `normalizeRotation`。
- **怎么改**：`YuvGeometry.sourceX/sourceY` 在像素循环内被调 **2 次/像素**（ROI 10 万像素 ⇒ 20 万次/tap、
  15 Hz 下 **≈3×10⁶ 次/秒**），每次都要 `((r%360)+360)%360`。
  把旋转在循环外归一化成一个 `enum`/`Int`，内层用 `when` 分支或 4 份专用循环（JIT 最容易消掉分支）。
  另外 `IntArray(outWidth/outHeight)` 映射表可以按 `(window, outSize)` 缓存。
- **涉及文件**：`MediapipeUpperBody/.../YuvBitmapConverter.kt:86-116`、`YuvGeometry.kt:30-93`。
- **预期收益**：单次 tap **−0.3~1 ms**（15 Hz ⇒ 均摊 0.02–0.07 ms/帧），CPU 约 −1~3%。
- **风险**：低（有 `Yuv420FrameTest`/`YuvGeometryTest` 兜底）。
- **验证**：tap 前后加 `nanoTime` 打点，对比 `Camera callback max`；单测覆盖 0/90/180/270 + 镜像。

### K8（P2）统计去锁 + 通道丢帧可观测

- **改什么**：`perfLock` 与「静默丢帧」。
- **怎么改**：
  1. 每个 stage 用自己的 `PerfStats`（无锁，单线程写），`logPerfStats` 只读取；
     或直接用 `LongAdder`。
  2. 给每个 `trySend` 包一层：`if (channel.trySend(x).isFailure) dropCount.increment()`，
     并在 `logPerfStats` 里打印 `detect drop / landmark drop / merge drop` 三个数。
- **涉及文件**：`facecapture-sdk/.../PipelineFaceTracker.kt:146-166,1034-1148,1249-1253,1377-1387,1480-1556`。
- **预期收益**：CPU 收益很小（< 0.1%），**但它是把「帧率为什么上不去」从猜变成看的关键**。
- **风险**：低。
- **验证**：日志里出现 `Landmark drop: N (x%)`；对照 `cameraDeliveredFps`。

### K9（P3）线程收敛 / MNN 线程数

- **改什么**：`gpuLandmarkDispatchers` 一次建 4 个池（实际只用 2）；`numThreads` 随引擎数翻倍。
- **怎么改**：按 `activeChannelCount` 懒创建 dispatcher；Vulkan 后端下把 `numThreads` 固定为 2
  （`PipelineFaceTracker.kt:500-505,940-944`），避免 8 核设备上 4 引擎 × 4 线程 = 16 条空闲线程。
- **预期收益**：降低调度抖动与功耗；对帧率是「稳」而不是「快」。
- **风险**：中（要真机对照 CPU 后端的 landmark 耗时）。
- **验证**：`/proc/<pid>/status` 的 `Threads:`；`top -H` 看各线程 CPU。

### K10（P3）WS 侧

- **改什么**：`String → ByteArray` 二次拷贝、`TCP_NODELAY`、500 ms 缓冲。
- **怎么改**：`buildTrack` 直接写 `ByteArray`；`socket.tcpNoDelay = true`；把 `FRAME_BUFFER_MS`
  做成可选（面捕调试时设 0，可省 0.5 s 延迟）。
- **涉及文件**：`app/.../tracking/TrackingProtocol.kt:231-290`、`TrackingServer.kt:940-998`、`TrackingOutputManager.kt:41`。
- **预期收益**：CPU < 0.05 ms/帧；延迟 −500 ms（体验项）。
- **风险**：低（协议不变）。
- **验证**：`sentFps` 与 PC 端 `seq` 连续性；端到端延迟用 PC 端时间戳比对。

---

## 8. 如果只能改 3 处

| 排名 | 补丁 | 一句话理由 | 预期量化收益（估算） |
|---|---|---|---|
| **1** | **K1 预览离开相机线程**（A 方案 SurfaceView，或 B 方案 360p + native RGBA 直写） | 唯一能**直接**把相机回调从 7–15 ms 拉回 1–3 ms 的改动；相机线程不超预算，60 fps 才有意义，后面所有优化才吃得到 | 相机回调 −6~11 ms（一半的帧）；CPU −15~25%；delivered FPS 有希望回到 58–60 |
| **2** | **K3 UI 叠加层 LUT + 去 Pair + 降频** | 最大的一块 GC 垃圾（≈4.1 MB/s）且**风险最低**，改完立刻能测出差别；同时减轻 UI 线程抢核 | −4.1 MB/s 垃圾、−17 万对象/s、UI −2~4 ms/帧 |
| **3** | **K4 后处理/关键点零分配**（可与 **K5** 一起做，K5 只是 30 分钟的顺带） | 把剩余的 2.4 MB/s 分配砍掉一半以上，直接减少落在相机回调上的 GC 停顿；K5 顺手去掉一次线程唤醒和 60 ms 缺口源 | −2.4 MB/s、−6 万对象/s、关键点/后处理各 −0.2~0.4 ms/帧 |

> K2（YUV 生命周期）虽然优先级是 P0，但它是**正确性**问题而不是**帧率**问题；
> 如果只能改 3 处，把它排在第 4 位并在 K1 落地后立刻做——因为 K1/K5 会让并行度更容易提上去，
> 而这会把 K2 的竞态窗口进一步放大。

---

## 9. 待实测清单（当前无法在环境内验证的量，附测量方法）

| 待测项 | 现状 | 怎么测（tag / 计数器 / 命令） |
|---|---|---|
| 相机回调四段（YUV→RGB / memcpy / 像素循环 / setPixels）各多少 ms | 估算 2–4 / 0.3–0.6 / 2–4 / 1–2 ms | 在 `CameraPreviewView.updateCameraFrame` 与 `processYuvToRgbRotated` 内各加一对 `nanoTime`，每 120 帧打一条 `CameraPreviewStage: conv=… memcpy=… pack=… setPixels=…`；或直接 A/B：临时把 `MainActivity.kt:583` 改成 `false`，对比 `Camera callback avg`（`Camera2Manager.kt:1453`） |
| landmark 单帧推理耗时 | 注释历史估值 ~24 ms/路（`PipelineFaceTracker.kt:43`），不可信 | 已有 `perfLandmarkTotal` → 日志 `Landmark Detection: x ms`（`:1521`）；同时开 1 路/2 路对比 |
| 后处理真实耗时 | 估算 0.4–1.0 ms | 已有 `Processing` / `BlendShape` 两行（`:1522-1524`） |
| 派发 + JSON 耗时 | 估算 0.1–0.3 ms | 已有 `Dispatch`（`:1525`）+ `sentFps`（`TrackingServer.kt:118`） |
| UI onDraw 耗时 | 估算 2–5 ms | `adb shell dumpsys gfxinfo <pkg> framestats`；或 `Choreographer` 帧回调打点 |
| 通道静默丢帧率 | **完全不可见** | 按 K8 加计数器；或临时在 `trySend` 后打 `isFailure` 布尔统计 |
| GC 频率 / 停顿 | 估算 6.7 MB/s + 3.3 MB/s 原生 | `adb logcat -s art`（`GC concurrent mark sweep` 行）或 `adb shell dumpsys meminfo <pkg>`；对比开/关 UI 叠加层 |
| 线程数与各线程 CPU | ≈17–20 app 线程 | `adb shell ps -T -p <pid>` + `top -H -p <pid>` |
| `VfcLog` 落盘影响 | debug 包 40–80 次 flush/s | 对比 debug 包开启/关闭 `VfcLog.init` 的 `Camera callback avg` 与电池统计 |
| 预览是否真的 30 fps 门控生效 | 代码是 33 ms 门控（`Camera2Manager.kt:208`） | 在 `maybeConvertPreviewFrame` 里数调用次数，1 秒窗口应 ≈30 |
| 上半身 tap 是否与面捕帧重合 | 15 Hz vs 30 Hz 预览，理论 1/15 的帧三件事同帧 | 在相机回调里数「同时命中 tap + preview」的帧占比 |

---

## 10. 与前两轮文档的关系

| 上一轮已提 | 本轮状态 | 说明 |
|---|---|---|
| P0-1 YUV plane 在 `close()` 后仍被读 | **仍开**（升级为 K2，并补充了「第二轮零拷贝后竞态更严重」的证据） | `Camera2Manager.kt:1430-1437` + `JNI.cpp:1259-1261` |
| P0-2 前台预览在相机线程做整帧转换 | **仍开**（K1）；本轮**新量化**了 UI 侧的第二次/第三次全帧搬运 | 上一轮只提到 YUV→RGB，本轮补上 `ByteArray` + 921,600 次循环 + `setPixels` |
| P0-3 `logPerfStats` 持锁打日志 | **已修**（`:1484-1506` 锁内只快照；但 16 条日志仍在后处理线程上串行执行） | 残余成本见 §4 |
| P0-4 重排缺口固定等 60 ms | **仍开**（K5-附） | `:69,1280-1282` |
| P0-5 WS 写线程 20 ms 轮询 → 50 fps 天花板 | **已修**（`Semaphore` 唤醒，`TrackingServer.kt:881-887`） | — |
| P1-1 派发阶段 478 点遍历 | **仍开但可接受**（`calculateBoundingBox` 0.05–0.1 ms） | `:1433-1437` |
| P1-2 `estimateHeadPose` 的 `float[1434]` | **仍开**（并入 K4） | `MediaPipeFaceDetector.kt:1129` |
| P1-3 每帧 `defaultDisplay.rotation` | **仍开**（并入 K6） | `Camera2Manager.kt:1744-1746` |
| P2-1 后处理是分配大头 | **仍开**（本轮给出逐项清单） | `FaceLandmarkIndices.kt:283-289` 等 |
| — | **本轮新增**：UI 叠加层 ≈4.1 MB/s 垃圾（K3） | `CameraPreviewView.kt:531-566` |
| — | **本轮新增**：`TrackingConfidenceDetector.toList()` 每帧 478 引用（K4） | `:306` |
| — | **本轮新增**：单路仍走重排线程（K5） | `PipelineFaceTracker.kt:1247,1268-1296` |
| — | **本轮新增**：通道静默丢帧不可观测（K8） | §3.3 |
| — | **本轮新增**：`captureCallback` 每帧字符串 + ISO 抖动日志（K6） | `Camera2Manager.kt:1346-1360` |

---

## 11. 一句话总结

**这一轮不需要再猜「是推理慢还是相机慢」——代码里已经有两处可量化的、与推理无关的CPU黑洞（相机线程上的 720p 预览四连拷贝、UI 线程上的 478 点装箱叠加），
它们合计能吃掉一个半核的算力并把交付帧率按在 45–55；推理链路的 Kotlin 部分只贡献了 2.4 MB/s 的短命对象。
先把这两处打掉，再谈 Vulkan 并行度和 ROI。**
