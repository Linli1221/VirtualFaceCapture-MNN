# 第二轮 ROI 分支改造：128p 检测 / 720p 脸部 ROI / 320p 上半身 ROI

> 分支：`feat/mediapipe-upper-body`
> 目的：把用户给出的三段式优化流程图落到代码：相机 720p YUV 一次分发，检测固定 128、
> 脸部关键点只吃 720p 源帧上的脸 ROI、上半身由脸框引导裁到 320p。
>
> **验证状态（重要）**：本机没有 JDK / Android SDK / 真机，**没有编译、没有跑单测、
> 没有实测毫秒数**。本文里的性能数字是静态估算（依据写在各条后面），CI 构建与真机日志
> 才是结论。数学部分用独立 Python 脚本复算过（见 §5）。

---

## 0. 流程图 → 代码对照

| 流程图节点 | 本轮实现 | 说明 |
|---|---|---|
| Camera2 720p60 采集 | 未改（上一轮已完成） | `Camera2Manager` |
| 零拷贝 YUV 帧分发 | 已有两路 channel 共享 ByteBuffer；本轮给原生补 `tryFastYuvView` | 相机线程仍只搬指针 |
| 128p 检测分支 | 保持 BlazeFace **128×128** 输入；**没有**再加一次 YUV→128p 预下采样 | MNN `ImageProcess` 是输出驱动采样：只采 16k 个输出像素，先下采样反而多读整帧 |
| 人脸框 + 6 关键点 → 一级扩充 | `PipelineFaceTracker.expandFaceBox(1.15×)` | 给 10Hz 框的滞后 + 二级扩充留余量 |
| 坐标映射回 720p | 已有（检测输出按旋转后尺寸归一化） | 语义未变 |
| 720p 面部 ROI 裁切 + roll 对齐 | `FaceLandmarker::buildYuvTransform` + 源图 ROI bbox | 只打包 ROI，不再整帧 1.38MB |
| 面部部位二级扩大 | `transformFaceRect` 的 1.5×（未改） | 与一级 1.15× 叠加 ≈ 1.725× |
| FaceLandmarker 高清推理 | 仍 256×256 输入，但源采样来自 720p 脸 ROI | 模型输入尺寸由 .mnn 固定，不能改 |
| 478 点 + 头姿 + 52 BlendShape | 未改 | |
| 上半身由脸框引导 | `UpperBodyRoi.fromFace` | 脸框 → 4.5×/5.5× 上半身 ROI |
| 裁切 → 320p → ARGB → PoseLandmarker | `YuvBitmapConverter` 的 ROI 采样 + `TARGET_LONG_SIDE=320` | 只采样 ROI 内像素 |
| 33 → 13 点 / One-Euro / 躯干角 | 未改；但**先回映到整帧再 extract** | 协议 `ub[]` 语义不变 |
| TrackingOutputManager → WS → PC | 未改 | |

---

## 1. 原生：脸部 ROI-only YUV 打包

### 1.1 问题

改前 `FaceLandmarker::detectFromYuv` 每帧把整帧 720p YUV 重排进 `yuv_buffer_`
（1,382,400 B），再交给 MNN `ImageProcess` 采样 256×256。整帧数据里只有脸 ROI 那一小块
会被采样，其余 90% 的拷贝是纯浪费。

### 1.2 做法

1. 把原来内联在 `preprocessImageFromYuv` 里的矩阵构造抽成
   `buildYuvTransform()`（dst 像素 → 整帧 source 像素），整帧/ROI 两条路径共用，
   避免两份矩阵逻辑漂移。
2. `detectFromYuv` 先算 **source 空间的 ROI bbox**：
   - 用 `trans.mapXY` 把 256×256 输入的 4 个角映射回 source；
   - 取 bbox，四周外扩 **8 px**；
   - x0/y0 向下、x1/y1 向上对齐到偶数（YUV 420 的 UV 起点/长度必须偶数）；
   - clamp 到 source 边界。
3. ROI 只有同时满足 `roiW>=64 && roiH>=64 && area < 85% 整帧` 才启用；否则整帧。
4. 命中时：
   - `yuv::prepareBufferRect` 只把 ROI 打包进 `yuv_roi_buffer_`；
   - `trans.postTranslate(-roiX, -roiY)`，让矩阵输出 ROI 局部坐标；
   - `preprocessImageFromYuvWithMatrix(src, fmt, roiW, roiH, trans, dest)`。
5. 整帧 fallback 也升级了零拷贝：`yuv::tryFastYuvView` 只在
   `uvPixelStride==2 && yRowStride==width && uvRowStride==width` 且 U/V 指针恰好紧接
   Y 平面（NV12/NV21）时才直接传相机缓冲；否则照旧 `prepareBuffer`。
   检测器 `FaceDetector::detectFromYuv` 同样接入这个快路径。
6. 每实例首次命中/退回各打一条日志（`std::atomic<bool>` 防多 worker 竞争），
   日志里带 ROI 尺寸、位置、占整帧比例，方便真机确认覆盖率。

### 1.3 边界正确性

- **越界采样**：ROI 是按「整帧 source 坐标」的 4 角 bbox 算的；如果脸贴边导致 bbox
  超出 source，clamp 后 ROI 只覆盖 source 内区域。超出 source 的采样点在整帧路径里
  本来也是 `wrap=ZERO`，ROI 路径的 `ImageProcess` 同样 `wrap=ZERO`，所以数值一致。
- **半像素/roll**：矩阵本身没改，只是最后补一个 source 平移；roll 对齐、正方形化、
  1.5× 二级扩充全部保持原逻辑。
- **UV 对齐**：ROI 起点/宽高偶数化，`prepareBufferRect` 内部再做一次防御性对齐。
- **零拷贝**：任何布局不匹配（有 padding、U/V 不相邻、I420）都返回 false，必须走
  repack fallback；不做机型猜测。

### 1.4 预期收益（静态估算）

| 项 | 改前 | 改后 | 估算 |
|---|---|---|---|
| landmark 每帧 YUV 打包 | 1.38 MB | 典型 0.15–0.4 MB（脸占 10–30%） | -0.15~0.30 ms/帧 + 带宽 |
| 检测每帧 YUV 打包 | 1.38 MB（10Hz） | 命中快路径时 0 | -0.15~0.30 ms/次（均摊 0.025–0.05 ms/帧） |
| `ImageProcess` 采样像素数 | 256×256 | 256×256 | 不变（仍然只采样输出像素） |
| 推理 | 14.5 ms（旧实测锚点） | 不变 | 瓶颈仍是推理 |

> 收益的意义不是把 60fps 变成 70fps，而是把 p95 从 18.9ms 往 16.7ms 以内拉。

### 1.5 风险

- ROI bbox 方向/平移写反会直接采样错位。已用 Python 复算矩阵（见 §5），但**必须**
  用固定图做「ROI 开/关」逐像素 diff（本机做不了）。
- 脸特别大（ROI > 85% 整帧）会退回整帧，这是预期行为，不是 bug。
- `tryFastYuvView` 依赖相机 buffer 连续；不命中只会多一次 repack，不会错。

---

## 2. 上半身：脸框引导的 320p ROI

### 2.1 问题

改前：上半身 tap 在相机线程上把**整帧**按长边 256 采样成 ARGB，再喂 PoseLandmarker。
人在画面里只占很小一块，模型看到的上半身有效像素可能不到 100×150；远距离 / 坐姿时
关键点抖动明显。

### 2.2 做法

```
SDK 最近脸框（一级扩充 1.15×，TTL 500ms）
  → FaceRoiHint（旋转后、未镜像、归一化）
  → UpperBodyRoi.fromFace：
       bodyW = faceW × 4.5，bodyH = faceH × 5.5
       水平以脸中心为中心；top = faceTop - faceH × 0.35
       先夹尺寸到帧内，再夹位置；任一边 < 48px → 整帧
  → RoiWindow（旋转后源像素）
  → YuvBitmapConverter 只采样 ROI，缩放到长边上限 320（只缩不放）
  → PoseLandmarker
  → 33 点在 ROI 局部坐标 → mapLocalToPreview 回映整帧 → 13 点 / One-Euro / 躯干角
```

**回映公式**（`sx=roi.w/W`、`sy=roi.h/H`、`left=roi.x0/W`、`top=roi.y0/H`）：

- 非镜像：`px = left + lx·sx`
- 镜像：`px = (1 - left - sx) + lx·sx`
- `py = top + ly·sy`
- `z  = lz·sx`（MediaPipe 的 z 与 x 同尺度，以输入图宽度为单位；不缩放会让躯干 yaw
  随 ROI 大小漂移）

满帧 ROI 时两条公式都退化为恒等，所以旧调用 / 没脸框时的行为不变。

### 2.3 回退

- 没脸框 / 脸框非法 / 脸 < 12px / ROI < 48px → 整帧 320p。
- 用 ROI 的帧没跟踪到上半身（`extract` 为空或 `isTracking=false`）→
  `fallbackUntilMs = now + 1000ms`，窗口内**一律整帧**，到期自动回 ROI。
  这样避免「ROI 失败一帧 → 整帧成功一帧 → 回 ROI 又失败」造成 `ubOk` ~7.5Hz 抖动。
- 回退窗口内整帧成功**不**提前结束窗口（否则又回到逐帧交替）。

### 2.4 成本与收益（静态估算）

| 项 | 改前 | 改后 |
|---|---|---|
| 采样输出像素 | 720×1280 → 144×256 ≈ 3.7 万 | ROI 450×440 → 320×313 ≈ 10 万（典型） |
| 上半身有效像素 | 脸高约 80px / 帧高 1280px | 同一张脸在 320p 里约 200px 高（4–5×） |
| 相机线程同步转换 | 1–4 ms / 66ms | 约 +0.5–1 ms（输出像素变多），15fps 下 duty 仍很低 |
| 精度 | 小目标抖动明显 | 预期明显更稳（需真机 A/B） |

> 这是一次**画质优先**的改动：用每 66ms 多约 0.5ms 的转换，换上半身在输入里大 4–5 倍。
> 如果真机发现 ROI 经常失败或转换超预算，先调 `FACE_WIDTH_TO_BODY` /
> `FACE_HEIGHT_TO_BODY` / `TARGET_LONG_SIDE`，不要直接回退整帧。

### 2.5 风险

- **镜像 / 90°·270° 旋转**：坐标空间最容易错。公式已用 JS 像素级反推，仍要在真机上
  对前置 + 横竖屏目视确认骨架与画面一致。
- **脸框坐标系**：假设脸框是「旋转后、未镜像」的归一化坐标（`FaceBox` 的既有约定）；
  如果上游给错，ROI 会框偏。1s 整帧回退是兜底，日志里的 ROI 尺寸是定位入口。
- **z 尺度**：假设 MediaPipe z 按输入图宽度归一化；若按长边，yaw 会有 ROI 长短边比
  量级的偏差（ROI 近似方形时 <5%）。
- **Bounding box 抖动**：ROI 由 10Hz 脸框驱动，框本身会跳；回映到整帧后坐标稳定，
  但 ROI 边界变化会轻微改变模型输入尺度，One-Euro 会吸收。

---

## 3. 一级扩充与提示 TTL

- `PipelineFaceTracker.expandFaceBox`：检测框中心放大 **1.15×**，clamp [0,1]；
  非 YUV / YUV 两条检测路径都先扩充再存。
- 目的：检测 10Hz + landmark 推理延迟下，框可能滞后 100–150ms；一级扩充给这个滞后
  和原生 1.5× 二级扩充留余量，减少大角度/快速移动时顶点被裁。
- `onYuvFrameForBodyWithHint` 的新回调带 `FaceBox?`；超过 **500ms** 没刷新就传 null，
  让上半身回退整帧。旧 `onYuvFrameForBody` 保留，两者都挂时只调新回调。

---

## 4. 明确没做的事

1. **检测的 YUV→128p 预下采样**：没有加。BlazeFace 输入本来就是 128×128，MNN
   `ImageProcess` 只按输出像素采样；先整帧下采样到 128p 会多读一遍源图，反而更慢。
   流程图里的「128p」在本仓库等价于「检测输入 128×128」。
2. **上半身转换移出相机线程**：仍然是同步转换（契约要求 ByteBuffer 在回调内消费）。
   ROI 已把采样量限制在人的区域；彻底解耦需要帧池/拷贝，留到下一轮。
3. **YUV plane 生命周期（P0-1）**：没有动。它仍是已知正确性风险，与本轮性能改造正交。
4. **模型输入尺寸**：landmark 仍 256、pose 仍由 Tasks 内部决定，不换模型。
5. **roll 对齐**：`transformFaceRect` 保留了两眼连线的 roll 分支，但当前 YUV 生产路径的
   `FaceBox` 只带框 + 分数、不带 6 个关键点，所以实际 `face_rotation=0`。要真正启用需要
   把检测器的 6 点从 JNI 一路传到 landmarker（本轮未做）。

---

## 5. 数学与静态验证

本机没有编译器，已做的：

1. Python 复算原生矩阵（row-vector、post 顺序与现有代码一致）：
   - rotation 0/90/180/270 × 随机/边界 face box × roll ±0.8rad；
   - 对每个 ROI 用 21×21 网格采样，验证**凡落在 source 内的采样点都在 ROI 内**
     （越界点两边都是 ZERO）；
   - 结果：5,000 组里约 4,200 组触发 ROI，越界误差 0。
2. Python 复算上半身回映：随机 local 点 → preview → 反解 local，非镜像/镜像误差
   ~2e-16（浮点极限）。
3. `UpperBodyRoiTest` 14 个 JVM 用例覆盖：无脸/非法脸/小脸/小 ROI 回退、边界 clamp、
   镜像、z 缩放、320 长边、窗口 helper。
4. `git diff --check` 干净；Kotlin/C++ 括号与字符串闭合人工检查。

**仍必须由 CI/真机完成**：

- `:app:assembleDebug`、`:MediapipeUpperBody:testDebugUnitTest`；
- 固定图 ROI 开/关 diff（原生）；
- 上半身骨架在 0°/90°/270°、前置镜像下与画面一致；
- 日志确认 `YUV ROI path: first hit` 的 ROI 比例、`ROI 引导生效` 的尺寸；
- 上半身 ROI 与整帧 A/B：远端坐姿下关键点抖动、手臂角度是否更稳。

---

## 6. 文件清单

**原生**
- `MediapipeFaceLandmark/src/main/cpp/include/utils/yuv_utils.h`
- `MediapipeFaceLandmark/src/main/cpp/include/face_landmarker.h`
- `MediapipeFaceLandmark/src/main/cpp/src/core/face_landmarker.cpp`
- `MediapipeFaceLandmark/src/main/cpp/src/core/face_detector.cpp`

**上半身**
- `MediapipeUpperBody/src/main/java/com/example/mediapipeupperbody/UpperBodyRoi.kt`（新）
- `.../YuvBitmapConverter.kt`
- `.../UpperBodyPoseDetector.kt`
- `.../src/test/java/com/example/mediapipeupperbody/UpperBodyRoiTest.kt`（新）
- `app/src/main/java/com/example/virtualfacecapture/UpperBodyPoseController.kt`

**接线**
- `facecapture-sdk/src/main/java/com/facecapture/sdk/PipelineFaceTracker.kt`
- `app/src/main/java/com/example/virtualfacecapture/MainActivity.kt`
