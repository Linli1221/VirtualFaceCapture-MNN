# 上半身关键点采集（MediaPipe Pose）——设计与接口契约

> 本文是 `feat/mediapipe-upper-body` 分支的实现契约。**改动本文件里的类名 / 方法签名 /
> 协议字段前，先同步所有端**。协议字段的最终说明写在
> [`docs/tracking-protocol.md`](./tracking-protocol.md)（§4.2 / §5.3 / §8.5），这里只讲分工。
> 上半身在整条动捕链路里的位置见 [`docs/pipeline.md`](./pipeline.md) §8。

---

## 1. 目标与范围

在现有「手机面捕 → PC 渲染」链路上，新增一路**上半身关键点采集**：

- 采集什么：MediaPipe Pose（BlazePose）33 点里挑出的 **13 个上半身点**
  （鼻子 / 双眼 / 双耳 / 双肩 / 双肘 / 双腕 / 双胯），归一化坐标 + 相对深度 + 可见度；
- 谁算：**手机端**，用 **官方 MediaPipe Tasks（`com.google.mediapipe:tasks-vision`）的
  `PoseLandmarker`（LIVE_STREAM 模式）**在相机同一路 YUV 帧上抽帧推理（默认 15 fps，可调）；
- 怎么传：沿用 VFC Tracking Protocol v1，在 `track` 报文里**新增可选字段** `ub` / `ubOk` /
  `ubT` / `body`（纯增量，不升 `version`，老客户端会按协议 §3 「未知字段忽略」直接跳过）；
- 谁用：Windows 客户端把 13 点换算成**上半身运动骨骼**（脊柱 → 锁骨 → 上臂 → 前臂），
  用 `body`（躯干 roll/yaw）+ 骨骼关节角驱动 Live2D 的身体 / 肩 / 手臂 / 肘参数
  （`ParamBodyAngleX/Y/Z`、`ParamShoulderL/R`、`ParamArmLA/RA`、`ParamArmLB/RB`，
  模型没有对应参数时自动跳过），并在调试面板画出骨架。

> 和面捕管线的关系：**完全解耦**。上半身是可选功能（默认关），关掉时相机线程零额外开销，
> 也不加载 MediaPipe Tasks 运行时；面捕的「零 MediaPipe 运行时依赖」设计不变。

## 2. 数据流

```
Camera2 (YUV_420_888, 主/后/外接)
   │  facecapture-sdk PipelineFaceTracker#onYuvFrameForBodyWithHint
   │  （抽帧 tap，默认 66ms = 15fps；附带最近脸框，一级扩充 1.15×，TTL 500ms）
   ▼
app: UpperBodyPoseController                 ← 生命周期 / 开关 / 基线 / 统计
   │  ① FaceBox → FaceRoiHint（score < 0.3 或非法 → null）
   │  ② MediaPipeUpperBody.UpperBodyPoseDetector#pushFrame(frame, faceHint)
   │     相机线程只把 Y/U/V 按 stride 拷进池化 DirectByteBuffer（0.15~0.4ms）→ 立即返回
   ▼
:MediapipeUpperBody  UpperBodyPoseDetector   ← PoseLandmarker(LIVE_STREAM), GPU 优先、失败回退 CPU
   │  ③ upper-body-worker 线程（YuvFrameRing：3 槽池化缓冲、**只保留最新帧**，慢了丢旧帧）：
   │     脸框 → 上半身 ROI（4.5×/5.5× 脸宽高）→ 只采样 ROI → ARGB Bitmap（长边 256/320 自适应）
   │     无脸框 / 脸太小 / ROI 太小 → 整帧；ROI miss → 1s 整帧回退
   │     输入长边：正常 256（优先帧率），质量下降 / 脸框变小 / ROI miss → 320（优先精度）
   │  ④ 33 点先回映整帧预览坐标 → 取 13 点 → One-Euro 平滑 → 相对基线 (roll/yaw)
   │     （upperBodyPostV1 协商成功时 ④ 只到「取 13 点」，平滑/基线在 PC，见 §3.7）
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

/** 输入长边档位（顶层枚举）：PRECISION = 固定 320、FRAME_RATE = 固定 256、ADAPTIVE = 自适应（默认）。 */
enum class InputSizeMode { PRECISION, FRAME_RATE, ADAPTIVE }

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
    fun pushFrame(frame: Yuv420Frame, faceHint: FaceRoiHint? = null)
                                                       // 相机线程只拷贝平面入「只保留最新」环形缓冲；
                                                       // ROI 裁切 + 转换 + detectAsync 在 upper-body-worker 线程
    fun resetBaseline()
    fun setMinConfidences(detection: Float, presence: Float, tracking: Float)
    fun setTargetFps(fps: Int)
    var inputSizeMode: InputSizeMode            // A/B：PRECISION(320) / FRAME_RATE(256)；ADAPTIVE(默认)
    fun isInputSizeBoosted(): Boolean          // 当前是否升档 320（调试 / A/B 统计）
    fun getStats(): Stats

    companion object {
        const val MODEL_LITE = "pose_landmarker_lite.task"
        const val MODEL_FULL = "pose_landmarker_full.task"
    }
}

/** 同一帧上的脸框提示（旋转后、预览方向、未镜像的归一化坐标，[0,1]）。 */
data class FaceRoiHint(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val score: Float = 1f,
) { val isValid: Boolean /* 有限、right>left、bottom>top、score∈[0,1] */ }

/** 旋转后源像素坐标里的裁切窗口（右/下边界不含）。 */
data class RoiWindow(val x0: Int, val y0: Int, val width: Int, val height: Int)

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

1. **输入图**：按 `rotationDegrees` 旋转、`mirrorHorizontal` 镜像，输出长边**上限 256 / 320**
   （保持长宽比，只缩不放；ROI 本身小于长边上限时不放大）
   的 ARGB_8888 Bitmap 喂给 `PoseLandmarker`。`faceHint` 有效时只采样「脸框 → 上半身 ROI」
   （`UpperBodyRoi.fromFace`：bodyW=faceW×4.5、bodyH=faceH×5.5，顶部留 0.35×脸高）；
   faceHint 为空 / 非法 / 脸太小 / ROI <48px 时整帧。返回的归一化坐标是 **ROI 局部**
   坐标，必须先 `UpperBodyRoi.mapLocalToPreview` 回映到整帧预览坐标，再做 13 点提取。
   **输入长边自适应**（`InputSizeMode.ADAPTIVE`，默认，状态机见 `InputSizePolicy`）：
   正常跟踪用 256 保帧率（像素量是 320 的 64%）；关键点质量下降（13 点平均可见度 < 0.5）、
   脸框变小（归一化面积 < 0.01，人变远）或 ROI miss 时**立刻**升 320 保精度；只有**连续 6 帧**
   平均可见度 ≥ 0.7 才回落 256，中间带 [0.5, 0.7) 保持现状，避免 256/320 来回抖。
   A/B 验证时用 `inputSizeMode = InputSizeMode.PRECISION`（320 优先精度组）或
   `InputSizeMode.FRAME_RATE`（256 优先帧率组）钉死档位，对照
   `isInputSizeBoosted()` / `getStats().avgLatencyMs` 观察帧率与关键点稳定性。
   只改输入预处理尺寸、**不改模型**，三种档位的坐标回映与协议语义完全一致；
   远距离、坐姿、快速转身三类场景的关键点稳定性仍需真机 A/B 验证后再定默认策略。
   转换内核（`YuvBitmapConverter` / `YuvSampleIndex`）：输出行列 → 源像素的采样映射按
   「源尺寸 + ROI 尺寸 + rotation + mirror + 输出尺寸」缓存坐标表（`YuvSampleCache`），
   Y/U/V 平面行索引与 UV 索引摊平成 `rowY/colY/rowUv/colUv` 四张小表（每帧
   O(输出行+输出列)，几何 / 原点 / stride 未变时免算复用），内层循环只剩数组寻址 +
   YUV→ARGB；与旧逐像素 `sourceX()/sourceY()` 路径的**逐位等价**由 `YuvSampleIndexTest`
   对拍保证。Bitmap 仍每帧新建（MediaPipe 异步持有当前 Bitmap，不能简单复用同一张；
   要做池化只能是**带生命周期确认的 2~3 张环形池**）；JNI / NEON 内核是后续方向。
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
   ⚠️ **upperBodyPostV1 协商成功时本条整体跳过**（平滑 / 躯干角 / 基线迁到 PC，见 §3.7）；
   未协商时保持本条不变。
7. **线程**：`pushFrame` 应在相机线程 / tap 回调里调用（ByteBuffer 只在回调内有效；内部
   在返回前只把 Y/U/V 按 stride 拷进池化 DirectByteBuffer，典型 0.15~0.4ms）；ROI 采样、
   Bitmap 构造与 detectAsync 提交都在模块内部的 `upper-body-worker` 线程做，队列
   （`YuvFrameRing`，3 槽）**只保留最新帧**：上一帧没被消费就直接顶掉（计入 `dropped`），
   绝不排队放大延迟；`onResult` 在 MediaPipe 回调线程；内部状态用锁保护。
   ROI 帧没跟踪到上半身时，接下来 **1000ms** 一律整帧再试（避免 ROI/整帧逐帧交替），
   ROI 帧成功会清掉残留窗口。
8. **不许**在模块里引 `androidx.compose` / App 类 / MediapipeFaceLandmark（模块只依赖
   `:CommonData` + tasks-vision + androidx.core）。

### 3.3 帧抽头：`facecapture-sdk` 的 `PipelineFaceTracker`

```kotlin
/** 旧 tap：只有帧，没有脸框。保留兼容；新接入方用下面的带提示版本。 */
var onYuvFrameForBody: ((YuvFrameData) -> Unit)? = null

/** 新 tap：帧 + 最近脸框（旋转后归一化、已一级扩充 1.15×；可能为 null）。优先于旧 tap。 */
var onYuvFrameForBodyWithHint: ((YuvFrameData, MediaPipeFaceDetector.FaceBox?) -> Unit)? = null

var bodyTapIntervalMs: Long = 66L   // 0 = 每帧都回调
```

- 实现方式：在 `camera2Manager.setYuvFrameCallback { ... }` 里，喂完面捕的 channel 之后，
  按 `bodyTapIntervalMs` 抽帧调用 tap，**同步**执行（不允许抛异常影响面捕，要 try/catch）。
- 两个 tap 都挂时**只调带提示的那个**；带提示为 null 时回退旧 tap。
- 脸框在 tap 时刻从 `lastFaceBox` 快照；超过 **500ms** 没刷新（掉脸 / 检测节流 / 重检）就传
  null，让上半身回退整帧。`lastFaceBox` 存的是检测框 **×1.15 一级扩充后**的脸 ROI。
- 回调参数直接复用 `MediapipeFaceLandmark` 的 `YuvFrameData`（有 y/u/v ByteBuffer、stride、
  width/height、rotation、rotatedWidth/Height）——app 模块已经能看见这个类型。
- `stop()` / 切后台时清空两个 tap（`= null`）。

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
- **rawTrackV1（P1）**：检测器的 `rawOutput` 是「M2 协商 ∪ raw 推流」的**合并开关**（任一生效
  即发原始 13 点）；raw 推流期间上半身原始点走 raw_track 的 **ubRaw 扩展块**（13×8B + ubT +
  torsoRaw + quality + ubTracking，见 [`raw-track-protocol.md`](./raw-track-protocol.md) §5），
  该连接不再发文本 track。

### 3.5 App 侧（`app/`）

| 文件 | 职责 |
|---|---|
| `UpperBodyPoseController.kt`（新） | 持有 `UpperBodyPoseDetector`、按开关 start/stop、把 `YuvFrameData` → `Yuv420Frame`、`FaceBox` → `FaceRoiHint`、把 `UpperBodyPoseFrame` 分发给 UI / 网络 |
| `MainActivity.kt` | 创建 controller；`faceTracker.onYuvFrameForBodyWithHint = { frame, box -> upperBody.pushYuvFrame(frame, box) }`；控制面板加「上半身: 开/关」；调试 HUD 加一行；把帧传给预览 |
| `CameraPreviewView.kt` | `var drawUpperBody: Boolean`、`fun updateUpperBody(frame: UpperBodyPoseFrame?)`，按 `UpperBodySkeleton.BONES` 画骨架（点坐标已是镜像后的，**不要再翻转**） |

### 3.6 Windows 客户端（`windows-client/`）

- `renderer/mapping.js`：上半身纯函数层（无 DOM，Node 可直接测）：
  - `BODY_LANDMARK_ORDER`：名字 → **协议顺序下标**（0..12），与 `UpperBodyLandmarkIndex.ORDER`
    的 MediaPipe 原始索引一一对应；`BODY_BONES` 15 条边，与 `UpperBodySkeleton.BONES` 逐项一致；
  - `parseUpperBody(ub)`：52 项 → 13 个 `{x,y,z,v}`（长度不对 / NaN → 清洗或 null）；
  - `buildUpperBodySkeleton(ub)`：13 点 → **上半身运动骨骼**（层级关节角：脊柱 → 锁骨 →
    上臂 → 前臂，角度都是相对父骨的旋转）。返回 `{points, spine, clavicle, arms:{L,R}}`，
    每根骨带 `valid` / `visibility` 门控信息；躯干倾斜时手臂的**躯干相对角**保持不变；
  - `mapBody(bodyAngles, ub, opts)`：输入 `body = [roll, yaw]` + `ub`（扁平 52 或
    `parseUpperBody()` 结果），返回 `{params, metrics, skeleton}`。两个参数按长度识别，
    写成文档顺序 `mapBody(ub, body)` 也会自动交换（有单测）；
  - `readUpperBodyTrack(track, now, prev)`：解析 `ub/ubOk/ubT/body`，返回
    `{ok, present, ubOk, stale, ageMs, points, torso, t, receivedAt}`。**`ubT` 只用来判断
    「是不是新样本」**（手机与本机时钟不同步，不能直接和本机 now 相减），过期用本地到达时间
    的 age：同一个 `ubT` 反复出现 → age 增长 → 1.5s 后 stale；
  - `smoothBodyParams(current, target)`：丢失 / 过期 / 低可见度时按帧向 0 前进 15%，
    衰减到 0 后**删掉参数**，交回模型自己的 idle motion（不会把参数写死成 0）；
  - `combineBodyAngleX(head, torso)`：头部与躯干的 `ParamBodyAngleX` **相加后 clamp ±30**，
    不互相覆盖。
  - 输出参数（骨骼驱动，协议 §8.5）：`ParamBodyAngleX/Y/Z`（躯干）、`ParamArmLA/RA`
    （上臂抬起角）、`ParamArmLB/RB`（肘屈曲，正 = 屈肘）、`ParamShoulderL/R`（锁骨差动，
    正 = 该侧肩抬起）、`ParamHandL/R` + 别名 `ParamHandAngleL/R`（手：前臂朝向的近似，
    协议没有手部关键点，只保证手跟着胳膊走）。模型没有的由 `live2d.js` 跳过。
  - 写进模型前由 `live2d.js` 的 `toModelRange()` 把 ±30 契约量程**等比映射到模型实际
    参数量程**（Haru 的 `ParamArmLA` 是 [-1,1]，直接写 27 会被 clamp 到极值、动作钉死）。
- `renderer/app.js`：每帧 `readUpperBodyTrack` → `mapBody` → `smoothBodyParams`，喂给 stage；
  `__vfcDebug.body` 暴露原始帧 / ageMs / torso / armAngle / elbowBend / clavicleRelTilt / 参数；
  调试骨架 canvas 也在这里（`D` 键或 `?skeleton=1`，低可见度点灰显，`ubOk=false` 灰显最后一帧）。
- `renderer/live2d.js`：`setBodyParams()` 在头部参数之后应用，上半身参数 0.16s 一阶低通；
  用 `getParameterIndex() >= 0` + 真实参数 id 表双保险判断模型是否有该参数。
- `tools/mock-body.js`（新）：mock 手机端的上半身合成（13 点 + 躯干角 + 双手交替抬起 +
  肘屈曲 + 耸肩差动 + 周期性丢失窗口）；`tools/mock-tracker.js` 的 track / hello 带上
  `ub/ubOk/ubT/body`（`--no-upper-body` 可关）；`windows-client/tools/selftest.js` 与
  `tools/verify/mapping-test.mjs`（仓库根，协议契约）新增解析 / 骨骼 / 映射 / 平滑 /
  全链路用例。

### 3.7 上半身后处理迁移（upperBodyPostV1，任务 M2）

**动机**：One-Euro 平滑、躯干角计算、TorsoBaseline 原先在手机端（§3.2 第 6 条），无法按
角色个性化、不能持久化基线、与 PC 渲染层 160ms 低通形成双重过度平滑。M2 把这三步迁到
PC，手机只保留「推理 → 抽 13 点 → isTracking」。

**协商**（协议 §4.1 / §4.2 / §4.5）：手机 `hello` 带 `upperBodyPostV1:true`（能力），
客户端 `hello.features` 含 `"upperBodyPostV1"`（能力）；**两者都出现**才启用原始模式，
此时 `track.ubRaw:true`、`ubRawBody:[roll,yaw]`，`body` = **原始角**（语义由 `ubRaw` 显式
声明，不存在「字段名没变但语义变了」）。未协商成功时手机继续走原有完整后处理链，报文
与 v1 老语义逐字节一致。多客户端时只有**所有**已连接客户端都声明能力才启用（track 是
广播同一条报文，混合客户端时全体回退旧链）。

**rawTrackV1（P1）增量**：raw 推流连接的 ubRaw **扩展块**（[`raw-track-protocol.md`](./raw-track-protocol.md)
§5）携带**未平滑** 13 点与原始躯干角 `torsoRaw`（One-Euro / TorsoBaseline 全部归 PC），块内
`ubTracking=0` 时 13 点全 0、`torso=[0,0]`（结构恒定）。扩展块只在「上半身开启 ∧ raw 推流」时
出现（帧头 flags bit1），与本文 `track.ubRaw:true` 是文本 / 二进制两种形态各自的原始通道，
互不混发。

**责任划分**：

| 步骤 | 手机（新） | PC（新，`mapping.js`） |
|---|---|---|
| YUV→Bitmap、ROI、detectAsync、15fps 限帧、单飞、generation | ✅ 保留（不变） | — |
| 33 点 → 13 点（extract）、isTracking（>0.5 至少 7 点） | ✅ 保留（不变） | — |
| One-Euro 平滑 13 点 xyz | ❌ 原始模式跳过 | ✅ `UpperBodyPostProcessor` |
| 躯干角（roll / yaw） | 算原始角（发 `ubRawBody`，对拍用） | ✅ `computeTorsoAngles`（对平滑点） |
| 躯干角 One-Euro | ❌ 原始模式跳过 | ✅ `torsoFilters` |
| TorsoBaseline（10 帧均值 + 渐入） | ❌ 原始模式跳过 | ✅ `TorsoBaseline`（可持久化 / 手动重置） |
| mapBody / smoothBodyParams / 三层回退 | — | ✅ 不变 |

**算法契约（PC 与 Kotlin 必须逐行等价）**：

- One-Euro：`cutoff = minCutoff + beta·|edx|`，`tau = 1/(2π·cutoff)`，`alpha = 1/(1+tau/dt)`；
  dt ≤ 0 按 1ms、dt > 0.5s 截断；13 点 xyz 用 `minCutoff=1.2, beta=0.02`，
  躯干 roll/yaw 用 `minCutoff=0.6, beta=0.01`（derivativeCutoff=1.0）。
- 躯干角（`computeTorsoAngles`，禁止直接除 dx）：
  `horizontal = max(|right.x − left.x|, 0.02)`；
  `roll = deg(atan2(right.y − left.y, horizontal))`（右肩下沉为正）；
  `yaw = deg(atan2(−(right.z − left.z), horizontal))`（本人左侧转为正）。
  肩线不可见按 肩→肘→胯 退化；**0.02 分母下限必须保留**（两肩画面重合时不出几十度假角度）。
  z 是相对深度（非米制、非角度），只进 atan2。
- TorsoBaseline：10 个有效帧均值为中性姿态；前 10 帧输出 = (当前 − 均值) × collected/10
  渐入（起播 10 帧内不从 0 突跳到基线）；之后输出 = 当前 − 基线。
- dt 规则：无锚点（首帧 / tracking 丢失后）→ 1/15 s；否则 `clamp(t − lastT, 1, 500)ms`。
- tracking 丢失（ubOk=false / 过期）→ 平滑器全重置 + dt 锚点清零，**基线不动**；
  坏点（x=y=z=v=0）透传不参与平滑。
- 顺序（与 Kotlin `buildFrame` 一致）：平滑点 → 对**平滑点**算躯干角 → 躯干角平滑 → 基线。

**滤波责任归属与总时间常数估算**（一阶串联 ≈ 时间常数相加）：

| 层 | 归属 | 内容 | τ（低速最坏） |
|---|---|---|---|
| 信号调理 | PC `UpperBodyPostProcessor`（One-Euro，全链**唯一**去噪层） | 点 1.2Hz / 躯干 0.6Hz | 点 ≈ **133ms**、躯干 ≈ **265ms** |
| 观感层 | `live2d.js` / M4 `filter.js`（**只允许一层**轻量低通） | 原始模式建议 ≤60ms（`RAW_BODY_RENDER_TAU_MS`）；未协商保持旧 160ms 不变 | **60ms**（旧链 160ms） |
| 丢帧处置 | `readUpperBodyTrack` + `smoothBodyParams`（**不是平滑**，不变） | 1500ms 过期 / ×0.85 按帧衰减 / 删参交还 idle | — |

- 迁移前（手机 One-Euro + PC 160ms）：手臂/点 ≈ 133+160 ≈ **293ms**，躯干 ≈ 265+160 ≈ **425ms**；
- 迁移后（PC One-Euro + 渲染 60ms）：手臂/点 ≈ 133+60 ≈ **193ms**，躯干 ≈ 265+60 ≈ **325ms**；
- 渲染层 τ 配到 0（直接写）时总 τ = One-Euro 单层：133 / 265ms。
- 快动作时 cutoff 抬升、τ 自动下降（例：躯干 50°/s → cutoff 1.1Hz → τ ≈ 145ms），
  One-Euro 的动态响应不慢于上面的低速估值。
- **禁止**再叠第三层平滑；`app.js` 的 `syncBodySmoothingTau()` 在模式切换时自动把渲染层
  τ 设为 60ms（原始）/ 160ms（旧链），可配置低通本体由 M4 `filter.js` 统一实现。

**基线持久化与手动重置（按角色 profile 区分）**：

- 存储：`localStorage["vfc.bodyBaseline.<profileKey>"]`（`profileKeyFor(modelName)`，与
  姿态偏移的 profile 同一套按角色区分语义）；保存时机 = 收齐 10 帧转 calibrated 时一次 +
  页面卸载；平滑器状态不持久化（冷启动重调，基线保留）。
- 换角色：`activateBodyBaselineProfile()` 自动载入该角色基线，各存各的、互不干扰。
- 手动重置：UI「重置上半身基线」按钮（动态创建）或 `__vfc.resetBodyBaseline()`：清掉
  持久化并重新收 10 帧渐入。`command:recalibrate` 的手机端语义不变（原始模式下手机基线
  不参与输出，重置操作仍安全）。

**跨语言对拍**（验收：同一份 ub 原始样本，PC 算出的 body 与 Kotlin 原实现误差 ≤ 0.5°）：

- 夹具（单一事实来源）：`MediapipeUpperBody/src/test/resources/ub_post_fixture.txt`，
  由 `tools/verify/gen-ub-post-fixture.mjs` 生成（内含 Kotlin 数学的**独立参考实现**转写，
  不复用 mapping.js，避免实现与自测共码的假阳性）；46 个有效帧 + 3 个丢失事件。
- Kotlin：`MediapipeUpperBody` 的 `UpperBodyPostEquivalenceTest`（CI 跑）；
- PC：`node tools/verify/ub-post-test.mjs`（对拍容差 0.05°，另含渐入 / 肩重合 /
  三层回退 / 基线持久化 / 老路径不变等验收用例）。

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
- `ubRaw` / `ubRawBody`（upperBodyPostV1，见 §3.7）：`ubRaw:true` 声明 `ub` 是**原始点**、
  `body` 是**原始角**（未 One-Euro、未减基线），`ubRawBody` 是同值的显式命名别名；
  平滑 / 躯干角 / 基线由客户端负责。未协商时两字段不出现，`body` 保持旧语义。
- 参数映射参考（客户端实现）：`ParamBodyAngleZ = clamp(roll * 0.5, ±30)`、
  `ParamBodyAngleX = clamp(headX + (-yaw * 0.3), ±30)`、`ParamBodyAngleY = clamp(-roll * 0.2, ±30)`；
  手臂抬起角 = **肩→腕** 与「竖直向下」的夹角（度，0..180），`armUp = clamp(angle / 150, 0, 1)`、
  `ParamArmLA/RA = armUp * 30`；肘屈曲（前臂相对上臂）→ `ParamArmLB/RB`，锁骨差动（肩线相对
  髋线）→ `ParamShoulderL/R`；该侧肩/肘/腕可见度 < 0.3 时**不输出**该侧手臂参数。
  详见 `docs/tracking-protocol.md` §8.5 参考实现。

## 5. 验收清单

1. `./gradlew :app:assembleDebug` 通过（新增模块参与构建；未放模型时**只警告不失败**）。
2. `./gradlew :app:testDebugUnitTest :MediapipeUpperBody:testDebugUnitTest` 通过
   （协议字段 + `UpperBodyRoiTest` 的 ROI/回映用例）。
3. 真机（arm64）：开关打开后预览出现上半身骨架；HUD 显示 body fps / 质量 / 躯干角；
   前置摄像头 + 0°/90°/270° 旋转下骨架与画面一致（ROI 回映正确）；
   logcat 出现 `ROI 引导生效：WxH @(x,y)`；远端坐姿下关键点不比整帧版本抖；
   关掉后相机线程无额外开销（面捕 fps 不下降）。
4. PC：mock 手机端（`npm run mock`）→ 形象手臂 / 身体跟着动；`npm run selftest` 通过；
   透明置顶窗口下帧率仍为 60 fps。
5. 断线 / 关手机上半身开关后，PC 手臂在 ~0.3 s 内平滑回默认，不冻结在最后一帧。
6. （M2）同一份 ub 原始样本，PC 算出的 body 与 Kotlin 原实现误差 **≤ 0.5°**（夹具对拍：
   `UpperBodyPostEquivalenceTest` + `tools/verify/ub-post-test.mjs`）。
7. （M2）起播 10 帧内角度从 0 **连续渐入**，不突跳到基线（TorsoBaseline 的 collected/10）。
8. （M2）两肩在画面中重合（dx < 0.02）时**不出现几十度假角度**（MIN_REFERENCE_SPAN）。
9. （M2）掉线后手臂能自然放下并**交还 idle motion**（×0.85 按帧衰减 → 删参，三层回退不变）。
10. （M2）未协商 `upperBodyPostV1` 时行为与改动前**完全一致**（报文逐字节、数值逐值）。

## 6. 模型获取（不进 Git 大文件）

```bash
# 官方 MediaPipe Pose Landmarker（lite ≈ 5.5 MB / full ≈ 9 MB）
curl -L -o MediapipeUpperBody/src/main/assets/pose_landmarker_lite.task \
  https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task
```

仓库里提供 `tools/fetch-pose-model.ps1`（Windows）与 `tools/fetch-pose-model.sh`（macOS/Linux）
一键下载到正确位置；模型文件已在 `.gitignore` 里排除（与第三方 Live2D 模型同样处理），
CI 未下载模型时功能自动降级为「不可用」。
