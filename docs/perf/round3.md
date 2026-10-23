# 第三轮：让 Vulkan 真正生效 + 砍掉预览路径的 CPU 大头

> 分支：`feat/mediapipe-upper-body`（本轮为工作区改动，未提交）
> 触发问题：**面捕帧率低、CPU 占用极高、GPU 显存明显空闲**
>
> **验证状态（重要）**：本机没有 JDK / Android SDK / NDK / 真机，**没有编译、没有跑单测、
> 没有实测毫秒数**。本文所有收益都是「可算的工作量减少」+「结构性判断」，实测数字必须由
> CI 构建 + 真机 logcat 给出。第 4 节给了拿到机器后 10 分钟内可出结论的步骤。

---

## 0. 一句话结论

> **Vulkan 从来没有真正启用过。** `libMNN_Vulkan.so`（Vulkan 后端插件）虽然随 APK 打包了，
> 但**全工程没有任何一处加载它**；MNN 核心库 `libMNN.so` 里既没有 Vulkan 代码，也没有
> dlopen 插件的路径。于是 `createSession(MNN_FORWARD_VULKAN)` 只会静默落到 CPU —
> 三个模型（检测 / 关键点 ×2 路）全部在 CPU 上跑，`GPU_CHANNEL_DEFAULT = 2` 又让两路
> 关键点引擎在 CPU 上互相抢核 —— 这就是「CPU 极高 + GPU 显存空闲」的直接原因。
>
> 第二个大 CPU 来源与推理无关：**手机预览每帧在相机线程上做整帧 YUV→RGB（2.76 MB）
> + 92 万次 Kotlin 逐像素打包 + `Bitmap.setPixels`**（估算 6~11 ms/帧，30 fps 常开），
> 它把相机线程的 16.67 ms 预算吃掉一大半，进而拖垮采集帧率。

三条独立审计（只读、带 file:line）：
- [`round3-gpu.md`](round3-gpu.md) —— GPU 为什么空着（二进制级证据）
- [`round3-native.md`](round3-native.md) —— 原生热路径每帧的 CPU 工作量
- [`round3-kotlin.md`](round3-kotlin.md) —— Kotlin 流水线 / 相机线程 / UI 线程

---

## 1. 根因链（按证据强度）

| # | 结论 | 证据 |
|---|---|---|
| 1 | Vulkan 后端在独立插件 `libMNN_Vulkan.so` 里，靠 `.init_array` 自注册 `RuntimeCreator` | `libMNN.so` 内 `Vulkan`/`libMNN_Vulkan`/`glsl_*` 字符串与符号均为 0；`libMNN_Vulkan.so` 导入 `_ZN3MNN28MNNInsertExtraRuntimeCreatorE…` |
| 2 | 全工程没有加载它 | `CMakeLists.txt` 只链 `MNN` + `mnn_Express`；Kotlin 只有 `System.loadLibrary("mediapipefacelandmark")` |
| 3 | 因此 `MNN_FORWARD_VULKAN` 请求必然落空 | MNN 在找不到 creator 时打印 `Can't Find type=.. backend, use .. instead` 并改用 CPU，`createSession` 仍返回成功 |
| 4 | App 自报的 "VULKAN" 是**请求值**不是**实际值** | `nativeGetBackend` 原来返回 `g_use_vulkan`（意图），只有三模型全部创建失败才置 false |
| 5 | 在 CPU 上并行 2 路是负优化 | `GPU_CHANNEL_DEFAULT = 2`，两路 landmark session 在 CPU 上抢核 |
| 6 | 预览把相机线程吃满 | 见 [`round3-kotlin.md`](round3-kotlin.md) §2；旧链路 720p 每帧约 705 MB/s 内存流量 |

> 为什么前两轮没发现：第一/二轮把「请求 Vulkan」当成「跑在 Vulkan 上」，且所有性能数字
> 都是静态估算；原生埋点又恰好把打包时间放在 `Preprocess` 计时点**之外**，日志结构上
> 无法回答「CPU 花在哪」。

---

## 2. 本轮改动

### 2.1 GPU 真正生效（P0）

| 文件 | 改动 |
|---|---|
| `MediapipeFaceLandmark/.../MediaPipeFaceDetector.kt` | **`System.loadLibrary("MNN_Vulkan")` 放在主 JNI 库之前**（插件依赖 `libMNN.so`，链加载会带起它）。缺失时只告警并退回 CPU，不崩 |
| `include/common/mnn_session_util.h`（新） | ① `applyScheduleConfig()`：Vulkan 分支**只写 `type` + `mode = MNN_GPU_TUNING_WIDE`**，不再把 `numThread` 写进那个 union；CPU 分支写 `numThread`。② `sessionVulkanActive()`：用 `getSessionInfo(BACKENDS)` 探测**真实**后端（扫描 4 个 int，兼容 MNN 各版本的填充布局差异） |
| `face_detector.cpp` / `face_landmarker.cpp` / `blendshape_predictor.cpp` | `createFromFile` → `setSessionMode(Session_Release)` → `createSession` → 探测真实后端 → 每个模型打一行 `MNN backend[xxx]: request=.. actual=.. backends=[a,b,c,d] mnn=<ver>`；请求 Vulkan 实际 CPU 时额外 `FACE_LOGE`；三处 `runSession` 检查 `ErrorCode` |
| `MediaPipeFaceLandmarkJNI.cpp` | `nativeGetBackend` 返回**真实**后端；`nativeInit` / `nativeSetBackend` / `nativeInitEngine` 都以真实后端判定，不匹配就回退/拒绝并返回 `JNI_FALSE` |
| `PipelineFaceTracker.kt` | 只有主引擎**实际**在 Vulkan 上才开额外引擎；`setMnnBackend` / `setGpuChannelCount` 同样以真实后端为准；初始化日志改为 `MNN backend in use: ...` |

### 2.2 预览路径去 CPU（P0）

| 文件 | 改动 |
|---|---|
| `yuv_converter.cpp` | 新 C ABI `convertYuvToBgraRotated()`：旋转 + 最近邻降采样 + YUV→**BGRA** 一次完成；坐标表按 dstX/dstY 预计算（`thread_local` 复用）；旋转映射与旧 `convertYuvToRgbRotated` 逐像素等价 |
| `YuvConverter.kt` | 新 Kotlin 门面 `convertYuvToBgra(...)` + JNI 绑定（名字与 native 逐字对齐） |
| `Camera2Manager.kt` | 预览改走 native BGRA（**1/2 降采样**，如 720×1280 → 360×640），复用 direct `ByteBuffer`；native 不可用/连续失败 5 次后走纯 Kotlin BGRA 回退（不黑屏、不出 B/R 互换）；预览限速仍为 30 fps |
| `CameraPreviewView.kt` | `updateCameraFrame` 只做一次 `Bitmap.copyPixelsFromBuffer()`；删除 `ByteArray` 中转、`IntArray` 逐像素打包、`setPixels`；叠加层改 478 项 Paint/半径 **LUT**（去掉每点 2 个 `Pair` + 最多 9 次 `Set<Int>` 装箱查找）；姿态/分数文本 10 Hz 缓存；`postInvalidate` 30 Hz 节流 |

### 2.3 顺手的减法

| 文件 | 改动 | 理由 |
|---|---|---|
| `PipelineFaceTracker.kt` | `initialize(enableBlendshape = false)` | 原生 blendshape MNN 模型**全仓库无调用点**，52 维表情一直由 Kotlin 几何启发式算出；这个 session 只是白占显存/内存 |
| `PipelineFaceTracker.kt` | 首帧 PPM dump 只在可调试包里开 | 检测线程上 921,600 次像素循环 + 写 2.7 MB，正式包纯浪费 |
| `PipelineFaceTracker.kt` | **单路时跳过重排阶段**（结果直达后处理） | 单路结果天然有序；重排每帧多一次通道唤醒 + `TreeMap`，且它的 60 ms 缺口超时是「永久丢一帧 → 输出定格」的来源 |
| `face_landmarker.cpp` / `face_detector.cpp` | 条件回读 | 关键点先读 presence（4 B），没脸就不读 478 点；检测先读 scores（3.5 KB），无候选就不读 57 KB regressor |
| `perf_profiler.h` + 两个 cpp | 新增 `Pack(YUV)` / `Upload` / `Readback` 三段计时 | 把「CPU 花在哪」变成可测；`Preprocess`/`Postprocess` 语义不变，可与旧日志直接对比 |

---

## 3. 预期收益（可算的部分）

| 项 | 改前 | 改后 | 依据 |
|---|---|---|---|
| 推理后端 | CPU（MNN CPU，2~4 线程 × 2~3 路） | **Vulkan**（插件加载成功后） | §1 |
| 检测/关键点常驻 session | 4 个（含 1 个永不运行的 blendshape） | 2 个（单路）或 3 个（双路） | 2.3 |
| 预览单帧内存流量 | 2.7 MB RGB + 2.7 MB `ByteArray` + 3.7 MB `IntArray` + 3.7 MB `setPixels` ≈ **12.8 MB** | 0.92 MB BGRA（360×640）写一次 + `copyPixelsFromBuffer` ≈ **1.8 MB** | [round3-kotlin.md](round3-kotlin.md) §2 |
| 预览 Kotlin 逐像素循环 | **921,600 次/帧 × 30 fps** | **0** | 2.2 |
| 关键点帧 Java 分配 | 478 个 `Point3D` + `float[1434]` + `ArrayList` | 不变（本轮未动后处理） | — |
| 关键点帧原生分配 | 几何 3× Procrustes ≈ 163 KB/帧 | 不变（本轮未动几何） | — |
| 单路时的通道跳数 | worker → merge → reorder → processing | worker → processing | 2.3 |
| 无脸帧的回读 | 5,740 B + 2 次同步 | 4 B + 1 次同步 | 2.3 |

> 本轮**没有**做「把预处理搬上 GPU」（MNN `ImageProcess` 只有 CPU 实现）和「几何解算零分配」，
> 这两项在 [`round3-native.md`](round3-native.md) §7（P1-2、第五轮建议）里，属于下一轮。

---

## 4. 验证步骤（真机 / CI）

### 4.1 先确认构建

```powershell
./gradlew :MediapipeFaceLandmark:assembleDebug :app:assembleDebug
```

本轮新增/改动的编译期风险点（静态审查已覆盖，但仍需 CI 兜底）：

1. `MNN::Interpreter::getSessionInfo(session, BACKENDS, ptr)` 的实参类型与 `Session_Release` 枚举名；
2. `MNN_GPU_TUNING_WIDE` / `MNN_GPU_RECORD_BATCH` 在该 MNN 版本（3.6.1）里是全局枚举常量；
3. `YuvConverter.nativeConvertYuvToBgra` 与 Kotlin `external fun` 名字逐字一致；
4. 新增头文件 `include/common/mnn_session_util.h` 已被 include 目录覆盖（无需改 CMake）。

### 4.2 看这三行日志（决定性问题）

```bash
adb logcat -s FaceDetector FaceLandmarker BlendShapePredictor PipelineFaceTracker MediaPipeFaceDetector
```

| 期望日志 | 含义 |
|---|---|
| `MNN backend[detector]: request=Vulkan actual=Vulkan backends=[..] mnn=3.6.1` | 插件加载成功，**真的**跑在 GPU 上（三个模型各一行） |
| `MNN backend[detector]: ... requested Vulkan but the real backend is NOT Vulkan` | 插件没加载 / 设备不支持 → 会显式回退 CPU（日志不再骗人） |
| `libMNN_Vulkan.so 不可用` | APK 里没有这个 so（打包问题） |
| `MNN backend in use: VULKAN (requested Vulkan), GPU parallel channels: 2 / 2` | SDK 侧认可真实后端并开启了双路 |
| `Preview BGRA buffer 360x640 (...)` | 预览走的是 native BGRA 快路径 |
| `Preview BGRA (native) failed` | native 转换失败（会走 Kotlin 回退，但需要查原因） |

若 `backends=[-1,-1,-1,-1]`：说明该 MNN 版本没有填充 `BACKENDS`，此时判定退化为
「按请求值兜底」，请把这一段日志发回来，我们改用 `MNN::getVersion()` + 运行期
`dumpsys gpu` 交叉判断。

### 4.3 指标对比（同机型、同一段场景、release 包）

从既有的 `PipelineFaceTracker` 每 60 帧日志里读：

- `Camera callback: avg .. ms, max .. ms`（相机线程）→ 预期从 6~11 ms 降到 1~2.5 ms 量级；
- `Camera delivered: .. FPS` → 预期回到 58~60（若传感器本身只有 30 fps 则不上升，日志会明说）；
- `Landmark Detection: .. ms` + `Theoretical max FPS` → 这是**唯一**能证明 Vulkan 生效的数字；
- `Face Landmarks Perf` 新增的 `Pack(YUV) / Upload / Readback / Inference` 四行 →
  用来判断瓶颈是「CPU 预处理 + 同步上传」还是「GPU 前向」。

### 4.4 画面回归（预览格式变了，必须目视）

1. 0°/90°/270° 三种屏幕方向下，预览方向、镜像（前置）与关键点叠加是否对齐；
2. 颜色不偏（BGRA 顺序正确，不应出现红蓝互换）；
3. 预览比改前**更软**是预期行为：本轮预览固定 1/2 降采样（720×1280 → 360×640）。
   想恢复清晰度就改 `Camera2Manager` 里预览尺寸推导的除数（代价是相机线程多 ~1 ms）；
4. 上半身开关、切换摄像头、前后台切换后预览恢复，无黑屏。

### 4.5 A/B 建议

- 控制面板的 **并行度** 按钮：`×1` vs `×2`，看 `Landmark Detection` 与 p95 帧间隔；
- 控制面板的 **Backend** 按钮：现在切 CPU 会真的切到 CPU（以前两个方向都可能仍是 CPU）；
- 若怀疑 Vulkan 反而更慢（老 GPU / 驱动差），用 CPU 后端 + 单路做对照，日志里的
  `actual=` 会明确告诉你当前到底在哪。

---

## 5. 风险与回滚

| 风险 | 说明 | 回滚方式 |
|---|---|---|
| 插件加载顺序 | `MNN_Vulkan` 必须在主库之前（插件依赖 `libMNN.so`，链加载会带起） | 删除 `MediaPipeFaceDetector` companion init 里的 `loadLibrary("MNN_Vulkan")`，立刻回到旧行为（全局 CPU） |
| `MNN_GPU_RECORD_BATCH` | **默认关闭**（`MNN_FACE_GPU_RECORD_BATCH=0`），需要真机 A/B 才能打开 | 保持不变即可 |
| 预览降采样 | 360p 预览比改前软 | 调整 `Camera2Manager` 的预览尺寸除数 |
| `Session_Release` | 关闭 op 级回调信息（本工程未使用 `runSessionWithCallBack`） | 删除该调用 |
| **精度档位（未改，但首次真正上 GPU）** | 三个模型仍用 `Precision_Low_BF16`。以前从没跑到过 Vulkan，所以这个档位在 GPU 上的行为**从未被验证**：若画面出现异常抖动/花屏，先用控制面板切回 CPU 对照；确认是精度问题就把 `mnn_session_util.h` 里的 precision 改成 `Precision_Normal`（最稳）或 `Precision_Low`（fp16，最快，移动 GPU 常见） | 改 `applyScheduleConfig()` 一行 |
| `getSessionInfo(BACKENDS)` 布局 | 已改为「扫描 4 个 int」，对两种布局都成立；若该版本不填充，则该函数返回 false → 会被判成 CPU（保守、安全方向） | — |
| YUV plane 生命周期（**既有 bug，本轮未修**） | `image.close()` 后推理线程仍零拷贝读 plane，高帧率/大负载下可能撕裂或花屏 | 见 §6 |

---

## 6. 本轮明确没做（下一轮候选，按收益排序）

1. **YUV plane 生命周期（正确性，P0）**：加帧池（N × 1.38 MB）+ 显式 memcpy，或让 JNI 在
   相机回调内同步消费。当前 `acquireLatestImage + close` 之后仍有线程在读这块内存。
2. **几何解算零分配**（`round3-native.md` P1-2）：每帧 ≈163 KB malloc/free、3 次 Procrustes；
   60 Hz ≈ 9.8 MB/s 堆抖动。
3. **关键点 + 头姿合并成一次 JNI**（`round3-native.md` P0-2）：现在每帧多一次往返 +
   478 个 `Point3D` → `float[1434]` 的再序列化。
4. **把预处理搬上 GPU**：MNN 的 `ImageProcess` 只有 CPU 实现；要真上 GPU 需要换
   uint8 输入模型或自写 Vulkan/GL 预处理。
5. **UI 叠加层彻底离开主线程**（若 478 点仍造成 jank）：本轮已降到 30 Hz + 零分配，
   进一步需要 SurfaceView + GL 层。
