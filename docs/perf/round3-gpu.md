# 第三轮 · GPU 实际利用率诊断：为什么"开着 Vulkan"却看不到 GPU 负载

> **本文性质**：只读静态审计 + 二进制字符串探测。本机没有 JDK / Android SDK / 真机 / Vulkan 环境，
> **没有编译、没有跑测试、没有实测毫秒数**。所有"待真机证实"的条目都在 §8 汇总。
>
> **唯一改动**：`docs/perf/round3-gpu.md`（本文件）。`src/`、`build.gradle`、`.so` 一律未动。
>
> **证据等级标记**：
> `[A]` 二进制/代码直接读出的事实 · `[B]` 由 [A] 推出的高置信度结论 · `[C]` 合理推断，必须真机验证。

---

## 0. 结论先行（七条）

**1. `[A]` Vulkan 后端代码根本不在 `libMNN.so` 里，而在独立的 `libMNN_Vulkan.so` 里。**
`libMNN.so`（2.90 MB）中 `glsl_*` 着色器符号 **0 个**、`vkCreateInstance`/`vkAllocateMemory` 等 `Vk*` 符号 **0 个**、
`Vulkan`/`libMNN_`/`libMNN_Vulkan` 字符串 **0 处**；而 `libMNN_Vulkan.so`（737 KB）里有 **170 条 `glsl_*` 串（去重后约 120 个着色器变体）**
（`glsl_convolution1x1_*`、`glsl_convolutionDepthwise_*`、`glsl_im2col_comp`、`glsl_matmul_*`、`glsl_softmaxImage_*` …）
以及全部 `vk*` 调用。→ **Vulkan 是一个插件式后端**，不是编进核心库的。

**2. `[A]` 全仓库没有任何一处加载 `libMNN_Vulkan.so`。** 三条链路全断了：

| 链路 | 现状 | 证据 |
|---|---|---|
| CMake 链接 | 只链 `MNN` + `mnn_Express`，**没有** `MNN_Vulkan` | `MediapipeFaceLandmark/src/main/cpp/CMakeLists.txt:49-60` |
| Java/Kotlin 加载 | 只有 `System.loadLibrary("mediapipefacelandmark")` | `MediapipeFaceLandmark/.../MediaPipeFaceDetector.kt:175`（`YuvConverter.kt:18`、`MNNYuvConverter.kt:28` 同） |
| 核心库反查 | `libMNN.so` 里 `libMNN_Vulkan` 出现 **0 次**、`libMNN_` 前缀 **0 次**、`%s.so`/`lib%s.so` **0 次** → 它不可能 `dlopen` 到 Vulkan 插件 | 字节级搜索（§9.1） |

而 `libMNN_Vulkan.so` 的唯一导入注册符号就是 `_ZN3MNN28MNNInsertExtraRuntimeCreatorE…`（`libMNN.so` 里正好导出这个符号），
且带 `.init_array` 段 —— 高度符合"**被加载时由构造函数自注册 RuntimeCreator**"的插件模式 `[B]`。
**它没被加载 ⇒ VULKAN 的 RuntimeCreator 从未注册到 MNN。**

**3. `[B]` 于是 `config.type = MNN_FORWARD_VULKAN` 必然落空，只有两种结局，两种最终都是 CPU：**

- **情形 A**：`createSession()` 返回 `nullptr` → 工程代码回退 CPU 并打日志（`MediaPipeFaceLandmarkJNI.cpp:183-190`），
  UI 会显示 CPU。
- **情形 B（更符合用户现象）**：MNN 在调度阶段发现没有 creator，打印 `Can't Find type=%d backend, use %d instead`，
  **换 CPU 继续建 session 并返回成功** → 本工程的 `g_use_vulkan` 保持 `true`，UI/日志照样喊 "Vulkan"，
  **但实际每帧都在 CPU 上跑**。这句格式串在 `libMNN.so` 里确实存在（§9.1）。

→ "帧率低 + CPU 极高 + 显存空闲"和**情形 B** 完全吻合；但这需要真机 logcat 二选一（§4.1、§7）。

**4. `[A]` App 自报的 "Vulkan" 不能作为证据 —— 它报的是"请求值"，不是"实际值"。**
`nativeGetBackend()` 只返回 `g_use_vulkan`（`MediaPipeFaceLandmarkJNI.cpp:284-290`），而这个原子量**只有整个
三模型创建全部失败**时才会被置 `false`（`:183-190`）；只要 `createSession()` 返回了非空指针（情形 B），它就永远 `true`。
所以 `PERFORMANCE.md:222` 那行 `MNN backend: VULKAN, MNN threads: 4` **同样不能证明真的在 GPU 上跑**。
→ 落地方案第一步必须是"把实际后端断言出来"（§6 Step 0，`getSessionInfo(..., BACKENDS, ...)`）。

**5. `[B]` 默认并行度 = 2 路（`PipelineFaceTracker.kt:80` `GPU_CHANNEL_DEFAULT = 2`）在"其实是 CPU"的前提下是负优化。**
两路 landmark 引擎会各建一个 MNN session，在 CPU 上互相抢核、互相抢内存带宽，而 GPU 一点没吃到。
→ 这是"CPU 极高"的**第二个放大器**，且极易验证（把并行度切到 1 路看帧率，§7.1）。

**6. `[A]` 就算 Vulkan 真的跑起来，预处理也一定还在 CPU，而且每帧有两次同步。**
MNN 的 `ImageProcess`（YUV→RGB / resize / 归一化）符号只存在于 `libMNN.so`（`ImageProcess` 命中 14 次、`CV` 71 次），
**`libMNN_Vulkan.so` 里 0 次** → 预处理不走 GPU。每帧还要 `copyFromHostTensor`（CPU→GPU 上传）
+ `copyToHostTensor`（GPU→CPU 回读，强同步）：`face_detector.cpp:529-532,576-577`、
`face_landmarker.cpp:286,444,472-473`、`blendshape_predictor.cpp:163-164,191`。

**7. `[A]` GPU 后端下 `config.numThread` 根本不是"线程数"，它是 GPU 调优位掩码。**
`ScheduleConfig` 里 `numThread` 与 `mode` 是 **union**（`ThirdParty/MNN/include/MNN/Interpreter.hpp:28-32`），
`mode` 的合法值是 `MNN_GPU_TUNING_NONE=1 / HEAVY=2 / WIDE=4`（`MNNForwardType.h:67-85`）。
当前代码传 `availableProcessors().coerceIn(2,4)`（`PipelineFaceTracker.kt:500`，`MediaPipeFaceDetector.kt:116-117`），
8 核机 → 4 → 恰好等于默认的 `WIDE`（侥幸正确）；**2~3 核机 → `HEAVY`(2) 或非法组合 3**，会触发重度调优/异常组合。
这条在 GPU 下必须显式写成 `WIDE`。

---

## 1. 证据层 1：Vulkan 后端到底在哪个 `.so`，以及它是怎么被注册的

### 1.1 探测方法（可复现，不反编译）

```powershell
# 1) 可打印字符串提取（>=6 字符）
$b = [System.IO.File]::ReadAllBytes("...\jniLibs\arm64-v8a\libMNN.so")
# 逐字节过滤 32..126，连续段 >=6 输出（见 §9.1 的完整脚本）

# 2) 关键字符串/符号的字节级出现次数（比 grep 可靠，不受编码影响）
Find-Ascii libMNN.so "libMNN_Vulkan"   => 0
Find-Ascii libMNN.so "glsl_"           => 0
Find-Ascii libMNN.so "vkCreateInstance"=> 0
Find-Ascii libMNN.so "3.6.1"           => 0
Find-Ascii libMNN_Vulkan.so "glsl_"    => 170 条（去重后 ~120 个）
```

### 1.2 三个 `.so` 的分工（`[A]`）

| 文件 | 大小 | 关键内容 | 结论 |
|---|---|---|---|
| `libMNN.so` | 2,899,328 B | CPU 后端、几何、`MNN::CV`、NNAPI、KleidiAI；**导出** `MNNInsertExtraRuntimeCreator` / `getVersion` / `getSessionInfo` | 核心 + CPU 后端 |
| `libMNN_Vulkan.so` | 737,216 B | 170 个 `glsl_*`、全套 `vk*`、`VK_KHR_shader_float16_int8`、`VK_KHR_16bit_storage`；**导入** `MNNInsertExtraRuntimeCreator`；`libMNN.so` 在它的依赖里 | **Vulkan 后端插件** |
| `libMNN_Express.so` | 732,880 B | `MNN::Express` 图 API（本工程实际没用，见 §3.3） | 无关 |

`libMNN_Vulkan.so` 里的运行时错误串（可直接用于 logcat 判读）`[A]`：

```
Load vulkan library error: %s          ← dlopen("libvulkan.so") 失败（设备无 Vulkan 驱动）
Invalide device for support vulkan     ← 找到了 loader 但没有可用物理设备
Create Vulkan Shader error: %d
Don't find shader for %s               ← 某算子没有对应着色器（会被 CPU fallback）
Don't support width too large feature: %d x %d, limit = %d
Invalide user's vulkan instance
```

### 1.3 为什么"MNN 会自己加载插件"这个假设可以排除（`[A]`）

MNN 的分离后端构建里，核心库若想在运行时拉起插件，必须能**拼出**插件文件名。我们对 `libMNN.so` 做了这些搜索：

| 搜索串 | 命中 |
|---|---|
| `libMNN_Vulkan` / `MNNVulkan` / `_Vulkan` / `Vulkan` | 0 |
| `libMNN_`（前缀）/ `%s.so` / `lib%s.so` | 0 |
| `MNN_OpenCL` / `OpenCL` / `Metal` | 0 |
| `dlopen` / `dlsym` | 有（被 NNAPI 等使用，但**没有**任何后端文件名可拼） |

即：核心库里**不存在**"按名字找后端插件"的代码路径。插件只能靠**链接期的 `DT_NEEDED`** 或**显式 `dlopen`/`System.loadLibrary`** 进入进程，
而这两者在本仓库都不存在（§3.2）。**结论强度：`[A]` 事实 + `[B]` 推断；最后一个不确定点（MNN 是否把注册表藏进了别的初始化路径）用 §7.2 的
`/proc/<pid>/maps` 一条命令即可终结。**

---

## 2. MNN 版本与能力判定

### 2.1 版本

| 证据 | 结论 |
|---|---|
| `MNN_VERSION_MAJOR 3 / MINOR 6 / PATCH 1`（`ThirdParty/MNN/include/MNN/MNNDefine.h:75-78`） | 头文件 = **MNN 3.6.1** `[A]` |
| `libMNN.so` 里搜 `"3.6.1"` → **0 命中**；`getVersion` 符号存在（`_ZN3MNN10getVersionEv`） | `.so` 里没有可读版本串，**无法从二进制直接确认版本** `[A]` |
| 构建特征：clang 19.0.1 / NDK r28+、KleidiAI、`/Users/yrzhu/CLionProjects/MNN/...` 源码路径、分离后端构建 | 是**同一位维护者本机自编译**的产物，与头文件同批的可能性高 `[C]` |

→ **运行时一行就能定版**：初始化处调 `MNN::getVersion()` 打日志（符号已导出，头文件 `Interpreter.hpp:103` 有声明）。
本文后面所有"该版本行为"的推断都以 **3.6.1** 为前提，若不是，请以运行时返回值重新核对。

### 2.2 与本案直接相关的 API 能力（`[A]`，都在头文件里）

| 能力 | 位置 | 对本项目的意义 |
|---|---|---|
| `MNN_FORWARD_VULKAN = 7` | `MNNForwardType.h:35` | 现在传的枚举值没错 |
| `numThread` / `mode` union | `Interpreter.hpp:28-32` | GPU 下 `numThread` 被当调优位掩码（见 §0-7） |
| `MNN_GPU_TUNING_NONE/HEAVY/WIDE` | `MNNForwardType.h:67-75` | 显式传 `WIDE(4)` 才是"默认好性能" |
| `MNN_GPU_RECORD_BATCH` | `MNNForwardType.h:84` | Vulkan 下"所有 op 共用一条 commandBuffer"，**减少提交次数**，小模型收益明显 |
| `PrecisionMode { Normal, High, Low, Low_BF16 }` | `MNNForwardType.h:111` | 现在固定 `Low_BF16`（三处），见 §2.4 |
| `backupType` 默认 `MNN_FORWARD_CPU` | `Interpreter.hpp:64` | **算子级 CPU fallback 是默认行为**（不是异常） |
| `setCacheFile` / `updateCacheFile` | `Interpreter.hpp:189,205` | Vulkan tuning 结果可落盘，省启动抖动 |
| `HintMode`（含 `WINOGRAD_MEMORY_LEVEL` 等） | `Interpreter.hpp:207-265` | CPU 侧调优入口；GPU 侧基本无关 |
| `SessionInfoCode::BACKENDS`（int 数组） | `Interpreter.hpp:444-464` | **断言"实际后端"的正规手段**（§6 Step 0） |
| `MNNVulkanContext`（共享 VkInstance/VkDevice/Queue） | `MNNSharedContext.h:17-36` | 多引擎共享 context 的能力**存在但本工程没用**（§4.6） |

### 2.3 三个模型的算子清单 vs Vulkan 覆盖（`[A]` 从 `.mnn` 资产里的节点名提取）

| 模型 | 资产 | 提取到的算子线索（节点名） | Vulkan 着色器覆盖判断 |
|---|---|---|---|
| BlazeFace 128×128 | `face_detector_mp.mnn` 419 KB | `conv2d_*` / `depthwise_conv2d_*` / `activation_*`(ReLU6) / `add_*` / `reshape_*` / `classificator_*` / `regressor_*` / `Gather` | **全覆盖**：`glsl_convolution1x1_*`、`glsl_convolutionDepthwise_*`、`glsl_im2col_comp`、`glsl_relu6`、`glsl_binaryImage_ADD`、reshape 走 `glsl_blit` / `packAsImage4x4` `[B]` |
| FaceLandmark 256×256 | `face_landmark_detector_mp.mnn` 4.89 MB | `Conv2D`×182、`depthwise`×336、`p_re_lu`×690、`Relu`×276、`FusedBatchNorm`×140、`add`×286、`mul`×138、`Pad`×12、`Sigmoid`×4、`Reshape`×6、`Gather`×6 | **基本覆盖**：conv/dw/prelu/binary/pad 都有专门 shader；`Gather` 是否在图里被常量折叠**待真机**（若未折叠会逐算子 CPU fallback） |
| BlendShape（MLP-Mixer） | `face_blendshape_mp.mnn` 1.86 MB | `Conv2D`×134（当 MatMul 用）、`Relu`×32、`Sigmoid`×4、`StridedSlice`×28、`Transpose`×38、`Concat`×6、`Reshape`×6、`add`×64 | **有风险**：`StridedSlice`/`Transpose`/`Concat` 在 Vulkan 侧主要靠 `glsl_blit` / CPU fallback；这个模型算子"碎"（几十个 1×1 conv + 切片），即便上 GPU 也容易被**逐算子同步**吃掉收益 |

> `[C]` 这解释了为什么 `PERFORMANCE.md` 里 **BlendShape 要 4.10 ms**（占 Processing 的 78.8%）—— 一个几百 KB 量级的 MLP
> 不该这么慢，典型病因是"小算子多 + 每算子一次提交/回读"。**这条对"要不要把 blendshape 放 GPU"是负向证据。**

### 2.4 FP16 / BF16（`[A]` + `[C]`）

- `libMNN_Vulkan.so` 同时有 `*_FP16_*` 和 `*_FP32_*` 两套着色器，并声明依赖 `VK_KHR_shader_float16_int8`、`VK_KHR_16bit_storage`。
  → 设备不支持 fp16 时**会退回 FP32 着色器**，不会因此掉到 CPU。`[B]`
- 但整个 Vulkan 库里**没有任何 BF16 符号/串**（搜 `bf16|BF16|bfloat` 无命中）。
  而三处 `loadModel` 都写死 `backendCfg.precision = MNN::BackendConfig::Precision_Low_BF16`
  （`face_detector.cpp:211`、`face_landmarker.cpp:144`、`blendshape_predictor.cpp:88`）。
  → **`Low_BF16` 在 Vulkan 后端上到底被当成 fp16 还是 fp32，是黑盒**；最坏情况是按 bf16 选 kernel 而找不到 → 逐层 fallback。`[C]` **待真机 A/B**（§6 Step 2）。

---

## 3. APK 打包链路：`libMNN_Vulkan.so` 会进包，但没人加载它

### 3.1 会进包（证据链，`[A]`）

1. 文件物理位置就是 AGP 的**默认 jniLibs 源集**：`MediapipeFaceLandmark/src/main/jniLibs/arm64-v8a/{libMNN.so, libMNN_Express.so, libMNN_Vulkan.so}`。
2. 全仓库**没有**任何 `sourceSets { jniLibs.srcDirs = ... }` 覆盖（grep `sourceSets` 无命中），也没用 `packagingOptions`/`packaging { jniLibs { excludes } }`。
3. 模块启用 `externalNativeBuild` 且只过滤 `arm64-v8a`：`MediapipeFaceLandmark/build.gradle:22-24,33-38`；app 同样只留 `arm64-v8a`：`app/build.gradle:46-48`。
4. `facecapture-sdk` 用 `api project(':MediapipeFaceLandmark')` 暴露给 app（`facecapture-sdk/build.gradle` 依赖段），
   库模块的 `jni/arm64-v8a/*.so` 会随 AAR 合并进 APK。
5. `minSdk 24 / targetSdk 36 / NDK 28.2`（`version.gradle`）→ AGP 默认 `useLegacyPackaging=false`，so 以**未压缩、页对齐**方式存进 APK，
   `System.loadLibrary` 可 mmap 直载，没有 `extractNativeLibs` 坑。

→ **三个 so 都在 APK 里**。也就是说：**737 KB 的 `libMNN_Vulkan.so` 是"死重量"—— 打进去了，但进程里没它。**
验证：`unzip -l app-debug.apk | findstr libMNN`（§7.2）。

### 3.2 不会被加载（证据链，`[A]`）

- CMake 只声明了两个 IMPORTED 目标并链接：`MNN`、`mnn_Express`（`CMakeLists.txt:49-60`）——**`MNN_Vulkan` 既没 IMPORTED 也没 link**。
- Java/Kotlin 侧 4 处 `System.loadLibrary` 全部是 `"mediapipefacelandmark"`，没有任何 `"MNN_Vulkan"`：
  `MediaPipeFaceDetector.kt:175`、`YuvConverter.kt:18`、`MNNYuvConverter.kt:28`、`NeonOptimizationTest.kt:19`。
- `libMNN.so` 的字符串里没有 `libMNN_Vulkan`（§1.3）→ 不可能是它自己去 dlopen。
- `libMNN_Vulkan.so` 的依赖是 `libMNN.so`（单向），所以"加载主库顺带把插件拉起来"也不成立。

### 3.3 附带发现：`libMNN_Express.so` 大概率也是死重量

本工程 C++ 只用 `MNN::Interpreter` / `MNN::Tensor` / `MNN::CV`（`libMNN.so` 的内容），
没有 `MNN::Express` 的 `Interpreter.hpp`→`expr/*.hpp` 用法（grep `MNN::Express` 无命中）。它只是被链上去了。`[B]`

---

## 4. 让 Vulkan 静默退回 CPU 的全部路径

> 表里"证实"列给出**具体 logcat 关键字或断言**，"证伪"列给出反向判据。
> 所有 MNN 内部串都来自 `libMNN.so` / `libMNN_Vulkan.so` 的字符串提取（`[A]`）。

### 4.1 路径 ①：插件未加载 → 无 RuntimeCreator（**主因**）

- **机制**：`createSession(type=VULKAN)` 找不到 creator。
- **位置**：`MediaPipeFaceLandmarkJNI.cpp:183-190`（工程侧回退）、三处 `loadModel` 的 `createSession`（`face_detector.cpp:214`、`face_landmarker.cpp:147`、`blendshape_predictor.cpp:91`）。
- **两种表现**：
  - 情形 A：`Create Runtime Failed because no creator for 7` / `Can't create Runtime: %s` / `Runtime not valid for create session` → session 为 null → 工程打 `Vulkan backend unavailable, falling back to MNN CPU backend`（`:185`）。
  - 情形 B：`Can't Find type=7 backend, use 0 instead` → **session 非空、跑 CPU**，工程侧毫无感知。
- **证实**：`adb logcat | grep -E "no creator for 7|Cannot find type|Can't Find type|Vulkan backend unavailable"`。
- **证伪**：`adb shell cat /proc/<pid>/maps | grep libMNN_Vulkan` 有映射，且上述日志一条都没有。

### 4.2 路径 ②：`createModelsLocked` 整体失败后重试 CPU

- **位置**：`MediaPipeFaceLandmarkJNI.cpp:183-190`。**注意粒度**：只要 detector 或 landmarker 任一失败，**三个模型一起**重建成 CPU；
  blendshape 单独失败只禁用自己（`:105-116`）。
- **证实**：日志 `Vulkan backend unavailable, falling back to MNN CPU backend` + `MediaPipe face models initialized (backend=CPU, threads=N)`（`:197`）。
- **证伪**：`Face detector loaded successfully` + `Face landmarker loaded successfully`，且后端日志仍是 Vulkan（但这**不能**排除情形 B）。

### 4.3 路径 ③：额外引擎失败**不**回退，直接把并行度砍到 1

- **位置**：`nativeInitEngine` 明确不 fallback（`MediaPipeFaceLandmarkJNI.cpp:371-379`，注释也写了原因）；
  Kotlin 侧 `ensureExtraEngines` 一次失败就 `return`（`PipelineFaceTracker.kt:928-950`）。
- **这是不需要 adb 的最佳探针**：`PipelineFaceTracker` 默认请求 2 路（`:80`），启动即尝试 slot 1。
  若 Vulkan 不可用：
  - 情形 A → slot 1 直接失败 → UI 显示 **`关键点: GPU 并行 ×1/2 (资源不足)`**（`MainActivity.kt:1504`），日志 `Extra GPU engine 1 unavailable, capping parallelism at 1`。
  - 情形 B → slot 1 也"成功"（实际 CPU）→ UI 显示 **`×2/2`**，日志 `Extra engine 1 ready: backend=Vulkan`（同样是假阳性）。
- **证实/证伪**：见 §7.1 的判读表。

### 4.4 路径 ④：算子级 fallback（默认开启，且**不报错**）

- **机制**：`ScheduleConfig::backupType` 默认就是 `MNN_FORWARD_CPU`（`Interpreter.hpp:64`）。
  Vulkan 缺 shader 的算子会被 `WrapExecution` 包一层 CPU 实现，前后各来一次 device↔host 拷贝。
- **触发点**：`Don't find shader for %s`、`Don't support ...`（Vulkan 库）。对本案最可能命中的是 **BlendShape 的 `StridedSlice`/`Transpose`/`Concat`** 与 **Landmark 的 `Gather`**。
- **证实**：native log 里 `Inference: xx ms` 与 `Preprocess/Postproc` 对比异常（`perf_profiler.h` 的 `=== FaceLandmarker Perf ===` 段）；
  或 Perfetto 里看到大量小的 GPU→CPU 同步。
- **证伪**：把 `config.backupType` 显式设成 `MNN_FORWARD_CPU` **之外**没有意义；正确做法是把精度/算子铺开观察
  `Inference` 时间是否随 `Precision` 改变（§6 Step 2）。

### 4.5 路径 ⑤：`Precision_Low_BF16` 在 Vulkan 上语义不明

- **位置**：`face_detector.cpp:211`、`face_landmarker.cpp:144`、`blendshape_predictor.cpp:88`。
- **机制**：Vulkan 库无任何 BF16 kernel（§2.4）。若被解释成 bf16 → 要么建 session 失败（回到路径 ①/②），要么逐层回退。
- **证实/证伪**：把 precision 分别改成 `Normal` / `Low` 各跑 60 帧，比较 native `Inference:` 与 logcat 里 `Don't find shader` 的数量。**待真机**。

### 4.6 路径 ⑥：多引擎各建一套 Vulkan 设备，没有共享 context

- **位置**：`MediaPipeFaceLandmarkJNI.cpp:48`（`kMaxEngines=4`）、`:355-388`；每个 `loadModel` 独立 `createSession`。
- `BackendConfig::sharedContext` 从未被赋值（三个 `loadModel` 里只设了 `precision`），`MNNVulkanContext`（`MNNSharedContext.h:17-36`）完全没用上。
- **后果**（`[C]`）：2 路 = 2 套 `VkInstance/VkDevice/Queue` + 各自的工作区；显存翻倍、命令队列互相打断，
  部分驱动对同进程多 device 支持很差 → 可能第二个引擎直接创建失败（表现为 §4.3 的 `×1/2`）。
- **证实**：logcat `Extra engine 1 landmarker load failed (want=Vulkan)`（`:375`）；
  或 `dumpsys gpu` 里本进程 GPU 内存 ≈ 单路 ×2。
- **证伪**：两路都 ready，且切到 1 路后 GPU 占用明显下降而帧率不降。

### 4.7 路径 ⑦：纹理/尺寸上限

- Vulkan 库有 `Don't support width too large feature: %d x %d, limit = %d`。
- 本案输入 128×128 / 256×256，**远低于任何移动 GPU 的 2D 上限**，基本不可能命中；列在这里只为排除法完整。`[B]`

### 4.8 路径 ⑧：模型加载失败（与 GPU 无关，但会伪装成"后端问题"）

- `createFromFile` 失败 → `last_error_ = "createFromFile failed"`（`face_detector.cpp:202-205`）。
- 模型是**从 assets 复制到 cacheDir** 再交给 MNN 的（`MediaPipeFaceDetector.kt:1267-1285`），
  若 cache 目录不可写/空间不足会先在这里炸。
- **证实**：`Copying asset to cache: ...` 之后没有 `Face detector loaded successfully`。

### 4.9 路径 ⑨：上半身 Tasks GPU delegate 与 MNN 抢 GPU

- `MediapipeUpperBody` 用 MediaPipe Tasks 的 `Delegate.GPU`（`UpperBodyPoseDetector.kt:242-247,761-767`），默认 **关**
  （`MainActivity.kt:222` `mutableStateOf(false)`）。开启后它会和 MNN 争 GPU/显存。`[A]`
- **证实**：关掉上半身再测一次帧率/GPU 占用。**这是最便宜的一次 A/B。**

---

## 5. 为什么 App 会"自信地"显示 Vulkan（假阳性来源汇总）

| 假阳性来源 | 位置 | 原因 |
|---|---|---|
| `nativeGetBackend()` | `MediaPipeFaceLandmarkJNI.cpp:284-290` | 返回 `g_use_vulkan`（请求值），只有三模型全失败才变 false |
| `currentBackend` | `MediaPipeFaceDetector.kt:560,638-643` | 直接采信 `nativeGetBackend()` |
| 初始化日志 | `MediaPipeFaceLandmarkJNI.cpp:197-198` | 后端名由 `use_vulkan` 决定，非实际 |
| 每 60 帧 Perf 日志 | `PipelineFaceTracker.kt:1533` | `MNN backend: ${faceDetector.getBackend()}` 同上一行 |
| `PERFORMANCE.md:222` | 历史实测日志 | `MNN backend: VULKAN` 同样是这条链路，**不能作为 GPU 实锤** |
| `loadModel` 日志 | `face_detector.cpp:246-248` 等 | `backend=%s` 用的是入参 |

**唯一可信的判定**：`Interpreter::getSessionInfo(session, SessionInfoCode::BACKENDS, buf)`（`Interpreter.hpp:451-452`）
或进程 `/proc/<pid>/maps` 里有没有 `libMNN_Vulkan.so`。

---

## 6. 落地方案（按成本从低到高）

> 每条的"预期收益"除特别说明外都是 `[C]` 方向性判断，**具体毫秒必须真机实测**。

### Step 0 · 把"后端探测"做扎实（≈30 行，半天，先做这个）

- **改动点**（只列位置，本文不改代码）：
  1. `createModelsLocked()` 里每次建 session 后：
     ```cpp
     int backends[8] = {0};
     interpreter_->getSessionInfo(session_, MNN::Interpreter::SessionInfoCode::BACKENDS, backends);
     LOGI("session[%s] actual backend = %d (7=Vulkan, 0=CPU)", name, backends[0]);
     ```
  2. 首次初始化时 `LOGI("MNN version = %s", MNN::getVersion());`。
  3. `runSession` 前后加一个 `std::atomic<int>` 计数，每 60 帧输出 `gpu_runs/cpu_runs + 平均推理 ms`。
  4. `nativeGetBackend()` 改成返回**上面探测到的实际值**（而不是 `g_use_vulkan`），让 UI 不再骗人。
- **预期收益**：把后面所有实验从"猜"变成"看数"。**没有这一步，其余步骤无法判断有没有生效。**
- **代价**：几行日志；`getSessionInfo` 是公开 API，无副作用。
- **验证**：CPU 后端下应打印 `actual backend = 0`；若打印 7 但仍 CPU 高，说明"情形 B 是假的"，问题在别处。

### Step 1 · 真正加载 Vulkan 插件（1 行，半天）

- **改动点**（二选一）：
  - Kotlin：`MediaPipeFaceDetector` 的 `init {}` 里，`System.loadLibrary("mediapipefacelandmark")` **之前**加
    `System.loadLibrary("MNN_Vulkan")`（它自带 `DT_NEEDED libMNN.so`，加载顺序由 linker 保证）。
  - 或 CMake：`add_library(MNN_Vulkan SHARED IMPORTED)` + `target_link_libraries(... MNN_Vulkan)`，
    **必须配 `-Wl,--no-as-needed`**，否则"没人引用它的符号"会被链接器丢掉 `DT_NEEDED`。
- **预期收益**：这是"GPU 从 0 到 1"的一步；成功的话 `actual backend = 7`，CPU 占用应显著下降（landmark 从 CPU 核上挪走）。
- **代价**：几乎为零；风险是**某些设备 Vulkan 驱动差 → 反而更慢/更抖**，所以必须保留 CPU 回退开关（已有）。
- **验证**：Step 0 的日志出现 `actual backend = 7`；`/proc/<pid>/maps` 出现 `libMNN_Vulkan.so`；
  两路引擎都 ready（`×2/2`）；原生 `Inference:` 时间与 CPU 路径对比。

### Step 2 · MNN 配置收敛（约 20 行，1 天）

| 项 | 现状 | 建议 | 理由 / 代价 |
|---|---|---|---|
| `precision` | 三处 `Low_BF16` | Vulkan 下先 `Precision_Normal`（fp32）跑通，再 A/B `Precision_Low`（fp16） | Vulkan 无 BF16 kernel（§2.4）。fp32 更稳、显存/带宽更大；fp16 更快但可能有数值差异。**代价：一次 A/B 实验** |
| `mode` | 用 `numThread` 顶替 | 显式 `config.mode = MNN_GPU_TUNING_WIDE`（`MNNForwardType.h:72`） | 现在 4 线程才"碰巧"等于 WIDE；2~3 线程是 HEAVY/非法（§0-7） |
| `MNN_GPU_RECORD_BATCH` | 未开 | 试 `mode |= MNN_GPU_RECORD_BATCH` | 小模型 + 碎算子的提交开销占比高（BlendShape 4.1ms 的嫌疑），合并 commandBuffer 通常有收益；**待真机** |
| `setCacheFile` | 未用 | `interpreter_->setCacheFile(<cacheDir>/<model>.cache)` | Vulkan tuning 结果落盘，减少每次冷启动的调优抖动（`Interpreter.hpp:189`） |
| `numThread`（GPU 下） | 传 2~4 | 无意义，别当性能旋钮 | 见 union 语义 |

- **验证**：固定一段 60 帧，比较 `Inference:` 与 `p95`；`Don't find shader for` 数量应不增加。

### Step 3 · 并行度策略改成"探测驱动"（约 30 行，1 天）

- **改动点**：
  - `GPU_CHANNEL_DEFAULT` 从 2 改回 **1**（`PipelineFaceTracker.kt:80`）。理由：现在有 50% 概率是在 CPU 上开两路互相抢核；
    多路是"GPU 已经跑满之后"的优化，不是默认值。
  - 只有 Step 0 断言 `actual backend == 7` **且** `isEngineReady(1)` 时才允许 `activeChannelCount > 1`；
    已有一条 `resolveActiveChannelCount()`（`:912-921`）但它的前提 `getBackend()==VULKAN` 是假阳性，需要换成实际值。
  - 失败时 UI 明示"Vulkan 不可用，已退回 CPU 单路"，而不是只显示"资源不足"。
- **预期收益**：如果当前真的在 CPU 上跑 2 路，这一条**单独就能明显降低 CPU 占用**（landmark 工作减半），代价是延迟变大；
  两条路是"吞吐 vs 延迟"的取舍，必须实测。
- **验证**：同一段场景，1 路 vs 2 路的 `Actual output FPS` / `Frame interval p95` / `top -H` 的 CPU%。**若 2 路明显更差 → 证实都在 CPU。**

### Step 4 · 检测模型是否留在 GPU（约 0 行，实验）

- 现状：检测已节流到 **10Hz**（`PipelineFaceTracker.kt:94` `DETECTION_INTERVAL_MS_STABLE=100`），输入 128×128。
- **判断**：单次 128² 的 GPU 计算量极小，但每次推理都有**固定的提交 + 回读同步**开销（`copyFromHostTensor` 128×128×3×4 ≈ 196 KB 上传，
  回读 regressor ≈ 57 KB）。这类"小输入 + 固定开销"在 GPU 上常常**不比 CPU 快**。
- **建议**：先测；若 GPU 单次 > CPU 单次，把 detector 单独切到 CPU（`loadModel(..., use_vulkan=false)` 已支持按模型传参，
  但当前 `createModelsLocked` 是三模型同一后端，需要加一个开关）。
- **代价**：一个配置项 + 一次 A/B。**收益：把 GPU 留给真正的瓶颈 landmark。**

### Step 5 · 把预处理搬到 GPU（成本最高，先量化再决定）

- 现状（`[A]`）：`ImageProcess`（YUV→RGB + resize + 归一化）在 `libMNN.so` = CPU；每帧还有
  `input_host_tensor_` → `copyFromHostTensor` 的上传（`face_landmarker.cpp:444` 等）。
- 可选项与代价：

| 方案 | 代价 | 说明 |
|---|---|---|
| 保留 CPU 预处理，只让推理上 GPU | 0 | 大多数机型的合理选择；先把 §6 Step 1~3 做完再看 `Preprocess:` 占比 |
| 自写 Vulkan compute 做 YUV→RGB+resize | 高（数百行 + 每个 GPU 驱动都要测） | 只适合确定 preprocess > 5ms 时 |
| 用 `AHardwareBuffer`/Camera2 `Image` 的 GPU 纹理 + 共享 context（`MNNVulkanContext`） | 很高 | MNN 有接口（`MNNSharedContext.h`），但本工程完全没用过，属于新功能 |
| 换 OpenCL 后端 | 高 | **本仓库没有 `libMNN_OpenCL.so`**，需要自己编 MNN；且高通/联发科驱动差异同样大，不建议现在投入 |

- **验证**：原生 `=== FaceLandmarker Perf ===` 里 `Preprocess` 的毫秒数；若 < 2ms，直接放弃这条路。

### Step 6 · 其他后端（不建议现在做）

- **NNAPI**（`MNN_FORWARD_NN=5`）：`libMNN.so` 里有 NNAPI 后端（字符串 `ANeuralNetworks*`），但 NNAPI 对动态 shape / 自定义算子支持差，
  且这些模型每帧输入固定（可以试）。成本中等、收益不确定。`[C]`
- **QNN / CoreML**：头文件有枚举（`MNN_FORWARD_QNN=16`），但**没有对应 `.so`**，本仓库不可用。`[A]`
- **结论**：把 Vulkan 真正打开是唯一"零新增依赖"的路线，先做它。

---

## 7. 真机 5 分钟诊断步骤

### 7.1 不用 adb（只用 App 内已有的东西）

App 的 ControlPanel 已经暴露了两个关键按钮（`MainActivity.kt:1463-1509`）：

| 按钮文案 | 含义 |
|---|---|
| `推理后端: Vulkan (点击切 CPU)` / `推理后端: CPU (点击切 Vulkan)` | **请求值**，见 §5，不可全信 |
| `关键点: GPU 并行 ×1/2 (资源不足)` / `×2/2` | `activeChannelCount / gpuChannelCount`，**这个更有信息量** |

**步骤 1（10 秒）看并行度按钮。** 默认请求 2 路（`PipelineFaceTracker.kt:80`），额外引擎**不会**回退 CPU（`:371-379`）：

- 显示 **`×1/2 (资源不足)`** → **Vulkan 明确不可用**（情形 A）。可以基本结案。
- 显示 **`×2/2`** → 主引擎"建 session 成功"。**仍可能全是 CPU**（情形 B），继续步骤 2。

**步骤 2（30 秒）切后端做 A/B。** 点"推理后端"→ CPU，等 3 秒，再点回 Vulkan，来回两次。观察：
- 面板 FPS / 手感**完全没变化** → Vulkan 没生效（否则两种后端至少会有几 ms 差异）。
- 切到 Vulkan 后 **CPU 占用（第三方悬浮窗/系统开发者选项的"GPU 渲染"看不到 CPU，用系统"电池/CPU"或游戏助手）明显上升** → 说明它在 CPU 上。

**步骤 3（1 分钟）并行度 A/B。** 点"关键点"按钮切到单路，看 FPS 与机身发热：
- **单路明显更流畅/更凉** → 之前两路都在 CPU 抢核，`[B]` 结论成立。

**步骤 4（2 分钟）关上半身。** 默认关（`MainActivity.kt:222`）；如果开着，关掉再测（§4.9）。

**步骤 5（2 分钟）导出日志（debug 包）。** ControlPanel 底部有"导出日志 (debug)"（`MainActivity.kt:1581-1600`），
把 zip 里的 `logs/vfc.log*` 拿来搜下面的关键字。

> ⚠️ 注意：`VfcLog` 只落盘 **Kotlin/Java 侧**的日志；`LogExporter.kt:35` 明确说明导出包**不含 logcat / native 日志**。
> 所以 **C++ 侧（`MediaPipeFaceJNI` / `FaceLandmarker` / MNN 自己的 `MNNJNI`）的日志只能用 adb 抓**（§7.2 第 2 条）。
> 这也是为什么 `[A]` 结论里的"插件有没有加载"必须靠 §7.2 的 `/proc/<pid>/maps` 而不是 App 内导出。

| 关键字 | 说明 |
|---|---|
| `MNN backend in use:` | App 自报后端（假阳性，仅参考） |
| `Vulkan backend unavailable, falling back to MNN CPU backend` | **情形 A 实锤** |
| `MediaPipe face models initialized (backend=CPU` | 已在 CPU |
| `Extra GPU engine 1 unavailable` | Vulkan 不可用（第二证据） |
| `GPU parallel channels: N / M requested` | 实际并行度 |
| `Actual output FPS` / `Frame interval p95` | 帧率与抖动 |

### 7.2 用 adb（能给出确定性答案，按顺序执行）

```bash
# 0) 先确认 APK 里到底有没有三个 so（不需要 root）
adb shell pm path com.example.virtualfacecapture
adb pull /data/app/.../base.apk
unzip -l base.apk | grep -i "lib/arm64-v8a/libMNN"
#   期望：libMNN.so / libMNN_Express.so / libMNN_Vulkan.so 都在 → 证明"打包没问题，是加载问题"

# 1) ★ 决定性一条：进程里有没有映射 Vulkan 插件
adb shell "cat /proc/$(adb shell pidof com.example.virtualfacecapture)/maps" | grep -i mnn
#   只有 libMNN.so + libMNN_Express.so → Vulkan 插件从未加载 → 结案（情形 A/B 都成立）
#   有 libMNN_Vulkan.so → 插件已加载，继续看 2)

# 2) MNN / 本工程 / Vulkan loader 的日志（只在 debug 包或 log 级别够低时可见）
adb logcat -c && adb logcat -s MediaPipeFaceJNI:V MediaPipeFace:V FaceDetector:V FaceLandmarker:V BlendShapePredictor:V MNNJNI:V
#   抓这些串：
#   "no creator for 7" / "Can't Find type=7 backend" / "Can't create Runtime"
#   "Create Vulkan Shader error" / "Don't find shader for" / "Load vulkan library error"
#   "Invalide device for support vulkan"
#   "Inference: xx.xx ms"（native perf，每 60 帧一条）

# 3) GPU 到底忙不忙（分厂商）
adb shell dumpsys gpu | head -80                 # 每进程 GPU 内存/驱动信息（Android 10+）
# Adreno（高通）：
adb shell cat /sys/class/kgsl/kgsl-3d0/gpubusy   # "busy total" 两个数，busy/total = 利用率
adb shell cat /sys/class/kgsl/kgsl-3d0/gpuclk    # 当前频率（空闲会掉到最低档）
adb shell cat /sys/class/kgsl/kgsl-3d0/gpumem    # GPU 内存占用（"显存"）
# Mali（联发科/三星）：
adb shell cat /sys/class/misc/mali0/device/utilisation   # 需 root；无 root 用 /sys/kernel/gpu/gpu_busy
adb shell cat /sys/kernel/gpu/gpu_busy /sys/kernel/gpu/gpu_clock

# 4) CPU 到底是谁在烧（关键：区分推理线程 / 相机线程 / 主线程）
adb shell top -H -p $(adb shell pidof com.example.virtualfacecapture) -d 1 -n 5
#   看 fc-* 线程名（PipelineFaceTracker.kt:200-216 起的名字：face-detect / face-landmark / face-landmark-gpu0..3）
#   若 face-landmark* 单核 90%+ → 推理在 CPU；若它们只有 20~30% 而总 CPU 高 → 瓶颈在预处理/拷贝

# 5) 帧时间与 GPU 排队（可选，10 分钟）
adb shell perfetto -o /data/misc/perfetto-traces/gpu.pftrace -t 10s \
  sched freq idle gfx view am wm
#   看 fc-* 线程的 Running 时间 vs GPU completion（gfx 轨道）；GPU 轨道空、CPU 满 = 铁证

# 6) 只切 Vulkan 跑 10 秒的对照实验（推荐）
#   切 CPU 采一次 → 切 Vulkan 采一次，比较：
#   - /sys/class/kgsl/kgsl-3d0/gpubusy 的 busy 占比
#   - top -H 的 fc-* CPU%
#   - logcat 的 Inference: ms
#   三者都没变 → Vulkan 没生效。
```

### 7.3 判读表

| 观测 | 结论 | 下一步 |
|---|---|---|
| `/proc/<pid>/maps` 无 `libMNN_Vulkan` | **插件未加载**（本文主结论） | 做 §6 Step 1 |
| 有 `libMNN_Vulkan` 但 `gpubusy` ≈ 0、`fc-*` 单核 100% | 插件加载了但 session 仍在 CPU → 查 `Can't Find type=7` / `createSession` 失败 | §4.1 / §4.2 |
| 有 `libMNN_Vulkan` 且 `gpubusy` 20~60%、`gpuclk` 升到高档 | Vulkan 真的在跑；瓶颈在 CPU 侧预处理/回读 | §6 Step 5，先量化 `Preprocess:` |
| `gpumem` 只有几十 MB | **正常**，不能据此判断"没跑 GPU" | 看 `gpubusy` / `gpuclk` 才有意义 |
| 切 CPU / Vulkan 帧率无差别 | 两种后端实际相同（= 都在 CPU） | §6 Step 0 先加断言 |
| 单路明显优于 2 路 | 之前两路在 CPU 抢核 | §6 Step 3 |

> **关于"显存明显空闲"这个现象本身**：即使 Vulkan 完全正常工作，这两个模型的权重只有 4.89 MB + 1.86 MB，
> 中间 buffer 也就几十 MB 量级；**"显存占用低"不能单独证明没跑 GPU**。
> 真正有判别力的是 **GPU busy% / 频率 / 本进程 GPU 内存三者一起看**（§7.2 第 3 条）。`[C]`

---

## 8. 待真机证实清单（按重要性）

1. **`libMNN_Vulkan.so` 是否真的从未被加载** —— `adb shell cat /proc/<pid>/maps | grep mnn`（一条命令定生死）。
2. **MNN 3.6.1 在"无 creator"时是"建 session 失败"还是"静默换 CPU"** —— logcat 里是 `no creator for 7` 还是 `Can't Find type=7 backend, use 0 instead`。
   这决定了 UI 显示 CPU 还是假 Vulkan。
3. **`Precision_Low_BF16` 在 Vulkan 上的实际行为** —— 改 `Normal`/`Low` 各跑 60 帧，比较 `Inference:` 与 `Don't find shader` 数量。
4. **BlendShape 4.1 ms 的构成** —— 是 `StridedSlice/Transpose/Concat` 的 CPU fallback，还是模型本身大？用 native perf 的 `Inference:` 拆分。
5. **多引擎各自建 VkDevice 是否可行/是否更慢** —— 两路 vs 单路的 `gpubusy` + `p95`。
6. **检测（128²@10Hz）放 GPU 是否值得** —— 单次 GPU vs CPU 的直接对比。
7. **`MNN::getVersion()` 的真实返回值** —— 确认 .so 与头文件同为 3.6.1。
8. **`Preprocess:` 的实际毫秒数** —— 决定 §6 Step 5 是否值得投入。

---

## 9. 附录：本次探测的命令与原始输出摘录

### 9.1 字符串/字节探测脚本（PowerShell，本机可复现）

```powershell
# 提取可打印字符串（>=6）
$b = [System.IO.File]::ReadAllBytes($path)
$out = New-Object System.Collections.Generic.List[string]
$sb = New-Object System.Text.StringBuilder
foreach ($x in $b) {
    if ($x -ge 32 -and $x -lt 127) { [void]$sb.Append([char]$x) }
    else { if ($sb.Length -ge 6) { $out.Add($sb.ToString()) }; [void]$sb.Clear() }
}
$out | Set-Content "libMNN.so.strings.txt"

# 字节级统计（不受 grep 编码影响）
function Find-Ascii($path, $needle) { ...逐字节比较... }
```

### 9.2 关键计数（原始输出）

```
libMNN.so            : 2,899,328 B, strings=3553
libMNN_Vulkan.so     :   737,216 B, strings=1953
libMNN_Express.so    :   732,880 B, strings=872

'libMNN_Vulkan'  in libMNN.so        : 0
'libMNN_'        in libMNN.so        : 0
'%s.so'/'lib%s.so' in libMNN.so      : 0 / 0
'glsl_'          in libMNN.so        : 0
'vkCreateInstance' in libMNN.so      : 0
'Vulkan'         in libMNN.so        : 0
'MNNInsertExtraRuntimeCreator' in libMNN.so       : 1
'MNNInsertExtraRuntimeCreator' in libMNN_Vulkan.so: 1
'libMNN.so'      in libMNN_Vulkan.so : 1
'ImageProcess'   in libMNN.so / libMNN_Vulkan.so : 14 / 0
'CV'             in libMNN.so / libMNN_Vulkan.so : 71 / 0
'3.6.1'          in 三个 .so            : 0 / 0 / 0
glsl_* shader strings in libMNN_Vulkan.so : 170
```

### 9.3 MNN 侧关键错误串（来自 `libMNN.so`）

```
Can't Find type=%d backend, use %d instead
Create Runtime Failed because no creator for %d
Create Runtime failed, the creator return nullptr, type = %d
Runtime not valid for create session
Can't create Runtime: %s
%d backend don't support resize fix optimize
type=%d backend don't Support Low Power, use %d instead
```

### 9.4 本文件引用的代码位置一览

| 主题 | 位置 |
|---|---|
| 后端选择 / 回退 | `MediapipeFaceLandmark/src/main/cpp/MediaPipeFaceLandmarkJNI.cpp:183-190,197-198,284-290,355-388,371-379` |
| 三模型 session 配置 | `.../src/core/face_detector.cpp:207-218`、`face_landmarker.cpp:140-151`、`blendshape_predictor.cpp:84-95` |
| 每帧上传/回读 | `face_detector.cpp:529-532,576-577`、`face_landmarker.cpp:286,444,449,472-473`、`blendshape_predictor.cpp:163-164,170,191` |
| 链接与打包 | `.../cpp/CMakeLists.txt:47-60`、`MediapipeFaceLandmark/build.gradle:22-24,33-38`、`app/build.gradle:46-48` |
| Kotlin 加载/后端 | `MediaPipeFaceDetector.kt:175,560,605-643` |
| 并行度 | `PipelineFaceTracker.kt:80,497-518,912-921,928-950,1533` |
| UI 按钮 | `MainActivity.kt:488-492,1463-1509` |
| MNN API | `Interpreter.hpp:28-32,64,189,207-265,444-464`、`MNNForwardType.h:35,67-85,111`、`MNNSharedContext.h:17-36` |
