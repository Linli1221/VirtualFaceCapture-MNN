# 上半身关键点采集（MediaPipe Pose）——设计与接口契约

> 本文是 `feat/mediapipe-upper-body` 分支的实现契约。**改动本文件里的类名 / 方法签名 /
> 协议字段前，先同步所有端**。协议字段的最终说明写在
> [`docs/tracking-protocol.md`](./tracking-protocol.md)（§4.2 / §5.3 / §8.5），这里只讲分工。

---

## 1. 目标与范围

在现有「手机面捕 → PC 渲染」链路上，新增一路**上半身关键点采集**：

- 采集什么：MediaPipe Pose（BlazePose）33 点里挑出的 **13 个上半身点**
  （鼻子 / 双眼 / 双耳 / 双肩 / 双肘 / 双腕 / 双胯），归一化坐标 + 相对深度 + 可见度；
- 谁算：**手机端**，用 **官方 MediaPipe Tasks（`com.google.mediapipe:tasks-vision`）的
  `PoseLandmarker`（LIVE_STREAM 模式）**在相机同一路 YUV 帧上抽帧推理（默认 15 fps，可调）；
- 怎么传：沿用 VFC Tracking Protocol v1，在 `track` 报文里**新增可选字段** `ub` / `ubOk` /
  `ubT` / `body`（纯增量，不升 `version`，老客户端会按协议 §3 「未知字段忽略」直接跳过）；
- 谁用：Windows 客户端用 `body`（相对躯干 roll/yaw）+ `ub`（手臂位置）驱动 Live2D 的
  身体 / 手臂参数（模型没有对应参数时自动跳过），并在调试面板画出骨架。

> 和面捕管线的关系：**完全解耦**。上半身是可选功能（默认关），关掉时相机线程零额外开销，
> 也不加载 MediaPipe Tasks 运行时；面捕的「零 MediaPipe 运行时依赖」设计不变。

## 2. 数据流

```
Camera2 (YUV_420_888, 主/后/外接)
   │  facecapture-sdk PipelineFaceTracker#onYuvFrameForBody（抽帧 tap，默认 66ms = 15fps）
   ▼
app: UpperBodyPoseController                 ← 生命周期 / 开关 / 基线 / 统计
   │  ① 采样 + 旋转 + 缩放 → ARGB Bitmap（256 长边，保持长宽比）
   │  ② MediaPipeUpperBody.UpperBodyPoseDetector#pushFrame()
   ▼
:MediapipeUpperBody  UpperBodyPoseDetector   ← PoseLandmarker(LIVE_STREAM), GPU 优先、失败回退 CPU
   │  ③ 取 13 点 → One-Euro 平滑 → 相对基线 (roll/yaw)
   ▼
UpperBodyPoseFrame (CommonData)
   ├─► CameraPreviewView.updateUpperBody()     手机预览上的骨架叠加
   ├─► DebugOverlay / HUD                       点数 / 质量 / 躯干角
   └─► TrackingOutputManager.submitUpperBody()  → TrackingServer 最新快照
                                                     → track.ub / ubOk / ubT / body
                                                     → Windows 客户端（renderer/mapping.js）
```

## 3. 模块与接口契约

### 3.1 `:CommonData`（已完成，Lead 负责）

`CommonData/src/main/java/com/example/commondata/UpperBodyPose.kt`：

| 类型 | 说明 |
|---|---|
| `UpperBodyLandmarkIndex` | MediaPipe 33 点常量 + `ORDER`（13 点协议顺序）+ 协议下标常量 `P_*` + `COUNT = 13` |
| `UpperBodySkeleton.BONES` | 15 条骨架连线（协议下标），手机预览与 PC 调试共用同一张表 |
| `BodyPoint(x, y, z, visibility)` | 单点；x/y 为镜像后的归一化坐标 |
| `TorsoAngles(roll, yaw)` | 相对基线的躯干角（度） |
| `UpperBodyPoseFrame(points, torso, tracking, quality, timestampMs)` | 一帧结果；`empty()` / `sanitizePoints()` / `isUsable()` |

### 3.2 `:MediapipeUpperBody`（新模块，见 §5.1）

```kotlin
package com.example.mediapipeupperbody

class UpperBodyPoseDetector(context: Context) {
    enum class Backend { GPU, CPU }
    enum class ModelVariant { LITE, FULL }
    data class Stats(val framesIn: Long, val framesOut: Long, val dropped: Long, val avgLatencyMs: Float)

    var onResult: ((UpperBodyPoseFrame) -> Unit)?     // 在 MediaPipe 回调线程触发
    var onError: ((String) -> Unit)?

    fun isModelAvailable(): Boolean
    fun initialize(backend: Backend = Backend.GPU, variant: ModelVariant = ModelVariant.LITE): Boolean
    fun isReady(): Boolean
    fun start()                                        // 允许 pushFrame
    fun stop()
    fun release()
    fun pushFrame(frame: Yuv420Frame)                  // 相机线程同步调用：内部立即转换，之后异步推理
    fun resetBaseline()
    fun setMinConfidences(detection: Float, presence: Float, tracking: Float)
    fun setTargetFps(fps: Int)
    fun getStats(): Stats

    companion object {
        const val MODEL_LITE = "pose_landmarker_lite.task"
        const val MODEL_FULL = "pose_landmarker_full.task"
    }
}

/** 相机线程上的一帧 YUV（Buffer 只在回调期间有效，pushFrame 必须在回调里同步调用）。 */
class Yuv420Frame(
    val y: java.nio.ByteBuffer, val u: java.nio.ByteBuffer, val v: java.nio.ByteBuffer,
    val width: Int, val height: Int,
    val yRowStride: Int, val uvRowStride: Int, val uvPixelStride: Int,
    val rotationDegrees: Int,      // 0 / 90 / 180 / 270
    val mirrorHorizontal: Boolean, // 前置摄像头 = true
)
```

行为要求：

1. **输入图**：按 `rotationDegrees` 旋转、`mirrorHorizontal` 镜像，输出长边 **256**（保持长宽比）
   的 ARGB_8888 Bitmap 喂给 `PoseLandmarker`。返回的归一化坐标因此可以直接当作该 Bitmap 的
   [0,1] 坐标，与相机画面一一对应。
2. **镜像**：只镜像像素，不改 MediaPipe 的左右语义（左手仍是 `LEFT_WRIST`）。
3. **推理**：`LIVE_STREAM` + `detectAsync`，时间戳单调递增（`System.nanoTime()/1_000_000`，
   重复时 +1）；同一时刻只允许一个在飞的 `detectAsync`，来帧过快就丢弃（计入 `dropped`）。
4. **模型**：查找顺序为 `assets/pose_landmarker_lite.task` → `assets/pose_landmarker_full.task`
   → `context.filesDir/models/<name>`（方便 `adb push` 免重新打包）。创建 landmarker 时按
   「asset 相对路径 → `cacheDir/upperbody_models/` 绝对路径（首次从 assets 复制）→ filesDir
   绝对路径」逐个候选尝试，全部失败才算该后端不可用 —— 这样不依赖 tasks-vision 版本对绝对
   路径 / 压缩 asset 的支持；`androidResources { noCompress += "task" }` 必须同时写进 **app 模块**
   （library 模块里的同名设置不会传递到最终 APK，CI 会断言 APK 内该 entry 为 `ZIP_STORED`）。
   都没有时 `isModelAvailable() = false`、`initialize()` 返回 false 并回调 `onError`，
   App 侧只关功能、不崩。
5. **GPU → CPU 回退**：GPU delegate 创建失败时自动用 CPU 重建，并回调一次 `onError`（仅提示）。
6. **平滑 / 基线**：逐点 One-Euro（位置）平滑；`resetBaseline()` 后从接下来 ~10 个有效帧
   取均值作为中性肩线，`TorsoAngles` 输出相对值（肩线 roll、绕竖轴 yaw，单位度）。
   `tracking = false` 的帧 `torso = ZERO`，`points` 用 `UpperBodyPoseFrame.empty()`。
7. **线程**：`pushFrame` 任意线程可调；`onResult` 在 MediaPipe 回调线程；内部状态用锁保护。
8. **不许**在模块里引 `androidx.compose` / App 类 / MediapipeFaceLandmark（模块只依赖
   `:CommonData` + tasks-vision + androidx.core）。

### 3.3 帧抽头：`facecapture-sdk` 的 `PipelineFaceTracker`

```kotlin
/** 相机 YUV 帧 tap（上层做上半身等附加推理用）。回调在**相机线程**，Buffer 只在回调内有效。 */
var onYuvFrameForBody: ((YuvFrameData) -> Unit)? = null  // YuvFrameData = com.example.mediapipefacelandmark.YuvFrameData
var bodyTapIntervalMs: Long = 66L   // 0 = 每帧都回调
```

- 实现方式：在 `camera2Manager.setYuvFrameCallback { ... }` 里，喂完面捕的 channel 之后，
  按 `bodyTapIntervalMs` 抽帧调用 tap，**同步**执行（不允许抛异常影响面捕，要 try/catch）。
- 回调参数直接复用 `MediapipeFaceLandmark` 的 `YuvFrameData`（有 y/u/v ByteBuffer、stride、
  width/height、rotation、rotatedWidth/Height）——app 模块已经能看见这个类型。
- `stop()` / `release()` / 切后台时清空 tap（`onYuvFrameForBody = null`）。

### 3.4 `TrackingOutputManager` / `TrackingServer`（协议组负责）

```kotlin
// TrackingOutputManager
fun setUpperBodyEnabled(enabled: Boolean)      // 影响 hello.upperBody 与 track 是否带 ub
fun submitUpperBody(frame: UpperBodyPoseFrame) // ~15fps 调用；内部只存「最新快照」

// TrackingServer（内部）
fun setUpperBodyEnabled(enabled: Boolean)
fun submitUpperBodySnapshot(frame: UpperBodyPoseFrame)
```

- `submitFrame(...)` 签名**不变**；`TrackingServer.submitTrack` 在序列化时读取最新快照。
- 快照过期（`now - timestampMs > 1500`）时按 `ubOk = false` 发（客户端回默认姿态）。
- 未开启上半身时，`track` 报文里**完全不出现** `ub`/`ubOk`/`ubT`/`body`（保持 0.5KB 级别）。

### 3.5 App 侧（`app/`）

| 文件 | 职责 |
|---|---|
| `UpperBodyPoseController.kt`（新） | 持有 `UpperBodyPoseDetector`、按开关 start/stop、把 `YuvFrameData` → `Yuv420Frame`、把 `UpperBodyPoseFrame` 分发给 UI / 网络 |
| `MainActivity.kt` | 创建 controller；`faceTracker.onYuvFrameForBody = { upperBody.pushYuvFrame(it) }`；控制面板加「上半身: 开/关」；调试 HUD 加一行；把帧传给预览 |
| `CameraPreviewView.kt` | `var drawUpperBody: Boolean`、`fun updateUpperBody(frame: UpperBodyPoseFrame?)`，按 `UpperBodySkeleton.BONES` 画骨架（点坐标已是镜像后的，**不要再翻转**） |

### 3.6 Windows 客户端（`windows-client/`）

- `renderer/mapping.js`：上半身纯函数层（无 DOM，Node 可直接测）：
  - `BODY_LANDMARK_ORDER`：名字 → **协议顺序下标**（0..12），与 `UpperBodyLandmarkIndex.ORDER`
    的 MediaPipe 原始索引一一对应；`BODY_BONES` 15 条边，与 `UpperBodySkeleton.BONES` 逐项一致；
  - `parseUpperBody(ub)`：52 项 → 13 个 `{x,y,z,v}`（长度不对 / NaN → 清洗或 null）；
  - `mapBody(bodyAngles, ub, opts)`：输入 `body = [roll, yaw]` + `ub`（扁平 52 或
    `parseUpperBody()` 结果），返回 `{params, metrics}`。两个参数按长度识别，写成文档顺序
    `mapBody(ub, body)` 也会自动交换（有单测）；
  - `readUpperBodyTrack(track, now, prev)`：解析 `ub/ubOk/ubT/body`，返回
    `{ok, present, ubOk, stale, ageMs, points, torso, t, receivedAt}`。**`ubT` 只用来判断
    「是不是新样本」**（手机与本机时钟不同步，不能直接和本机 now 相减），过期用本地到达时间
    的 age：同一个 `ubT` 反复出现 → age 增长 → 1.5s 后 stale；
  - `smoothBodyParams(current, target)`：丢失 / 过期 / 低可见度时按帧向 0 前进 15%，
    衰减到 0 后**删掉参数**，交回模型自己的 idle motion（不会把参数写死成 0）；
  - `combineBodyAngleX(head, torso)`：头部与躯干的 `ParamBodyAngleX` **相加后 clamp ±30**，
    不互相覆盖。
  - 输出参数：`ParamBodyAngleX/Y/Z`、`ParamArmLA/RA`（模型没有的由 `live2d.js` 跳过）。
    `ParamShoulderL/R`、`ParamHandL/R` 协议没有可靠数据源，参考实现**不输出**，留给以后扩展。
- `renderer/app.js`：每帧 `readUpperBodyTrack` → `mapBody` → `smoothBodyParams`，喂给 stage；
  `__vfcDebug.body` 暴露原始帧 / ageMs / torso / armAngle / 参数；调试骨架 canvas 也在这里
  （`D` 键或 `?skeleton=1`，低可见度点灰显，`ubOk=false` 灰显最后一帧）。
- `renderer/live2d.js`：`setBodyParams()` 在头部参数之后应用，上半身参数 0.16s 一阶低通；
  用 `getParameterIndex() >= 0` + 真实参数 id 表双保险判断模型是否有该参数。
- `tools/mock-body.js`（新）：mock 手机端的上半身合成（13 点 + 躯干角 + 双手交替抬起 +
  周期性丢失窗口）；`tools/mock-tracker.js` 的 track / hello 带上 `ub/ubOk/ubT/body`
  （`--no-upper-body` 可关）；`tools/selftest.js` 与 `tools/verify/mapping-test.mjs`
  新增解析 / 映射 / 平滑 / 全链路用例。

## 4. 协议增量（最终以 docs/tracking-protocol.md 为准）

`hello` 新增：

```json
{"type":"hello", "...":"...", "upperBody": true}
```

`track` 新增（全可选）：

```json
{
  "type":"track", "seq":128, "...":"...",
  "ubOk": true,
  "ub": [x0,y0,z0,v0, x1,y1,z1,v1, ... 13×4],
  "ubT": 1730000000123,
  "body": [roll, yaw]
}
```

- `ub`：13 个点 × (x, y, z, visibility)，顺序 = `UpperBodyLandmarkIndex.ORDER`，x/y ∈ [0,1]，
  已镜像（与预览方向一致）、`left/right` 为被拍者本人左右；保留 3~4 位小数。
- `ubT`：该关键点帧的手机采集时间（ms），客户端用来判过期 / 插值。
- `body`：相对基线的躯干角 `[roll, yaw]`（度）。roll > 0 = 右肩下沉，yaw > 0 = 向本人左侧转。
- `ubOk`：`false` = 本次没有上半身结果（丢失 / 过期），客户端应平滑回默认姿态。
- 参数映射参考（客户端实现）：`ParamBodyAngleZ = clamp(roll * 0.5, ±30)`、
  `ParamBodyAngleX = clamp(headX + (-yaw * 0.3), ±30)`、`ParamBodyAngleY = clamp(-roll * 0.2, ±30)`；
  手臂抬起角 = **肩→腕** 与「竖直向下」的夹角（度，0..180），`armUp = clamp(angle / 150, 0, 1)`、
  `ParamArmLA/RA = armUp * 30`；该侧肩/肘/腕可见度 < 0.3 时**不输出**该侧手臂参数。
  详见 `docs/tracking-protocol.md` §8.5 参考实现。

## 5. 验收清单

1. `./gradlew :app:assembleDebug` 通过（新增模块参与构建；未放模型时**只警告不失败**）。
2. `./gradlew :app:testDebugUnitTest` 通过（协议新字段的序列化 / 解析用例）。
3. 真机（arm64）：开关打开后预览出现上半身骨架；HUD 显示 body fps / 质量 / 躯干角；
   关掉后相机线程无额外开销（面捕 fps 不下降）。
4. PC：mock 手机端（`npm run mock`）→ 形象手臂 / 身体跟着动；`npm run selftest` 通过；
   透明置顶窗口下帧率仍为 60 fps。
5. 断线 / 关手机上半身开关后，PC 手臂在 ~0.3 s 内平滑回默认，不冻结在最后一帧。

## 6. 模型获取（不进 Git 大文件）

```bash
# 官方 MediaPipe Pose Landmarker（lite ≈ 5.5 MB / full ≈ 9 MB）
curl -L -o MediapipeUpperBody/src/main/assets/pose_landmarker_lite.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
```

仓库里提供 `tools/fetch-pose-model.ps1`（Windows）与 `tools/fetch-pose-model.sh`（macOS/Linux）
一键下载到正确位置；模型文件已在 `.gitignore` 里排除（与第三方 Live2D 模型同样处理），
CI 未下载模型时功能自动降级为「不可用」。
