# 后处理迁移设计：Android 后处理链 → Windows 完整后处理

> 本文是 **P0 规格**：把 Android 的 OpenSeeFace 风格后处理链整体迁到 Windows，
> Android 只保留感知层。协议（rawTrackV1 二进制帧、协商、控制消息）见
> [docs/raw-track-protocol.md](./raw-track-protocol.md)；上层语义仍以
> [docs/tracking-protocol.md](./tracking-protocol.md) 为准。
>
> **附录 A~J 是机器常量表 / 状态转移表**（由 win-postproc 移植清单提供、以 worktree
> 源码为准），实现与评测**直接引用本文表格取值**，不得另行取值或「顺手修正」——
> 两份文档漂移是本任务明令禁止的失败模式。

---

## 0. 已拍板裁决（写死，不再讨论）

| # | 裁决 | 影响 |
|---|------|------|
| 1 | frameId uint32 为**重排/去重/丢旧帧的唯一主键**；seq = frameId & 127、epoch = frameId >> 7 仅诊断 | §4 第 2 步；协议 §7 |
| 2 | **校准所有权迁 Windows**：手机只响应 resetTracking / startStream / stopStream；recalibrate 是 PC 侧动作 | §6 |
| 3 | raw 链延迟语义 = **Web Worker 侧固定 500 ms playout 缓冲是唯一人为延迟**；interp.js 不改，但 raw 模式下 stage 以 jitterBuffer **最小口径**启动（jitterBufferV1 自适应预缓冲不叠加）；v1 文本 track 路径的 jitterBufferV1 行为保持不变 | §4 第 9/10 步 |
| 4 | **不上 SharedArrayBuffer**（Electron 走 http://127.0.0.1:17890，crossOriginIsolated=false）；跨线程一律 **Transferable ArrayBuffer / TypedArray** | §4 线程模型 |
| 5 | **worker 缺失 / 异常必须回退同步路径**，保证 --smoke 在无 raw 流时也 ready | §4 第 1 步 |
| 6 | **TrackingConfidenceDetector 留在 Android 感知层**（重检测触发），不迁 Windows | §2 |
| 7 | FaceCapturePipeline 第 2/3 步存在**单位不一致**（extractKeyPoints 像素转换 vs avgLandmarks 未转换）：按源码**保真移植**，golden 评测列为**已知观察项**，禁止迁移时顺手修正 | §7.5 |
| 8 | wire 的 52 维 BlendShape 一律**协议下标序**（附录 A）；ARKitBlendShapes **构造器声明顺序 ≠ 下标序**，映射/评测禁止按下标直读构造器字段 | 附录 A |
| 9 | Android 实际 **advancedMode=false**，只产出 17 个非零下标；黄金对齐**分层**（主层 false、副层 true） | §7 |
| 10 | 黄金对齐评测（golden parity）是 **P2（Windows 后处理）的验收依据** | §7 |
| 11 | **temporal-filter = face_pipeline_filter.h TimeBasedEMA 移植**（tau 默认 **0.12** = face_landmarker.cpp 构造实参 DEFAULT_TIME_EMA_TAU=0.12 的线上实跑值，可配、下限 0.01、dt clamp [0.001,0.5]s、首样本透传；0.08 只是被覆盖的默认构造值，仅作可配值）；**作用域 = 478 点关键点域 + rawHeadPose，不作用于 52 维 BlendShape 域**；在链序中位于**提取之前**。任务书原「时间滤波在眨眼后」**作废**：那是写任务书时未发现原生关键点域 EMA 的口径，按 Android 迁移前原链（原生 EMA → 提取 → 眨眼）只有滤波在提取前才与迁移前观感一致，也才能进 golden 基准 | §4 第 5 步、附录 K |
| 12 | **Android raw 模式旁路原生关键点 EMA**，raw_track 携带**未滤波 MNN 输出**（主口径）；回退口径：raw 带已滤波点位时置 LANDMARKS_PRE_SMOOTHED（flags bit5），Windows 不再平滑关键点域 | 协议 §4.2、附录 K.4 |
| 13 | **校准累计器（rawEAR/rawMAR/rawSmileFeature）内置 BlendShapeExtractor**，接口 add(landmarks, headPoseRaw)；expression-calibrator 只管窗口策略与 serialize/load | §5、附录 K.5 |

---

## 1. 目标与范围

### 1.1 迁移后的责任边界

    Android（感知层，只做）          Windows（后处理，全做）
    ─────────────────────          ──────────────────────────
    Camera2 YUV 采集                rawTrackV1 接收 / 解析
    人脸检测 + MNN 478 点            frameId 重排 + 丢过期帧
    rawHeadPose（Procrustes）        BlendShape 提取（478 → 52）
    presence / detectionBox          姿态 / 表情校准（含眨眼校准）
    inferenceMs / cameraFps          眨眼曲线状态机
    （可选）上半身 13 点原始抽取      时间滤波（One-Euro / EMA）
    TrackingConfidenceDetector       上半身融合（TorsoBaseline + 骨骼映射）
    控制消息（流控三命令）            姿态归一化 + 参数映射
                                     固定 500 ms playout 缓冲 + 插值
                                     Live2D 参数应用（主线程）

### 1.2 不迁 / 不改

- **TrackingConfidenceDetector**（附录 J）：重检测触发是感知层的推理调度决策
  （IoU / presence / 运动分 → 是否重跑 BlazeFace），留在 Android，不迁。
- **interp.js（ParamTimeline）**：结构不改；只改启动口径（§4 第 9 步）。
- **v1 文本 track 路径**：jitterBufferV1 / send.bufferMs / 500 ms 手机缓冲行为**逐字节不变**。
- **上半身协议 ORDER**（附录 H）：不变更。

---

## 2. 迁移映射表：Android 模块 → Windows 模块

| Android（源文件） | 当前职责 | 迁移判定 | Windows 目标模块 | 备注 |
|---|---|---|---|---|
| FaceCapturePipeline.kt（OpenSeeFaceProcess） | 一条龙编排：距离估计、keyPoints、姿态基线、relativePose、extractor.calibrate / setCalibrationPose | **拆分迁移** | windows-post-pipeline（编排）+ pose-calibrator + expression-calibrator | 基线减除、校准收集全部迁 PC；顺序见附录 G，逐条保真（含 §7.5 观察项） |
| BlendShapeExtractor.kt | extractFullBlendShapes：眼→注视→嘴→微笑→advanced 分支→统一 clamp | **整体迁移** | blendshape-extractor | clamp 只作用于附录 A 的 7 个字段；advancedMode 默认 false（附录 A） |
| SimpleEyeExtractor.kt | EAR 计算、pitch/yaw/距离补偿、眼校准（closed/open/wide） | **整体迁移** | blendshape-extractor（子单元）+ expression-calibrator（校准统计） | 常量与顺序见附录 C；「校准收集 rawEAR（未补偿）」的顺序必须保留 |
| SimpleMouthExtractor.kt | MAR、pitch 补偿、jawOpen 映射、MAR 校准 | **整体迁移** | blendshape-extractor + expression-calibrator | 附录 D |
| SimpleSmileExtractor.kt | 外轮廓 smile/frown 特征、pitch/roll 补偿、动态范围 | **整体迁移** | blendshape-extractor + expression-calibrator | 附录 E；前置条件（mouthOuter>=16、faceHeight 阈值）不满足时整帧不写 |
| AdvancedBlendShapeExtractor.kt | 眉/颊/颌/鼻/嘴高级/eyeSquint（advancedMode=true 才跑） | **整体迁移（副层）** | blendshape-extractor（advanced 分支） | 附录 F；P2 主层不做，golden 副层单独覆盖 |
| BlinkCurveProcessor.kt | 6 态眨眼曲线 + 眯眼锁值 + 双眼同步 | **整体迁移** | blink-curve | 附录 B 状态转移表是唯一契约；代码行为 ≠ 注释处以**代码**为准（表内已标注） |
| TrackingConfidenceDetector.kt | 跟踪置信度 → 是否重跑检测 | **不迁**（留 Android 感知层） | — | 附录 J；raw 链不消费其输出 |
| facecapture-sdk FrameReorderBuffer.kt | 乱序推理结果按 frameId 还原 | **语义迁移**（换输入源） | raw-frame-reorder | 缺口 60 ms / maxPending 8 / 迟到丢弃 / 首帧直放；主键改用 u32 frameId（32 位序列算术） |
| facecapture-sdk FaceCaptureEngine / PipelineFaceTracker | 感知输出（landmarks / rawHeadPose / presence / frameId / t） | **保留 Android** | — | rawTrackV1 的数据源；frameId/t 在相机回调打点 |
| MediapipeUpperBody UpperBodyPoseDetector | Pose 推理、13 点抽取、isTracking | **保留 Android** | — | 只发原始点（ubRaw 扩展块） |
| MediapipeUpperBody OneEuroFilter | 逐点/逐角 One-Euro | **整体迁移** | temporal-filter + body-fusion | 附录 H 公式与参数；Kotlin Float vs JS double 用容差对拍（附录 I） |
| MediapipeUpperBody TorsoBaseline | 10 帧基线 + 渐入 | **整体迁移** | body-fusion | 可持久化、可手动重置（§6） |
| MediapipeUpperBody UpperBodySmoother | 13 点平滑编排、坏点透传 | **整体迁移** | body-fusion | visibility 不平滑；ZERO 点透传且不推进滤波器 |
| MediapipeUpperBody UpperBodyGeometry | 躯干角、退化对、isTracking/quality | **整体迁移** | body-fusion | 附录 H：0.02 分母下限必须保留 |
| app tracking TrackingProtocol / TrackingServer / TrackingFrameBuffer / TrackingSendQueue | JSON 序列化、WS、500 ms 缓冲、发送队列 | **保留 Android（legacy）+ 新增 raw 发送**；500 ms 缓冲在 raw 模式旁路 | windows-post-pipeline 的 playout 承接 500 ms | 协议 §2.5；control 保序规则保留 |
| windows-client renderer mapping.js | 参数映射（头/脸 + 上半身） | **保留 Windows** | mapping.js（接入新管线输出） | 公式不变；输入从 track.bs/pose 换成管线输出 |
| windows-client renderer filter.js / calibrate.js / interp.js / live2d.js | 时间滤波、PC 姿态偏移、时间轴、渲染 | **保留 Windows**（interp.js 不改） | 并入/对接 windows-post-pipeline | filter.js 承接 temporal-filter 的观感层（禁止第三层平滑，附录 I.8） |

---

## 3. Windows 处理链总览

    WS 收 raw_track（OP_BINARY）
      │  [Web Worker：解析 / 重排 / 点域滤波 / 提取 / 校准 / 眨眼 / 融合]
      ▼
    Transferable TypedArray → 主线程
      │  [主线程：映射 → 500ms playout 缓冲 → 插值 → Live2D]
      ▼
    Live2D 参数应用（60 fps 渲染循环）

- **Web Worker 承担 CPU 重活**（478 点解析 + 几何解算）；主线程只做映射与渲染。
- 跨线程数据 = **Transferable ArrayBuffer / TypedArray**（零拷贝）；**禁止 SharedArrayBuffer**
  （crossOriginIsolated=false，裁决 4）。
- **worker 缺失 / 构造失败 / 运行时异常 → 回退同步路径**（全部步骤在主线程跑同样代码），
  UI 降级提示一次；--smoke 无 raw 流时必须仍然 ready（裁决 5）。

---

## 4. Windows 端 12 步处理顺序（逐步契约）

顺序**不可调换**；带 [S] 的步骤有内部状态，输入必须单调（frameId / 时间），
乱序输入要么被上游重排挡掉、要么在接口层显式拒绝（附录 I.8）。

| # | 步骤 | 模块 | [S] | 输入 → 输出 | 为何不能乱序 |
|---|------|------|-----|------------|--------------|
| 1 | **接收** | windows-post-pipeline | — | WS message（OP_BINARY raw_track 或文本 track 降级）→ ArrayBuffer | 同一连接只消费一种 track 语义（协议 §2.2）；解析失败计数不抛异常；**worker 不可用时本步直接走同步路径** |
| 2 | **frameId 重排** [S] | raw-frame-reorder | ✅ | 乱序帧 → 有序帧流 | 主键 = u32 frameId（32 位序列算术比较）；缺口最多等 60 ms、pending ≤ 8、迟到旧帧丢弃（附录 I.8）；先于一切时序处理 |
| 3 | **丢过期帧** | raw-frame-reorder | — | 有序帧 → 有效帧 | 高水位之外（≤ lastEmittedId）的帧是旧会话残留；新 hello/resetTracking 后高水位清空。**先丢旧帧再算帧间隔**，否则间隔统计被乱序污染 |
| 4 | **帧间隔 / 时间戳** [S] | windows-post-pipeline | ✅ | 帧时间戳 → dt（ms） | dt 是时间滤波 / 眨眼曲线 / 时间轴的时基；只能用**已重排**的相邻差值；点域滤波 dt clamp [0.001, 0.5]s（附录 K），上半身 dt clamp [1, 500]ms、无锚点取 1/15 s（附录 H） |
| 5 | **时间滤波（提取前）** [S] | temporal-filter | ✅ | 478 点 + rawHeadPose → 滤波后点 / 姿态 | **作用域 = 478 点关键点域 + rawHeadPose，不作用于 52 维 BlendShape 域**（附录 K）；TimeBasedEMA 移植（tau 默认 0.12 = DEFAULT_TIME_EMA_TAU 线上实跑值，可配、下限 0.01、首样本透传；0.08 仅是被覆盖的默认构造值）；必须在提取**之前**（与 Android 迁移前观感一致；任务书原「时间滤波在眨眼后」作废，见 §0 裁决 11） |
| 6 | **BlendShape 提取** [S] | blendshape-extractor | ✅ | 478 点 + rawPose + 图幅 → 52 维（协议下标序，附录 A） | 含距离估计 EMA、keyPoints 提取、眼/嘴/微笑/advanced 解算；输出**未过眨眼曲线、bs 域不平滑**（附录 K.3）；校准累计器（rawEAR/rawMAR/rawSmileFeature）内置本模块（附录 K.5） |
| 7 | **姿态 / 表情校准** [S] | pose-calibrator + expression-calibrator | ✅ | rawPose / raw 特征 → 校准后值 + 基线 | 姿态基线（首窗 1 s 且 ≥10 帧，附录 G）；眼/嘴/微笑**各单元在 extract 过程中自我校准**（独立 1000ms + ≥10 帧窗口自动落地，外部禁止二次喂入）；**校准窗口收集未补偿 raw 特征**（附录 C/D/E）；expression-calibrator 只做窗口进度观测 + serialize/load + reset（附录 K.5） |
| 8 | **眨眼状态机** [S] | blink-curve | ✅ | 校准后 blinkRaw（EAR 代理）→ 眨眼曲线值 | 6 态机（附录 B）对**时间**敏感：必须吃校准后输入；曲线是整形不是平滑，bs 域不再叠加时间平滑 |
| 9 | **上半身融合** [S] | body-fusion | ✅ | ubRaw 13 点 + torsoRaw → 身体参数 | 逐点 One-Euro → 躯干角 → 躯干角 One-Euro → TorsoBaseline（附录 H）；与面捕并行、按 ubT 对齐 |
| 10 | **参数映射** | mapping.js + live2d.js | —（归一化器有状态） | 参数 → 模型参数 | 映射公式不变（tracking-protocol.md §8）；姿态归一化（AdaptiveAngleNormalizer）与 pcOffset 在**校准后**进行；toModelRange 按模型量程等比缩放 |
| 11 | **固定 500 ms playout** [S] | windows-post-pipeline | ✅ | 参数帧 → 匀速放行 | **全链唯一人为延迟**（裁决 3）：预缓冲 500 ms 后按生产节奏匀速放行（TrackingFrameBuffer 语义移植：起播预缓冲 / 匀速放行 / 深度调节 / 120 条溢出丢最旧）；raw 模式下手机端缓冲已旁路，若 PC 再叠 jitterBuffer 自适应预缓冲会双倍延迟 → **stage 以 jitterBuffer 最小口径启动**；v1 文本路径不变 |
| 12 | **插值 → Live2D 应用** [S] | interp.js（ParamTimeline，不改）+ live2d.js | ✅ | 放行帧 → 渲染时刻采样 → 模型参数 | 时间基准用 t（相机回调 wall-clock 相邻差值），时钟偏移锚定、快升慢降、见底外推上限沿用，sampleAt 单调只前进；60 fps 渲染循环内应用；上半身与头部 ParamBodyAngleX **相加后 clamp** 不互相覆盖；参数删除交还 idle motion（附录 I.8） |

**步骤 6/7 的耦合说明**：表情校准器（眼/嘴/微笑窗口统计）在**提取过程中**收集未补偿
raw 特征（附录 C/D/E 的执行顺序），校准完成后阈值立即生效；这是「提取 ↔ 校准」单向
数据流（提取喂校准器，校准器回阈值），不允许校准器反向改写已输出帧。

**状态生命周期**：新 hello / resetTracking / 断线重连 / 切模型 → 步骤 2、4、5、6、7、8、9、11
的全部 [S] 状态 reset（校准文件的持久化部分按 §6 保留）；步骤 10 按 interp.js 既有
resetTimeline 语义。

---

## 5. 状态模块清单与职责边界

| 模块 | 职责 | 状态 | 明确不做 |
|------|------|------|----------|
| **raw-frame-reorder** | u32 frameId 排序、缺口超时放行、过期帧丢弃、会话高水位 | pending 表、lastEmittedId、gapStart、forceSkipGap | 不做插值、不看 t、不做降级 |
| **blendshape-extractor** | 478 点 + rawPose → 52 维原始 BlendShape（下标序）；距离估计 EMA；keyPoints 提取；**校准累计器内置**（rawEAR/rawMAR/rawSmileFeature，add(landmarks, headPoseRaw)） | 距离 EMA、各提取器实例状态、校准累加槽 | 不做平滑、不做眨眼曲线、不减姿态基线 |
| **blink-curve** | 6 态眨眼曲线 + 眯眼锁值 + 双眼同步（附录 B） | 每眼状态机、earHistory、baselineEAR、时间戳 | 不做 EAR 计算（上游给 blinkRaw）、不做 One-Euro |
| **expression-calibrator** | **窗口进度观测 + serialize/load + reset**（附录 K.5）；阈值产出与窗口统计在各提取器内部完成 | 窗口进度快照（每眼 + 嘴 + 微笑）、isCalibrated | **不拥有累计器、不触发二次校准**（防止基线双份）；不做姿态基线、不动眨眼状态机 |
| **pose-calibrator** | 姿态基线（1 s 且 ≥10 帧窗口）、relativePose = raw − baseline、pcOffset 叠加、归一化器 reset 时机 | 基线三轴、窗口累加、校准 pose 快照 | 不做表情阈值、不做插值 |
| **temporal-filter** | **face_pipeline_filter.h TimeBasedEMA 移植**（附录 K）：作用域 = 478 点关键点域 + rawHeadPose；**bs 域默认不平滑**（可配挂点，缺省 τ=0） | 每通道 lastValue/dt 锚点、tau 配置 | 不作用于 bs 域、不做重排、不做基线、不叠第二层平滑 |
| **body-fusion** | ubRaw 13 点：One-Euro → 躯干角 → 躯干角 One-Euro → TorsoBaseline；骨骼映射融合（mapBody / smoothBodyParams 既有逻辑） | 逐点滤波器、躯干滤波器、基线、dt 锚点、丢失衰减状态 | 不做面捕、visibility 不平滑、坏点透传 |
| **windows-post-pipeline** | 编排器：步骤顺序、线程模型（worker/同步回退）、Transferable 移交、playout 500 ms、状态生命周期、降级（文本 legacy） | playout 队列、节奏 EMA、dt 锚点、模式状态 | 不含任何算法本体（算法都在上面模块） |

接口纪律（附录 I.8）：有状态模块必须暴露 **reset()**；输入接口加单调性检查
（frameId / t 不回退），检测到乱序/重复显式拒绝并计数，**不得静默接受**。

---

## 6. 校准：Windows 拥有

### 6.1 控制流

    PC UI / 快捷键
      │  recalibrate {target: face|body|all, requestId}（PC 内部动作，不发手机）
      ▼
    windows-post-pipeline → pose-calibrator / expression-calibrator / blink-curve / body-fusion
      │  清基线 + 重新收集校准窗口（1 s 且 ≥10 帧）→ 写校准文件
      ▼
    本地校准文件（localStorage + 可导出 JSON）

- 手机只响应 **resetTracking / startStream / stopStream**（协议 §6.2）；
  resetTracking 清感知层（切摄像头 / 换人时由 PC 主动发），**与校准无关**。
- 旧客户端的 command:recalibrate 由手机兼容映射为 resetTracking（deprecated，协议 §6.3）。
- **幂等**：同 requestId 重复触发只执行一次并回同一 ack；窗口内同 target 触发合并。
  **超时**：2 s 未完成 → UI 提示可重试，重试带新 requestId。
- recalibrate 触发时：姿态归一化器、AdaptiveAngleNormalizer、pcOffset ramp 一并 reset
  （tracking-protocol.md §8.2 / §8.3 的「reset 归一化窗口」语义保留）。
- ubOk=false / 上半身丢失：平滑器全重置 + dt 锚点清零，**基线不动**（附录 H）。

### 6.2 校准文件 JSON schema（vfc-calibration/v1）

维度 = **model（Live2D 模型名）+ cameraProfile（手机 + 相机 + 朝向）**；换模型、换机、
换镜头各存各的。

    {
      "schema": "vfc-calibration/v1",
      "model": "Haru",                        // Live2D 模型名（profileKeyFor(modelName) 同源）
      "cameraProfile": {
        "device": "Pixel 8 Pro",
        "app": "VirtualFaceCapture/1.0.3",
        "cameraId": "0",
        "lensFacing": "front",
        "rotationDegrees": 270,
        "mirrorHorizontal": true,
        "vfovDeg": 63.0,                      // setCameraVerticalFov 的输入
        "imageWidth": 720,
        "imageHeight": 1280
      },
      "createdAt": 1730000000000,             // Unix ms
      "source": "auto | manual | imported",
      "pose": {
        "baseline": { "pitch": 0.0, "yaw": 0.0, "roll": 0.0 },   // 姿态基线（raw − baseline）
        "pcOffset": { "pitch": 0.0, "yaw": 0.0, "roll": 0.0 },   // 本地显示校准（±45°）
        "rangeScale": 1.0                     // 归一化量程系数 [0.5, 2]
      },
      "expression": {
        "eyeLeft":  { "closed": 0.10, "open": 0.18, "wide": 0.26, "calibPitch": 0.0, "calibYaw": 0.0, "calibFaceRatio": 0.4 },
        "eyeRight": { "closed": 0.10, "open": 0.18, "wide": 0.26, "calibPitch": 0.0, "calibYaw": 0.0, "calibFaceRatio": 0.4 },
        "mouth":    { "closed": 0.10, "open": 0.45, "calibPitch": 0.0 },
        "smile":    { "baselineLeft": 0.0, "baselineRight": 0.0,
                      "observedSmileMax": 0.06, "observedFrownMax": 0.04,
                      "calibPitch": 0.0, "calibRoll": 0.0 }
      },
      "blink": { "baselineEarLeft": 0.18, "baselineEarRight": 0.18 },   // 状态机 baseline 的持久化（可选）
      "body": {
        "torsoBaseline": { "roll": 0.0, "yaw": 0.0 },
        "collectedFrames": 10
      },
      "checksum": "sha256:<hex>"              // 防手改损坏；校验失败按未校准处理
    }

- 保存时机：校准完成（收齐窗口）时一次 + 页面卸载；**平滑器 / 状态机运行态不持久化**
  （冷启动重调，基线保留）。
- schema 不认识的字段**忽略**；缺字段按附录默认值。checksum 不符 → 整份作废回默认。
- 换模型：activateBodyBaselineProfile / activatePoseProfile 的 profile 语义与
  profileKeyFor(modelName) 对齐（mapping.js 既有）。

---

## 7. 黄金对齐评测（golden parity，P2 验收依据）

### 7.1 目标

证明 Windows 后处理与 Android 原实现在**同一输入**下输出在容差内一致：
逐帧、逐维、可重放、可 CI。**分层**（裁决 9）：

- **主层（P2 必过）**：advancedMode = false（Android 现状），17 个非零下标
  （附录 A：9,10,11,12,13,14,15,16,17,18,21,22,25,30,31,44,45）+ pose + blink + 上半身。
- **副层（可后置）**：advancedMode = true 的 35 维（附录 F）单独 fixture 单独报告。

### 7.2 raw 帧录制格式（.vfcraw）

    偏移 0   : magic "VFCRAW1
"（8 B）
    偏移 8   : u32 version = 1（LE）
    偏移 12  : u32 metaJsonLen（LE）
    偏移 16  : metaJson（UTF-8：device、cameraProfile、git sha、开始时间、模式、图幅）
    之后     : N × [ u32 frameLen（LE）][ frameBytes ]
               frameBytes = 原样 rawTrackV1 消息 payload（协议 §4，可带 ubRaw 块）
    结尾     : u32 frameLen = 0（结束标记）

- 录制入口：Android debug 包的录制开关（把发往 WS 的 raw_track payload 同步落盘），
  或 Windows 接收端旁路录制（工具 tools/verify/record-raw.mjs）。
- metaJson 里记录**同一录制内的期望值来源**（见 §7.3）；录制文件不进 Git（大文件），
  CI 用合成 fixture（§7.4）。

### 7.3 Android 期望值导出

- **期望值 = Android 原实现对录制输入的逐帧输出**。导出方式：
  1. Android debug 包提供 golden 导出器：对每帧记录
     { frameId, t, bs[52]（协议下标序）, relativePose[3], blinkOut[2], body[2]（若开上半身）,
       中间量：rawEAR×2、rawMAR、smileFeature×2、earProxy、blinkState }，
     写 expected.json（与 .vfcraw 同名）。
  2. 纯逻辑类（TrackingProtocol / 提取器 / BlinkCurveProcessor）可直接跑 **JVM 单测导出**
     （不依赖 Android SDK）：把 .vfcraw 解析回 478 点序列，喂进 Kotlin 原实现，逐帧 dump。
     本机无 JDK → 由 android-ci.yml / GitHub Actions 生成产物下载。
- expected.json 与 .vfcraw 一起构成 golden 对；Windows 侧跑同一 .vfcraw，逐帧对比。
- **基准边界（验收报告必须写明，防下游误读）**：本机无 JDK/Android SDK 时，「黄金基准」
  是照 Kotlin 源码逐条抄的**第二份独立转录**，不是 Kotlin 运行输出 —— 它能抓抄写/分支/
  阈值类笔误（win-postproc 的 golden 评测即以此抓到 3 处真问题），**不能**证明与 Kotlin
  逐位一致。该缺口由 task-5 / task-7 用**真·Kotlin 输出**对拍补齐：消费
  tools/evals/golden-export/ 跨端数据集（12 场景 × 40 帧 + manifest + verify.mjs + SHA-256
  哈希表），JVM 端跑 Kotlin 原实现导出期望值后逐帧比对。

### 7.4 合成 fixture（CI 必跑，不依赖真机）

- tools/verify/gen-raw-fixture.mjs：确定性生成 478 点序列（固定 seed），至少覆盖：
  neutral-hold（静止 2 s）、pitch-sweep（±30°）、yaw-sweep（±40°）、roll-sweep、
  blink-cycle（快眨 ×5 + 长眯 0.5 s）、smile-ramp、jaw-open-ramp、face-lost（300 ms 空洞）、
  jitter-dt（帧间隔 8~40 ms 抖动）、reorder-gap（乱序 + 缺口注入）、ub-arm-raise（上半身）。
- fixture 文件（.json：点序列 + rawPose + t + 图幅）是**单一事实来源**：Kotlin 侧
  UpperBodyPostEquivalenceTest 模式与 JS 侧 tools/verify/golden-test.mjs 都读同一份。
- fixture 生成器内置**独立参考实现**（不复用被测 JS 模块，避免实现与自测共码的假阳性，
  沿用 upper-body.md §3.7 的既有做法）。

### 7.5 逐帧误差阈值

| 层 | 对比量 | 阈值 | 备注 |
|----|--------|------|------|
| 量化往返 | x/y | ≤ 1.53e-5（绝对，归一化域） | 协议 §4.3 公式的数学上限 |
| 量化往返 | z | ≤ 1.53e-4（绝对） | Z_SCALE/32767 |
| 感知层 | poseRaw | 逐位相同（float32 直传） | — |
| 解算层 | 52 维（主层 17 下标） | abs ≤ 0.01；动态段 abs ≤ 0.02 | Kotlin Float vs JS double，附录 I.1 |
| 解算层 | 其余 35 恒 0 下标 | == 0 | advancedMode=false 主层 |
| 眨眼层 | blinkOut[2] | abs ≤ 0.02 | 曲线阶段逐帧对齐；状态序列要求**完全一致** |
| 姿态层 | relativePose | abs ≤ 0.5°（每轴） | — |
| 上半身 | torso roll/yaw | abs ≤ 0.5° | 沿用 upper-body.md §3.7 既有验收 |
| 上半身 | 13 点平滑后 x/y/z | abs ≤ 1e-4 | One-Euro 跨语言 |
| 参数层 | Live2D 契约量程 ±30 | abs ≤ 0.5（≈1.7% span） | 归一化器状态相同时 |
| 统计口径 | — | 报告 max / mean / p95 | 附录 I.1 |

**容差口径（Lead 终裁，golden-export 口径表同此）**：跨端 Kotlin↔JS 比对基线
**abs 1e-4 / rel 1e-4**（503f549）；JS↔JS（双转录自检）沿用 abs 1e-5 / rel 1e-4。
逐层阈值（上表 0.01/0.5°/0.5 等）是**语义层**验收线，两套并存、同时满足才算过。

- **状态机额外断言**：blink 6 态序列、眯眼锁值进入/退出、双眼同步触发时机必须**逐帧一致**
  （状态是离散的，不适用数值容差）。
- 通过标准：主层 fixture 全部用例 100% 在阈值内；.vfcraw 真机样本 ≥ 300 帧通过率 100%；
  丢帧/乱序/时钟抖动注入用例通过。

### 7.6 已知观察项（**保真复刻，禁止顺手修正**）

1. **FaceCapturePipeline 单位不一致（裁决 7）**：第 ② 步 extractKeyPoints(landmarks,
   imageWidth, imageHeight) 在 landmarks 归一化且给图幅时逐点 ×W/×H 转像素；第 ③ 步
   校准用 avgLandmarks 调 extractKeyPoints(avgLandmarks) **不带宽高、不做转换**。
   两条路径的 keyPoints 单位可能不一致。Windows 移植必须**逐行复刻同一不一致**，
   golden 评测把它列为观察项（记录两侧输出差异），修正与否另立任务裁决。
2. 附录 C：左眼 finishCalibration 瞬间写 calibrationPitch/Yaw/FaceRatio，随后被
   setCalibrationPose(baseline…) 覆盖 —— 顺序照抄。
3. 附录 B：transitionTo 清零规则、CLOSING 眯眼整条曲线 ×0.5、BLINK_DETECT /
   BLINK_CLOSED 的空历史 fallback 分别是 baselineEAR / 0f —— 均以**代码行为**为准
   （表内已按代码写，注释不符处不跟注释）。
4. 附录 C：yaw 补偿的远/近侧分支按代码（yaw>0 时 imageLeft=远侧），Kotlin 注释自相矛盾，不跟注释。
5. **EAR / MAR 的画幅（宽高比）依赖（win-postproc 发现，保真复刻不修）**：
   extractKeyPoints 的像素转换是**各向异性**的（x × imageWidth、y × imageHeight、z 不变），
   因此「垂直距离 / 水平距离」型比值特征都带 **H/W 因子**：EAR = 归一化 EAR × (H/W)、
   MAR 同理。例：归一化 EAR=0.18 在 1280×720 下变成 0.18×0.5625=0.10125，恰好压到默认
   闭眼阈值 0.10，eyeBlink 从 ~0 跳到 ~0.98；同一张脸 720×1280 竖幅结果又不同。
   含义：(a) 这是 Kotlin 真实行为，Windows **按源码复刻、不修**（与观察项 1 同一原则）；
   (b) 自校准从同一坐标空间学阈值可自适应，但**默认阈值 0.10/0.18/0.26 只对特定画幅成立**；
   (c) **golden 对齐的参考实现与被测实现必须喂同一 width/height**，画幅是 fixture 的一部分
   （写进 .vfcraw metaJson 与合成 fixture），拿 16:9 与 9:16 的录数据对齐会得出「移植错了」
   的错误结论。另：Float32 往返使「合成 EAR 恰好等于阈值」类用例留 ~2e-6 残余，
   §7.5 的 abs 1e-5 / rel 1e-4 基线容差已覆盖。

---

## 8. 分阶段实施顺序与验收清单

### P0 规格（本任务）

- [x] docs/raw-track-protocol.md（协议 + 协商 + 控制 + 异常）
- [x] docs/post-pipeline-migration.md（迁移 + 12 步 + 状态模块 + 校准 + golden）
- 验收：Lead 评审通过；android-raw / win-postproc / win-integrate 以本文为唯一契约。

### P1 Android 感知层 raw 输出（task-4，android-raw）

- [ ] raw_track 打包（帧头 + 478 点量化 + ubRaw 扩展块），逐字节符合协议 §4/§5
- [ ] frameId / t 在相机回调打点（System.currentTimeMillis()）；cameraFps / inferenceMs 必备
- [ ] OP_BINARY 发送（sendFrame）+ per-client 分发（rawTrackV1 客户端二进制 / v1 客户端文本）
- [ ] 仅存在 v1 客户端时才跑手机后处理链（否则旁路）
- [ ] startStream / stopStream / resetTracking + ack（requestId 幂等）；raw 模式旁路 500 ms 缓冲、control 保序
- [ ] legacy 文本路径逐字节不变（回归：TrackingProtocolTest / 现有 CI）
- 验收：android-ci.yml 绿；抓包比对协议字节偏移表；单帧打包 < 0.3 ms。

### P2 Windows 后处理（task-5，win-postproc）

- [ ] raw-frame-reorder / blendshape-extractor / blink-curve / expression-calibrator /
      pose-calibrator / temporal-filter / body-fusion / windows-post-pipeline 八模块
- [ ] 常量与状态转移**逐条**按附录 A~J；附录 I 数值陷阱逐条落实
- [ ] 校准文件 schema v1 + 幂等/超时控制流
- [ ] golden parity 主层通过（§7.5），观察项（§7.6）如实报告
- [ ] worker + 同步回退双路径；--smoke 无 raw 流 ready
- 验收：golden 主层 100% 阈值内；node tools/verify/golden-test.mjs 绿；selftest 绿。

### P3 集成（win-integrate）

- [ ] WS 双模接收（OP_BINARY raw_track / 文本 legacy）互斥状态机 + 降级
- [ ] Web Worker 接线（Transferable，无 SharedArrayBuffer）；异常回退同步
- [ ] 500 ms playout 缓冲接入 stage（jitterBuffer 最小口径启动）；interp.js 不改
- [ ] mapping.js / live2d.js 接新管线输出；参数层 golden 用例
- [ ] UI：校准按钮（face/body/all）、校准状态、降级提示、调试面板（frameId/epoch/seq）
- 验收：真机联调 60 fps 稳定；--smoke ready；v1 文本路径回归不变。

### P4 集成验收（task-6，spec-arch）

- [ ] 对照本文与协议文档逐条验收 P1~P3
- [ ] golden 全层（含 .vfcraw 真机样本）通过；观察项清单确认
- [ ] 与 tracking-protocol.md / upper-body.md 的交叉引用无漂移
- 验收：验收报告 + 遗留问题清单（含 §7.6 观察项的修正裁决建议）。

---

## 9. 附录 A~J：机器常量表与状态转移表（win-postproc 移植清单）

> 以下表格是**唯一取值来源**；权威 = worktree 源码。Kotlin/JS 实现与 golden 评测都按
> 本表逐值核对。标注「按代码」的条目以代码行为为准（注释有误也不跟）。

### 附录 A：52 维 BlendShape 字段序（wire 一律下标序）

ARKitBlendShapes **构造器声明顺序**（眼14/眉5/颊3/颌4/嘴23/鼻2/舌1）与**协议数组下标
顺序完全不同**。协议 wire / mapping / 评测一律按下标序：

    0 NEUTRAL(恒0)      1 BROW_DOWN_LEFT     2 BROW_DOWN_RIGHT   3 BROW_INNER_UP
    4 BROW_OUTER_UP_LEFT 5 BROW_OUTER_UP_RIGHT 6 CHEEK_PUFF      7 CHEEK_SQUINT_LEFT
    8 CHEEK_SQUINT_RIGHT 9 EYE_BLINK_LEFT    10 EYE_BLINK_RIGHT  11 EYE_LOOK_DOWN_LEFT
   12 EYE_LOOK_DOWN_RIGHT 13 EYE_LOOK_IN_LEFT 14 EYE_LOOK_IN_RIGHT 15 EYE_LOOK_OUT_LEFT
   16 EYE_LOOK_OUT_RIGHT 17 EYE_LOOK_UP_LEFT 18 EYE_LOOK_UP_RIGHT 19 EYE_SQUINT_LEFT
   20 EYE_SQUINT_RIGHT 21 EYE_WIDE_LEFT     22 EYE_WIDE_RIGHT   23 JAW_FORWARD
   24 JAW_LEFT         25 JAW_OPEN          26 JAW_RIGHT        27 MOUTH_CLOSE
   28 MOUTH_DIMPLE_LEFT 29 MOUTH_DIMPLE_RIGHT 30 MOUTH_FROWN_LEFT 31 MOUTH_FROWN_RIGHT
   32 MOUTH_FUNNEL     33 MOUTH_LEFT        34 MOUTH_LOWER_DOWN_LEFT  35 MOUTH_LOWER_DOWN_RIGHT
   36 MOUTH_PRESS_LEFT 37 MOUTH_PRESS_RIGHT 38 MOUTH_PUCKER     39 MOUTH_RIGHT
   40 MOUTH_ROLL_LOWER 41 MOUTH_ROLL_UPPER  42 MOUTH_SHRUG_LOWER 43 MOUTH_SHRUG_UPPER
   44 MOUTH_SMILE_LEFT 45 MOUTH_SMILE_RIGHT 46 MOUTH_STRETCH_LEFT 47 MOUTH_STRETCH_RIGHT
   48 MOUTH_UPPER_UP_LEFT 49 MOUTH_UPPER_UP_RIGHT 50 NOSE_SNEER_LEFT 51 NOSE_SNEER_RIGHT

- tongueOut 仅 Kotlin 内部字段，**不在 52 维数组里**；协议数组恒 52 项。
- clamp() **只 clamp 7 个字段**：9 eyeBlinkLeft、10 eyeBlinkRight、21 eyeWideLeft、
  22 eyeWideRight、25 jawOpen、44 mouthSmileLeft、45 mouthSmileRight；其余字段在各
  提取器内已 coerceIn(0,1)。
- **默认行为**：FaceCapturePipeline 用 BlendShapeExtractor() = advancedMode=false，
  且高级分支要求 landmarks.size>=468 && advancedMode。Android **实际只产出**下标
  9,10,11,12,13,14,15,16,17,18,21,22,25,30,31,44,45（17 维），其余恒 0。
  golden 主层按 advancedMode=false 对齐，true 分支单独覆盖（§7.1）。

### 附录 B：BlinkCurveProcessor 状态机

**常量表**

| 常量 | 值 |
|---|---|
| CLOSING / CLOSED / OPENING 时长 | 80 ms / 30 ms / 120 ms |
| BLINK_CONFIRM_FRAMES（30fps 基准） | 2 |
| DROP_RATE_THRESHOLD / MIN_TOTAL_DROP | 0.12 / 0.35 |
| SQUINT_TIME_THRESHOLD_MS / SQUINT_BLINK_VALUE | 300 ms / 0.5 |
| SYNC_WINDOW_MS | 50（双眼同步常量） |
| blinkSpeedScale | 1.0，clamp [0.3, 5.0] |
| enableBinocularSync / isEnabled | true / true（置 true 时 reset） |
| earHistory 容量 / baselineEAR 初值 | 5 / 0.18 |
| currentFrameIntervalMs 初值 | 33.3；updateFrameRate(fps) = fps>0 ? 1000/fps : 33.3 |
| getDynamicConfirmFrames() | floor(2 * (33.3 / currentFrameIntervalMs))，clamp [1,5] |
| deltaMs | lastUpdate>0 ? clamp(now−last, 1, 100) : round(currentFrameIntervalMs) |
| 输入换算 | earProxy = 1 − rawBlinkValue |
| closingCurve(t) / openingCurve(t) | (1−cos(clamp(t,0,1)·π))/2 / (1+cos(clamp(t,0,1)·π))/2 |

**transitionTo(newState, now)**：state=newState；stateStartTimeMs=now；
**除进入 SQUINT_HOLD 外一律 curveProgress=0**（等价 if (state!=SQUINT_HOLD) curveProgress=0，
【按代码】）。调用方在 transitionTo **之后**再设 curveProgress=0.5 的两处例外必须保留。

**状态转移表**（输出列 = 该状态每帧输出值）

| 状态 | 输出 | 每帧行为 / 转移 |
|---|---|---|
| TRACKING | 恒 0 | earProxy > baseline·0.85 → baseline = baseline·0.95 + earProxy·0.05；历史≥2：dropRate=(prev−curr)/baseline，>0.12 → consecutiveDropFrames=1, dropStartEAR=prev, →BLINK_DETECT, return；lowThreshold=baseline·0.65，earProxy<lowThreshold：首次置 isInLowEarZone=true, lowEarStart=now（**不清标志**，按代码），now−lowEarStart>300 → isBlinkNotSquint=false, →CLOSING；否则 isInLowEarZone=false |
| BLINK_DETECT | 恒 0 | totalDrop=(dropStartEAR−curr)/baseline；dropRate=(prev−curr)/baseline；totalDrop>0.42 → isBlinkNotSquint=true, →CLOSING, return；dropRate>0.06 → consecutiveDropFrames++，frames>=dynamicConfirm && totalDrop>0.35 → isBlinkNotSquint=true, →CLOSING, return；否则 dropRate<−0.036 且 totalDrop<0.175 → →TRACKING, consecutiveDropFrames=0, return；timeInState>300：currEar=earHistory.lastOrNull() ?: **baselineEAR**，currEar<baseline·0.4 → isBlinkNotSquint=true →CLOSING（慢速眨眼），否则 isBlinkNotSquint=false →CLOSING（眯眼） |
| CLOSING | 眨眼 closingCurve(p)；眯眼 closingCurve(p)·0.5 | duration=80·speed；p += delta/duration；眨眼 p>=1 → p=0, →BLINK_CLOSED；眯眼 p>=1 → →SQUINT_HOLD（**不清 p**）【按代码：整条曲线×0.5 铺满 80 ms，不是半程停】 |
| BLINK_CLOSED | 恒 1 | duration=30·speed；p += delta/duration；currEar=earHistory.lastOrNull() ?: **0f**【按代码，与 BLINK_DETECT 的 baselineEAR fallback 不同】；isEyeStillClosed = currEar < baseline·0.5；p>=1：仍闭 → p=0 留 BLINK_CLOSED；想睁 → p=0, →BLINK_OPENING |
| BLINK_OPENING | openingCurve(p) | duration=120·speed；p += delta/duration；p>=1 → p=0, consecutiveDropFrames=0, →TRACKING |
| SQUINT_HOLD | 恒 0.5 | earProxy > baseline·0.8 → →BLINK_OPENING, 然后 p=0.5, isInLowEarZone=false, return；历史≥2 且 dropRate>0.12 且 curr<baseline·0.3 → isBlinkNotSquint=true, →CLOSING, 然后 p=0.5 |

**双眼同步**（enableBinocularSync 时覆盖两眼输出；SQUINT_HOLD **不参与**同步，按代码）：

- 眨眼态集合 = {CLOSING, BLINK_CLOSED, BLINK_OPENING}；
- 两眼都在眨眼态：|L.stateStart − R.stateStart| ≤ 150 ms → 双眼同取 max(vL, vR)；
- L==CLOSING 且 R==BLINK_DETECT：now − R.stateStart ≤ 100 ms → (vL, vL)；
- R==CLOSING 且 L==BLINK_DETECT：now − L.stateStart ≤ 100 ms → (vR, vR)。

**reset()**：state=TRACKING, stateStart=0, lastUpdate=0, earHistory.clear(), baseline=0.18,
consecutiveDropFrames=0, dropStartEAR=0, curveProgress=0, currentOutputValue=0,
targetValue=0, transitionStartValue=0, lowEarStart=0, isInLowEarZone=false,
isBlinkNotSquint=true。（targetValue / transitionStartValue 为死字段。）

### 附录 C：SimpleEyeExtractor

**常量表**：默认阈值 CLOSED=0.10 / OPEN=0.18 / WIDE=0.26；
eyeSensitivity=1.0（companion 全局共享，clamp [0.3,3.0]）；
closedThresholdOffset / openThresholdOffset=0（clamp ±0.08）；
rangeShrinkFactorPerDegree=0.012；pitchDownShrinkMultiplier=1.2；minShrinkRatio=0.4；
pitchCompensationThreshold=5；yawCompensationFactor=0.4；yawCompensationThreshold=1；
nearSideCompensationMultiplier=5.0（动态模式覆盖为 clamp [0.5,15]）；enableDynamicCompensation=true；
cameraFovDegrees=63（clamp [30,120]）；referenceRatioRatio=0.4；baseCompensationMultiplier=3.0；
distanceSensitivity=2.5；enableHeadPoseCompensation=true。

**校准（每眼独立）**：isWindowReady = n>=10 && elapsed>=1000ms；finishCalibration
（n<10 直接 return 不校准）：

    mean = sum/n；var = sumSq/n − mean²；std = sqrt(max(0,var))；range = max − min
    observedOpenRatio = range > 0.02 ? max − 0.1·range : mean − std
    inferredClosedRatio = (range > 0.03 && min < mean − 2·std) ? min + 0.1·range : observedOpenRatio·0.6
    open = clamp(observedOpenRatio, 0.12, 0.35)
    closed = clamp(inferredClosedRatio, 0.05, open − 0.03)
    wide = open + (open − closed)·0.8
    若 (open − closed) < 0.04 → 回退三个默认值（isCalibrated 仍置 true）

**生效阈值**：

    effClosed = clamp(baseClosed + (sens−1)·(baseOpen−baseClosed)·0.3 + closedOffset, 0.05, baseOpen−0.02)
    effOpen   = clamp(baseOpen − (sens−1)·(baseOpen−baseClosed)·0.3 − openOffset, baseClosed+0.02, baseWide−0.02)

**EAR 与映射**：EAR = mean_over_upper( min_over_lower 2D 距离 ) / dist(内眼角, 外眼角)；
horizontal <= 1e-6 → 0；下眼睑为空按 0 计入。
earToBlinkValue：<=effClosed → 1；>=effOpen → 0；else (effOpen−ear)/(effOpen−effClosed) clamp [0,1]。
detectEyeWide：ear > open ? clamp((ear−effOpen)/(wide−effOpen), 0, 1) : 0。

**pitch 动态范围补偿**：|pitch|<=5 → 原值；ear <= effClosed·1.2 → 原值；
shrinkRatio = clamp(1 − (|pitch|−5)·0.012·(pitch<0 ? 1.2 : 1), 0.4, 1)；
shrunkOpen = effClosed + (effOpen−effClosed)·shrinkRatio；
ear>=shrunkOpen → effOpen；ear<=effClosed → 原值；shrunkOpen−effClosed<0.01 → 原值；
否则 (ear−effClosed)/(shrunkOpen−effClosed)·(effOpen−effClosed)+effClosed。

**动态透视**：faceRatio = sqrt(w·h)；currentFaceRatio = current·0.9 + faceRatio·0.1
（输入非法 w<=0 || h<=0 || w>1.5 || h>1.5 直接 return）；
distanceFactor = clamp((current−0.4)/0.4, −0.5, 1.5)；
perspectiveStrength = tan(fov/2)/tan(30°)；
multiplier = clamp(3.0·(1 + 2.5·perspectiveStrength·distanceFactor), 0.5, 15.0)。

**yaw 补偿**（仅 |yaw|>1）：currentYawFactor=max(0,(|yaw|−1)/90)；
calibYawFactor=max(0,(|calibYaw|−1)/90)；delta=差值；
decay = clamp((**raw**EAR − effClosed)/(effOpen−effClosed), 0, 1)；
delta>0：far=delta·0.4；near=delta·0.4·nearMult；yaw>0 时 imageLeft += far·rangeL·decayL、
imageRight += near·rangeR·decayR；yaw<=0 反之【按代码：yaw>0 时 imageLeft=远侧】；
方向改变（sign(calibYaw)!=sign(yaw) 且 |calibYaw|>1）：opp=calibYawFactor·0.4，oppNear=opp·nearMult；
当前方向>0 时 imageRight += opp·rangeR·decayR、imageLeft += oppNear·rangeL·decayL；<0 反之。

**执行顺序（不可换）**：rawEAR → pitch 补偿 → yaw 补偿 → **校准收集 rawEAR（未补偿）** →
earToBlinkValue / detectEyeWide（补偿后）。
⚠️ 左眼 finishCalibration 瞬间写 calibrationPitch/Yaw/FaceRatio，随后
FaceCapturePipeline 的 setCalibrationPose(baseline…) **覆盖**为基线值，顺序不可换（观察项 §7.6.2）。

### 附录 D：SimpleMouthExtractor

默认 CLOSED=0.10 / OPEN=0.45；enablePitchCompensation=true；threshold=5°；factor=0.008；
pitchDownMultiplier=1.3。

    MAR = dist2D(mouthInner[5], mouthInner[15]) / dist2D(mouthInner[0], mouthInner[10])
    （mouthInner = Mouth.INNER_FULL：[0]=78 左嘴角 [5]=13 上唇内中心 [10]=308 右嘴角 [15]=14 下唇内中心）

校准：closed = clamp(mean + 2·std, 0.05, 0.25)；open = clamp(closed·3.5, 0.25, 0.70)。
pitch 补偿：delta = pitch − calibPitch；|delta|<=5 → 0；rawMAR < closedThreshold·1.5 → 0；
effectiveDelta = delta>0 ? (delta−5)·1.3 : −(|delta|−5)；返回 effectiveDelta·0.008。
compensatedMAR = max(0, rawMAR + comp)（仅当已校准且有 headPose）；**校准收集 rawMAR**。
marToJawOpen：<=closed → 0；>=open → 1；else (mar−closed)/(open−closed)。

### 附录 E：SimpleSmileExtractor

常量：SMILE_DEAD_ZONE=0.003；FROWN_DEAD_ZONE=0.002；DEFAULT_SMILE_MAX=0.06；
DEFAULT_FROWN_MAX=0.04；pitchCompensationFactor=0.0008；rollCompensationFactor=0.001；
threshold=5°。

- 前置：mouthOuter.size>=16 且 faceHeight>=1e-6，否则**整帧 return 不写任何 smile/frown**。
- mouthOuter = Mouth.OUTER_FULL：[0]=61 左嘴角 [5]=17 下唇外中心 [10]=291 右嘴角 [15]=0 上唇外中心。
- mouthCenterY = (upper.y + lower.y)/2；rawLeft = (mouthCenterY − leftCorner.y)/faceHeight；rawRight 同理。
- pitch 补偿：effectiveDelta = delta>0 ? delta−5 : delta+5；comp = effectiveDelta·0.0008，左右同加。
- roll 补偿：|deltaRoll|>3 时 rollComp = deltaRoll·0.001；left += −rollComp；right += +rollComp。
- 校准：baselineL/R = 原始特征均值；std 同常规；leftDelta = 补偿后 − baselineL。
- 动态范围（只扩不缩）：avg=(l+r)/2；avg>0 → observedSmileMax=max(old, min(avg·1.2, 0.15))；
  avg<0 → observedFrownMax=max(old, min(|avg|·1.2, 0.10))。
- deltaToSmile = delta>0.003 ? clamp((delta−0.003)/(smileMax−0.003), 0, 1) : 0；
  deltaToFrown = delta<−0.002 ? clamp((|delta|−0.002)/(frownMax−0.002), 0, 1) : 0。

### 附录 F：AdvancedBlendShapeExtractor（advancedMode=true 才跑，golden 副层）

灵敏度：browUp=2.5 browDown=3.0；cheekPuff=3.0 cheekSquint=2.5；jawMove=3.0；
noseSneer=4.0；lipRoll=3.0 lipStretch=2.5 mouthMove=3.0（browUp/DownThreshold、
jawMoveThreshold 声明未用）。

calibrate 需 landmarks>=478：baselineBrowHeight=(lm[159].y−lm[105].y)/faceHeight；
baselineCheekWidth=dist(lm[36],lm[266])/faceWidth；baselineNoseWidth=dist(lm[98],lm[327])/faceWidth。
未校准默认 brow 0.08、cheek 0.8。faceWidth=dist(lm[33],lm[263])；faceHeight=dist(lm[168],lm[152])。

| 输出 | 公式（全部 clamp [0,1]） |
|---|---|
| browDown | (baseline − browToEye)·3.0/0.02；browToEyeL=(lm[159].y−lm[105].y)/faceHeight，R=(lm[386].y−lm[334].y)/faceHeight |
| browInnerUp | (((lm[168].y − (lm[107].y+lm[336].y)/2)/faceHeight) − 0.05)·2.5/0.02 |
| browOuterUp | (browToEye − baseline)·2.5/0.02 |
| cheekPuff | (cheekDist − cheekBaseline)·3.0/0.05；cheekDist=左右脸颊中心均值点距离/faceWidth（左 36,205,206,187,123；右 266,425,426,411,352） |
| cheekSquintL/R | (0.08 − dist(lm[111],lm[117])/faceWidth)·2.5/0.03（右 340,346） |
| jawRight / jawLeft | (lm[152].x − (lm[168].x+lm[13].x)/2)/faceWidth·3.0/0.02（left 负号） |
| jawForward | (lm[168].z − lm[152].z)·3.0/0.05 |
| noseSneerL/R | ((lm[1].z − mean(lm[49,129,102,48].z)) + (−(lm[98].y−lm[1].y))·0.5)·4.0/0.02（右 327 / 279,358,331,278） |
| mouthRight / mouthLeft | (mouthCenterX − lm[1].x)/faceWidth·3.0/0.02（left 负号；mouthCenterX=(lm[61].x+lm[291].x)/2） |
| mouthFunnel | (mouthForwardOffset·0.5 + widthNarrowing)·3/0.05；mouthForwardOffset=lm[1].z−mean(lm[13].z,lm[14].z,lm[61].z,lm[291].z)；widthNarrowing=0.35−mouthWidth；mouthWidth=dist(lm[61],lm[291])/faceWidth |
| mouthPucker | widthNarrowing·4/0.05 |
| mouthRollUpper / Lower | (0.02−(lm[13].y−lm[0].y)/faceHeight)·3/0.01 / (0.02−(lm[17].y−lm[14].y)/faceHeight)·3/0.01 |
| mouthShrugLower / Upper | shrugOffset·3/0.01 / shrugOffset·2/0.01；shrugOffset=0.02−(lm[14].y−lm[13].y)/faceHeight |
| mouthStretchL/R | (mouthWidth−0.35)·2.5/0.03 |
| mouthDimpleLeft / Right | (lm[206].z−lm[61].z)·5/0.01（右 426/291） |
| mouthPressL/R | pressOffset·3/0.02；pressOffset=0.06−(lm[17].y−lm[0].y)/faceHeight |
| mouthLowerDownL/R | (mean(lm[84,181,91].y)−mouthCenterY)/faceHeight·3/0.03（右 314,405,321） |
| mouthUpperUpL/R | (mouthCenterY−mean(lm[40,39,37].y))/faceHeight·3/0.03（右 270,269,267） |
| mouthClose | (0.01 − dist(lm[14],lm[13])/faceHeight)·5/0.01 |
| eyeSquintL/R | 上下睑逐对 2D 距离均值/faceHeight = openness；仅 0.025<openness<0.035 时 (0.035−openness)/0.01，否则 0（左上 159,158,157,173 / 左下 144,145,153,154；右上 386,385,384,398 / 右下 373,374,380,381） |

### 附录 G：FaceCapturePipeline 顺序（CALIBRATION_DURATION_MS=1000、MIN_CALIBRATION_FRAMES=10）

顺序不可换：

1. updateFaceDistanceFromLandmarks：bbox over 478 点；max<=1.5 且 min>=-0.5 视归一化直接
   用 bbox 宽高，否则除以 imageWidth/Height，否则跳过；
2. extractKeyPoints(landmarks, imageWidth, imageHeight)：landmarks[0].x<=1 && y<=1 且给尺寸时
   逐点 ×W/×H 转像素，z 不变；
3. 姿态基线累计 rawPitch/Yaw/Roll，窗口 ready = elapsed>=1000 && frames>=10 → baseline=sum/n
   （除数 = 实际帧数）；同时累计 478 点 Double 求和槽，avg = sum.toFloat()/n，
   extractKeyPoints(**avgLandmarks**，无宽高) → extractor.calibrate +
   setCalibrationPose(baselinePitch/Yaw/Roll)；
4. relativePose = rawPose − baseline（未校准时 = raw）；
5. extractFullBlendShapes(keyPoints, landmarks, **rawPose**) ← 传 rawPose 而非 relativePose。

isCalibrating = !isPoseCalibrated || (眼 || 嘴 || 微笑校准中)，**不含 advanced**。
⚠️ 移植疑点（观察项 §7.6.1）：第 2 步做像素转换、第 3 步 avgLandmarks 未做转换，单位可能
不一致 —— **按源码复刻**。

### 附录 H：MediapipeUpperBody 常量

**OneEuroFilter**：默认 minCutoff=1.2, beta=0.02, derivativeCutoff=1.0；
MIN_DT=0.001s, MAX_DT=0.5s。

    alpha(cutoff, dt) = cutoff<=0 ? 1 : 1/(1 + tau/dt)，tau = 1/(2π·cutoff)
    filter(v, dt)：
      v 非有限 → 返回 lastValue（无则 0）
      首次有效 → 透传、lastDerivative=0
      dt = (有限且>0) ? min(dt, 0.5) : 0.001
      rawDer = (v−last)/dt
      lastDer += alpha(derivCutoff, dt)·(rawDer − lastDer)
      cutoff = minCutoff + beta·|lastDer|
      out = last + alpha(cutoff, dt)·(v − last)

**UpperBodySmoother**：13 点 ×(x,y,z) 各一滤波器；**visibility 不平滑**；
BodyPoint.ZERO 直接透传且**不推进**该点滤波器；torso roll/yaw 用 minCutoff=0.6, beta=0.01。

**TorsoBaseline**（默认 10 帧）：非法输入返回 ZERO 且**不计入**校准；校准中 collected++、
mean=sum/collected；collected>=10 → baseline=mean；weight=min(1, collected/10)；
输出 (angle−mean)·weight；已校准输出 angle−baseline。

**UpperBodyGeometry**：RELIABLE_VISIBILITY=0.5、MIN_TRACKING_POINTS=7、
MIN_TORSO_VISIBILITY=0.3、MIN_REFERENCE_SPAN=0.02。

    quality = 13 点 visibility 均值（clamp [0,1]，非有限记 0）
    isTracking = count(visibility > 0.5) >= 7（严格大于）
    torso 退化对顺序：肩(5,6) → 肘(7,8) → 胯(11,12)；每对需两 vis 有限且 min(vis)>=0.3 且坐标有限
    horizontal = max(|dx|, 0.02)；roll = deg(atan2(dy, horizontal))；yaw = deg(atan2(−dz, horizontal))
    （dz = zR − zL；>0 = 向本人左转）

**13 点协议顺序 ORDER** = [NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR, LEFT_SHOULDER,
RIGHT_SHOULDER, LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST, LEFT_HIP, RIGHT_HIP]
（MediaPipe 33 点下标 0,2,5,7,8,11,12,13,14,15,16,23,24）。
⚠️ ORDER 变更 = 协议不兼容，须三处同步（UpperBodyPose.kt / track.ub 序列化 / windows
mapping.js）。dt 规则：无锚点（首帧 / tracking 丢失后）→ 1/15 s；否则 clamp(t−lastT, 1, 500)ms；
tracking 丢失 → 平滑器全重置 + dt 锚点清零，**基线不动**。

### 附录 I：跨端数值与实现陷阱

1. Kotlin 全程 Float，JS number 是 double；Float32Array 只保证存储精度。逐位一致不可能，
   评测用容差（abs 1e-5 / rel 1e-4 为基线，逐层阈值见 §7.5），报告分别统计 max/mean/p95。
2. distance2DTo 用 Math.sqrt(dx*dx + dy*dy)，**不要**用 hypot。
3. clamp() 只 clamp 附录 A 的 7 个字段。
4. transitionTo 清零规则（除 SQUINT_HOLD）+ 两处 transitionTo 后设 0.5（附录 B）。
5. BLINK_DETECT / BLINK_CLOSED 的空历史 fallback 分别是 baselineEAR / 0f（附录 B）。
6. TRACKING 进眯眼分支**不清** isInLowEarZone（附录 B）。
7. 眯眼 CLOSING 是整条曲线 ×0.5，不是半程停（附录 B）。
8. 有状态模块（blink 状态机、One-Euro、TorsoBaseline、各校准器）必须显式 reset()；
   乱序/重复帧不得静默接受，接口层加单调性检查，与 raw-frame-reorder 的 frameId 语义对齐；
   **禁止第三层平滑**（One-Euro 是唯一去噪层，观感层 filter.js 只允许一层轻量低通，
   blink 维度不参与时间滤波）。

### 附录 J：TrackingConfidenceDetector（**不迁**，留 Android 感知层）

rawConfidence = 0.5·presence + 0.3·IoU + 0.2·movementScore；
smoothed = 0.7·prev + 0.3·raw；isValid = presence>=0.5 && IoU>=0.3；
shouldRedetect = !isValid || framesSinceDetection>30 || IoU<0.1 || presence<0.3。
用途 = 是否重跑人脸检测（感知层推理调度）。Windows 不消费、不移植；如未来 PC 侧需要
丢帧/遮挡判定，另立任务，不在本迁移范围。

### 附录 K：temporal-filter 与校准归属（TimeBasedEMA 移植契约）

**K.1 TimeBasedEMA 契约**（移植对象：core/include/face_pipeline_filter.h 的
TimeBasedEMA / TimeBasedEMAFilter3D，face_landmarker.cpp 对 478 点与 head pose 的时域 EMA）：

    alpha = 1 - exp(-dt / tau)        // dt 恒定时可缓存（perf/round3-native.md）
    out = last + alpha * (v - last)
    首样本：透传并重置历史；v 非有限：返回 lastValue（无则 0）

- tau 默认 **0.12 s**（face_pipeline_filter.h:600 DEFAULT_TIME_EMA_TAU = 0.12，**线上实跑值**；
  face_landmarker.cpp 里 config.time_ema_tau = 0.08f 一行是**注释掉的**，Kotlin
  configureForHighFps(landmarkTau = 0.08f) 形参**全仓无调用点**，均从未生效；0.08 只作可配值）；
  **头姿专用 tau = 0.3 s**（face_pipeline_filter.h:601 DEFAULT_TIME_EMA_TAU_HEAD_POSE，与
  关键点域 0.12 不同，禁止混用）；tau **下限 0.01 s**（setTau 的 max(0.01, tau)）；
  dt clamp **[0.001, 0.5] s**。face_pipeline_filter.h 的 One-Euro（minCutoff 0.05 / beta 80）
  与 body-fusion 的 One-Euro（minCutoff 1.2 / beta 0.02）是**两套不同滤波器**，禁止混用。
- **K.2 作用域**：478 点关键点域 + rawHeadPose；**不作用于 52 维 BlendShape 域**。
- **K.3 bs 域平滑默认关闭**（可配挂点：filter.js 的 bs 组缺省 τ = 0）——Android bs 域
  无平滑（blink 曲线是整形不是平滑），这是黄金对齐基准。
- **K.4 双口径（协议 flags bit5）**：
  - **主口径（旁路）**：Android raw 模式旁路原生关键点 EMA —— landmark
    setSmoothingEnabled(false)、detection setDetectionFilterType(NONE)、head pose 新增 JNI
    nativeSetHeadPoseSmoothingEnabled(false)（本次唯一 C++ 改动）；模式切换带 resetSmoothing()。
    raw_track 携带**未滤波 MNN 输出**（bit5 = 0），Windows 执行点域 TimeBasedEMA。
  - **回退口径**：raw_track 带已滤波点位（手机端无法旁路 EMA 的实现）→ 发送端置
    LANDMARKS_PRE_SMOOTHED（bit5 = 1），Windows **跳过**点域平滑，防止双重平滑。
- **K.5 校准归属（保真度事实）**：
  - BlendShapeExtractor.calibrate() **只**校准 AdvancedBlendShapeExtractor（要求
    advancedMode && landmarks>=468）；其日志 "All BlendShape extractors calibrated" **有误导**，
    不代表眼/嘴/微笑已校准。
  - SimpleEye / SimpleMouth / SimpleSmile **各自在 extract 过程中自我校准**（每单元独立
    1000ms + >=10 帧窗口，到点自动落地）；**外部禁止再喂一次校准**，否则基线双份。
  - 校准累计器（rawEAR / rawMAR / rawSmileFeature）在 **BlendShapeExtractor 内部**，
    接口 add(landmarks, headPoseRaw)。
  - ExpressionCalibrator 职责 = **窗口进度观测 + serialize/load + reset**（不拥有累计器、
    不触发二次校准）。
  - isCalibrating = !poseCalibrated || 眼 || 嘴 || 微笑（**不含 advanced**）。
  - setCalibrationPose(baseline) **必须在左眼窗口落地之后**调用（附录 C 时序），否则
    calibrationPitch/Yaw/FaceRatio 被覆盖的时序就不对。
- **K.6 已知注释不一致（不修）**：Android 原生侧存在 0.08 / 0.12 的注释与实参不一致；
  本次迁移**不修改**（无行为变更原则），仅文档记录；golden 评测按 0.12 实跑值对齐。

