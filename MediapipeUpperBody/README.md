# :MediapipeUpperBody —— 上半身关键点采集

用**官方 MediaPipe Tasks** 的 `PoseLandmarker`（LIVE_STREAM 模式）在相机 YUV 帧上采集
上半身 13 个关键点，产出 :CommonData 里的 `UpperBodyPoseFrame`。
设计/协议契约见 [docs/upper-body.md](../docs/upper-body.md)（§2 / §3.1 / §3.2 / §6）。

```
Camera2 YUV_420_888（app 侧 15fps 抽帧）+ 同一帧脸框
   │  pushFrame(Yuv420Frame, faceHint)
   ▼
UpperBodyPoseDetector
   │  ① 脸框 → 上半身 ROI（4.5×/5.5× 脸宽高）；无脸框/太小 → 整帧
   │  ② 只采样 ROI：旋转 + 镜像 + 缩放到长边上限 320（只缩不放）→ ARGB Bitmap（约 1~4ms）
   │  ③ PoseLandmarker.detectAsync(mpImage, ts)   ← 单飞，忙时丢帧
   │  ④ MediaPipe 回调线程：33 点回映整帧 → 13 点 → One-Euro → 相对基线躯干角
   ▼
onResult(UpperBodyPoseFrame)
```

模块边界（契约要求）：只依赖 :CommonData + com.google.mediapipe:tasks-vision +
androidx.core，**不**引 Compose / App 类 / :MediapipeFaceLandmark。

---

## 1. 模型放哪（模型不进 Git，约 5.5MB / 9MB）

模型文件名固定为：

| 档位 | 文件名 | 体积 | 用途 |
|---|---|---|---|
| LITE（默认） | `pose_landmarker_lite.task` | 约 5.5 MB | 手机实时优先 |
| FULL | `pose_landmarker_full.task` | 约 9 MB | 更准、更慢 |

### 方式 A：一键下载到 assets（推荐，打包进 APK）

Windows PowerShell：

```powershell
# 只下 lite（默认）；-Variant full / both 可下其它档位；-Force 覆盖已有文件
.\tools\fetch-pose-model.ps1
.\tools\fetch-pose-model.ps1 -Variant both
```

macOS / Linux：

```bash
./tools/fetch-pose-model.sh          # 默认 lite
./tools/fetch-pose-model.sh both     # lite + full
./tools/fetch-pose-model.sh full
# 首次执行若提示 Permission denied：
bash tools/fetch-pose-model.sh both
```

脚本会把模型下载到 `MediapipeUpperBody/src/main/assets/`。模型文件被
`MediapipeUpperBody/.gitignore` 的 `/src/main/assets/*.task` 排除，不会提交。

### 方式 B：adb push 到 `filesDir/models`（免重新打包）

调试包（`android:debuggable=true`）可用 `run-as` 直接写应用私有目录：

```bash
adb push pose_landmarker_lite.task /data/local/tmp/
adb shell chmod 644 /data/local/tmp/pose_landmarker_lite.task
adb shell "run-as com.example.virtualfacecapture mkdir -p files/models"
adb shell "run-as com.example.virtualfacecapture sh -c 'cat /data/local/tmp/pose_landmarker_lite.task > files/models/pose_landmarker_lite.task'"
```

### 模型查找与加载兜底链

`UpperBodyPoseDetector.findModel` 按档位查找：

1. `assets/pose_landmarker_lite.task`（FULL 档位时优先 `assets/pose_landmarker_full.task`）
2. assets 里的另一档位
3. `context.filesDir/models/<name>`

找到模型后，同一个 backend 内**逐个候选路径尝试**创建 PoseLandmarker：

| 顺序 | 候选 | 说明 |
|---|---|---|
| a | assets 相对路径（如 `pose_landmarker_lite.task`） | 官方文档写法；部分 tasks-vision 版本只认 asset 名 |
| b | `cacheDir/upperbody_models/<name>` 绝对路径 | 首次从 assets 复制；绕开压缩 asset 的 openFd 限制 |
| c | `filesDir/models/<name>` 绝对路径 | adb push 的模型优先走这里，不复制 |

- assets 来源：a → b → c；filesDir 来源：c → a → b；
- 每个候选失败都会记录一条中文提示并继续下一个；全部失败才算该 backend 不可用；
- **noCompress 必须写在 app 模块**：library 里的 `androidResources.noCompress` 不会传递到
  最终 APK（CI 实测过，APK 里仍是 DEFLATED，MediaPipe 读不到）。本仓库在
  `app/build.gradle` 与模块 `build.gradle` 各写了一份，CI 还会断言 APK 内该 entry 是
  `ZIP_STORED`；a 命中后 b / c 只是版本兼容 / adb push 的兜底；
- 都没有模型时：`isModelAvailable() == false`、`initialize()` 返回 `false` 并回调一次
  `onError`，**不抛异常**，app 只需关掉上半身功能。

---

## 2. 快速接入

```kotlin
package com.example.mediapipeupperbody

val detector = UpperBodyPoseDetector(context)

detector.onError = { message ->
    // 模型缺失 / GPU 回退 / 推理错误；只提示，别让相机线程停
    Log.w("UpperBody", message)
}
detector.onResult = { frame ->
    // 注意：这里是 MediaPipe 回调线程，UI 更新要自己 post 到主线程
    mainHandler.post { preview.updateUpperBody(frame) }
}

// 打开功能前可以先问一次（不初始化、不崩）
if (detector.isModelAvailable()) {
    if (detector.initialize(
            backend = UpperBodyPoseDetector.Backend.GPU,   // GPU 失败自动回退 CPU
            variant = UpperBodyPoseDetector.ModelVariant.LITE,
        )
    ) {
        detector.setTargetFps(15)          // 默认就是 15
        detector.start()
    }
}

// —— 相机线程（Buffer 只在回调内有效，必须同步调）——
detector.pushFrame(
    Yuv420Frame(
        y = yBuffer, u = uBuffer, v = vBuffer,
        width = width, height = height,
        yRowStride = yRowStride, uvRowStride = uvRowStride, uvPixelStride = uvPixelStride,
        rotationDegrees = rotation,          // 0 / 90 / 180 / 270
        mirrorHorizontal = isFrontCamera,    // 前置 = true
    )
)

// —— 生命周期 ——
detector.stop()                  // 暂不推理，可 start() 恢复
detector.resetBaseline()         // 让用户摆正后重新取中性姿态
detector.release()               // 关闭 landmarker；幂等，未 initialize 也能调
```

> `pushFrame` 内部只做「限帧判断 + 同步 YUV→Bitmap + 投递 detectAsync」，
> 不等待推理锁；单飞用 `AtomicBoolean`，忙时当前帧直接 `dropped++`。
> 转换只读 `ByteBuffer.get(index)`、不依赖 position，返回后不保留 ByteBuffer 引用。

---

## 3. 公开 API（与 docs/upper-body.md §3.2 一致）

```kotlin
class UpperBodyPoseDetector(context: Context) {
    enum class Backend { GPU, CPU }
    enum class ModelVariant { LITE, FULL }
    data class Stats(val framesIn: Long, val framesOut: Long, val dropped: Long, val avgLatencyMs: Float)

    var onResult: ((UpperBodyPoseFrame) -> Unit)?   // MediaPipe 回调线程
    var onError: ((String) -> Unit)?

    fun isModelAvailable(): Boolean
    fun initialize(backend: Backend = Backend.GPU, variant: ModelVariant = ModelVariant.LITE): Boolean
    fun isReady(): Boolean
    fun start()
    fun stop()
    fun release()
    fun pushFrame(frame: Yuv420Frame, faceHint: FaceRoiHint? = null)
    fun resetBaseline()
    fun setMinConfidences(detection: Float, presence: Float, tracking: Float)
    fun setTargetFps(fps: Int)
    fun getStats(): Stats
    fun setBackend(backend: Backend): Boolean        // 契约外补充：运行时重建

    companion object {
        const val MODEL_LITE = "pose_landmarker_lite.task"
        const val MODEL_FULL = "pose_landmarker_full.task"
    }
}
```

`Stats` 语义：

- `framesIn`：`pushFrame` 被调用且检测器处于 ready+started 的帧数（含被丢弃的）；
- `framesOut`：`onResult` 实际产出的帧数（含 `tracking=false` 的空帧）；
- `dropped`：因限帧 / 上一帧仍在推理 / 转换失败而丢弃的帧数；
- `avgLatencyMs`：`detectAsync` 提交 → 结果回调的指数滑动平均（0.8/0.2）。

---

## 4. 行为约定与调参

### 4.1 输入图（旋转 / 镜像 / 缩放）

- 先按 `rotationDegrees` **顺时针**旋转，再按 `mirrorHorizontal` 在旋转后的画面上水平镜像；
- `faceHint` 有效时只采样「脸框 → 上半身 ROI」（`UpperBodyRoi.fromFace`：bodyW=faceW×4.5、
  bodyH=faceH×5.5，顶部留 0.35×脸高）；否则整帧；
- 长边缩到 **320**（保持宽高比，不放大），输出 `ARGB_8888`；
- 最近邻采样，**不做整帧拷贝**；整帧 1080p 竖屏约采样 180×320 ≈ 5.8 万像素，ROI 典型
  10 万像素上下；
- **镜像只处理像素**：返回的 `LEFT_WRIST` 仍然是 MediaPipe 语义的「左手」，不交换左右。

返回的归一化坐标直接就是这张 Bitmap 的 [0,1] 坐标，所以与手机预览方向一致
（app 侧叠加骨架时不要再翻转）。

### 4.2 推理（LIVE_STREAM + 单飞）

- 时间戳 = `System.nanoTime()/1_000_000`（单调），重复时 +1；
- 同一时刻最多一个未完成的 `detectAsync`；来帧过快直接丢弃并计入 `dropped`；
- 默认限帧 15fps（`setTargetFps(1..60)`）；相机侧 66ms tap 与默认值对齐。

### 4.3 13 点 / tracking / quality

- 按 `UpperBodyLandmarkIndex.ORDER` 取 33 点里的 13 个：
  鼻 / 双眼 / 双耳 / 双肩 / 双肘 / 双腕 / 双胯；
- `visibility` 缺失按 0；
- `tracking = 检出 pose 且 13 点里 visibility > 0.5 的至少有 7 个`；
- `quality = 13 点平均 visibility`；
- `tracking=false` 的帧：`points = UpperBodyPoseFrame.empty()`、`torso = ZERO`、
  `tracking=false`，上层据此把形象平滑回默认姿态。

### 4.4 躯干角 / 基线

- 肩线优先，不可见时退化到肘线、胯线（可见度 ≥ 0.3）；
- `roll = atan2(y_R - y_L, |x_R - x_L|)`，**正 = 被拍者右肩下沉**；
- `yaw = atan2(z_L - z_R, |x_R - x_L|)`，**正 = 向本人左侧转**
  （MediaPipe z 越小越靠近镜头；向本人左侧转时右肩靠近镜头）；
- `resetBaseline()` 后取接下来约 10 个有效帧的均值作为中性姿态，10 帧内按
  `(当前 - 均值) × 进度` blending 到新基线，避免首帧抖动；之后输出相对值。

> 与契约字面公式的差异：契约写的是 `atan2(z_R - z_L, x_R - x_L)`。MediaPipe 的 z
> 越小越近，且镜像后画面里的左右顺序依赖相机/标签；按字面实现会在正对镜头时得到
> ±180° 的常量偏置，atan2 回绕会让相对角突跳。这里改为 `atan2(z_L - z_R, |x_R - x_L|)`，
> 符号符合「正 = 向左转」、值域稳定在 (-90°, 90°)。

### 4.5 平滑

- 13 个点逐点 One-Euro（x / y / z 各一个滤波器）：`minCutoff = 1.2Hz`、`beta = 0.02`；
  `visibility` 不平滑，保持原始值；
- 躯干角另做轻度 One-Euro：`minCutoff = 0.6Hz`、`beta = 0.01`；
- `dt` 取相邻有效帧的时间戳差（秒）；丢帧时滤波器 reset，重新捕获不会拖影。

### 4.6 GPU → CPU 回退

`initialize(GPU)` 创建失败时自动用 CPU 重建，并把候选路径 / 回退信息合并成
**一条** `onError` 提示；CPU 也失败才返回 `false`。`setBackend(CPU/GPU)` 运行时重建：新实例创建成功才替换，
失败返回 `false` 并保持原状态。`setMinConfidences(...)` 已初始化时也会立即重建
（失败保留旧实例并提示）。

> 注意：回退只覆盖**创建阶段**的失败。如果 GPU delegate 在推理阶段异步报错，
> 会通过 `onError` 上报，但不会自动重建（避免在错误回调线程里重建实例）；
> 调用方可以稍后显式 `setBackend(CPU)`。

### 4.7 线程

- `pushFrame` 在相机线程 / tap 回调里调用；内部同步完成 ROI 采样 + Bitmap 构造；
- `onResult` / `onError` 在 MediaPipe 回调线程；
- 生命周期 / 配置方法主线程调用即可；内部状态由锁 + volatile 保护；
- `release()` 幂等，未 `initialize` 过也能调；`release()` 后可再次 `initialize()`。

---

## 5. 与 docs/upper-body.md 的对应

| 契约 | 本模块实现 |
|---|---|
| §2 ① 脸框 → 上半身 ROI | `UpperBodyRoi` |
| §2 ② 只采样 ROI + 旋转 + 缩放 → ARGB Bitmap（320 长边） | `YuvBitmapConverter` + `YuvGeometry` |
| §2 ③ `UpperBodyPoseDetector#pushFrame` | `UpperBodyPoseDetector.pushFrame` |
| §2 ③ 13 点 → One-Euro → 相对基线 | `UpperBodyGeometry` / `UpperBodySmoother` / `TorsoBaseline` |
| §3.1 `UpperBodyPoseFrame` / `BodyPoint` / `TorsoAngles` | 直接使用 :CommonData（`api project(':CommonData')`） |
| §3.2 公开 API | 见 §3；`setBackend` 为契约外补充 |
| §3.2-4 模型查找顺序 / 缺模型降级 | `findModel` / `initialize` + 候选路径兜底链 |
| §3.2-5 GPU→CPU 回退 | `initialize` / `setBackend` |
| §3.2-6 平滑 / 基线 / tracking=false | `UpperBodySmoother` / `TorsoBaseline` / `buildFrame` |
| §4 协议字段 | 由 app/协议组消费 `UpperBodyPoseFrame`，本模块不碰 JSON |
| §6 模型获取 | `tools/fetch-pose-model.ps1` / `.sh` + 本 README §1 |

---

## 6. 测试

`src/test/java/com/example/mediapipeupperbody/`：

| 文件 | 覆盖 |
|---|---|
| `UpperBodyGeometryTest` | 33→13 提取 / 协议顺序 / tracking 门限 / quality / 肩与肘退化 / roll 符号与数值 / yaw 符号与数值 / NaN 兜底 |
| `UpperBodySmootherTest` | One-Euro 收敛 / 不越界 / NaN 安全 / reset；点位与躯干平滑 |
| `TorsoBaselineTest` | 10 帧校准 / 相对角 / reset / 非法值不污染 |
| `UpperBodyRoiTest` | 脸框 → ROI 扩展/clamp/回退、镜像回映、z 缩放、320 长边 |
| `Yuv420FrameTest` | 旋转归一化 / 旋转尺寸 / 长边 256 缩放（帧对象默认值）/ 不放大 / 源坐标映射 / stride 校验 |

这些测试只依赖 JUnit + JVM 类（`java.nio`）和 :CommonData 里的纯数据类，
不需要 Android 设备。构建机上运行：

```bash
./gradlew :MediapipeUpperBody:testDebugUnitTest
```

---

## 7. 性能与已知限制

- **默认参数**：长边上限 320（ROI 引导，只缩不放）、15fps、lite 模型；1080p 相机上转换约 1~4ms/帧。
  实际推理耗时用 `getStats().avgLatencyMs` 观察；中端机 GPU 通常 6~15ms。
- 未放模型时功能自动降级（`isModelAvailable()=false`），不影响面捕主链路。
- assets 模型默认按 asset 相对路径加载（`noCompress` 已配置）；只有该路径在当前
  tasks-vision 版本上不可用时，才会首次复制到 `cacheDir`（几十毫秒）再试绝对路径。
  建议在打开开关的初始化阶段做，不要放在每帧路径。
- `setBackend` / `setMinConfidences` 会重建 landmarker（几十 ~ 上百 ms），
  应在 UI 线程、且避免与高频 `pushFrame` 同时发生；重建期间 `pushFrame` 不阻塞，
  但旧实例的在飞结果会按 generation 作废，切换瞬间可能丢 1 帧。
- GPU delegate 的**异步**推理错误不会自动回退 CPU（见 §4.6）。
- 模块不做协议序列化；`track.ub / ubOk / ubT / body` 由 app / 协议组负责。
