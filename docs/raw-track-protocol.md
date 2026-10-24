# rawTrackV1 二进制追踪协议规格（v1 扩展）

> 本文是 **P0 规格**：定义 VFC Tracking Protocol v1 的二进制扩展 **rawTrackV1** ——
> Android 只输出**感知层原始数据**（478 关键点 + 原始头姿 + 置信度），Windows 完整拥有
> 后处理（重排 → 校准 → BlendShape → 眨眼 → 归一化 → 平滑 → 融合 → 映射 → 缓冲 → Live2D）。
>
> 上层语义契约仍以 [docs/tracking-protocol.md](./tracking-protocol.md) 为基线；本文只定义
> **新增的二进制帧格式、协商与控制语义**。迁移设计见
> [docs/post-pipeline-migration.md](./post-pipeline-migration.md)。
>
> 本文所有字段的字节偏移、字节序、量化公式都是**唯一契约**，Android 与 Windows 两端
> 实现必须逐字节一致；改动必须升 msgType 或 version，并同步两端与本文。

---

## 0. 设计裁决（已拍板，不可回退讨论）

| # | 裁决 | 理由 |
|---|------|------|
| 1 | **frameId 用 uint32 全量进帧头**，不用 epoch+seq 128 环回做主键；seq = frameId & 127、epoch = frameId >> 7 **仅诊断** | 128 环回在丢帧 + 乱序并存时有歧义（跳 128 无法与乱序区分）；u32 在 60 fps 下约 828 天回绕，回绕用 32 位序列算术比较即可 |
| 2 | frameId **在相机回调处打点**（采集时刻分配），不在推理完成时分配；**t 同点取相机回调 wall-clock（System.currentTimeMillis()）毫秒** | frameId 的语义 = 采集顺序；推理乱序由 PC 重排消化，分配点必须先于任何排队/并行。sensor timestamp（CLOCK_MONOTONIC 纳秒）不在 v1 线上出现，只在 flags 保留位留给未来扩展 |
| 3 | 478 点 x/y 用 **Uint16 归一化到当帧图幅宽高**，z 用 **Int16 统一 scale 因子**；**little-endian** | 6 B/点 × 478 = 2868 B，约为 float32 方案（5736 B）的一半；量化误差远低于 Live2D 可感知阈值 |
| 4 | 帧头必须带 **width/height** | x/y 归一化量化的逆变换、宽高比矫正、调试回放都需要图幅尺寸 |
| 5 | 协议**向后兼容**：hello.features 协商 **rawTrackV1**；老客户端继续收文本 JSON track（bs/pose 为手机后处理结果） | 不打断现有用户；混合客户端全体回退 |
| 6 | **校准所有权迁到 Windows**：手机只响应 **resetTracking / startStream / stopStream** | 校准（姿态基线/表情阈值/眨眼/躯干基线）随角色与 PC 个性化，见迁移文档 §5 |
| 7 | 黄金对齐评测（golden parity）是 P2 的验收依据，格式见迁移文档 §6 | 迁移必须可证明等价，不能靠肉眼 |

---

## 1. 目标与范围

### 1.1 目标链路

    Android:  相机 → 人脸检测 → MNN 关键点 → 原始头姿 → 原始关键点输出
    Windows: 重排 → 校准 → BlendShape 提取 → 眨眼曲线 → 姿态归一化 → 平滑
             → 上半身融合 → 参数映射 → 固定 500ms 缓冲 → Live2D

Android 侧**只保留感知层**：

- Camera2 采集（YUV_420_888，旋转 + 前置镜像）；
- MNN BlazeFace 检测 + 478 点关键点 + Procrustes 头姿（rawPitch/rawYaw/rawRoll）；
- presenceScore / detectionBox / inferenceMs 统计；
- （可选上半身）MediaPipe Pose 33 → 13 点抽取 + isTracking，原始点直接输出。

**不做**：基线减除、BlendShape 解算、眨眼曲线、One-Euro/EMA 平滑、TorsoBaseline、
参数映射、发送帧缓冲（500 ms 缓冲迁至 Windows，见迁移文档 §4 第 9 步）。

### 1.2 传输层

- 追踪数据走 **WebSocket 二进制帧（RFC 6455 opcode 0x2）**，一帧追踪数据 = 一条完整
  WS message（一条 message 可由多个 WS frame 分片组成，接收端必须支持 OP_CONTINUATION
  重组，见 §8.4）。
- 控制面（hello / ping / pong / ack / bye / startStream / stopStream / resetTracking）
  继续走**文本帧 JSON**，与 tracking-protocol.md §3 的规则一致（未知 type / 未知字段忽略）。
- 端口与拓扑不变：手机是 WS 服务端（8765），Windows 主动连接。

### 1.3 现状锚点（源码事实）

- app/src/main/java/com/example/virtualfacecapture/tracking/TrackingServer.kt 的
  readLoop 已解析完整 RFC 6455 帧（含 mask / 126 / 127 长度 / OP_CONTINUATION），但
  OP_BINARY 的 payload 目前**解析后直接丢弃**（只回调 onText），发送侧 sendFrame(opcode, payload)
  已支持任意 opcode —— 二进制只需补「发送 rawTrackV1」与「接收端按 message 转发」两处。
- facecapture-sdk PipelineFaceTracker.kt 在相机线程为每帧分配 frameId（YuvFrameDataInternal
  创建处，frameId++），多路并行推理后由 FrameReorderBuffer 按 frameId 还原顺序。
- FaceCaptureResult（facecapture-sdk FaceCaptureEngine.kt）已区分原始层
  （landmarks 478 / imageWidth / imageHeight / detectionBox / presenceScore / rawHeadPose）
  与后处理层（blendShapes / relativeHeadPose）—— rawTrackV1 只发**原始层**。

---

## 2. 能力协商（hello.features → rawTrackV1）

### 2.1 声明

- **手机 → PC** 的 hello（tracking-protocol.md §4.1）新增**可选字段**：

        "rawTrackV1": true

  含义：手机支持发送 rawTrackV1 二进制追踪帧（含 §5 的帧格式与 §6 的控制消息）。
- **PC → 手机** 的 hello.features（§4.5）新增标记：

        "rawTrackV1"

  含义：客户端实现了 rawTrackV1 解析与 Windows 侧完整后处理。

### 2.2 生效规则

1. **双方都声明**才启用 raw 模式：手机以文本 JSON 之外**追加/替代**二进制帧发送原始数据。
2. 任一方未声明（含老客户端、老手机、还没发 hello 的新连接）→ **保持 v1 文本 JSON track**，
   bs / pose 为手机后处理结果（老语义逐字节不变）。
3. **多客户端按客户端分发（per-client，不沿用 upperBodyPostV1 的全体协商规则）**：
   手机对每个连接独立决定形态 —— 声明 rawTrackV1 且 startStream(rawMode=rawTrackV1) 的
   客户端收 **OP_BINARY raw_track**；未协商的 v1 客户端继续收**文本 JSON track**（bs/pose
   为手机后处理结果）。同一连接同一时刻只有一种 track 语义。**只有存在至少一个 v1 文本
   客户端时手机才运行 legacy 后处理链**（BlendShape/眨眼/平滑/基线），全部客户端都是
   raw 模式时整条后处理链旁路，省 CPU。
   **边界细化（android-raw 实现 + spec-arch 评审确认）**：旁路的精确条件是
   「存在 RAW 推流客户端 **且** 没有任何 TEXT 客户端」；**零客户端 / 仅 IDLE（已协商未
   startStream）保持 legacy 链** —— 手机本地 UI（MainActivity 的 onBlendShapesUpdate /
   onHeadPoseUpdate / onVisualizationUpdate / onCalibrationStatusUpdate）订阅的是后处理
   结果，彻底旁路会让纯手机场景 UI 全 0 / 定格。此差异只影响手机 CPU，**wire 无任何影响**
   （无 RAW 推流就没有 raw_track；文本 track 只发 TEXT 客户端，报文逐字节不变）。纯 RAW
   推流期间手机 UI 的 BS 列表不更新、头姿显示 rawPose，属「整条旁路省 CPU」的既有取舍。
4. 同一连接重复发 hello：以最后一条为准，重算生效模式（切换时清发送缓冲，避免旧语义帧混入）。
5. 未知 features 标记忽略；畸形 hello 宽容解析，绝不因畸形报文断连。

### 2.3 startStream 的 rawMode 显式指定

PC 连接并协商后，用控制消息显式开启原始流（§6.2）：

    {"type":"startStream","rawMode":"rawTrackV1","requestId":"r-0001"}

- rawMode 缺省或为 "legacy" 时即使协商成功也继续发文本 JSON（供抓包排障 / 降级演练）。
- 手机收到 startStream 后回 ack（§6.4）并开始按指定模式推流。

### 2.4 降级矩阵

| 手机  PC | 声明 rawTrackV1 | 未声明 |
|---|---|---|
| 支持 rawTrackV1 | raw 二进制帧（startStream rawMode=rawTrackV1 之后） | 文本 JSON track（手机后处理结果） |
| 老版本 | 文本 JSON track（老语义） | 文本 JSON track（老语义） |

多客户端 = 按上表**逐连接分发**（rawTrackV1 客户端拿二进制、v1 客户端拿文本），不是全体回退。

**中途降级**：raw 模式下手机重启 / 重发 hello 未带 rawTrackV1 / 收到 legacy startStream，
立即切回文本 JSON；PC 见到文本 track 即回退 legacy 链，两侧都不需要重连。
PC 侧降级入口是同一个 handleMessage（文本）与 handleBinary（二进制），互斥状态由
「最近收到的消息形态」驱动，不允许同时消费两种语义的帧（迁移文档 §4 第 1 步）。

---

### 2.5 发送缓冲语义（raw 模式旁路 500 ms）

- **raw 模式旁路手机端 TrackingFrameBuffer 500 ms 发送帧缓冲**（协议 v1 §7.1 的保守缓冲）：
  raw_track 算完即发，帧间隔抖动与固定延迟全部归 Windows（Win 侧固定 500 ms playout
  缓冲是全链路**唯一**人为延迟，见迁移文档第 4 节第 11 步）。legacy 文本 track 的
  jitterBufferV1 / send.bufferMs 行为保持不变。
- **发送队列的 control 优先保序保留**：per-client 发送队列（8 track + 32 control、
  保 control 弃 track、control 严格保序）不变；hello / ack / bye / pong 绝不被 raw 帧
  挤掉或乱序（calib 与 track 错配的历史坑不允许在 raw 模式重现）。
- 切换 rawMode（startStream）时清发送缓冲并重置 playout 节奏，避免旧模式残留帧。

---

## 3. 字节序与对齐

- **所有多字节字段 little-endian**（含 float32 / uint64）。
- 帧头按 4 字节对齐排布；uint64 的 t 放在 8 字节对齐偏移（16）。
- 接收端**不得**依赖结构体对齐做指针强转（Electron/Node 用 DataView 按偏移读取；
  Kotlin 用 ByteBuffer.order(LITTLE_ENDIAN)），必须按偏移逐字段读写 —— 两端的序列化
  是逐字段的，不存在编译器 padding 问题。

---

## 4. RAW_TRACK 消息帧（msgType = 0x01）

一条 WS message 的 payload 布局 = **72 字节帧头** + **478 × 6 字节关键点块** +
（可选）**128 字节上半身 ubRaw 扩展块**。

### 4.1 帧头（72 字节）

| 偏移 | 大小 | 类型 | 字段 | 说明 |
|---:|---:|------|------|------|
| 0 | 4 | u8[4] | magic | ASCII **"VFCR"**（0x56 0x46 0x43 0x52） |
| 4 | 1 | u8 | version | 帧格式版本，**1** |
| 5 | 1 | u8 | msgType | 0x01 = RAW_TRACK；其余保留 |
| 6 | 2 | u16 | flags | 见 §4.2 |
| 8 | 4 | u32 | frameId | 采集序号（§7），主键 |
| 12 | 4 | f32 | cameraFps | 相机采集帧率（必备字段，诊断 / 节奏参考，无统计时写 0） |
| 16 | 8 | u64 | t | 采集时间戳：**相机回调处 System.currentTimeMillis() 的 wall-clock 毫秒**（与 track.t 同源同点） |
| 24 | 2 | u16 | width | 当帧图幅宽（px，旋转后） |
| 26 | 2 | u16 | height | 当帧图幅高（px，旋转后） |
| 28 | 4 | f32 | presence | 人脸存在置信度 [0,1]（FaceCaptureResult.presenceScore） |
| 32 | 4 | f32 | inferenceMs | 本帧感知推理耗时（ms，**必备字段**，无统计时写 0） |
| 36 | 4 | f32 | poseRaw.pitch | 原始俯仰角（度，**rawHeadPose，非 relativeHeadPose**，未减任何基线） |
| 40 | 4 | f32 | poseRaw.yaw | 原始偏航角（度，未减基线） |
| 44 | 4 | f32 | poseRaw.roll | 原始翻滚角（度，未减基线） |
| 48 | 4 | f32 | box.left | 检测框左（归一化 [0,1]，可越界） |
| 52 | 4 | f32 | box.top | 检测框上 |
| 56 | 4 | f32 | box.right | 检测框右 |
| 60 | 4 | f32 | box.bottom | 检测框下 |
| 64 | 2 | u16 | landmarkCount | **478**（保留字段，允许未来变更） |
| 66 | 2 | u16 | landmarkStride | **6**（每点字节数 = 2+2+2） |
| 68 | 4 | f32 | box.score | 检测框置信度 [0,1]（无检测时 0） |

> 帧头总大小 = **72 字节（0x48）**。poseRaw 符号约定与 tracking-protocol.md §5.2 相同
> （pitch < 0 抬头、yaw < 0 真人右转、roll 正 = 顺时针歪头），但**未减任何基线**。

### 4.2 flags（u16）

| bit | 名称 | 含义 |
|---:|------|------|
| 0 | FACE_VALID | 本帧检测到人脸（presence 与关键点有效）；0 = 无脸，关键点块为全 0 占位（结构稳定） |
| 1 | UB_EXT_PRESENT | 帧尾存在 128 字节 ubRaw 扩展块（§5） |
| 2 | POSE_VALID | poseRaw 三轴有限有效；0 时接收端忽略 poseRaw |
| 3 | LANDMARKS_CLAMPED | 量化时发生过 x/y/z 裁剪（诊断用） |
| 4 | SENSOR_TIMESTAMP_EXT | 保留给未来「sensor timestamp 上线」扩展；v1 发送端恒 0，接收端忽略 |
| 5 | LANDMARKS_PRE_SMOOTHED | 1 = 关键点域已在手机端滤波（回退口径），Windows **不再**平滑关键点域；主口径（旁路原生 EMA）恒 0（迁移文档附录 K.4） |
| 6..15 | 保留 | 发送端必须为 0；接收端忽略未知位 |

### 4.3 关键点块（478 × 6 = 2868 字节，偏移 72 起）

每点 6 字节，顺序 = MediaPipe FaceMesh 478 点**原始索引序**（与
FaceCaptureResult.landmarks 一致，索引 0..477，含虹膜 468..477）：

| 每点偏移 | 大小 | 类型 | 字段 |
|---:|---:|------|------|
| 0 | 2 | u16 | x_q |
| 2 | 2 | u16 | y_q |
| 4 | 2 | i16 | z_q |

**量化公式**（little-endian，两端必须逐式一致）：

    x_q = round(clamp(x, 0, 1) * 65535)          // x 归一化到当帧 width
    y_q = round(clamp(y, 0, 1) * 65535)          // y 归一化到当帧 height
    z_q = round(clamp(z / Z_SCALE, -1, 1) * 32767)

    反量化：
    x = x_q / 65535
    y = y_q / 65535
    z = z_q * Z_SCALE / 32767

- **Z_SCALE = 5.0f**（统一 scale 因子，与 tracking-protocol.md §5.3 的 z ∈ [-5,5]
  裁剪口径一致；面部关键点与 ubRaw 扩展块共用同一因子）。
- x/y 的语义：FaceCaptureResult.landmarks 的归一化坐标（[0,1] 基于 imageWidth × imageHeight）。
  越界点（脸贴边出画）**裁剪到 [0,1]** 并置 LANDMARKS_CLAMPED；需要未裁剪值时用
  detectionBox / 调试录制（原始浮点留在 .vfcraw 录制里，见迁移文档 §6）。
- z 是 MediaPipe 相对深度（量纲与 x 相近，越小越靠近镜头），非米制、非角度。
- 量化误差：x/y ≤ 1/65535 ≈ 1.53e-5（约 720p 下 0.011 px）；z ≤ 5/32767 ≈ 1.53e-4。
- FACE_VALID = 0 时本块仍占 2868 字节、内容全 0（**结构长度恒定**，接收端无需特判）。
- **主口径下 478 点是未滤波 MNN 输出**（Android raw 模式旁路原生关键点 EMA，
  landmark setSmoothingEnabled(false) / detection setDetectionFilterType(NONE) /
  nativeSetHeadPoseSmoothingEnabled(false)，迁移文档附录 K.4）；Windows 在提取前做点域
  TimeBasedEMA（tau 默认 0.12）。发送端已滤波时置 flags bit5 = LANDMARKS_PRE_SMOOTHED，
  Windows 跳过点域平滑（双口径，主口径 = 旁路）。

### 4.4 总大小核算

| 形态 | 大小 | 60 Hz 带宽 |
|------|------|-----------|
| 帧头 72 B + 关键点 2868 B | **2940 B / 帧** | ≈ **172 KB/s** |
| + ubRaw 扩展 128 B | **3068 B / 帧** | ≈ **180 KB/s** |
| 对照：现行文本 track（含 ub） | ≈ 933 B / 帧 | ≈ 66 KB/s |

带宽约为文本协议的 2.6 倍（局域网 Wi-Fi 5/6 下无压力）；省掉的是**手机端全部后处理
CPU 与文本序列化**，换来的是 PC 端可重放、可校准、可对拍的原始数据。

---

## 5. ubRaw 扩展块（可选，保留字段，flags bit1 置位时存在）

上半身原始 13 点，顺序严格按 CommonData UpperBodyPose.kt 的
UpperBodyLandmarkIndex.ORDER（0=nose, 1=eye_left, 2=eye_right, 3=ear_left, 4=ear_right,
5=shoulder_left, 6=shoulder_right, 7=elbow_left, 8=elbow_right, 9=wrist_left,
10=wrist_right, 11=hip_left, 12=hip_right）。**ORDER 变更 = 协议不兼容**，必须同步
CommonData / 手机序列化 / Windows 解析三处（现行三处同步规则不变）。

布局（紧随关键点块，偏移 72 + 2868 = 2940 起）：

| 偏移 | 大小 | 类型 | 字段 |
|---:|---:|------|------|
| 0 | 104 | 13 × 8 B | 13 点，每点：x_q u16、y_q u16、z_q i16、vis_q u16 |
| 104 | 8 | u64 | ubT：关键点帧采集时间（手机 Unix ms，同 track.ubT 语义） |
| 112 | 4 | f32 | torsoRaw.roll：原始躯干角（度，未平滑、未减基线） |
| 116 | 4 | f32 | torsoRaw.yaw：原始躯干角（度，未平滑、未减基线） |
| 120 | 4 | f32 | quality：13 点平均可见度 [0,1] |
| 124 | 1 | u8 | ubTracking：1 = 本帧检出上半身 |
| 125 | 3 | u8[3] | 保留，必须为 0 |

每点量化（与关键点块同式，vis 用 [0,1] 线性）：

    x_q = round(clamp(x, 0, 1) * 65535)
    y_q = round(clamp(y, 0, 1) * 65535)
    z_q = round(clamp(z / 5.0, -1, 1) * 32767)
    vis_q = round(clamp(visibility, 0, 1) * 65535)

语义（沿用 tracking-protocol.md §5.3）：x/y 已镜像（前置摄像头像素镜像，不换左右标签）、
归一化 [0,1]；z 相对深度；visibility [0,1]。torsoRaw 的符号：roll 正 = 被拍者右肩下沉、
yaw 正 = 被拍者向自己左侧转（TorsoAngles 同约定）。ubTracking = 0 时 13 点全 0、
torso = [0,0]（结构稳定）。

> **v1 保留**：本块定义即契约，但 P1 可以先不发送（flags bit1 = 0）。发送侧一旦启用，
> 必须整块发送（全有或全无），不得只发一部分点。

---

## 6. 控制消息（文本 JSON）

### 6.1 总则

- 全部走 WS 文本帧，形状沿用 tracking-protocol.md §4.4 / §4.5；未知 type 忽略、
  未知字段忽略。
- 手机侧**只响应**三种流控命令：**resetTracking / startStream / stopStream**（§6.2）。
- **校准所有权在 Windows**：手机不再执行 / 响应任何校准命令（§6.3）。

### 6.2 手机侧流控（新增）

    {"type":"startStream","rawMode":"rawTrackV1","requestId":"r-0001"}
    {"type":"startStream","rawMode":"legacy","requestId":"r-0002"}
    {"type":"stopStream","requestId":"r-0003"}
    {"type":"resetTracking","requestId":"r-0004"}

| 消息 | 语义 | 幂等 |
|------|------|------|
| startStream | 开始推流；rawMode = "rawTrackV1"（需已协商）或 "legacy"；缺省 = legacy | 重复调用同参数 → 直接回同一结果的 ack，不重复开流 |
| stopStream | 停止推流（不关 WS）；清发送缓冲 | 未在推流时也回 ack ok |
| resetTracking | 重置**感知层**状态：清多路并行排队、FrameReorderBuffer、检测/跟踪状态、frameId 会话序（见 §7） | 重复调用 → 每次都重置，ack 携带新的 sessionId |

- resetTracking 与「校准」无关：它只清感知层（例如切摄像头、换人、模型重载）。
  Windows 侧校准的重置走 §6.3。
- requestId 可选（老客户端不带）；带上时 ack 必须原样回显，供 PC 做请求-应答配对。

### 6.3 PC 侧校准指令（recalibrate 语义变更）

原 command {"name":"recalibrate"}（手机重做中性脸基线）**语义废止**，改为**纯 PC 侧**：

    {"type":"recalibrate","target":"face","requestId":"r-0100"}
    {"type":"recalibrate","target":"body","requestId":"r-0101"}
    {"type":"recalibrate","target":"all","requestId":"r-0102"}

- 这是 **PC 内部动作**（Windows 后处理链的校准重置，见迁移文档 §5），**不发给手机**。
  旧客户端仍可能发 command:recalibrate 给手机：手机**兼容接受**但只做
  resetTracking 的等价动作（清感知层状态）并回 ack，日志标注 deprecated；
  绝不再做任何基线减除。
- target：face = 姿态基线 + 表情阈值 + 眨眼状态机；body = TorsoBaseline；all = 两者。
- **幂等**：同一 requestId 重复提交返回同一 ack 结果（校准只触发一次）；
  同一 target 在校准窗口（默认 1 s / ≥10 帧，见迁移文档 §5.2）内重复触发被合并。
- **超时**：PC 发起后 **2 s** 未收到本地校准完成信号 → UI 提示可重试；
  重试必须带**新** requestId（旧 requestId 的结果已作废）。

### 6.4 ack / calib / bye

- ack：{"type":"ack","name":"<消息名>","requestId":"...","ok":true}；失败时
  "ok":false + "error":"<原因>"（如 rawMode 未协商、不在推流）。
- calib（手机侧校准窗口通知）在 raw 模式下**不再发送**（手机无校准）；legacy 模式不变。
- bye / ping / pong 语义不变（tracking-protocol.md §4.4 / §7.7）。

---

## 7. frameId 语义

1. **分配点**：相机回调（采集时刻）。PipelineFaceTracker 在相机线程创建帧数据时递增
   （现状已是 frameId++ 于相机线程；rawTrackV1 把它直接写进帧头，不再另分配 seq）。
2. **类型**：uint32 全量，从 0 起，**单调递增**；会话 = 手机一次连续推流
   （resetTracking / startStream 重开会话时可重置，由 sessionId/ack 通告）。
3. **seq / epoch 仅诊断**：seq = frameId & 0x7F、epoch = frameId >> 7，
   只出现在调试面板 / 日志 / GET /status，**任何重排、去重、丢旧帧逻辑一律用 frameId**。
4. **回绕**：frameId 到 2^32-1 后回 0。接收端用 32 位序列算术比较
   （(int32)(a - b) < 0 视为 a 更新），回绕前后无需任何特殊分支；
   要求发送端在途帧 < 2^31（现实远小于）。
5. **与 t 的关系**：frameId 保证顺序，t 保证节奏。二者都在采集侧打点；
   t 是手机 Unix ms（跨设备不可比，只用相邻差值，见 tracking-protocol.md §7.8）。
6. **GET /status** 的 "seq" 字段保留 = 最新 frameId（文本兼容），新增可选 "sessionId"。

---

## 8. 异常处理

### 8.1 丢帧

- 发送端绝不重传（UDP 式语义跑在 TCP 上：宁丢不堵）。
- 接收端按 frameId 检测缺口；重排缓冲语义沿用 facecapture-sdk FrameReorderBuffer：
  首帧立即放行、缺口最多等 **60 ms**、pending 上限 **8** 帧、超限强制跳缺口、
  迟到旧帧（≤ lastEmittedId）直接丢。绝不为等一帧空转（迁移文档 §4 第 2 步）。

### 8.2 乱序

- 只在多路并行 / 网络层出现。按 frameId（32 位序列算术）排序后放行；
  禁止用到达时间、seq 环回值或 t 排序。

### 8.3 时钟漂移

- 两端时钟**不同步**：t 只允许求相邻差值（帧间隔、播放节奏）；延迟估算用 ping/pong RTT。
- PC 时间轴用「到达 − 采集」最小值锚定时钟偏移（interp.js 既有策略），突变 > 120 ms
  重锚；**播放时间只前进，绝不倒退**。

### 8.4 截断帧 / 半包 / 粘包

- WebSocket 是 message 语义，**粘包/半包由 WS 层解决**；接收端必须支持：
  - 单 frame（FIN=1, opcode=0x2）；
  - 分片 message（opcode=0x2 + OP_CONTINUATION…，TrackingServer readLoop 已有重组）；
  - 组装后总长 > MAX_INCOMING_FRAME（现行上限）→ 关闭连接。
- **长度校验**：收到的 message 长度必须等于
  72 + landmarkCount × landmarkStride (+ 128 若 UB_EXT_PRESENT)，
  且 landmarkCount == 478、landmarkStride == 6；不等 → **整条丢弃**并计数
  （badBinaryFrames），连续丢弃 > 10 条 → 断言日志 + 考虑回退 legacy。
- **magic / version 校验**：magic ≠ "VFCR" 或 version ≠ 1 → 丢弃整条 + 节流日志
  （每连接每 5 s 最多 1 条），不得断开（可能是未来版本探测）。
- **NaN / 非有限**：poseRaw 任一轴非有限 → POSE_VALID 置 0（发送端负责）；
  接收端再遇非有限值按 0 处理并计数。presence / box.score 裁剪 [0,1]。

### 8.5 断线 / 会话边界

- 断线重连、心跳、bye 语义沿用 tracking-protocol.md §7.4 / §7.7。
- 每次收到 hello（新会话）：PC 必须重置重排缓冲、frameId 高水位、时间轴与全部
  后处理状态（现行 lastSeq = -1 的坑位规则推广到 frameId 高水位 = NONE）。

---

## 9. 性能预算

| 指标 | 目标 |
|------|------|
| 手机端每帧打包（帧头 + 量化） | < 0.3 ms（ByteBuffer 直写，TypedArray/直接缓冲复用，禁止逐点装箱） |
| 单帧线上大小 | 2940 B（+128 B 带 ubRaw） |
| 60 Hz 带宽 | ≈ 172~180 KB/s（局域网） |
| 手机端后处理 CPU（迁移后） | 相比现状**净减少**（基线/解算/眨眼/平滑全部移走） |
| PC 端解析一帧（DataView 478 点） | < 0.5 ms |

---

## 10. 兼容与演进

- 加字段 = 新增 msgType（0x02…）或 version +1；**rawTrackV1 冻结本文的字节布局**，
  任何偏移变更都必须升 version。
- landmarkCount / landmarkStride 是**保留字段**：未来 3D 网格 / 更少点数可在同 version
  下变更，接收端按帧头值解析（但 v1 实现必须只接受 478/6）。
- 文本 JSON 与二进制是**两种互斥模式**，同一会话不允许混发两种 track 语义
  （控制面文本不受影响）。
- 抓包排障：可随时 startStream rawMode=legacy 切回文本模式；raw 帧可用迁移文档 §6 的
  .vfcraw 录制器原样保存回放。
