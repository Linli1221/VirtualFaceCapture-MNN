# 帧率优化说明（目标：稳定 60 FPS）

> ⚠️ **架构变更提示（请先读）**：本文写作时 Live2D 是在**手机端**用 Cubism SDK 渲染的，
> 现在已经拆成「手机只做面捕 + 电脑渲染」（见 [README.md](README.md) 与
> [docs/tracking-protocol.md](docs/tracking-protocol.md)）。
> 因此文中与 **手机端 GL 渲染 / 抓帧 / 浏览器输出（OBS）** 相关的章节（例如
> 「与 Live2D 抢 GPU」「离屏 FBO 贴回」「Web Output 抓帧」等）属于**历史记录**，
> 这些路径已从 app 中删除；而**推理侧的全部优化（检测节流、派发单消费者化、
> Vulkan⇄CPU 切换、YUV 直通、后处理零分配、NEON）仍然有效**，这两类内容在本文中混排，
> 读的时候按此区分即可。

本文记录「优化帧率、稳定 60 帧」这一轮改动的**全部内容、原理、验证方法与已知风险**。

> ✅ **编译状态**：已通过 GitHub Actions 的 **Android CI**（run #6，commit `7e1898d`）：
> `:app:assembleDebug` / `assembleRelease` 与 `arm64-v8a` 原生库全部构建成功，APK 已作为
> artifact 上传（Actions → 最新一次 run → Artifacts）。
> 但**代码只在 CI 里编译过，没有在真机上运行过** —— 运行时行为请按第 5 节实测。
>
> 📌 首次 CI 报过唯一的编译错误：`DisplayMetrics.refreshRate` 是 `@hide` 字段
> （编译期不可见），已改为 `WindowManager.defaultDisplay.refreshRate` 兜底。

---

## 1. 为什么之前上不去 60 帧

面捕主链路是：

```
Camera2 YUV ──► 人脸检测(MNN) ──► Landmark(MNN) ──► 后处理(BlendShape/姿态)
                                              └──► Live2D(GL) + Compose UI
```

改动前的实测结构性问题（按影响排序）：

| # | 问题 | 位置 | 后果 |
|---|---|---|---|
| 1 | **每帧都跑一次人脸检测**，哪怕脸框几乎没变 | `PipelineFaceTracker` 检测阶段 | 检测+landmark 两个 MNN 推理抢占同一份算力 |
| 2 | **三个模型全部硬编码 Vulkan 后端**，和 Live2D 渲染抢 GPU，每帧还有 GPU→CPU 回读同步 | `face_detector.cpp` / `face_landmarker.cpp` / `blendshape_predictor.cpp` | 帧时间抖动大且**无法按机型调** |
| 3 | **相机线程每帧做一次整帧 YUV→RGB**（调试预览），而推理走 YUV 直通根本用不到 | `Camera2Manager.enablePreviewInYuvMode = true` | 480p 下每帧几百 KB 写入 + 数十万次像素运算，直接压低采集帧率 |
| 4 | **每帧 `pipelineScope.launch` 一个协程派发结果** | `PipelineFaceTracker` | 60fps = 每秒 60 个协程 + 60 次线程调度；回调一慢就无上限堆积 |
| 5 | **每帧 3~4 次写 Compose state** | `MainActivity` 回调 | DebugOverlay 整棵子树每秒重组数十次（含 Live2D 的 `AndroidView`） |
| 6 | **每帧 `Bitmap.copy()` 全尺寸 ARGB_8888** | `CameraPreviewView.updateCameraFrame` | 480×480 ≈ **900 KB/帧**、30fps ≈ 27 MB/s 的额外分配与拷贝 |
| 7 | **每帧创建 ~650 个临时对象** | `OpenSeeFaceProcess` 后处理 | GC 压力大，帧时间出现尖峰 |
| 8 | 原生每帧重复 `ImageProcess::create`、host tensor 分配 + **768 KB memcpy** | MNN 预处理 | 每帧固定浪费 |
| 9 | 校准窗口持有 N 帧 × 478 点（60fps ≈ 230 KB+） | `FaceCapturePipeline` | 启动首秒 GC 尖峰，校准结束那一帧卡 1~2 ms |
| 10 | 眨眼曲线帧率写死 `60f` | `PipelineFaceTracker` | 实际帧率不足时眨眼时长被拉伸 |
| 11 | **AE 区间被当成目标帧率**：`(30,60)` 只是一个「允许范围」，没设 `SENSOR_FRAME_DURATION` 时 AE 会从区间下端跑 | `Camera2Manager.applyCaptureRequestTuning` | **传感器稳定跑 30fps**，整条管线在等相机喂帧 —— CPU/GPU 双闲却只有 30 帧（详见第 7 节） |

另外还有若干「白做功」：相机预览尺寸没有像素上限、AE FPS 区间可能选到 30fps 封顶、
检测框每帧都要遍历 478 点（即便没人订阅）、每帧 4 次 `String.format` 打日志、
高刷屏每个 vsync 都重画一遍 Live2D、无抓帧时仍走「离屏 FBO + 全屏贴回」。

---

## 2. 改动总览

一共改了 20 个文件。按层分：

### 2.1 相机层 `MediapipeFaceLandmark/.../Camera2Manager.kt`

| 改动 | 说明 |
|---|---|
| 预览 RGB 帧默认关闭 + `@Volatile` | `enablePreviewInYuvMode` 默认 `false`，由上层按需开关（见 2.2） |
| `willProducePreviewFrames` | 「开关打开 && 有订阅者」才真的转 RGB |
| 新增 `MAX_PIXEL_COUNT = 640*480` 硬上限 | 人脸检测只吃 128/192 输入、landmark 只吃 192 的 ROI，整帧再大纯属浪费，大尺寸 YUV 也常常拿不到 60fps |
| `chooseTargetFpsRange()` | 优先 `(60,60)` → 天花板≥60 且地板≤30（典型 `(30,60)`）→ 地板≤30 里最高 → 最高天花板 |
| `ImageReader` 2 → 3 个 buffer | 处理端偶发慢一拍时相机可以继续出帧（配合 `acquireLatestImage` 不积压延迟） |
| `windowManager` 缓存 | `getRotationDegrees()` 在相机线程**每帧**跑，之前每帧一次 `getSystemService` |

### 2.2 流水线编排 `facecapture-sdk/.../PipelineFaceTracker.kt`

| 改动 | 说明 |
|---|---|
| **检测节流到 10Hz** | 稳定追踪时 100ms 内脸框位移极小，但每次检测要占满一次完整推理。脸框失效（掉脸 / `TrackingConfidenceDetector` 判定低置信度）时**立即重检**，不影响跟脸 |
| **陈旧帧丢弃** | 超过 `MAX_FRAME_AGE_MS = 300ms` 的帧不再进后处理 —— 算力应该花在最新一帧上，否则延迟会滚雪球 |
| **派发阶段改为单消费者通道** | `outputChannel`（容量 1 + `DROP_OLDEST`）替代「每帧 launch 一个协程」。回调变慢时只保留最新一帧，不堆积 |
| **每个流水线阶段独占常驻线程** | 原来 `Dispatchers.Default.limitedParallelism(1)` 只限并发度、仍会落在共享池任意线程上并和 GC 抢调度。现在 4 条 `fc-*` 线程（`NORM_PRIORITY+2`），帧时间抖动更小 |
| `send` → `trySend` | 通道满时不再挂起阶段协程 |
| 检测框按需计算 | 每帧 O(478) 的额外遍历，只有真的有人订阅 `onFaceCaptureResult` / `onVisualizationUpdate` 才算 |
| 眨眼曲线用真实帧率 | `blinkCurveProcessor.updateFrameRate(currentFps)`（原来写死 60f） |
| MNN 线程数自适应 | `availableProcessors().coerceIn(2, 4)` |
| `Dispatch` 指标改为测真实派发耗时 | 现在测到的才是「回调 + Live2D 参数写入」的时间 |
| **新增帧间隔抖动统计** | 日志输出 `p50 / p95 / max` —— 判断「稳不稳」看这个，不要只看平均 FPS |

新增公开 API（都是新增，不破坏既有调用方）：

```kotlin
fun setCameraPreviewFramesEnabled(enabled: Boolean)   // 调试预览开关（默认关）
fun isCameraPreviewFramesEnabled(): Boolean
fun setMnnBackend(backend: MnnBackend): Boolean       // Vulkan ⇄ CPU，内部 stop→切→start
fun getMnnBackend(): MnnBackend
```

### 2.3 原生层 `MediapipeFaceLandmark/src/main/cpp/**`

| 改动 | 说明 |
|---|---|
| **运行时切换推理后端** | 三个 `loadModel()` 增加 `bool use_vulkan = true`；`nativeSetBackend` / `nativeGetBackend` 用 `nativeInit` 缓存的模型路径原地重建 session |
| Vulkan 建不起来自动回落 CPU | `nativeInit` 里失败就重试 CPU 并 `g_use_vulkan=false`，Kotlin 侧 `nativeGetBackend()` 读到的是**真实**后端 |
| 去掉每帧 `ImageProcess::create` | 按源格式缓存，只在格式变化时重建 |
| 去掉每帧 host tensor 分配 + memcpy | landmark 预处理直接写复用 tensor，省掉**每帧 ~768 KB memcpy**；检测器省 ~192 KB/帧；blendshape 省 292 float 中间 vector |
| YUV 打包缓冲成员复用 | `resize` 而不是重新 `malloc`（整帧拷贝本身无法避免：MNN 的 YUV 输入要求 Y+UV 连续） |
| landmarks 返回数组 `thread_local` 复用 | 首帧之后零分配 |

新增 JNI 符号（与 Kotlin `external fun` 逐字对齐）：

```
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeSetBackend(JNIEnv*, jobject, jboolean)
Java_com_example_mediapipefacelandmark_MediaPipeFaceDetector_nativeGetBackend(JNIEnv*, jobject)
```

> **契约**：推理热路径**不加锁**（`g_model_mutex` 只在 init/release/setBackend 取）。
> 切后端前必须保证没有推理在跑 —— 请始终通过 `PipelineFaceTracker.setMnnBackend()`
> （它会 stop→切→start），不要直接调 `MediaPipeFaceDetector.setBackend()`。

### 2.4 后处理层 `OpenSeeFaceProcess/.../`

| 改动 | 说明 |
|---|---|
| **校准累积器改为求和槽** | `MutableList<List<Point3D>>`（60 帧 × 478 引用 ≈ 230 KB+）→ 3 个 `DoubleArray(478)`（≈11.5 KB 常数）。校准完成那一帧从 **~8.6 万次运算 → 1434 次** |
| 去掉重复 `clamp()` | 一次 `clamp()` 就复制一整个 52 字段对象，原来是 2 次 |
| `centerOf()` 替代 12 处 `map{}.average()` | 每帧省 12 个 List + 84 个装箱 Float + 12 个迭代器 |
| `computeEyeAspectRatio` 零分配 | 原来 2 个 ArrayList + 14 装箱 + 迭代器；**保留 `distance2DTo` 原数值路径**以求逐位一致 |
| `SimpleSmileExtractor` 去掉 Pair | 每帧 4 个 Pair + 8 个装箱 Float → 0 |
| `BlinkCurveProcessor` 去 `setOf(...)` | 每帧一个 LinkedHashSet → 枚举比较 |
| `TrackingConfidenceDetector` 延迟构建 reason | 4 次 `String.format`（20~40 µs/帧）只在真的要重检时才做 |
| 距离日志加 `isLoggable` 守卫 | 每帧路径上的 `String.format` |

合计：后处理阶段 **≈650 → ≈509 对象/帧（-22%）**，且全部是等价改写（数值逐位一致）。

### 2.5 UI / 渲染 `app/src/main/java/com/example/virtualfacecapture/`

| 文件 | 改动 |
|---|---|
| `MainActivity.kt` | fps/BlendShape/姿态**合并为一个 `DebugSnapshot`**，限流 ≤10Hz；`showDebug=false` 时一次都不写；`DebugOverlayHost` 把重组关进独立作用域（Live2D 的 `AndroidView` 与 ControlPanel 不再跟着重组）；`LaunchedEffect(showCameraPreview)` 同步预览开关；**新增 MNN 后端切换按钮**（Vulkan ⇄ CPU，IO 线程 + try/catch + 回读真实后端） |
| `CameraPreviewView.kt` | 去掉每帧 `Bitmap.copy()`（≈900 KB/帧）；`IntArray` + 双 Bitmap 复用 + `AtomicReference` 发布 + `uiDrawing` 防止覆写正在绘制的位图；recycle 统一走主线程并复查 `isRecycled` |
| `Live2DView.kt` | 采样屏幕刷新率（API30 `display.refreshRate` → `displayMetrics` → 60Hz 兜底）传给渲染器 |
| `Live2DCaptureRenderer.kt` | ≤75Hz **完全不变**；120Hz 每 2 个 vsync 画一帧（→60fps）；无人抓帧且 60Hz 时**直接画窗口**，省掉整趟「离屏 FBO + 全屏贴回」 |

> 关于「跳过 native 绘制会不会让动画变慢」：不会。native 侧 `LAppPal::UpdateTime()`
> 用 `clock_gettime(CLOCK_MONOTONIC)` 算真实 dt，跳过一次只是让下一帧 dt 变大。
> 被跳过的那一帧仍然会把上一帧的离屏纹理重贴一次窗口（EGL swap 后后备缓冲内容未定义，
> 裸 `return` 在部分驱动上会闪黑）。

---

## 3. 预期收益（定性）

| 改动 | 省下的东西 |
|---|---|
| 检测节流 10Hz | 稳定追踪时约 **5/6 的检测推理**不再执行 |
| 预览 RGB 门控 | 相机线程每帧少一次整帧 YUV→RGB（默认关） |
| 单消费者派发 + 常驻线程 | 每秒 60 个协程 → 0；帧时间抖动下降 |
| 后端可切 CPU | 推理不再和 Live2D 抢 GPU，消除 GPU→CPU 回读停顿（**具体哪个更快必须按机型实测**） |
| Compose 合批 | DebugOverlay 重组 30~60 Hz → ≤10 Hz（关调试面板时 0） |
| Bitmap 双缓冲 | 少 900 KB/帧 的分配与拷贝 |
| 后处理 | -140 对象/帧、校准峰值 230 KB → 11.5 KB |
| 原生 | 每帧少一次 768 KB memcpy + 多次堆分配 |

**这些改动消除的是「浪费」，不改变推理本身的算力需求。** 能否真的稳住 60 帧，
取决于你机型上 landmark 推理的单帧耗时（日志里 `Landmark Detection` 那一行）：

- `< 16 ms` → 稳定 60 帧有希望；
- `16~22 ms` → 45~60 帧，可以试 CPU 后端 / 调线程数；
- `> 22 ms` → 单靠这轮改动到不了 60，需要换更小的 landmark 模型或降频到 30 帧输出。

---

## 4. 关键设计取舍（为什么这么改）

1. **为什么检测可以降到 10Hz**：脸框只用于给 landmark 做 ROI 裁剪，100 ms 内的位移远小于
   裁剪余量；一旦脸框失效（掉脸、置信度低）会立刻重检，所以跟脸不受影响。
2. **为什么不直接把预览 RGB 删掉**：调试预览仍有价值，改成「显示时开、不显示时关」。
3. **为什么用 `outputChannel` 而不是 `SharedFlow`**：需要 `DROP_OLDEST` 的「只保留最新」语义，
   且不想每帧 `emit` 挂起。
4. **为什么后端开关做成运行时**：Vulkan / CPU 谁更快**因机型而异**（GPU 驱动质量、与
   Live2D 的 GPU 争用、回读开销），只能在真机 A/B。
5. **为什么 UI 端不把数据降到 60Hz 以下以外的手段**：`onFaceCaptureFrame → Live2D` 这条
   真实驱动链路**没有被改动**，降频只作用于调试面板。

---

## 5. 上机验证步骤

### 5.0 装哪个包

CI 每次推送都会构建并上传 APK：仓库 **Actions → 最新一次 `Android CI` → 页面底部 Artifacts**。
debug 与 release 是**两个独立 Artifact**，并行构建、互不阻塞：

- `VirtualFaceCapture-<sha>-arm64-debug`：debug 包，装机快、能看日志
- `VirtualFaceCapture-<sha>-arm64-release`：release 包 + SDK AAR，**测帧率用这个**

> ⚠️ 测帧率优先用 release 包：debug 包带调试信息、JIT/校验开销更大，同一份代码会低几帧。
> 手边只有 debug 包时，结论请按「相对变化」看，不要拿绝对值卡 60。
>
> 手机上不方便连 adb 时，debug 包控制面板有 **「导出日志 (debug)」** 按钮：把应用日志
> （`filesDir/logs/vfc.log`）、设备 / 版本信息、检测器首帧 dump 打成一个 zip，用系统
> 「保存到」对话框直接导出。native / 系统日志仍需下面的 `adb logcat`。

### 5.1 看什么

```bash
adb logcat -s PipelineFaceTracker Camera2Manager MediaPipeFaceDetector
```

每 60 帧会输出一段：

```
=== Parallel Pipeline Perf (avg of 60 frames) ===
  Face Detection: 3.20 ms (runs in parallel)
    Detection runs: 10 (16.7%)
    Skipped (reuse box): 50 (83.3%)
    Re-detect triggers: 0 (low confidence)
  Landmark Detection: 14.50 ms (runs in parallel)   <-- 真正的瓶颈
  Processing: 5.20 ms
    BlendShape: 4.10 ms (78.8%)
    Dispatch:   1.10 ms (callback + Live2D params)
  Stale frames dropped: 0 (> 300ms old)
  Theoretical max FPS (1000/landmark_ms): 69 FPS
  Actual output FPS (UI display): 59.4 FPS
  Frame interval p50=16.7ms p95=18.9ms max=34.2ms (target 16.7ms)
  MNN backend: VULKAN, MNN threads: 4
```

**判断标准**：
- `Frame interval p95` 接近 `16.7ms` 且 `max` 不频繁超过 30ms = 稳；
- `Landmark Detection` 是瓶颈，其它都是小头；
- `Stale frames dropped` 长期 > 0 说明管线真的处理不过来（掉帧把延迟压住了）。

### 5.2 怎么调

1. **先看默认（Vulkan）**，记下 `Landmark Detection` 与 p95。
2. 点 ControlPanel 里的 **`Backend: Vulkan (tap for CPU)`** 切到 CPU，等 2 秒再看同一段日志。
   帧时间更小／p95 更稳就用 CPU（很多机型上 CPU + 4 线程比 Vulkan 更稳，因为没有回读停顿）。
3. 如果两种后端都不够：`DETECTION_INTERVAL_MS_STABLE`（100 → 150）可以再省一点算力，
   代价是快速转头时脸框刷新变慢。
4. 如果 `Dispatch` 时间偏大（>3 ms），瓶颈在 Live2D 参数写入，与推理无关。

### 5.3 回归检查清单

- [ ] 掉脸后能立刻重检、重新跟脸（检测节流的正确性）
- [ ] 快速转头 / 摇头时 landmark 不丢（脸框 10Hz 刷新的正确性）
- [ ] 抖动预览打开/关闭都正常（`setCameraPreviewFramesEnabled`）
- [ ] 预览画面不撕裂、不闪黑（Bitmap 双缓冲）
- [ ] 60Hz 机型画面不闪黑（直接画窗口路径）；120Hz 机型动作流畅、不卡顿（跳帧节流）
- [ ] 点后端切换按钮后能正常切换、失败时停留在原后端且按钮恢复可用
- [ ] 校准过程正常，校准完成那一帧不再卡顿
- [ ] 浏览器输出（OBS）仍能拿到透明背景（抓帧路径未改）

---

## 6. 已知风险 / 后续可做

### 已验证 / 待验证

1. **编译：已通过**（CI run #6，debug + release，arm64-v8a，含全部 JNI 改动）。
   因此 `MNN::Tensor::elementSize()` / `getDimensionType()`、`ExecutorCoroutineDispatcher`
   / `asCoroutineDispatcher`、`FloatArray` + `java.util.Arrays.sort` 这些编译期风险全部排除。
2. **运行时：未验证**。以下只能在真机上确认 —— 首次启动请看 logcat 是否出现
   `MNN backend in use: VULKAN`（或自动回落到 `CPU`）、有没有
   `Failed to initialize native detector`，以及画面/表情是否正常。
3. **`.so` 需要重新构建**（`nativeSetBackend` / `nativeGetBackend` 是新符号）。
   如果 App 用了旧的 `.so`，`setBackend()` 会捕获 `UnsatisfiedLinkError` 返回 `false`，
   `initialize()` 里的 `nativeGetBackend()` 也已 try/catch 兜底，**不会崩**，只是切不了后端。

### 有意的行为变化

| 项 | 变化 | 影响 |
|---|---|---|
| `Camera2Manager.enablePreviewInYuvMode` | 默认 `true` → `false` | 直接使用 `Camera2Manager` 而不调 `setCameraPreviewFramesEnabled(true)` 的调用方将拿不到预览帧 |
| `TrackingStatus.reason` | 非重检路径返回 `""` | 仓库内唯一读取点在重检分支，无影响；仓库外若无条件打印会看到空串 |
| 调试面板 | 刷新率 ≤10 Hz | 有意降频，肉眼仍可读 |
| 60Hz + 无抓帧 | 直接画窗口 | 数学上等价；若某机型出现背景异常，把 `Live2DCaptureRenderer.onDrawFrame` 里那个分支注释掉即可回退 |

### 明确的后续优化（本轮未做）

1. **`CommonData/FaceLandmarkIndices.extractKeyPoints` 每帧把 478 个点像素化**
   （`landmarks.map { Point3D(x*w, y*h, z) }`），是后处理阶段最大的单项分配（≈500 对象/帧）。
   下游（EAR / MAR / 微笑 / faceHeight 归一化）用的都是**比值**，理论上整段可以去掉，
   但不是逐位一致（坐标量级变化会改最后几位），**需要 A/B 对比表情表现后再动**。
2. 同文件里 `contour` / `eyebrow` / `nose` 在 `advancedMode=false` 下**没有消费者**，可以按需构建。
3. `ARKitBlendShapes.clamp()` 每次都复制整个对象；若改成原地 `coerce` 可再省 1 个对象/帧
   （需要 CommonData owner 同意，会改变 `clamp()` 的返回值语义）。
4. `PipelineFaceTracker` 里开启眨眼曲线时每帧还有一次 `blendShapes.copy(...)`。
5. `TrackingConfidenceDetector` 的 `calculateFaceBox` + `calculateLandmarkMovement`
   是两趟 478 遍历，可以合并成一趟。
6. 混合后端（检测/Blendshape 走 CPU、landmark 走 Vulkan）—— 检测已经降到 10Hz，
   收益可能不大，但如果 CPU 后端下检测变成新瓶颈，值得试。

---

## 7. 追加修复：AE 把传感器按在 30fps

### 现象

第一轮改动上线后实测：**帧率仍然上不去，但 CPU 和 GPU 都没有跑满。**
这条信息本身就很关键 —— 它排除掉「算力不够」，指向「整条链路在**等**」。

### 根因

Camera2 的 `CONTROL_AE_TARGET_FPS_RANGE` 是一个**允许区间**，不是目标帧率。
`chooseTargetFpsRange()` 在没有 `(60,60)` 的机型上会选 `(30,60)`，这样写：

| 写法 | 结果 |
|---|---|
| 只设 `(30,60)`，不设 `SENSOR_FRAME_DURATION` | AE 从区间下端起步 → **稳定 33.3ms/帧 = 30fps**，多出来的 16ms 全拿去延长曝光换亮度 |
| 同时钉 `SENSOR_FRAME_DURATION = 1/60s` | 传感器被强制 16.67ms 出帧，AE 改用 ISO / 降噪补曝光 → **60fps**（画面略暗） |

面捕是「动作跟随」的场景，**稳态帧率比暗光画质重要得多**，所以应该钉帧率。

### 改动

| 位置 | 内容 |
|---|---|
| `Camera2Manager.applyCaptureRequestTuning` | 新增 `pinFrameRate`（默认 `true`）：当所选 AE 区间上限 ≥ 60 时，显式 `set(SENSOR_FRAME_DURATION, 1e9/60)`。上限 < 60 的相机不动，避免强行拉到它做不到的帧率 |
| `Camera2Manager.createCaptureSession` | 抽出 `buildCaptureRequest()`；若 HAL 拒绝这个 key 抛 `IllegalArgumentException`，自动 `pinFrameRate = false` 重建一次请求重试（旧机型兜底） |
| `Camera2Manager.captureCallback` | 新增 `CaptureCallback`，从 `TotalCaptureResult` 读**真实生效**的 `CONTROL_AE_TARGET_FPS_RANGE` / `SENSOR_FRAME_DURATION` / `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY`，仅在变化时打日志 |
| `Camera2Manager.updateDeliveredFps` | `onImageAvailable` 里统计滚动 1 秒窗口的**实际交付帧率** |
| `PipelineFaceTracker.logPerfStats` | perf 块新增相机侧两行 + 自动判语 |

### 新增日志

```
  Camera delivered: 59.8 FPS (applied frameDuration=16.72 ms)
    Requested AE range: [30, 60], applied: [30, 60], exposure=12.4 ms, pinFrameRate=true
```

- `Camera delivered` 就是**权威答案**：读数是 30 说明相机只给 30 帧，后面所有推理优化都是白做；
  读数 60 而 `Actual output FPS` 只有 40，才是推理/后处理的问题。
- `applied frameDuration` 稳定在 33.3ms → AE 仍然在拿曝光换帧率（`pinFrameRate` 没生效，
  看是不是走了上面那个 `IllegalArgumentException` 回退分支）。
- 日志还会自动印一行结论：
  - `⚠ Camera only delivers 30.0 FPS → 相机是瓶颈…`
  - `⚠ Camera delivers 60.0 FPS but pipeline outputs 41.2 FPS → 瓶颈在推理/后处理…`

### 如果钉了帧率仍然不到 60

那就不是 AE 的问题了，按顺序排查：

1. `Camera delivered` < 60 且 `applied frameDuration` 仍是 33.3ms → 该机型在该分辨率下
   就不会给 60fps。可以试：把 `MAX_PIXEL_COUNT` 降到 `640*360`，或换 `(60,60)` 广播的
   分辨率（部分机型 720p 支持 60、480p 反而只到 30）。
2. `Camera delivered` ≈ 60 但输出 FPS 低 → 瓶颈在推理：先点 **Backend 切到 CPU** A/B，
   再看 `Landmark Detection` 耗时。
3. 两者都正常但画面看着不 60 → 渲染侧：高刷屏节流、Live2D 参数更新频率、OBS 抓帧。
---

## 8. 摄像头：物理超广角 + 横竖屏

### 目标

- 后置超广角（以及长焦）也能像普通摄像头一样在 App 内选择、切换。
- 手机横过来（架在支架上）时，预览 / 面捕 / 输出照常工作，旋转不打断采集。

### 问题

- `CameraManager.cameraIdList` 只返回「逻辑相机」。很多机型把后置超广角藏在逻辑后置
  相机后面，`cameraIdList` 里没有它，`openCamera(id)` 也打不开。
- 旋转屏幕默认会重建 Activity：`onPause` 释放相机，新 Activity 里面捕管线是停的，
  用户必须重新点「开始面捕」。

### 实现

| 位置 | 改动 |
|---|---|
| `Camera2Manager.getAvailableCameras()` | API 28+ 读取逻辑相机的 `physicalCameraIds`，把不在 `cameraIdList` 里、且支持 YUV 输出的物理镜头作为额外条目返回；`CameraDeviceInfo.key = "logical#physical"`；按 35mm 等效焦距打「超广角 / 长焦」标签并排序 |
| `Camera2Manager.setPreferredCamera()` | 接受 `"logical#physical"` 组合键（兼容旧偏好 `"0"` 这类纯逻辑 id）；物理镜头不可用时退回同一逻辑相机 |
| `Camera2Manager.openCamera()` | 逻辑相机负责 `openCamera` / 3A / 请求构建；尺寸、sensor 方向、FOV 改用物理镜头的 characteristics |
| `Camera2Manager.createCaptureSession()` | 物理镜头用 `OutputConfiguration.setPhysicalCameraId()` + `SessionConfiguration`（API 28+）；API 29+ 先 `isSessionConfigurationSupported()` 预检；被 HAL 拒绝时自动退回逻辑相机 |
| `app/AndroidManifest.xml` | `screenOrientation="sensor"` + `configChanges="orientation|screenSize|..."`：竖屏 / 左右横屏都允许，旋转不重建 Activity |
| `MainActivity` | 控制面板最大高度按 `LocalConfiguration.screenHeightDp` 计算（横屏默认收起），摄像头选择列表高度自适应；选择按 `key` 记忆 |

### 代价与验证

- 物理镜头只保证「能出流」，不保证 60fps：超广角 sensor 往往只有 30fps，以
  `Camera delivered` 为准；若物理流被 HAL 拒绝，日志会打印
  `HAL rejects physical stream ...` 并自动回退到逻辑相机。
- 横屏旋转时帧尺寸随设备上报的横/竖尺寸切换（如 640×480 / 480×640），预览与关键点按新尺寸重建位图，属预期。
- 验证日志：`Camera selected: Back 超广角 (id=0 · physical 2), sensor=90°, fov=...`；
  旋转屏幕时 logcat 不应再出现新的 `MainActivity onCreate`（Activity 不重建）。


---

## 9. 摄像头：物理超广角 + 横竖屏

### 目标

- 后置超广角（以及长焦）也能像普通摄像头一样在 App 内选择、切换。
- 手机横过来（架在支架上）时，预览 / 面捕 / 输出照常工作，旋转不打断采集。

### 问题

- `CameraManager.cameraIdList` 只返回「逻辑相机」。很多机型把后置超广角藏在逻辑后置
  相机后面，`cameraIdList` 里没有它，`openCamera(id)` 也打不开。
- 旋转屏幕默认会重建 Activity：`onPause` 释放相机，新 Activity 里面捕管线是停的，
  用户必须重新点「开始面捕」。

### 实现

| 位置 | 改动 |
|---|---|
| `Camera2Manager.getAvailableCameras()` | API 28+ 读取逻辑相机的 `physicalCameraIds`，把不在 `cameraIdList` 里、且支持 YUV 输出的物理镜头作为额外条目返回；`CameraDeviceInfo.key = "logical#physical"`；按 35mm 等效焦距打「超广角 / 长焦」标签并排序 |
| `Camera2Manager.setPreferredCamera()` | 接受 `"logical#physical"` 组合键（兼容旧偏好 `"0"` 这类纯逻辑 id）；物理镜头不可用时退回同一逻辑相机 |
| `Camera2Manager.openCamera()` | 逻辑相机负责 `openCamera` / 3A / 请求构建；尺寸、sensor 方向、FOV 改用物理镜头的 characteristics |
| `Camera2Manager.createCaptureSession()` | 物理镜头用 `OutputConfiguration.setPhysicalCameraId()` + `SessionConfiguration`（API 28+）；API 29+ 先 `isSessionConfigurationSupported()` 预检；被 HAL 拒绝时自动退回逻辑相机 |
| `app/AndroidManifest.xml` | `screenOrientation="sensor"` + `configChanges="orientation|screenSize|..."`：竖屏 / 左右横屏都允许，旋转不重建 Activity |
| `MainActivity` | 控制面板最大高度按 `LocalConfiguration.screenHeightDp` 计算（横屏默认收起），摄像头选择列表高度自适应；选择按 `key` 记忆 |

### 代价与验证

- 物理镜头只保证「能出流」，不保证 720p60：超广角 sensor 往往只有 30fps，以
  `Camera delivered` 为准；若 720p 物理流被 HAL 拒绝，日志会打印
  `HAL rejects physical stream ...` 并自动回退到逻辑相机。
- 横屏旋转时帧尺寸在 1280×720 / 720×1280 之间切换，预览与关键点按新尺寸重建位图，属预期。
- 验证日志：`Camera selected: Back 超广角 (id=0 · physical 2), sensor=90°, fov=...`；
  旋转屏幕时 logcat 不应再出现新的 `MainActivity onCreate`（Activity 不重建）。

---

## 10. 可选：上半身关键点（MediaPipe Pose）的开销

### 目标

在不影响面捕 60 fps 的前提下，额外提供 13 个上半身点 + 相对躯干角；**默认关**，
关掉时对相机线程 / 推理 / 显存零影响。

### 实现要点

| 位置 | 改动 |
|---|---|
| `facecapture-sdk/.../PipelineFaceTracker.kt` | 相机 YUV 回调里新增 `onYuvFrameForBody` tap：先喂完面捕两条 channel，再按 `bodyTapIntervalMs`（默认 66 ms ≈ 15 fps）同步调用；整段 `try/catch`，异常绝不影响面捕；`stop()` 置 null |
| `app/.../UpperBodyPoseController.kt` | 相机线程上把 `YuvFrameData`（duplicate ByteBuffer，不拷贝像素）包成 `Yuv420Frame` 交给检测器；`upperBody = false` 时 tap 为 null，回调里只多一次判空 |
| `:MediapipeUpperBody` | 采样 + 旋转 + 镜像缩放到长边 256 的 ARGB（约 5 万像素，1~4 ms），`PoseLandmarker` LIVE_STREAM 异步推理，单飞（上一帧没回来就丢帧，计入 dropped）；GPU 优先，创建失败自动回退 CPU。**（本轮已改为脸框 ROI → 320p，见 §12）** |
| 协议 | 开启后每帧 `track` 多 `ubOk`/`ub(52)`/`ubT`/`body`，约 +0.6 KB/帧（60 fps ≈ +36 KB/s） |

### 代价

- 相机线程：每 66 ms 多 1~4 ms 的 YUV→Bitmap 转换（约 6% 预算）；面捕 channel 仍然先喂。
  **（后续已优化：相机线程只做 0.15~0.4ms 平面拷贝入「只保留最新」的 YuvFrameRing，
  ROI 采样 / Bitmap / detectAsync 移到 `upper-body-worker` 线程，不再占相机帧预算。）**
- 推理：15 fps Pose 推理与面捕的 face landmarker 并行；GPU 满载时才回退 CPU（帧率会掉到 5~10）。
- 内存：模型 5.5 MB（lite）/ 9 MB（full），每帧一张 Bitmap（本轮起长边上限 320，约 300–400 KB（256 档约 200–260 KB；256/320 自适应见 InputSizePolicy），交给 GC）。
- 关掉时：tap 为 null，没有 Bitmap、没有额外推理，面捕路径与之前完全一致。

### 验证日志

- 开关打开：`UpperBodyPose` / `UpperBodyPoseDetector` tag 打印初始化后端、模型路径、回退原因；
- HUD：`上半身: 运行中 · x fps / 点数 n/13 · 质量 q · 躯干 roll/yaw`；
- 面捕 `Body fps`（相机侧）不应因开启上半身下降超过 1~2 fps，若明显下降说明 GPU 被抢；
- 关闭后相机线程不应再出现 `UpperBodyPose` 的逐帧日志。

> 本节数字是设计预算（75% 相机线程 60 fps 下），**未在本仓库环境真机量化**；
> 无 JDK / Android SDK 的环境只能做静态审查 + PC 侧 Node 单测。

---

## 11. 采集侧：30 fps「相机瓶颈」的定位与自适应降档（2026-10）

### 11.1 现象

实测整条管线只有 ~30 fps，`Camera delivered` 也停在 30 附近 —— 也就是
**相机没在喂 60 帧**，推理再快也没用。这一轮不改推理，专治采集侧。

### 11.2 三个根因

| # | 根因 | 位置 | 后果 |
|---|---|---|---|
| 1 | **720p 只有 30fps，但策略仍然保留 720p**：旧逻辑只打警告，不回退到能跑 60fps 的尺寸 | `Camera2Manager.chooseCaptureSize()` | 整条管线被相机按在 30fps |
| 2 | **预览 RGB 每帧在相机线程上整帧转换**：720p 一帧 YUV→RGB 2.7MB，再 `ByteBuffer.get()` 拷 2.7MB、92 万次逐像素打包、`Bitmap.setPixels` 3.7MB；回调一旦超过 16.7ms，`acquireLatestImage()` 就开始丢帧，`cameraDeliveredFps` 直接掉到 ~30 | `Camera2Manager.onImageAvailableListener` → `CameraPreviewView.updateCameraFrame` | 相机线程自己把交付帧率压到 30 |
| 3 | **HAL 声称 60fps、实际被 AE/带宽按在 30fps**（部分机型 720p 就是如此） | 平台行为，无 API 可查 | 同上；只能实测后降档 |

> 说明：`Camera delivered` 是相机线程**真正拿到**的帧数（在推理之前统计）。
> 它掉到 30，说明瓶颈在「相机 → 相机线程」这一段；如果它是 60 而
> `Actual output FPS` 只有 40，才是推理/后处理的问题。

### 11.3 改动

| 位置 | 内容 |
|---|---|
| `Camera2Manager.chooseCaptureSize()` | **帧率优先**：720p 只有在 `getOutputMinFrameDuration` 证明能跑满 60fps 时才选；否则在 ≤720p 里挑「能跑满 60fps 的最大尺寸」（通常是 640×480）。平台没上报能力时仍按可用处理，交给下面的探针实测兜底 |
| `Camera2Manager` 帧率探针 | 采集启动 **2.5s** 后读真实交付帧率；仍 < `MIN_CAPTURE_FPS = 50` 则 0.8s 后复测。**先判因**：`SENSOR_FRAME_DURATION` ≈ 33ms（或 AE 区间上限 < 60）→ 传感器/AE 就是 30fps，**不降档**（换了也没用）；只有传感器确实是 16.7ms、交付仍偏低时才降一档（像素至少小 25%）重开相机，最多 2 次 |
| 采集尺寸记忆 | 探明「能跑满 60fps」的尺寸后按相机 key 记进 `resolvedCaptureSizes`：stop/start、切走再切回同一镜头时直接用，不再花 3.3s 重新探测 |
| `Camera2Manager.recordCallbackTime()` | 新增**相机回调耗时**统计（120 帧窗口）。交付帧率 = min(传感器帧率, 1000/回调耗时)，这是区分「传感器只有 30fps」和「我们回调太慢」的唯一直接数据，并透出 `cameraCallbackAvgMs` 给 `PipelineFaceTracker` |
| `Camera2Manager.maybeConvertPreviewFrame()` | 预览 RGB **限速到 30fps**（`PREVIEW_INTERVAL_NS = 33ms`）。预览只用于取景/调试，减半后相机线程负载明显下降 |
| `mnn_yuv_converter.cpp` | 预览转换的**整帧 YUV 打包缓冲**与 `MNN::CV::ImageProcess` 按 (源尺寸/目标尺寸/旋转/格式) 缓存复用：不再每帧 `new/delete` 1.38MB（720p），也不再每帧重建 ImageProcess。缓存是 `thread_local`，只在相机线程调用 |
| 生命周期 | `startCamera/stopCamera` 用 `captureLifecycleLock` 串行化；探针跑在独立单线程（不能在相机 HandlerThread 上 `stopCamera()`，那会 join 自己）；`cameraSessionActive` + `fpsProbeGeneration` 保证 stop 之后旧探针不会把相机重新拉起来；**stop 时先 quitSafely + join 相机线程，再关 session/ImageReader**（反过来会让在途回调操作已关闭的 reader，异常打死相机线程 ⇒ 永久断流）；回调整体包 try/catch，单帧失败只丢帧 |
| 相机线程优先级 | `CameraBackground` 从默认优先级提到 `THREAD_PRIORITY_DISPLAY`(-4)：它的 16.7ms 是硬预算，而 `fc-*` 推理线程本来就跑在 NORM+2，默认优先级会在 CPU 忙时把相机回调压后 |
| `PipelineFaceTracker.logPerfStats()` | 改为「锁内快照 + 重置（微秒级），锁外格式化 + 输出」。之前 13 条 `Log.i` 全程持 `perfLock`，2 路关键词 worker 的每帧计数被顶住 1~20ms，正好制造重排缺口 |
| `TrackingServer` | writer 空闲时不再靠 `controlQueue.poll(20ms)`：`offerTrack/offerControl/sendPing/requestClose` 都会 `release()` 一个 Semaphore 立刻唤醒 writer（20ms 仅兜底）。修掉「发送帧率被钉在 ~50fps + 0~20ms 额外延迟」 |

### 11.4 预期收益

- 原 720p@30 机型：降到能跑 60 的尺寸后，`Camera delivered` 应该从 30 → 58~60，
  整条管线有机会到 60fps（代价是关键点 ROI 的源分辨率下降）。
- 720p 本来就能跑 60 的机型：仍然用 720p；预览限速把相机线程上最重的一段减半，
  帧时间抖动更小。
- 原生预览缓存：每帧省一次 1.38MB 的堆分配/释放 + 一次 ImageProcess 构建。
- WS 写线程唤醒：发送侧从 ~50fps 上限恢复到跟得上的 60fps，并去掉 0~20ms 抖动。
- perf 日志：不再每 60 帧卡关键词 worker 一次。

### 11.5 日志判读

新增日志（`adb logcat -s Camera2Manager PipelineFaceTracker`）：

```
✓ Selected 720p capture: 1280x720 (max 60.0 fps)
✓ Selected capture size: 640x480 (target 60fps)
⚠ 720p (1280x720) is advertised but this camera caps it at 30.0 fps (< 60); picking a 60fps-capable size instead (frame rate first)
Camera callback: avg 8.40 ms, max 21.30 ms over 120 frames (60fps budget = 16.67 ms)
Camera FPS self-check: 59.8 FPS ✓ (target 60)
⚠ Camera delivers only 30.0 FPS at 1280x720 (callback avg 18.20 ms, downshift 1/2) — switching to a smaller capture size
Adaptive capture downshift #1: 1280x720 → 640x480
⚠ SENSOR_FRAME_DURATION is 33.33 ms (30.0 fps) — the sensor/AE itself is not delivering 60 fps; a smaller capture size cannot help
⚠ Camera AE ranges top out at 30fps — this lens cannot deliver 60 fps at any size; the pipeline will follow the camera
```

- 先看 `Selected capture size`：如果选了 1280x720 却仍然 30fps，探针会在 ~3.3s 后判因/降档；
- `Camera FPS self-check` 是权威结论：`59.x ✓` = 采集侧没问题，接下来才该看推理；
- `Camera callback: avg … max …` 是**判因**的关键一行：avg ≤ 16.7ms 说明我们的处理没拖后腿，
  交付帧率低只能是传感器/AE；avg 明显 > 16.7ms 才是我们自己在相机线程上太慢；
- `Camera delivered: … (applied frameDuration=…, callback avg=…)` 同一行给出最终判定，
  后面还会跟一行 ⚠ 结论（传感器限制 / 回调拖慢 / 推理瓶颈）；
- 如果出现 `SENSOR_FRAME_DURATION is 33.33 ms` 或 `AE ranges top out at 30fps`，说明这台
  相机/镜头硬件就是 30fps，**任何软件优化都到不了 60**（换镜头 / 换尺寸都无用）。

### 11.6 判因模型

`ImageReader` 的 listener 在单线程 Handler 上串行，`acquireLatestImage()` 又只留最新帧，所以：

```
delivered_fps ≈ min(传感器实际帧率, 1000 / 单次回调耗时)
回调 16.7ms → 60fps      回调 33ms → 30fps      回调 50ms → 20fps
```

| 现象 | 结论 | 该做什么 |
|---|---|---|
| `SENSOR_FRAME_DURATION` ≈ 33.3ms | 传感器/AE 就是 30fps | 降尺寸没用；换镜头或接受 30fps |
| `SENSOR_FRAME_DURATION` ≈ 16.7ms，`callback avg` > 16.7ms | 我们在拖慢交付 | 砍相机线程的活（预览限速/移出线程） |
| 两者都正常但 `delivered` < 55 | HAL 带宽/热限 | 自适应降档会自己处理 |

### 11.7 内存代价（12GB 总内存 / 4GB 可用）

- 原生预览缓存：1.38MB（720p YUV 缓冲，thread_local，随相机线程存活）；
- 预览限速只减少分配，不新增缓冲；
- 降档探针：一个线程 + 几 KB 状态；`resolvedCaptureSizes` 每个相机一个 `Size`。
- 合计 **< 2MB**，与「4GB 可用」相比可以忽略；换句话说，这轮不是内存换帧率，
  而是把相机线程上白做的活砍掉。

### 11.8 已知未修（按风险排序，需真机验证后再动）

1. **YUV plane 生命周期（正确性）**：`onImageAvailable` 返回即 `image.close()`，但帧已经
   入队；`ImageReader` 只有 3 块 buffer，60fps 下 16.7~50ms 就被复用。检测通道排队的帧
   可能已被覆盖，关键点通道偶发撕裂。修法：帧池（6×1.38MB）+ 显式 memcpy（+0.3ms/帧）。
   收益是正确性不是帧率，且改动跨 `Camera2Manager` / `PipelineFaceTracker`，本轮未做。
2. **重排缺口 60ms**：永久丢一帧会让输出定格 ~67ms 再突发放行。直接改小超时会在 30fps
   采集（帧间隔 33ms）下误判缺口，必须改成「知道某帧已失败就 `markSkipped`」的方案。
3. ~~**上半身抽帧仍在相机线程上**~~ **已解耦**：`pushFrame` 现在在相机线程只把 Y/U/V
   按 stride 拷进池化 DirectByteBuffer（`YuvFrameRing`，3 槽，典型 0.15~0.4ms）并发布为
   「最新帧」，ROI 采样 → Bitmap（1~4ms）→ detectAsync 全部在 `upper-body-worker` 线程；
   队列只保留最新帧（慢了丢旧帧），相机线程不再出现 1~4ms 的转换峰值。
4. **预览移出相机线程 / 降预览分辨率**：720p 预览在相机线程上峰值 5.5~13.5ms；
   限速到 30fps 已把均摊减半，峰值仍需「独立预览线程」或
   「MNN 输出 BGRA + `Bitmap.copyPixelsFromBuffer()`」。
5. **双 ImageReader / GPU 预览**：相机同时输出到推理 ImageReader 与 SurfaceView，
   预览零 CPU 转换（改动最大，收益也最大）。

> 更细的证据、时序与补丁草案见 `docs/perf/audit-kotlin.md`（Kotlin 链路）与
> `docs/perf/audit-native.md`（原生 MNN 热路径）。

---

## 12. 第二轮：按 ROI 分支重排（2026-10）

这轮不改模型、不改协议，按「相机 720p 一次分发 → 128p 检测 / 720p 脸 ROI / 320p 上半身 ROI」
重排三条分支。设计、数学推导与静态验证记录在
[`docs/perf/round2-roi.md`](docs/perf/round2-roi.md)，这里只列改动与预期。

### 12.1 改动

| 层 | 改动 |
|---|---|
| 原生 landmark | 每帧不再整帧打包 1.38MB：用 `trans.mapXY` 把 256×256 输入的 4 角映射回 720p，取 bbox + 8px margin + 偶数对齐，只打包脸 ROI（典型 0.15–0.4MB）；ROI >85% 或边长 <64px 时退回整帧 |
| 原生检测/landmark | 新增 `tryFastYuvView`：连续 NV12/NV21 + 无 padding 时直接引用相机缓冲（零拷贝）；任何布局不匹配自动 repack |
| SDK | 检测框存入前做 **一级扩充 ×1.15**；新增 `onYuvFrameForBodyWithHint`，把最近脸框（TTL 500ms）传给上半身；旧 tap 保留 |
| 上半身 | `UpperBodyRoi.fromFace`：脸宽×4.5、脸高×5.5，顶部留 0.35×脸高；只采样 ROI 到 **320p**（原整帧 256）；33 点先回映整帧再 extract；ROI miss → 1s 整帧回退 |
| 文档 | `docs/pipeline.md` / `docs/upper-body.md` 同步新数据流；新增 `docs/perf/round2-roi.md` |

### 12.2 预期收益（静态估算，未实测）

- 原生 landmark 每帧少拷 1.0–1.2MB（≈ -0.15~0.30ms），检测命中零拷贝时每 100ms 少一次
  1.38MB 重排；p95 更容易压回 16.7ms。
- 上半身输入从「整帧 256」变成「ROI 320」：人的有效像素多 4–5 倍，远距离/坐姿关键点
  应更稳；代价是每 66ms 多约 0.5ms 转换（15fps 下 duty 仍很低）。
- 一级 + 二级扩充合计约 1.725×，大角度/快速转头时 landmark 顶点被裁的概率下降。

### 12.3 验证状态

**本机没有 JDK / Android SDK / 真机，未编译、未跑单测、未实测。** 已做的只有：
原生 ROI 矩阵/打包的独立 Python/Node 复算（3,456 组，覆盖率 0 越界）、上半身回映公式
复算、`UpperBodyRoiTest` 14 个用例静态核对。必须由 CI / 真机完成：

1. `:app:assembleDebug`、`:MediapipeUpperBody:testDebugUnitTest`；
2. 固定图「ROI 开/关」逐像素 diff；
3. 前置镜像 + 0°/90°/270° 下上半身骨架与画面一致；
4. logcat 看 `YUV ROI path: first hit` 的 ROI 占比、`ROI 引导生效` 的尺寸；
5. 上半身 ROI vs 整帧的 A/B（远端坐姿抖动）。

### 12.4 已知风险

- 原生 ROI 的 bbox/平移方向写反会采样错位；数学已复算，仍需真机固定图 diff。
- 上半身 ROI 依赖脸框坐标系（旋转后、未镜像）；脸框给错时靠 1s 整帧回退兜底。
- ~~上半身转换仍在相机线程同步执行~~ 已移到 `upper-body-worker`（相机线程只剩平面拷贝
  0.15~0.4ms）；ROI 限制了采样量，worker 上的转换峰值不再进 `callback max`。
- 本轮的 128p 检测分支**没有**新增 YUV→128p 预下采样：检测输入本来就是 128×128，
  MNN 只按输出像素采样，先下采样反而多读整帧（见 round2 文档 §4）。

---

## 13. 第三轮：Vulkan 从未真正启用（2026-10）

现象：**面捕帧率低、CPU 占用极高、GPU 显存明显空闲**。

### 13.1 根因（二进制级证据）

MNN 的 Vulkan 后端不在 `libMNN.so` 里，而在**独立插件** `libMNN_Vulkan.so` 里，靠它自己的
`.init_array` 调 `MNNInsertExtraRuntimeCreator` 完成注册。本工程：

- `CMakeLists.txt` 只链接 `MNN` + `mnn_Express`，**没有** `MNN_Vulkan`；
- Kotlin 侧只有 `System.loadLibrary("mediapipefacelandmark")`，**从没加载过插件**；
- `libMNN.so` 里 `Vulkan` / `libMNN_Vulkan` / `glsl_*` 字符串与符号全是 0，也没有任何
  `dlopen` 插件的路径 —— 它不会自己去加载。

于是 `createSession(MNN_FORWARD_VULKAN)` 找不到 creator，MNN 会打印
`Can't Find type=.. backend, use .. instead` 并**静默改用 CPU**、而且 `createSession` 仍返回成功。
因此 `nativeGetBackend()`（旧实现返回"请求值"）和日志里的 `MNN backend: VULKAN` **一直是假的**；
三个模型的每一帧都在 CPU 上跑。再叠加 `GPU_CHANNEL_DEFAULT = 2`（两路关键点引擎在 CPU 上抢核），
就得到「CPU 极高 + GPU 显存空闲」。

第二个与推理无关的大头：**手机预览**每帧在相机线程上做整帧 YUV→RGB（2.76 MB）+
92 万次 Kotlin 逐像素打包 + `Bitmap.setPixels`，估算 6~11 ms/帧且常开（30 fps），
把相机线程的 16.67 ms 预算吃掉一大半。

### 13.2 改动

| 层 | 改动 |
|---|---|
| 加载插件 | `MediaPipeFaceDetector` companion init 里**先** `System.loadLibrary("MNN_Vulkan")` 再加载 JNI 库；缺失时只告警、退回 CPU |
| 真实后端断言 | 新增 `include/common/mnn_session_util.h`：`applyScheduleConfig()`（Vulkan 只写 `mode = MNN_GPU_TUNING_WIDE`，不再把 `numThread` 写进 union）+ `sessionVulkanActive()`（`getSessionInfo(BACKENDS)` 扫 4 个 int） |
| 三个模型 | `setSessionMode(Session_Release)` → `createSession` → 探测真实后端 → 每个模型一行 `MNN backend[xxx]: request=.. actual=..`；`runSession` 检查 `ErrorCode` |
| JNI / SDK | `nativeGetBackend` 返回真实值；`nativeInit` / `nativeSetBackend` / `nativeInitEngine` 以真实后端判定；只有实际在 Vulkan 上才开额外引擎（`PipelineFaceTracker`） |
| 预览 | 新 native `convertYuvToBgraRotated()`（旋转 + 1/2 降采样 + YUV→BGRA，坐标表复用）；`CameraPreviewView` 只做一次 `Bitmap.copyPixelsFromBuffer()`，删除逐像素打包；叠加层 478 项 LUT + 文本 10 Hz 缓存 + 重绘 30 Hz 节流 |
| 减法 | 关掉**从未被调用**的原生 blendshape 模型加载；首帧 PPM dump 只在 debug 包开；单路时跳过重排阶段；关键点/检测回读条件化；原生新增 `Pack/Upload/Readback` 三段计时 |

### 13.3 验证状态

**本机没有 JDK / Android SDK / NDK / 真机，未编译、未跑单测、未实测。** 全部改动只做了
静态审查（括号/包含/JNI 符号名逐字核对；BGRA 旋转映射用 Python 与旧实现逐像素比对；
logit 剪枝 20 万次随机试验 0 假阴性）。完整验证步骤见
[`docs/perf/round3.md`](docs/perf/round3.md) §4，最短路径：

1. `./gradlew :MediapipeFaceLandmark:assembleDebug :app:assembleDebug`；
2. logcat 里确认三行 `MNN backend[...]: actual=Vulkan`（或明确的 `NOT Vulkan` + 回退日志）；
3. 对比 `Camera callback: avg .. ms`（预期 6~11 → 1~2.5）与 `Landmark Detection`（唯一能证明
   Vulkan 生效的数字）；
4. 目视回归：0°/90°/270° 预览方向、镜像、颜色（BGRA 不得红蓝互换）。预览固定 1/2 降采样，
   比改前软是**预期行为**。

三份只读审计：[`docs/perf/round3-gpu.md`](docs/perf/round3-gpu.md)、
[`docs/perf/round3-native.md`](docs/perf/round3-native.md)、
[`docs/perf/round3-kotlin.md`](docs/perf/round3-kotlin.md)；本轮实现说明见
[`docs/perf/round3.md`](docs/perf/round3.md)。
