# Kotlin 层性能审计：相机 → 推理 → 重排 → 后处理 → 派发 → JSON

- 分支：`feat/mediapipe-upper-body`，HEAD = `8ee65b2`。
- 范围：**只读**审计 Kotlin 生产代码；原生 C++ 层见另一份 `docs/perf/audit-native.md`。
- 环境：无 JDK / Android SDK / 真机 / adb。**本报告所有 ms、MB 都是静态推导的估算**，
  每条都给出推导依据；没有一条来自实测。需要实测才能定论的，统一列在第 6 节。
- 与上一轮（[PERFORMANCE.md](../../PERFORMANCE.md)）的关系：那份文档已解决「每帧检测」「每帧协程派发」
  「整帧 `Bitmap.copy`」「AE 把传感器按在 30fps」「校准累积器」等问题，**本报告不重复**，
  只记录它没覆盖的、以及它改造后新暴露的开销。

### ⚠️ 审计基线（重要，请先读）

本次审计期间，`src/` 下**有并发编辑正在进行**（不是我做的，我只写了本文件）：

| 文件 | `git status` | 最后修改 | 说明 |
|---|---|---|---|
| `MediapipeFaceLandmark/.../Camera2Manager.kt` | **M**（未提交） | 02:58:15 → 03:01:01 仍在编辑（1538 → 1814 行） | 新增：预览 30fps 限速（`PREVIEW_INTERVAL_NS`）、采集帧率自检 + 自动降档（`scheduleFpsProbe` / `restartAtSmallerSize`）、`captureLifecycleLock` |
| `MediapipeFaceLandmark/src/main/cpp/mnn_yuv_converter.cpp` | **M** | 02:58:15 | 原生侧，不在本报告范围 |

因此：
1. 本报告的 `Camera2Manager.kt` 行号是 **03:00 快照（1814 行）**；该文件在审计期间一直小幅增删
   （1538 → 1815 → 1814），行号可能漂移 **±几行** —— **请以函数名为准**（每条证据里都写了函数名）。
   其余文件与 HEAD 一致、行号稳定。
2. 该文件里新加的「预览 30fps 限速」正好命中本报告 P0-2 的第一条建议 ——
   **已在工作区实现**，我在 P0-2 里把它标为「已修」，只保留尚未覆盖的部分。
3. 新增的「帧率自检 + 自动降档」代码本身有并发风险，已作为 **P0-6** 纳入审计（它是本报告唯一
   针对「新代码」的发现）。

---

## 1. 端到端链路时序表

一帧 720p 的完整路径（`useDirectYuvMode = true`、`activeChannelCount = 2`、预览开启 = App 前台默认状态）。
「均摊」= 按 30 fps 预览门控折算到每帧。

| # | 阶段 | 线程 | 估算 ms/帧 | 每帧拷贝 / 分配 | 证据（file:line） |
|---|---|---|---|---|---|
| 1 | `acquireLatestImage` + 交付帧率统计 | `CameraBackground`(HandlerThread，**默认优先级 0**) | 0.01–0.03 | 1 个 `Image`；0 分配 | `Camera2Manager.kt:1350`, `:1353`, `:1381-1396` |
| 2 | `processYuvDirect`：取 3 个 plane + 旋转角 + 组 `YuvFrameData` | 同上 | 0.05–0.25（`getRotationDegrees()` 若走 DisplayManager IPC 占大头） | 1 `Pair` + 1 `YuvFrameData` ≈0.2 KB；**YUV 像素 0 拷贝** | `Camera2Manager.kt:1540-1573`, `:1551-1554`, `:1557-1569`, `:1621-1638` |
| 3 | 入两条通道 `trySend(data)` + `trySend(data.copy())` | 同上 | <0.01 | 1 个 `YuvFrameDataInternal`（~40 B）；**两通道共享同一组 plane 引用，无像素拷贝** | `PipelineFaceTracker.kt:546-548` |
| 4 | 预览 YUV→RGB（**02:58 起 30 fps 门控**） | 同上 | 转换帧 2.0–5.0；**均摊 1.0–2.5** | 0 分配（`reusableRgbBuffer` 复用），写 2.7 MB | `Camera2Manager.kt:1363-1365`, `:1406-1413`, `:1604-1608`, `:1647-1667`；门控常量 `:209` |
| 5 | 预览 RGB→ARGB（Kotlin 循环）+ `setPixels` | 同上 | 转换帧 3.3–8.0；**均摊 1.65–4.0** | 0 分配（`IntArray`/`ByteArray` 复用）；memcpy 2.7 MB + 3.7 MB | `CameraPreviewView.kt:253-328`, `:276-291`, `:318` |
| 6 | `image.close()`（**回调返回即关闭**） | 同上 | ~0 | — | `Camera2Manager.kt:1370-1372` |
| 7 | 人脸检测（10 Hz 节流，稳定态 1/6 帧跑一次） | `fc-face-detect`(prio 7) | 0.53 均摊（单次 3.2） | 可忽略 | `PipelineFaceTracker.kt:978-1020`, `:990-996` |
| 8 | 关键点推理（2 路并行，单帧 14.5 ms） | `fc-face-landmark-gpu0/1`(prio 7) | 14.5 延迟 / ≈7.3 均摊 | 478 `Point3D` + `ArrayList` ≈17 KB + JNI `FloatArray(1434)` 5.7 KB | `PipelineFaceTracker.kt:1044-1126`, `MediaPipeFaceDetector.kt:1011-1056` |
| 9 | `estimateHeadPose`（关键点 worker 内同步） | 同上 | 0.05–0.2 | `FloatArray(1434)` 5.7 KB + `Triple` | `MediaPipeFaceDetector.kt:1118-1140`，调用点 `PipelineFaceTracker.kt:1090-1092` |
| 10 | 重排（TreeMap 按 frameId 排序） | `fc-face-landmark-merge`(prio 7) | 0.02–0.05 顺流；**缺口时阻塞 60–67** | 1 TreeMap 节点 + `ArrayList` | `PipelineFaceTracker.kt:1134-1162`；`FrameReorderBuffer.kt:58-133` |
| 11 | 后处理（BlendShape / 姿态 / 眨眼） | `fc-face-process`(prio 7) | **5.2**（BlendShape 4.1） | ≈509 对象/帧 | `PipelineFaceTracker.kt:1164-1258`, `:1184-1187`；`FaceCapturePipeline.kt:84-152` |
| 12 | 派发（4 回调 + 检测框 + SDK 快照） | `fc-face-output`(prio 7) | 0.02–0.05 | 1 `SdkFaceCaptureFrame` + 478 次遍历 | `PipelineFaceTracker.kt:1290-1338`, `:1295-1301` |
| 13 | JSON 序列化（`onFaceCaptureResult` → `submitTrack` → `buildTrack`） | 同 #12（**串在派发线程**） | 0.03–0.08（无上半身）/ 0.15–0.30（带 `ub`） | `StringBuilder(640~1024)` + `String` ≈0.6–1.3 KB | `MainActivity.kt:261-269`, `TrackingServer.kt:318-369`, `TrackingProtocol.kt:231-290` |
| 14 | 入 0.5 s 发送帧缓冲（`ReentrantLock` + `signalAll`） | 同 #12 | 0.01–0.03 | `Entry` | `TrackingServer.kt:380-394`；`TrackingProtocol.kt:57` |
| 15 | 放行线程（0.5 s 缓冲匀速出队） | `tracking-playout` | ≈0（每帧一次 `awaitNanos`） | 0 | `TrackingServer.kt:402-438` |
| 16 | WS 写线程：`toByteArray` + `write` + `flush` | `tracking-ws-writer` | 0.1–1.0（阻塞写 socket；**受 20 ms 轮询上限约束，见 P0-5**） | 0.6–1.3 KB `ByteArray` | `TrackingServer.kt:941-977`, `:915-939`, `:962` |
| 17 | 预览绘制（`drawBitmap` 上传 3.7 MB 纹理） | UI/render 线程 | 0.5–1.5（30 fps 后减半） | 0（Bitmap 双缓冲） | `CameraPreviewView.kt:397-434` |

**串行关键路径**（单帧延迟，不含 0.5 s playout）：
`相机回调 → 队列 0–2 帧 → 关键点 14.5 → 重排 0.03 → 后处理 5.2 → 派发+JSON 0.2 → socket 0.5`
≈ **22–56 ms + 500 ms 固定缓冲**。

**吞吐**：2 路关键点 ≈138 帧/s 上限、后处理 5.2 ms ≈192 帧/s 上限 ——
**Kotlin 推理/后处理阶段不是 60 fps 的瓶颈**。真正的风险只有三处：
（a）相机回调线程 #1–#5：**均摊 3.0–7.0 ms / 16.7 ms，但含转换的帧峰值 5.5–13.5 ms**，
且该线程优先级**低于**所有 `fc-*` 线程；
（b）重排缺口 #10 的 60 ms 停顿；
（c）WS 写线程 #16 的 50 fps 天花板。

> 估算依据：#4 按 NEON YUV420→RGB888 ≈200–450 Mpx/s（含 2.7 MB 写带宽）；
> #5 按 ART AOT 下「3 次 byte load + 位运算 + int store + 边界检查」≈3–6.5 ns/px；
> #8 的 14.5 ms、#11 的 5.2 ms 直接引用上一轮真机日志（`PERFORMANCE.md:210-213`）；
> #13 按 55（或 263）个浮点 × `Math.round` + `StringBuilder.append` ≈0.5 µs/值。

---

## 2. 问题清单（按 风险 × 收益 排序）

### P0-1　`YuvFrameData` 持有的 plane 在回调返回时就被 `close()`，而帧已经入队

- **现象**：相机回调把 `image.planes[i].buffer` 直接放进 `YuvFrameData` 后 `trySend` 到
  `yuvFrameChannel` / `yuvLandmarkFrameChannel`，回调末尾 `finally { image.close() }`；
  消费这两条通道的 `fc-face-detect` / `fc-face-landmark-gpu*` 是**在另一个时刻**才读这些 ByteBuffer。
  `ImageReader` maxImages=3、60 fps ⇒ 被 close 的这块 buffer **16.7–50 ms 后**就会被采集端原地复用。
- **证据**：`Camera2Manager.kt:1557-1569`（Buffer 直接引用）、`:1572`（同步 invoke）、
  `:1370-1372`（回调返回即 close）、`:688-695`（maxImages=3）；
  `PipelineFaceTracker.kt:546-548`（`data.copy()` 是 data class 浅拷贝，共享同一组 Buffer）、
  `:1000-1004` / `:1068-1072`（在别的线程拿这些 Buffer 调 JNI）。
- **后果分级**（无真机，无法定级到具体机型）：
  1. 检测通道（容量 2、10 Hz 消费）里排队的帧几乎**必然**已被覆盖 —— 检测读到的其实是 1–2 帧
     之后的画面。对「找脸框」影响小（所以现在没炸），但它是错的输入；
  2. 关键点通道排队 1–2 帧时读到「半新半旧」的撕裂帧 → 该帧关键点整体错误 → 表现为偶发一次
     表情/头姿跳变（下一帧又正常，极易被误判成平滑器问题）；
  3. 若某 HAL 在 `close()` 时 unmap 该 gralloc 映射，JNI 侧就是 use-after-free（SIGSEGV）。
     同一个模型在 `stop()` 的注释里已经写明（`PipelineFaceTracker.kt:613-617`），
     只是**运行中的每帧路径没有对应保护**。
- **建议改法**（推荐 A）：
  - **A. 帧池 + 显式拷贝**：相机回调里把 Y/UV 拷进池化 direct buffer 再入队；池大小
    = `max(检测容量, 关键点容量) + 并行度 + 1` = 6 块，**8.3 MB** 常驻（720p 每帧 1.38 MB）。
    拷贝 ≈0.15–0.4 ms/帧（1.38 MB memcpy，LPDDR4x 10–20 GB/s），换来彻底的生命周期正确。
  - **B. Image 交接 + 引用计数**：把 `Image` 本身入队，最后一位消费者负责 `close()`；
    `Channel(onUndeliveredElement=…)` 可兜住 `DROP_OLDEST` 丢弃，但 N 路 worker 的引用计数、
    丢帧路径、`stop()` 排空都要改，复杂度明显高于 A，收益只是省 0.3 ms + 8 MB。
- **风险**：A 低；B 中。
- **预期收益**：消除偶发单帧关键点错误与潜在 native 崩溃；**帧率收益 ≈0**。这是正确性缺陷，
  建议在调手感之前先修。

### P0-2　前台常开预览在相机线程上做两次整帧转换 —— 30 fps 门控**已在工作区实现**，仍需降分辨率

- **现象**：`MainActivity` 用 `DisposableEffect(Unit)` 无条件开启预览
  （`MainActivity.kt:476-481`），所以前台的每一帧都走
  `maybeConvertPreviewFrame` → YUV→RGB（写 2.7 MB）→ Kotlin RGB→ARGB（0.92 M 次循环）
  → `Bitmap.setPixels`（3.7 MB）→ `postInvalidate()`，**全部在相机回调线程上**，
  而该线程优先级（默认 0）**低于** `fc-*` 的 `NORM_PRIORITY+2`。
- **证据**：`MainActivity.kt:476-481`；`PipelineFaceTracker.kt:734-738`；`Camera2Manager.kt:1363-1365`
  （预览分支）、`:1406-1413`（30 fps 门控，**02:58 新增**）、`:1604-1608`、`:1647-1667`；
  `CameraPreviewView.kt:262`, `:276-291`, `:318`, `:327`；
  线程优先级：`Camera2Manager.kt:582-584`（未设置）vs `PipelineFaceTracker.kt:194-199`（+2）。
- **现状判定**：30 fps 门控（`PREVIEW_INTERVAL_NS = 33 ms`，`Camera2Manager.kt:209`）把这项开销
  **减半**（均摊 3.0–7.0 ms/帧），方向正确。但**峰值仍在 5.5–13.5 ms**（每 2 帧一次），
  叠加 #1–#3 的 0.1–0.3 ms 后，在 16.7 ms 预算里余量只有 3–8 ms；
  一旦被 2 路推理线程抢占，仍会漏帧。
- **建议改法**（在已实现的门控之上再加两条）：
  1. **预览按 640×360 生成**：`MNNYuvConverter.convertYuvToRgb` 本来就接受 `dstWidth/dstHeight`
     （`MNNYuvConverter.kt:58-91`），给 `yuv420ToRgbRotated` 加 `previewScale` 参数（预览传 2），
     YUV→RGB 与 RGB→ARGB **同时降到 1/4**；`reusableRgbBuffer` 按两档尺寸分别缓存。
  2. **相机线程显式提优先级**：`HandlerThread("CameraBackground", Process.THREAD_PRIORITY_DISPLAY)`
     （`Camera2Manager.kt:582-584`）。采集线程的 deadline 是 16.7 ms，理应高于推理线程。
- **风险**：低（只影响监视画面，PC 渲染拿的是 JSON，完全不受影响）。
- **预期收益**：峰值 5.5–13.5 → **≈1.5–4.0 ms**；相机线程余量回到 9–14 ms；
  预览内存 −12 MB（见 §5）。若只做 2（不加缩放），收益是「不再被推理线程抢占」，约等于消灭
  偶发的 33 ms 漏帧。

### P0-3　`logPerfStats()` 在 `synchronized(perfLock)` 里执行（11 条 `Log.i` + 十几次 `String.format`）

- **现象**：处理阶段每 60 帧（≈1 s）在**持锁**状态下打 11 条日志并做 `FloatArray` 复制 + 排序；
  同一把 `perfLock` 被检测线程、2 个关键点 worker、处理线程、派发线程**每帧各抢 1–2 次**
  （稳定态 ≈300 次加锁/s）。日志一慢，两个关键点 worker 被顶住 → 丢一拍 → 触发 P0-4 的 60 ms 停顿。
- **证据**：`PipelineFaceTracker.kt:1243-1251`（持锁调用 `logPerfStats`）、`:1344-1400`（日志体）、
  `:130`（锁）、`:993` / `:1009-1013` / `:1115-1119` / `:1192-1196` / `:1274-1281`（每帧加锁点）、
  `:103-104`（`ENABLE_PERF_PROFILING` 是 `const`，release 也照跑）。
- **建议改法**：（a）锁内只 `add` + 计数，到点做一次浅拷贝快照，**日志/排序搬到锁外**；
  （b）计数器换 `AtomicInteger`/`LongAdder`，`PerfStats` 每线程各自累加、1 s 汇总；
  （c）`ENABLE_PERF_PROFILING` 改 `@Volatile var`，release 默认关。
- **风险**：低。**预期收益**：消除每 60 帧一次的 **1–20 ms 全局锁持有**（估算：logd 背压时单条
  `Log.i` 0.1–2 ms）；锁竞争 ≈300 → ≈60 次/s。对 **p95/max 帧间隔**的改善远大于平均值。

### P0-4　重排缺口固定等 60 ms：丢一个 frameId ⇒ 停 67 ms + 一次 5 帧突发

- **现象**：`FrameReorderBuffer` 遇到 `frameId` 缺口最多等 60 ms 才跳过；缺口极易产生：
  worker 只要**没产出结果**（`null` 检测 / `lastFaceBox == null` / 抛异常）或
  `yuvLandmarkFrameChannel`（容量 3）`DROP_OLDEST` 丢一帧，`frameId` 就永久缺一号。
  60 fps 下推演一次缺口（缺 5 号帧）：

  ```
  T+0    收到 6（expected 5）→ gapStart=T，break
  T+16.7 收到 7 → 16.7 < 60，继续等
  T+33.4 收到 8 → 继续等
  T+50.1 收到 9 → 继续等
  T+66.8 收到 10 → 超时 → 一次性放行 6,7,8,9,10
  ```

  ⇒ 下游看到「**67 ms 空档 + 5 帧挤在 ~1 ms**」。后处理每条 5.2 ms，这 5 帧会挤爆
  `outputChannel`（容量 1 + `DROP_OLDEST`），实际只派发 1–2 帧。
  PC 端表现：**画面定住约 70 ms 然后跳一下**。
- **证据**：`FrameReorderBuffer.kt:106-124`、`PipelineFaceTracker.kt:1141-1157`、
  `:68-69`（`REORDER_GAP_TIMEOUT_MS = 60`）、`:66`（`REORDER_PENDING_CAPACITY = 8`）、
  缺口来源：`:1062-1065`（box==null 直接 `continue`）、`:1073`（`landmarkResult == null` 不 send）、
  `:1120-1122`（异常吞掉不标记）、`:548`（通道 `DROP_OLDEST`）、`FrameReorderBuffer.kt:60-61`。
- **建议改法**：
  1. `FrameReorderBuffer` 加 `fun markSkipped(frameId)`（把 `lastEmittedId` 推到 `frameId-1`）；
     worker 在**所有没产出的分支**调用它 —— 但必须**经 `landmarkMergeChannel` 投递**
     （一个 `sealed class MergeItem { Data, Skip }`），**不能跨线程直接改 TreeMap**；
  2. 再加一层兜底：worker 暴露「在飞的最小 frameId」，若 `pendingFirst > minInFlight`
     就立刻判定缺口补不上；
  3. `REORDER_GAP_TIMEOUT_MS` 60 → 33（≈2 个帧间隔）。
- **风险**：中低（改帧序推进逻辑；补 `FrameReorderBufferTest` 用例：
  `offer(6)` → `markSkipped(5)` → `offer(7)` 应连续放行 6、7）。
- **预期收益**：每次掉帧的 **~67 ms 输出停顿归零**；`Frame interval p95` 不再被缺口支配。

### P0-5　WS 写线程 20 ms 空闲轮询 → 发送帧率天花板 50 fps

- **现象**：`writeLoop` 在「控制队列/心跳/数据槽位都空」时 `controlQueue.poll(20ms)` 阻塞等待，
  而 `offerTrack()` 只是 `pendingTrack.set(message)`，**不唤醒**这个 `poll`。
  playout 每 ≈16.7 ms 投一帧，写线程每 20 ms 才醒一次、每次只发最新一帧
  ⇒ 实际发送上限 **50 帧/s**，每帧额外延迟 0–20 ms，被覆盖的那 1/6 帧在 `seq` 上留空洞。
  这解释了「推理已达标、PC 却只有 ~50 fps」。
- **证据**：`TrackingServer.kt:887-891`、`:941-969`（`poll(20L)`）、`:868`、`:433`、`:915-939`。
- **建议改法**：给写线程真正的唤醒源 —— `Semaphore` 或 `synchronized/wait/notifyAll`：
  `offerTrack` 里 signal，写线程「非阻塞取三个槽位 → 都空就 `await(≤20ms)`」；`pendingPing` 一并纳入。
- **风险**：低（纯发送侧，不动协议）。**预期收益**：发送上限 50 → ≥60 fps，去掉 0–20 ms 额外延迟。

### P0-6　【新代码】自动降档的 `stopCameraInternal()` 先关 `ImageReader` 再 join 相机线程 → 回调可能操作已关闭的 reader

- **现象**（02:58 新增代码）：`restartAtSmallerSize` 在 `captureLifecycleLock` 内调用
  `stopCameraInternal()`，后者顺序是
  `captureSession.close()` → `cameraDevice.close()` → **`imageReader.close()`** → `stopBackgroundThread()`（`quitSafely` + `join`）。
  而 `onImageAvailableListener` 跑在 `backgroundHandler` 上，此刻可能正在
  `acquireLatestImage()` / `processYuvDirect()` / `finally { image.close() }`。
  先关 reader 再 join，意味着**在途回调会操作一个已关闭的 ImageReader**（`image.close()`
  可能抛 `IllegalStateException`）。回调里没有 try/catch，异常会冒到 HandlerThread 顶层 →
  该线程死亡 → 之后不再有新帧，只能等下次 start 才能恢复。
- **证据**：`Camera2Manager.kt:561-580`（stop 顺序）、`:1350-1373`（回调无兜底 catch）、
  `:1494-1525`（`restartAtSmallerSize`）、`:1426-1431` / `:1433-1476`（探针）。
- **建议改法**：把顺序改成「先让相机线程停下来，再关资源」：
  `captureSession.close()` → `stopBackgroundThread()`（`quitSafely` 会先跑完在途消息，再 `join`）
  → `imageReader.close()` → `cameraDevice.close()`；并给整段回调包一层
  `try/catch (t: Throwable) { Log.e(...) }`（现在只有 `finally`）。
- **风险**：低（纯顺序 + 兜底）。**预期收益**：消除「自动降档那一瞬间偶发帧源永久中断」的隐患。
  **这条本身不产生帧率收益，但它是新增代码里唯一可能把 60 fps 变 0 的地方。**

### P1-1　`onFaceCaptureResult` 让派发阶段每帧多做一趟 478 点遍历（调用方其实不需要）

- **现象**：App 只订阅 `onFaceCaptureResult` 来拿 BlendShape/姿态/presence，但这条通路强制
  `calculateBoundingBox(frame.landmarks)`（478 次比较）+ 构造 `SdkFaceCaptureFrame`。
- **证据**：`MainActivity.kt:261-269`；`PipelineFaceTracker.kt:1295-1301`, `:1411-1434`。
- **建议改法**：改订阅 `onFaceCaptureFrame`（`PipelineFaceTracker.kt:320`）并补
  `presenceScore / isCalibrating / fps` 三个字段（或新增 `onTrackingFrame`）。
- **风险**：低（改回调契约，需同步 SDK facade）。**收益**：−0.02 ms/帧、−1 object/帧，**顺手清理**。

### P1-2　`estimateHeadPose` 每帧新建 `FloatArray(1434)` 并把 478 点重新打包

- **证据**：`MediaPipeFaceDetector.kt:1029-1055`（native 数组 → 478 `Point3D`）、
  `:1118-1140`（`List` → 新 `FloatArray`）、`PipelineFaceTracker.kt:1090-1092`。
- **建议改法**：worker 线程持有 `threadLocal FloatArray(478*3)`，让 native 多暴露一个
  「直接写进调用方数组」的重载；或直接用已存在的 `nativeGetLastHeadPose()`
  （`MediaPipeFaceDetector.kt:1218`）省掉往返。
- **风险**：中（动 JNI 签名；需确认 `nativeGetLastHeadPose` 按引擎槽位隔离）。
- **收益**：−5.7 KB/帧、−2 趟 478 遍历（≈0.05–0.1 ms，在并行路径上）。

### P1-3　相机线程 `getRotationDegrees()` 每帧读 `windowManager.defaultDisplay`

- **现象**：`windowManager` 已缓存（上一轮改的），但 `defaultDisplay.rotation` 在部分 ROM 上会走
  `DisplayManagerGlobal` 的 binder 查询；旋转是低频事件，不需要每帧查。
- **证据**：`Camera2Manager.kt:1621-1638`、`:163-165`。
- **建议改法**：缓存 `lastRotation` + 每 200 ms 或 `onConfigurationChanged` 时刷新；
  或注册 `DisplayManager.DisplayListener` 主动失效。
- **风险**：低。**收益**：0.05–0.2 ms/帧（若该 ROM 走 IPC；否则 ≈0）。

### P1-4　`MAX_FRAME_AGE_MS = 300` 对 60 fps 面捕过于宽松

- **证据**：`PipelineFaceTracker.kt:96-100`, `:1171-1175`；`TrackingProtocol.kt:57`（`FRAME_BUFFER_MS = 500`）。
- **建议改法**：下调到 100–150 ms（约 6–9 帧）。
- **风险**：低（只影响极端卡顿时的取舍；PC 侧时间轴插值会把它变成「动作变慢」而非跳帧）。
- **收益**：极端情况下端到端延迟上限 ≈800 → ≈650 ms；正常帧率无影响。

### P1-5　多路并行默认 2 路：在本仓库的推理耗时下收益存疑

- **现象**：单帧关键点 14.5 ms × 2 路 ≈138 帧/s 吞吐，而目标只有 60 fps。
  当前瓶颈不在关键点吞吐，多开一路的代价是「多一份 landmark 模型 + 一个 Vulkan memory pool」，
  收益取决于 GPU 是否真能并发（很多移动 GPU 的 Vulkan 队列是串行的）。
- **证据**：`PipelineFaceTracker.kt:80`（`GPU_CHANNEL_DEFAULT = 2`）、`:838-847`、`:1030-1042`、
  `:179-182`、`:485`。
- **建议改法**：把它变成真机 A/B 项：HUD 并列显示 1 路/2 路的 `Landmark p95` 与
  `Frame interval p95`，2 路更差就默认回 1 路。
- **风险**：低（已有 `setGpuChannelCount`，`PipelineFaceTracker.kt:812-832`）。
- **收益**：不确定；1 路可省 30–80 MB + 1 条线程（§5）。

### P2-1　后处理仍是每帧分配大头（上一轮已记录，这里只更新量化）

`FaceLandmarkIndices.extractKeyPoints` 在归一化坐标下 `landmarks.map { Point3D(x*w, y*h, z) }`
= **478 个新对象/帧**（`FaceLandmarkIndices.kt:281-292`，由 `FaceCapturePipeline.kt:96` 每帧调用）。
60 fps ≈2.9 万对象/s、约 0.9 MB/s（`Point3D` 3×float，估算 32 B → 15 KB/帧）。4 GB 可用内存下
这点分配**不会**造成 GC 卡顿（ART 年轻代吞吐 >100 MB/s），所以它是「可优化项」而非「掉帧原因」，
与 `PERFORMANCE.md:273-278` 一致，不重复展开。

### P2-2　`pipelineScope` 建在 `Dispatchers.Default` 上，但每个 stage 都自带 dispatcher（无实际开销，记录以免误改）

`PipelineFaceTracker.kt:273`；`start*Stage()` 的每个 `launch` 都显式传了
`ExecutorCoroutineDispatcher`，不会落到共享池。**不需要改**。

---

## 3. Top 5 可直接落地的补丁草案

> 只描述改法，不改源码（约束：`src/` 只读）。

### 补丁 1（收益最大）：预览再降一档 —— 640×360 + 相机线程提优先级

```text
文件：MediapipeFaceLandmark/.../Camera2Manager.kt
A) 预览按 1/2 分辨率生成（30fps 门控已存在，保留）
   - yuv420ToRgbRotated 增加参数 previewScale: Int = 1（预览调用传 2）
   - dstWidth  = rotatedWidth  / previewScale
   - dstHeight = rotatedHeight / previewScale
   - reusableRgbBuffer 按 (fullSize, previewSize) 分别缓存两块，避免两档互相顶掉
   - MNNYuvConverter.convertYuvToRgb(..., dstWidth, dstHeight) 已支持任意 dst（:58-91）
   - 同步改 CameraPreviewView：尺寸变化时按新尺寸重建 Bitmap（已有该逻辑，:296-302），
     pixelBuffer/rgbBytesBuffer 也随之缩小（-12 MB）
B) 相机线程优先级
   - startBackgroundThread（:582-584）：
     HandlerThread("CameraBackground", Process.THREAD_PRIORITY_DISPLAY)
```

**收益**：转换帧 5.5–13.5 → ≈1.5–4.0 ms；相机线程余量回到 9–14 ms；预览内存 −12 MB。
**风险**：低。**验证**：`Camera delivered` 是否更稳、`Frame interval max` 是否下降。

### 补丁 2（正确性）：YUV 帧池，杜绝「close 后仍被读」

```text
文件：MediapipeFaceLandmark/.../Camera2Manager.kt + facecapture-sdk/.../PipelineFaceTracker.kt
1) Camera2Manager 内新增私有池：
   class YuvBufferPool(capacity, width, height)   // 每块 = Y + UV，约 1.38 MB @720p
   Holder { y: ByteBuffer, uv: ByteBuffer, yRowStride, uvRowStride, uvPixelStride }
2) processYuvDirect 改为「先拷再发」：
   val h = pool.acquire() ?: return          // 池耗尽：丢这一帧，绝不阻塞相机线程
   按 rowStride 逐行把 Y/UV memcpy 进 h；h.rowStride 改写为 width/uvRowStride，
   让 JNI 侧的 stride 语义与拷贝后的紧密布局一致
3) YuvFrameData 增加 release: (() -> Unit)?（或让内部类持有 holder）：
   - 检测阶段消费点：PipelineFaceTracker.kt:982-1017
   - 关键点 worker 消费点：:1058-1123
   - 每个出口（continue / return / catch）都要 release
   - newYuvChannel: Channel(..., onUndeliveredElement = { it.release() }) 兜住 DROP_OLDEST
4) 池容量 = max(2, landmarkQueueCapacity) + activeChannelCount + 1（默认 2 路 → 6 块 → 8.3 MB）
```

**收益**：消除撕裂帧输入与潜在 UAF；帧率 ≈0。**风险**：中低（+8.3 MB、+0.15–0.4 ms/帧；
必须补「release 不漏」的断言）。

### 补丁 3：perf 统计去锁 —— 锁内只累加，日志搬出去

```text
文件：facecapture-sdk/.../PipelineFaceTracker.kt
1) PerfStats / 计数器改为每线程本地（ThreadLocal）或原子累加
2) synchronized(perfLock) 只保留「perfFrameCount++ 并判断是否到 60」
3) 到点时锁内做一次浅拷贝快照（约 10 个标量），锁外调用 logPerfStats(snapshot)
4) logPerfStats(s: PerfSnapshot) 不再读任何共享字段；frameJitterStats() 也在锁外算
5) ENABLE_PERF_PROFILING: const val -> @Volatile var（release 默认 false）
```

**收益**：消除每 60 帧 1–20 ms 锁持有；锁竞争 ≈300 → ≈60 次/s。**风险**：低。

### 补丁 4：重排缺口快速跳过（消灭 67 ms 停顿）

```text
文件：facecapture-sdk/.../FrameReorderBuffer.kt + PipelineFaceTracker.kt
1) FrameReorderBuffer 新增：
   fun markSkipped(frameId: Long) {
       if (lastEmittedId == Long.MIN_VALUE) { lastEmittedId = frameId - 1; return }
       if (frameId - 1 > lastEmittedId) lastEmittedId = frameId - 1
       gapStartMs = 0L
   }
2) 关键点 worker 在所有「这帧没结果」的出口投递一个 Skip 项（不能跨线程直接改 TreeMap）：
   - box == null（:1062-1065）
   - landmarkResult == null / landmarks 为空（:1073）
   - catch (Exception)（:1120-1122）
   把 landmarkMergeChannel 的元素改成 sealed class MergeItem { Data(LandmarkData), Skip(Long) }
3) REORDER_GAP_TIMEOUT_MS 60 -> 33（:68-69）
4) FrameReorderBufferTest 补三条：markSkipped 后连续放行 / 首帧前 markSkipped / skip×timeout 交叉
```

**收益**：掉帧的 67 ms 停顿归零。**风险**：中低（并发归属必须走通道）。

### 补丁 5：WS 写线程唤醒 —— 去掉 50 fps 天花板

```text
文件：app/.../tracking/TrackingServer.kt
1) private val trackSignals = Semaphore(0)
2) offerTrack（:887-891）：pendingTrack.set(message); trackSignals.release()
3) writeLoop（:941-969）：
   非阻塞取 control/ping/pong/track；都空时 if (!trackSignals.tryAcquire(20, MILLISECONDS)) continue
   取到信号后立刻回循环顶部取 pendingTrack
4) pendingPing 也走同一信号
```

**收益**：发送上限 50 → ≥60 fps；额外延迟 0–20 ms → ≈0；`seq` 连续。**风险**：低。

---

## 4. 重点问题逐条回答

### 4.1　720p 采集 vs 480p 采集：相机线程与预览转换上的真实成本

先拆清「像素量到底在哪些环节被摸到」：

| 环节 | 随像素线性？ | 720p(1280×720=0.92 Mpx) vs 480p(640×480=0.31 Mpx，2.25×) |
|---|---|---|
| Camera2 采集 / ISP 带宽 | 是 | 2.25×（HAL 侧，不在 App 进程；决定「能否 60 fps」的第一因素） |
| `processYuvDirect`（Kotlin） | **否**（只传 plane 指针） | ≈1×，与分辨率无关 |
| 预览 YUV→RGB（native，写 RGB888） | 是 | 0.66–1.7 ms → **2.0–5.0 ms**（30fps 门控后按帧均摊再除以 2） |
| 预览 RGB→ARGB（Kotlin 循环） | 是 | 0.85–2.0 ms → **2.5–6.0 ms**（同上） |
| `Bitmap.setPixels` | 是（3.7 MB） | 0.4–0.9 ms → **0.8–2.0 ms** |
| 关键点/检测的 native 预处理（缩到 192） | 是（读全图） | 2.25×，但在 `fc-face-landmark-*` 线程，不占采集预算 |
| WS/JSON | 否 | 1× |

**结论**：
1. **720p 相对 480p 的增量几乎全部落在「预览转换」和「ISP 带宽」**；YUV 直通主链路在 Kotlin 侧是
   O(1) 的（这正是上一轮 `useDirectYuvMode` 的价值）。
2. 前台常开预览时（30 fps 门控**之后**）：720p 相机回调均摊 3.0–7.0 ms/帧、含转换帧峰值
   5.5–13.5 ms；480p 只需 2.5–6.0 ms（峰值）。16.7 ms 预算下，720p 的峰值仍有 3–11 ms 风险窗口。
3. **不建议为此外退到 480p**：720p 对小脸/远景关键点稳定性的收益是链路里最实在的。
   正确做法是「保留 720p 采集，把**预览**这个纯监视消费降到 360p@30fps」（补丁 1）：
   360p@30fps 的预览成本 ≈ 480p@60fps 的 1/4，而采集分辨率不损失。
4. 还有一个**新增变量**：工作区的新代码会在交付帧率 <50 fps 时**自动降到更小的采集尺寸**
   （`Camera2Manager.kt:1433-1476`）。也就是说 720p 在弱机上会自己退到 ≈960×540 / 640×480 ——
   这条策略和「要不要手动用 480p」是同一个取舍，且已经有实现，**建议先上机看它是否真的触发**。

### 4.2　预览 RGB 前台上限 30 fps 是否可接受

**可以，而且工作区已经这么做了**（`PREVIEW_INTERVAL_NS = 33 ms`，`Camera2Manager.kt:209`, `:1406-1413`）。
理由：
- 预览在这套架构里**只喂手机屏幕**：PC 端拿的是 `/ws` 的 JSON（`README.md:104-107`），
  完全不依赖这路 RGB；`onCameraFrameUpdate` 的唯一消费者是 `CameraPreviewView`
  （`MainActivity.kt:428-430`）。
- 30 fps 的监视画面在「看构图 / 看关键点跟不跟得上」这个用途上和 60 fps 无差别；
  需要 60 fps 手感的是 PC 上的 Live2D，而它由 JSON 驱动。
- 收益确定：相机线程这项开销减半；UI 线程 3.7 MB 纹理上传也减半，顺带减少与
  2 路 Vulkan 关键点引擎的 GPU 争用（`CameraPreviewView.kt:430` 每帧 `drawBitmap` 上传一次）。
- 代价：旋转/快速移动时预览画面观感略降。建议「调试面板打开时 60 fps，平时 30 fps」。
- **还剩一步**：30 fps 只把**均摊**减半，**峰值不变**（每 2 帧仍有一次 5.5–13.5 ms）。
  要消灭峰值，需要补丁 1 的 640×360。

### 4.3　`YuvFrameData` 的 Camera2 `Image` 生命周期风险（回调返回即 close、帧已入队）

**风险确认存在，是本报告的第一优先正确性缺陷**（详见 P0-1）。事实链：

1. `processYuvDirect` 把 `image.planes[i].buffer` 直接放进 `YuvFrameData`（`Camera2Manager.kt:1557-1569`）；
2. 回调同步 `invoke`（`:1572`），`PipelineFaceTracker` 只做 `trySend`（`PipelineFaceTracker.kt:547-548`），
   `data.copy()` 是 data class 浅拷贝，**两通道共享同一组 plane**；
3. 回调 `finally { image.close() }`（`Camera2Manager.kt:1370-1372`）；
4. 消费发生在 `fc-face-detect` / `fc-face-landmark-gpu*`（`PipelineFaceTracker.kt:1000-1004`、`:1068-1072`）；
5. `ImageReader.newInstance(..., 3)`（`Camera2Manager.kt:688-695`）→ 3 块 buffer 在 60 fps 下
   每 16.7 ms 轮转，被 close 的这块 16.7–50 ms 后就会被相机重新写入。

**预期症状**（无真机，按机制推断）：关键点偶发单帧整体偏移/表情跳变；检测偶尔像用了「下一帧」的图；
在真正 unmap 的 HAL 上会 SIGSEGV。`stop()` 的注释（`PipelineFaceTracker.kt:613-617`）说明作者
已经意识到同一模型，只是没覆盖「运行中每帧」的情形。

**无 profiler 的验证方法**：在 `processYuvDirect` 入队前对 Y 平面首 64 B 算校验和，
worker 消费时再算一次并比较，不一致打 `Log.e`。跑 30 s，出现次数 >0 即证实。
探针本身 <1 µs/帧，可直接放进 debug 分支。

### 4.4　perf 统计的锁开销

- **加锁次数**：稳定态每帧 ≈5 次（检测 1、2 个关键点 worker 各 1、处理后段 2、派发 1），
  60 fps ⇒ ≈300 次/s，全部落在**同一把 `perfLock`**（`PipelineFaceTracker.kt:130`），5 条线程争用。
- **单次成本**：无竞争 `monitorenter` 20–50 ns；有竞争涉及 futex，1–10 µs。
  正常成本 <0.01 ms/帧，可忽略。
- **真正的问题不是次数，而是持锁时长**：每 60 帧 `logPerfStats()` 在**锁内**执行，
  含 11 条 `Log.i` + ~10 次 `String.format` + `FloatArray` 复制排序
  （`PipelineFaceTracker.kt:1243-1251`、`:1344-1400`）。logd 背压时单条 `Log.i` 0.1–2 ms，
  合计 **1–20 ms 持锁**，期间两个关键点 worker 被顶住 → 丢一拍 → 触发 P0-4 的 67 ms 停顿。
- **结论**：日志搬出锁 + 计数器原子化（补丁 3）后，这项从「偶发毫秒级」降到「微秒级」。
  这也是「为什么 p95 比平均值差」的候选解释之一。

### 4.5　`FrameReorderBuffer` 的 60 ms 缺口等待是否带来帧间隔抖动

**会，而且是「一次缺口 = 一次 67 ms 定格 + 5 帧突发」的强非线性放大**（推演见 P0-4）。补充四点：

1. **缺口不罕见**：`box == null`、`landmarkResult == null`、`DROP_OLDEST`（容量 3）、worker 异常
   都会让某个 `frameId` 永久缺失。1% 的帧失败 ⇒ 每秒 ≈0.6 次 67 ms 停顿。
2. **它被 0.5 s playout 缓冲伪装了**：`TrackingFrameBuffer` 按平滑后的「生产节奏」匀速放行
   （`TrackingFrameBuffer.kt:106-117`, `:144-148`），所以 PC 端**收到的帧间隔是均匀的**，
   看起来「网络没问题」；但每帧的 `t`/`seq` 暴露了内容定住 67 ms 再连跳 5 帧。
   ⇒ **不能只看 outputFps/发送节奏判断重排健康**，要看 `seq` 连续性与帧间隔 p95。
3. **60 ms 的取值偏大**：它是 3.6 个帧周期；合理上限 1.5–2 个帧周期（25–33 ms）。
4. **还有一个细节**：`withTimeoutOrNull(60ms)` 的超时窗口是**从每次 receive 重新计时**的
   （`PipelineFaceTracker.kt:1146-1148`），而 `gapStartMs` 只在 `drain()` 里更新
   （`FrameReorderBuffer.kt:115`）。两个不是同一个时钟 → 实际等待可以是「缺口年龄 60 ms
   之后再等一个 poll 周期」，最坏 ≈67 ms。修的时候应以 `gapStartMs` 为唯一判定依据
   （把剩余时间算出来传给 `withTimeoutOrNull`）。

### 4.6　慢回调下 `ImageReader(maxImages=3)` + `acquireLatestImage` 的交付帧率模型

前提：`setOnImageAvailableListener(listener, backgroundHandler)`（`Camera2Manager.kt:694`）⇒
回调在**单线程 Handler 上串行**执行。设单次回调墙钟耗时 `T_cb`（含持有 `Image` 的时间、
预览转换、以及被 `fc-*` 线程抢占的时间），则：

```
delivered_fps ≈ min(sensor_fps, 1000 / T_cb_ms)
```

推导：
1. 回调 A 在 t=0 取帧并**持有到返回**（`finally { image.close() }`，`Camera2Manager.kt:1370-1372`）；
2. t=16.7 ms 新帧到达，listener 再次投递，但 Handler 正在跑 A ⇒ 消息排队；
3. t=33.3 A 结束，B 立刻开始，`acquireLatestImage` 取「最新」并把中间帧丢弃；
4. ⇒ 交付节奏 = `1/T_cb`，而且**读数会非常"整齐"**（不是抖动）—— 这与「传感器本来只有 30 fps」
   在日志上**完全无法区分**，必须靠 `SENSOR_FRAME_DURATION` 才能分辨（见 4.7）。

`maxImages=3` 的余量只在 `T_cb < 50 ms` 时够用；`T_cb > 50 ms` 时缓冲耗尽、producer 被顶住，
才会出现明显的抖动/长停顿（此时 `acquireLatestImage` 频繁返回 `null`）。

| `T_cb` (ms) | 8 | 16.7 | 20 | 25 | **33** | 50 | 80 |
|---|---|---|---|---|---|---|---|
| estimated delivered fps | 60（传感器限） | 60 | 50 | 40 | **30** | 20 | 12.5 |

⇒ **实测 `Camera delivered = 30` 只有三类原因**，全部可用现有日志分辨（见 4.7）。

### 4.7　「`Camera delivered` 只有 30」的判定树（给下一次上机用）

| 日志证据 | 结论 | 修法 |
|---|---|---|
| `SENSOR_FRAME_DURATION` ≈33.3 ms，或 `appliedFpsRange` = `[30,30]`（`Camera2Manager.kt:1286-1293`） | **传感器/AE 就是 30**，与 Kotlin 层无关 | 改 `chooseTargetFpsRange`（`:1165`）选 `[30,60]`/`[60,60]`；换物理/逻辑相机。**降采集尺寸无效** |
| `SENSOR_FRAME_DURATION` ≈16.7 ms 但 delivered=30 | **回调被拖到 ≈33 ms**（§4.6 模型） | 砍相机线程开销：预览 360p@30fps + `THREAD_PRIORITY_DISPLAY`（补丁 1） |
| 出现 `Native conversion failed, falling back to Kotlin` 或 `MNN YUV converter not available` | **Kotlin 逐像素回退在跑**（0.92 M 次 `coerceIn`，估 40–80 ms/帧，`Camera2Manager.kt:1701-1708`、`:1331-1343`） | 先修 native/MNN 转换器可用性 —— 这比任何调优都关键 |
| 以上都不符 | 3 buffer 饥饿 / HAL 侧 | 提高 `maxImages`，或缩短单帧持有时间 |

**拿判决性数据只需 2 行代码**（不涉及功能）：在 `onImageAvailableListener`（`:1349-1372`）里
`cbStart = System.nanoTime()` → `finally` 里累计 `cbTotal / cbCount`，每 2 s 打一条
`cbAvg/cbMax`。这是区分「传感器 30」与「回调 33 ms」的**唯一直接证据**；
现有 `updateDeliveredFps`（`:1381-1396`）只测结果、不测原因。

---

## 5. 「12 GB RAM / 4 GB 可用」下 Kotlin 侧的「用内存换帧率」选项

现状基线（720p，预览相关）：

| 缓冲区 | 大小 | 证据 |
|---|---|---|
| `reusableRgbBuffer`（YUV→RGB 输出） | 2.76 MB | `Camera2Manager.kt:1319`, `:1662-1667` |
| `rgbBytesBuffer`（RGB→ARGB 中转） | 2.76 MB | `CameraPreviewView.kt:276-281` |
| `pixelBuffer: IntArray(921 600)` | 3.69 MB | `CameraPreviewView.kt:265-270` |
| 2 × `Bitmap` ARGB_8888（双缓冲） | 7.37 MB | `CameraPreviewView.kt:304-324` |
| ImageReader 3 × YUV 帧（gralloc） | ≈4.15 MB | `Camera2Manager.kt:688-695` |
| **小计** | **≈20.7 MB** | |

可选项：

| 选项 | 内存 | 帧率/延迟收益 | 风险 | 建议 |
|---|---|---|---|---|
| **A. 预览降到 640×360**（补丁 1） | **−12 MB** | 转换帧 5.5–13.5 → ≈1.5–4.0 ms | 低 | **强烈建议**（省内存 + 省时间，双赢） |
| **B. 预览 30 fps 门控**（已在工作区） | 0 | 均摊减半（−1.4 ~ −3.3 ms/帧） | 低 | 已实现，保留 |
| **C. YUV 帧池 6 块**（补丁 2） | **+8.3 MB** | 帧率 ≈0；正确性 | 中低 | 建议（修 bug，不是换帧率） |
| **D. `ImageReader` 3→5 块**（仅交接方案需要） | +2.8 MB | 采集端多 2 帧余量 | 低 | 仅采用方案 B 时 |
| **E. 关键点通道容量 3→4/5** | 池化后 +1.4~2.8 MB | 减少 `DROP_OLDEST` 缺口 → 减少 P0-4 停顿 | 低 | 与补丁 4 二选一即可（补丁 4 更根本） |
| **F. 第 3 路 GPU 引擎** | **+30–80 MB（估算）**：landmark 权重 4.66 MB fp32 ≈2.3 MB bf16 + Vulkan 中间张量池/命令缓冲 | 只在「单路 >33 ms」时才有意义；14.5 ms 前提下 **≈0** | 中（显存/热/降频） | **先 A/B 实测再决定**，不要默认开 |
| **G. `FrameReorderBuffer.maxPending` 8→16** | +0.12 MB | ≈0（8 帧≈133 ms 已远超 60 ms 超时，加到 16 只是多吃延迟） | — | **不做** |
| **H. 478 `Point3D` 对象池（×2 路）** | +30 KB | ≈0（ART 年轻代扛得住，见 P2-1） | 低 | 不做，收益抵不上改动面 |
| **I. worker 线程本地 `FloatArray(1434)`** | +6 KB | −0.05 ~ −0.1 ms/帧 | 中（动 JNI） | 可做，优先级低 |

**结论**：
- 12 GB / 4 GB 可用内存对这套管线的 Kotlin 侧**完全不是约束**（所有选项加起来 <100 MB）。
- 但 **Kotlin 侧能用内存换到的帧率很有限**：瓶颈是「相机线程 16.7 ms 的固定预算」，
  它**不能靠加缓冲变大**，只能靠**减少相机线程的工作**。所以性价比最高的是 A+B
  （减少工作、还顺带省内存），其次 C（正确性）；F 只在实测证明 Vulkan 利用率不足时才值。
- 如果一定要花 30–80 MB 买帧率，最值得的是 F；但按 14.5 ms 的关键点耗时，
  **瓶颈不在关键点吞吐**，这 30–80 MB 大概率买不到帧率。

---

## 6. 复测清单（本报告无法在环境内验证的量）

1. `adb shell logcat -s PipelineFaceTracker Camera2Manager` 抓 3 段：
   `Camera delivered` / `Landmark Detection` / `Frame interval p50/p95/max`。
   **判定**：`Camera delivered` <55 而 CPU/GPU 不满 → 先做补丁 1；
   `Camera delivered` ≈60 但 `Frame interval p95` ≫16.7 → 先做补丁 4。
2. 在 `processYuvDirect` 里加「Y 平面首 64 B 校验和」探针，跑 30 s，确认 P0-1 是否在发生
   （若 0 次，说明该机型 buffer 复用比估算慢，可降级为 P1，但代码契约仍然是错的）。
3. 在 `writeLoop` 统计相邻两次 `sendFrame(track)` 的间隔分布，确认 20 ms 轮询是否真把发送
   钉在 50 fps（对照 `TrackingOutputManager.outputFps` 与 PC 端 `seq` 增量）。
4. 观察新增的自动降档是否触发：日志 `⚠ Camera delivers only ... switching to a smaller capture size`
   与 `Adaptive capture downshift #`。若触发，记录降档前后的 `Camera delivered` 与
   `Landmark p95` —— 这会直接回答「720p 该不该保」。
5. `setGpuChannelCount(1/2/3)` 做 A/B：记录 `Landmark p50/p95`、`Frame interval p95`、
   `Camera delivered`，据此定默认并行度（当前默认 2 来自设计假设，未经实测）。
6. `adb shell dumpsys meminfo <pkg>` 看 Java Heap / Graphics / GL mtrack，验证 §5 的
   ≈20.7 MB 预览基线；按补丁 1 后应降到 ≈9 MB。

---

## 7. 一页结论

**先做哪一步**：实测 `Camera delivered = 30` 时，**不要先降采集尺寸** ——
先照 §4.7 判定树把「传感器 30」和「回调 33 ms」分开（读 `SENSOR_FRAME_DURATION`），
再照本表选条目；同时按 §8.8 加 2 行回调耗时统计，让归因有数据。

| 优先级 | 问题 | 影响 | 补丁 |
|---|---|---|---|
| P0 | YUV plane 在回调返回时 close、帧已入队 | 偶发单帧关键点错误 / 潜在 SIGSEGV | 帧池 + 显式拷贝（+8.3 MB） |
| P0 | 前台预览占相机线程（均摊 3–7 ms，峰值 5.5–13.5） | 采集掉帧 / 抖动 | 30fps **已做**；再加 640×360 + 线程优先级 |
| P0 | `logPerfStats` 持锁打 11 条日志 | 每 60 帧 1–20 ms 全局停顿 | 日志搬出锁 + 原子计数 |
| P0 | 重排缺口等 60 ms | 一次掉帧 = 67 ms 定格 + 5 帧突发 | `markSkipped` + 超时降到 33 ms |
| P0 | WS 写线程 20 ms 轮询 | 发送硬上限 50 fps、额外 0–20 ms | 信号量唤醒 |
| P0 | 【新代码】降档时先关 ImageReader 再 join 相机线程 | 回调操作已关 reader，可能让帧源永久中断 | 调换 stop 顺序 + 回调兜底 catch |
| P1 | bbox 遍历 / `estimateHeadPose` 打包 / 每帧读旋转 / 300 ms 帧龄 | 小 | 见 §2 |
| 待实测 | GPU 并行默认 2 路是否真降 p95 | 不确定（+30–80 MB） | A/B |

## 8. 对「已落地相机侧补丁」的只读审查（采集尺寸帧率优先 + 自适应降档 + 预览限速 + 原生转换缓存）

被审对象（工作区未提交，非本审计改动）：`Camera2Manager.kt` 的
`PREVIEW_INTERVAL_NS` / `maybeConvertPreviewFrame` / `scheduleFpsProbe` / `runFpsProbe` /
`pickDownshiftSize` / `restartAtSmallerSize` / `captureLifecycleLock`，
以及 `mnn_yuv_converter.cpp` 的 `thread_local` 转换缓存。方向都正确（尤其是预览限速与
「转换缓冲 + ImageProcess 复用」），以下是可以再收紧的地方：

1. **降档只认「交付帧率 <50」，没区分「传感器就是 30」和「回调被拖慢」** —— 这是最大的遗漏。
   若 `SENSOR_FRAME_DURATION` 就是 33.3 ms（或 `appliedFpsRange`=[30,30]），
   **降采集尺寸完全不会提升帧率**，代价却是 ~9 s 的不稳定（2 次降档 × (2.5+0.8) s 探测 + 重开相机）
   加上画面先糊后稳。建议：`runFpsProbe` 先读 `appliedFrameDurationNs`（`:1286-1293` 已经采到了），
   若 ≈33.3 ms 就直接打一条「sensor/AE limited」并 **不动尺寸**；只有 frameDuration≈16.7 ms
   才继续走降档。改一行判断即可，风险极低。
2. **降档的顺序值得再想**：降采集尺寸会同时降低关键点输入质量（小脸更糊），而「预览降到 360p
   或干脆关预览」对关键点零影响、收益同样是二次方级。既然目标先是 60 fps，**建议先降预览、
   再考虑降采集**（现在只有降采集这条路）。
3. **`stopCameraInternal()` 的资源释放顺序** 会让在途回调操作已关闭的 `ImageReader`
   （详见 P0-6）。原来这条路径只在用户手动 stop 时走到；新补丁让它**每次启动后 3.3 s 自动走到**，
   风险从「罕见」升为「每次启动都可能」。这是新增代码里最该先修的一处。
4. **`restartAtSmallerSize` 在 `captureLifecycleLock` 内做 stop→start**，其中 `openCamera()` 的
   `getCameraCharacteristics` 等是 binder 调用。此时主线程若点「停止」会阻塞在
   `synchronized(captureLifecycleLock)`（`:558`）上几十 ms。建议把「决定降档」与「执行重开」
   分开加锁，或至少接受并注释这一 UI 卡顿。
5. **降档结果不跨 `startCamera()` 保留**：`startCameraInternal` 在 `keepCaptureSizeOverride=false` 时
   清零 `sizeDownshiftCount` 与 `captureSizeOverride`（`:524-529`）⇒ 切前后摄 / 切镜头 / 重进页面后
   720p 会**再抖一次**（再花 ~9 s）。建议把降档结果按相机 id 持久化（和 `getPreferredCameraId` 同级）。
6. **`pickDownshiftSize` 的兜底会挑一个平台不保证 60fps 的尺寸**：`... ?: smaller.first()`（`:1476`），
   即「没有明确支持 60 fps 的更小尺寸」时仍取最大的一档 ⇒ 尺寸降了、帧率没涨，纯粹掉质量。
   建议兜底改成 `return null`（沿用已有的「no smaller 60fps-capable size」日志），或兜底时优先
   再往下找一档明确支持 60 fps 的尺寸。
7. **原生转换缓存本身没有正确性问题**（`thread_local` + 按 `(src,dst,rotation,format)` 做 key，
   `minSdk 24` 下 TLS 析构可用，1.38 MB 打包缓冲随线程回收）。两个小提醒：
   (a) 每次重开相机都会新建 HandlerThread ⇒ 缓存变冷，首次转换要重新 resize + `ImageProcess::create`
   （一次几 ms，自动降档最多重开 2 次，可接受，但值得在日志里留一条）；
   (b) 该缓存假设「只有相机线程调用」——若以后把预览转换挪到独立线程（本报告补丁 1 的「外移」变体），
   缓存会各自一份（+1.38 MB/线程），别误以为共享。
8. **缺一个直接测 `T_cb` 的量**。补丁把「结果」（交付帧率）测得很全，却没测「原因」（回调耗时）。
   加 2 行计数器就能让 §4.7 的判定树立刻有数据支撑，**这是当前性价比最高的一行代码**。

9. **顺手看到的新改动（`TrackingServer.kt`，同一批并发编辑）**：P0-5 的 `Semaphore` 唤醒已经落地
   （`TrackingServer.kt:881-886`, `:993`），方向正确、能去掉 50 fps 天花板。一个小瑕疵：
   `signalWriter()` 用 `if (availablePermits() < 2)` 抑制信号（`:884-887`），突发时会吞掉一次唤醒
   ⇒ 那一帧最多多等 20 ms 兜底超时。由于 `pendingTrack` 是 latest-only 槽位、writer 每轮只取 1 个，
   许可天然不会堆积，建议直接 `release()` 不做抑制，或让 writer 醒来后一次性 drain。

---

> 本报告未修改 `src/` 下任何文件；唯一写入是 `docs/perf/audit-kotlin.md`。
> `git status` 里的 `M Camera2Manager.kt` / `M mnn_yuv_converter.cpp` / `M PERFORMANCE.md`
> 来自审计期间的并发编辑，不是本审计产生的。
