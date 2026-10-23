# VirtualFaceCapture-MNN

> ✨ **安卓面捕 → Windows 渲染**：手机上只跑面部捕捉，追踪数据经局域网 WebSocket 送到电脑，
> 由 Windows 客户端驱动 Live2D 虚拟形象。推理框架基于 [alibaba-MNN](https://github.com/alibaba/MNN)。

> 🌐 English README: [README.en.md](./README.en.md)

[![Android CI](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml)
___

## 🧠 简介

基于 **MNN** 推理引擎、在移动端复刻 **MediaPipe Face Landmarker** 面部捕捉方案，
配合 **仿 OpenSeeFace** 风格的后处理流水线，实时输出 **ARKit-52 BlendShape + 头部姿态**。

这一版把系统拆成了两半：

```
   手机（Android）                              电脑（Windows）
┌────────────────────────┐   Wi-Fi / 局域网   ┌────────────────────────┐
│ 相机 → MNN 推理         │   每帧 ~0.5 KB     │ WebSocket 客户端        │
│ 478 关键点 → 后处理      │   WebSocket JSON  │ 自适应归一化 → 参数映射   │
│ ARKit-52 + 头姿（无渲染）│  ────────────────► │ Cubism Web 渲染 Live2D  │
└────────────────────────┘      60 fps        └────────────────────────┘
```

为什么要拆开来：

| | 手机端只做面捕 | 电脑端渲染 |
|---|---|---|
| **发热 / 耗电** | 省掉整条 GL 渲染 + 编码链路，推理帧率更稳 | — |
| **画质** | — | 桌面独显渲染，想开多大开多大 |
| **叠加** | — | 透明窗口直接叠在游戏 / 桌面上，或给 OBS 当浏览器源 |
| **迭代** | 改形象、改映射只动电脑，不用重装 App | 模型放目录里就自动发现 |

手机上仍然保留完整的调试能力：相机预览 + 关键点叠加、帧率 / 帧耗时、头姿指示、
ARKit 数值、眨眼曲线与眼睛灵敏度、MNN 后端（Vulkan / CPU）运行时切换。

> 📈 手机端帧率优化清单、日志判读方法与上机调优步骤见 **[PERFORMANCE.md](PERFORMANCE.md)**
> （注意：该文档里与「手机端 Live2D 渲染」相关的章节属于历史记录，渲染已迁至 Windows 客户端）。

---

## ✨ 运行示例

<p align="center">
  <img src="./sensi.gif" width="45%" />
  <img src="./zzzz.gif" width="45%" />
</p>

---

## 🧠 方案概览

一句话概括：

> **把 MediaPipe Face Landmarker 的 Detection + Landmark 两个模型搬到 MNN 上跑，
> 自己实现整套图前/图后处理，再接一层仿 OpenSeeFace 的滤波与表情解算，
> 输出 ARKit-52 BlendShape + 头部姿态；手机只负责产出这份数据，Live2D 在电脑上画。**

与原版 MediaPipe Android 方案的差异：

| 维度 | 官方 MediaPipe Tasks | 本工程 (MNN 版) |
|------|----------------------|-----------------|
| 推理引擎 | TFLite + GPU Delegate | **MNN**（CPU / GPU / NNAPI 后端可选） |
| 图调度 | MediaPipe CalculatorGraph | **本地 C++ 流水线**，零图计算依赖 |
| 图像处理 | JavaCV / OpenCV / GL | **MNN-CV** + **NEON kernel** |
| 模型数量 | Detection + Landmark + Blendshape | 仅 **Detection + Landmark** 两个模型，BlendShape 走单独解算 |
| 后处理 | MediaPipe 内置 | **仿 OpenSeeFace** 平滑 / 标定 / 头姿恢复 |
| 渲染位置 | 端上（MediaPipe 侧） | **Windows 客户端**（Cubism Web），手机端零渲染 |
| 体积 | 依赖 mediapipe_tasks_vision aar | 仅 `libMNN.so` + `libmediapipefacelandmark.so` + 2 个 `.mnn` 模型 |

---

## 🧩 流水线结构

```
            ┌───────────────────────────────┐
Camera ───► │ YUV_420_888 (Camera2/CameraX) │
            └──────────────┬────────────────┘
                           │  NEON YUV→RGB / 旋转 / 缩放
                           ▼
              ┌──────────────────────────┐
              │  FaceDetector  (MNN)     │   face_detector_mp.mnn
              │   - BlazeFace 风格        │   (含 int8 量化版本)
              └──────────────┬───────────┘
                             │ ROI 仿射裁剪 (MNN-CV)
                             ▼
              ┌──────────────────────────┐
              │  FaceLandmark (MNN)      │   face_landmark_detector_mp.mnn
              │   - 输出 478 点 3D 关键点 │
              └──────────────┬───────────┘
                             ▼
              ┌──────────────────────────────────────────────┐
              │  仿 OpenSeeFace 后处理 (C++ / Kotlin)         │
              │   • PnP / SVD 头部姿态恢复 (pitch/yaw/roll)   │
              │   • 眨眼曲线 + 嘴形 + 眉毛                    │
              │   • One-Euro / 滚动均值平滑                   │
              │   • 首帧自动标定 (calibration)                │
              │   • ARKit-52 BlendShape 解算                  │
              └──────────────┬───────────────────────────────┘
                             ▼
              ┌──────────────────────────┐        ┌───────────────────────────┐
              │  TrackingServer (手机)    │ ─────► │ Windows Client (电脑)      │
              │  WS /ws, JSON, ~60 fps   │  Wi-Fi │ 自适应归一化 → Live2D 参数  │
              └──────────────────────────┘        │ Cubism Web + PIXI 渲染     │
                                                  └───────────────────────────┘
```

所有矩阵运算 / 仿射变换 / SVD / Jacobi 旋转 / 点云变换都走本地的
`mini_linalg` + `neon_intrinsics`，针对 3×N 关键点这种典型 shape 做了
向量化，加载和首帧推理耗时相比标量实现有明显下降。

### GPU 多路并行关键点推理

关键点阶段占掉一帧 80% 的推理时间，但单路 Vulkan 的 GPU 利用率其实不高：一次推理里
还夹着 YUV 预处理、命令录制、结果回读这些 CPU 段，GPU 经常在等。`PipelineFaceTracker`
因此**多开几路 GPU 引擎**（每路一个独立的 MNN session）从同一个帧队列里抢帧，
让 GPU 命令队列始终有活儿；CPU 这边只负责把乱序结果重排回时间顺序：

```
                      ┌──► [ GPU 引擎 0 (Vulkan) ] ──┐
yuvLandmarkFrame ─────┤                              ├──► 重排(CPU 线程) ──► 后处理
      Channel         ├──► [ GPU 引擎 1 (Vulkan) ] ──┤   按 frameId 排序
   (通道分派语义)      └──► [ GPU 引擎 2 (可选)    ] ──┘
```

- 各路 MNN session 完全独立：原生侧用 `EngineSlot[4]` + `thread_local` 选择引擎，
  每个推理线程启动时绑定一次自己的槽位，per-frame JNI 签名不用改；
- 结果按 `frameId` 重排后再交给后处理，下游看到的帧序仍然严格递增
  （校准基线、眨眼曲线、发给 PC 的 `seq` 都依赖这个前提）；
- 缺口最多等 60 ms（`REORDER_GAP_TIMEOUT_MS`），超时就接受丢帧继续跑，
  不会为了等某一帧把整条链路拖慢；重排只做排序、不碰推理，纯 CPU；
- 并行度 1..4（`MAX_GPU_CHANNELS`，含主引擎），默认 2。只在「YUV 直通 + 主引擎
  Vulkan」时 >1；主引擎切到 CPU 后自动退回单路，额外引擎的显存也会释放；
- 时序滤波（landmark / 头姿）是**按通道**喂的：每路只看得到约 1/N 的帧，滤波的
  时间常数不变、只是响应略慢一点；头姿解算用的全局几何管线 + 全局滤波已经加锁
  串行化，不会数据竞争；
- 代价是**每路多一份 landmark 模型常驻显存**。设置面板的
  **「关键点: GPU 并行 ×N」** 按钮可以在 单路 / ×2 / ×3 之间循环（会短暂重启管线），
  也可以改 `PipelineFaceTracker.GPU_CHANNEL_DEFAULT` 或调用 `setGpuChannelCount(n)`。

---

## 📦 目录与模块

工程是一个多 module Gradle 项目 + 一个独立的 Windows 客户端：

| 路径 | 是什么 |
|------|--------|
| `app/` | **Android 采集端**：相机、面捕管线、调试 HUD、追踪数据 WebSocket 服务 |
| `windows-client/` | **Windows 渲染端**：Electron + Cubism Web，连手机 WS 渲染 Live2D |
| `docs/tracking-protocol.md` | **两端之间唯一的契约**：VFC Tracking Protocol v1 |
| `docs/pipeline.md` | **动捕流水线架构**：一帧数据从相机到 Live2D 的完整路径（含延迟账与已知取舍） |
| `docs/upper-body.md` | 上半身关键点采集的设计与接口契约（模块 API / 数据流 / 验收） |
| `:MediapipeFaceLandmark` | **核心推理模块**：MNN + `.mnn` 模型 + 原生 C++ 流水线 + NEON 优化 |
| `:OpenSeeFaceProcess` | 仿 OpenSeeFace 的后处理：平滑、头姿、BlendShape 解算 |
| `:CommonData` | 共享数据类型（`Point3D` / `HeadPose` / `ARKitBlendShapes` …） |
| `:facecapture-sdk` | 对外发布的 AAR 门面，封装上面 3 个模块 |
| `:MediapipeUpperBody` | **可选的上半身关键点模块**：官方 MediaPipe Tasks `PoseLandmarker`，13 个上半身点，默认关 |
| `Live2D/` | **已停止构建**。旧版手机端 Cubism SDK 封装（CMake + JNI），仅作历史参考保留 |
| `ThirdParty/MNN/` | 仅提供头文件 |
| `tools/verify/` | 端到端验证脚本（mock 手机端 + 无头 Chrome 截图 + 映射单测） |

---

## 🚀 快速开始

### 1. 手机端（Android）

```bash
git clone https://github.com/Linli1221/VirtualFaceCapture-MNN.git
cd VirtualFaceCapture-MNN
./check_setup.sh                                     # 可选：环境自检（只查文件是否存在）
git submodule update --init --depth 1 ThirdParty/MNN   # 仅需 MNN 头文件
./gradlew :app:assembleDebug
# 或直接在 Android Studio 中打开并运行 app
```

装到手机（arm64 设备）、授予相机权限，然后：

1. 点 **开始面捕**，正脸看镜头确认画面正常；
2. 点 **重置基线**（戴眼镜 / 换光线 / 换人时都建议点一次）；
3. 点 **网络输出** → 启动，记下对话框里的地址，形如 `ws://192.168.1.23:8765/ws`。

> 手机与电脑必须在同一个局域网（同一台路由器下）；手机需要保持 App 在前台
> （App 会自动保持屏幕常亮，切后台会停相机，这是 Android 相机模型决定的）。

### 2. Windows 端

需要 **Node.js ≥ 20**（只用于跑本地服务与 Electron 壳，不用编译任何原生模块）。

```bash
cd windows-client
npm install          # 国内网络建议先配好 registry / electron_mirror（见下）
npm start            # 启动 Electron 客户端
```

首次启动后在地址栏填手机的地址 `ws://<手机IP>:8765/ws`，回车连接。
连上后：状态点变绿、显示手机型号、输入帧率与端到端 RTT，形象开始跟着动。

**国内网络装不上 Electron** 的话：

```bash
# windows-client/.npmrc 里已经写好，正常 npm install 即可
registry=https://registry.npmmirror.com
electron_mirror=https://npmmirror.com/mirrors/electron/
```

**不想装 Electron**（只想要一个浏览器窗口 / 给 OBS 用）：

```bash
npm run web      # 起服务并用 Edge 的 --app 模式打开一个独立窗口
npm run serve    # 只起本地静态服务，自己用浏览器开 http://127.0.0.1:17890/
npm run mock     # 不用手机：启动一个假手机端（ws://127.0.0.1:8765/ws）验证渲染
```

在完全没装依赖的机器上，`node server.js` 也能单独跑起来（服务本身零依赖，
`renderer/vendor/` 里已经带了 pixi / pixi-live2d-display / Cubism Core 的浏览器构建）。

### 3. 模型从哪来

放进 `windows-client/models/<名字>/<任意>.model3.json` 即可，**目录会被自动发现**，
下拉框里按目录名显示。仓库自带 4 个：`ATRI` / `sensei` / `Yuuka` / `zzzz`。

上半身（可选功能）另需 MediaPipe Pose 模型，用仓库脚本一键下载到
`MediapipeUpperBody/src/main/assets/`：

```bash
./tools/fetch-pose-model.sh          # macOS / Linux
pwsh -File tools/fetch-pose-model.ps1   # Windows
```

> **CI 构建的安装包自带模型**：GitHub Actions 在打包前会自动下载 lite 模型并打进 APK
> （体积 +5.5 MB，构建日志里有 `Fetch upper-body pose model` / `Verify pose model packaged`
> 两步校验）；从 Actions 下载的 APK 开箱可用，只有**本机构建**才需要先跑上面的脚本。

---

## 🎮 操作指南

### 手机端控制面板

| 控件 | 作用 |
|------|------|
| **开始 / 停止面捕** | 启停相机 + 推理管线 |
| **重置基线** | 把当前帧记为「中性脸」（头姿 / 表情零点），等价于 OpenSeeFace 的首帧标定；电脑端也能发 `command: recalibrate` 远程触发 |
| **网络输出** | 打开对话框：启动 / 停止追踪服务、复制 `ws://` 地址、查看客户端数与输出帧率、当前帧缓冲时长、打开 `/status` 状态页 |
| **Camera: 前置 / 后置 / 外接 / 超广角** | 枚举并切换摄像头，包括逻辑多摄后面隐藏的物理镜头（后置超广角 / 长焦）；切换后自动重做基线，选择会记住；竖屏 / 横屏都能用 |
| **Backend: Vulkan / CPU** | 运行时切换 MNN 推理后端（会重建 session，画面中断一帧左右） |
| **关键点: GPU 并行** | 在 单路 / GPU×2 / GPU×3 之间循环：多开几路 GPU 引擎抢同一个帧队列，把 GPU 喂饱（会短暂重启管线） |
| **眨眼曲线 / 眨眼速度** | 是否把眨眼整形为更自然的 230 ms 曲线，以及曲线速度 |
| **眼睛灵敏度** | 眼部 EAR 阈值灵敏度（0.5x ～ 2.0x），下方实时显示生效的 `closed / open` 阈值 |
| **上半身: 开 / 关** | 启动 / 停止 MediaPipe Pose 上半身采集（需要先放模型，见「模型从哪来」）；开启后预览画面叠加骨架、HUD 显示 body fps / 点数 / 躯干角；与面捕开始 / 停止联动 |
| **显示调试 / 关键点** | 顶栏调试 HUD（fps、帧耗时、pitch/yaw/roll + 条形图、ARKit 数值）与相机画面上的关键点 / 检测框叠加 |
| **导出日志（仅 debug 包）** | 控制面板底部「导出日志 (debug)」：把应用日志、设备 / 版本信息、检测器首帧 dump、SharedPreferences 打成一个 zip，用系统「保存到」对话框导出 |

> **日志落在哪**：debug 包启动时 `VfcApp` 会调用 `VfcLog.init()`，所有 `Log.x` 调用
> （面捕 / 相机 / 上半身 / 网络输出 / 崩溃栈）在写 logcat 的同时异步追加到
> `filesDir/logs/vfc.log`（2 MB 轮转、保留 3 份），点「导出日志 (debug)」即可打包成
> `vfc-log-<时间>.zip`（内含 `README.txt` / `device-info.txt` / `app-info.txt` / `logs/` /
> `dump/` / `prefs/`）。release 包不写文件、也不显示按钮。`dump/` 里是检测器首帧
> （相机原始画面，仅用于排查取景 / 旋转），日志目录也不参与云备份。native(C++) 日志、
> 其它进程日志和 tombstone 仍只存在于 logcat，需要时用 `adb logcat`
> （见 [PERFORMANCE.md](PERFORMANCE.md) §5）。

### Windows 客户端

| 控件 | 作用 |
|------|------|
| **地址栏 + 连接** | 填手机地址并连接；地址会记住，下次启动自动连 |
| **模型下拉 + 重载** | 切换 Live2D 模型（自动发现 `models/` 下的目录），重载用于改完参数后重新读 |
| **透明** | 窗口背景透明（需要重建窗口，勾选后会闪一下） |
| **无边框** | 去掉标题栏，配合透明就是「只有角色」的悬浮窗口 |
| **置顶** | 窗口永远在最前，适合叠在游戏 / 桌面上 |
| **鼠标穿透** | 点击穿过窗口点到下面的程序；**穿透后用 `Ctrl + Alt + T` 取消**（全局热键） |
| **一键校准 + 手动微调** | 手机摄像头没正对你（斜着架 / 放在屏幕旁边 / 换了座位）时，保持坐姿 3 秒把当前头姿记为中性，再用 左右 / 上下 / 歪头 滑块微调；偏移自动保存，重开客户端仍生效 |
| **手机重做基线** | 让手机重做中性脸基线（表情 + 头姿），并把电脑端的自适应量程归零；完成后本机姿态偏移自动清零，避免双重校准 |
| **隐藏 UI / 调试** | `H` 隐藏全部面板（只剩角色），`D` 展开实时参数网格（含上半身：ub age / 躯干角 / 手臂参数） |
| **上半身调试骨架** | 打开调试面板后自动显示浮层骨架（13 点 / 15 条边）；也可以直接开 `?skeleton=1`；低可见度点与丢失状态灰显，不影响鼠标穿透 |

断线会自动重连（0.5 s → 1 s → 2 s → 5 s 封顶），状态栏会显示当前阶段；
断开时形象回到默认姿态，不会定格在最后一帧的鬼脸上。
渲染目标帧率固定 **60 fps**（高刷屏不再按屏幕刷新率空转）；手机输入帧率低于 60 时自动做时间轴插值，
输入到达间隔不稳定（Wi-Fi 排队 / PC 调度 / 推理卡顿）时抖动缓冲自适应放宽，画面仍是均匀的
60 fps 运动。细节与 `?fps=<n>` 参数见 [`windows-client/README.md`](windows-client/README.md)。

---

## 🌐 追踪协议（VFC Tracking Protocol v1）

两端之间只认 **[`docs/tracking-protocol.md`](docs/tracking-protocol.md)** 这一份契约：
JSON 文本帧、手写序列化、单帧约 0.5 KB（60 fps ≈ 240 kbps），手机端零图像编码。

| 端点 / 消息 | 方向 | 说明 |
|---|---|---|
| `GET /` | PC → 手机 | 内嵌信息页，确认服务在线 |
| `GET /status` | PC → 手机 | JSON 状态：`running / tracking / clients / fps / device / protocol / version / seq` |
| `WS /ws` | 双向 | 追踪数据长连接（端口固定 8765） |
| `hello` | 手机 → PC | 连接建立时一次：协议名 / 版本 / 设备 / 帧率 / 抽帧系数；开启上半身时带 `upperBody: true` |
| `track` | 手机 → PC | 每帧：`seq` / `t` / `bs[52]` / `pose[3]` / `face` / `score` / `fps` / `calibrating`；开启上半身后**可选**带 `ubOk` / `ub[52]` / `ubT` / `body[2]`（纯增量，老客户端忽略） |
| `calib` | 手机 → PC | 进入 / 离开首帧标定窗口 |
| `ping` / `pong` | 双向 | 保活与 RTT 测量（客户端 5 s 一次，手机 30 s 主动 ping） |
| `command` / `ack` | PC → 手机 | 目前只有 `recalibrate`（等价于手机上的「重置基线」） |
| `bye` | 手机 → PC | 主动告知停止推流的原因 |

关键约定：`bs` 是 **MediaPipe 顺序的 52 项**（索引 0 恒为 neutral），`pose` 是**相对基线**的
`[pitch, yaw, roll]`（度，`pitch>0` 低头、`yaw>0` 向左转头），客户端按 `seq` 丢旧帧，
慢客户端不会拖慢手机推理（每个客户端只有「最新一帧」槽位）。

> **手机端 0.5 s 发送帧缓冲**：追踪帧不直接发走，而是先进队列，攒够 0.5 s 后按生产节奏
> 匀速放行（`TrackingProtocol.FRAME_BUFFER_MS = 500`）。这样 PC 收到的帧间隔不再随推理耗时、
> 系统调度、Wi-Fi 突发抖动，形象动起来不会被「一卡一卡」的输入带偏；代价是端到端延迟固定
> 多 0.5 s。需要最低延迟时把它改成 `0` 即关闭缓冲（退化为「算完就发」）。
> 手机端「网络输出」对话框里会显示当前帧缓冲时长。

---

## 🧍 上半身关键点（可选，MediaPipe Pose）

面捕默认只驱动头与表情。这个可选功能在**同一路相机画面**上再跑一个
[MediaPipe Pose](https://ai.google.dev/edge/mediapipe/solutions/vision/pose_landmarker)（官方
`com.google.mediapipe:tasks-vision` 的 `PoseLandmarker`，LIVE_STREAM），取出 13 个上半身点
（鼻子 / 双眼 / 双耳 / 双肩 / 双肘 / 双腕 / 双胯）与**相对基线的躯干角**，
让形象能跟着肩膀倾斜、转身、抬手：

- **默认关**：不打开时相机线程零额外开销，也不加载 MediaPipe Tasks 运行时；
  打开后按 33 ms（≈30 fps）抽帧推理，GPU 优先、失败自动回退 CPU。
- **脸框引导 ROI**：用面捕最近的人脸框推出上半身区域，只把这块裁到 320p 再推理
  （没有可信脸框 / ROI 太小时回退整帧）；结果点回映整帧坐标，协议 `ub[]` 语义不变。
- **线上一帧只多约 0.6 KB**：`track` 里追加 `ubOk` / `ub`（13×4）/ `ubT` / `body`
  （[`docs/tracking-protocol.md` §4.2/§5.3](docs/tracking-protocol.md)），60 fps 下约 36 KB/s。
- **PC 自动消费**：13 点换算成上半身运动骨骼（脊柱 → 锁骨 → 上臂 → 前臂），躯干驱动
  `ParamBodyAngleX/Y/Z`、肩驱动 `ParamShoulderL/R`、手臂驱动 `ParamArmLA/RA`、肘驱动
  `ParamArmLB/RB`；模型没有这些参数时**自动跳过**（仓库自带 4 个模型只有
  `ParamBodyAngleX/Y/Z`，所以肩 / 手臂 / 肘动作在它们身上看不到，属预期降级；
  在仓库根执行 `node tools/fetch-sample-models.mjs` 可导入 Live2D 官方 Haru / Hiyori
  验收完整骨骼驱动——许可禁止再分发，不进 Git）。
- **丢失平滑回默认**：`ubOk=false` 或 1.5 s 没更新时，参数按帧向 0 衰减回模型自己的 idle motion。
- **预览 / 调试可见**：手机预览叠加 13 点骨架；PC 调试面板有上半身参数组，
  `D` 面板 + `?skeleton=1` 可看浮层骨架。

启用步骤：

```bash
# 1) 下载模型（约 5.5 MB，不进 Git）
./tools/fetch-pose-model.sh          # Windows: pwsh -File tools/fetch-pose-model.ps1
# 2) 重新构建安装 App，打开「开始面捕」→ 点「上半身: 开」
./gradlew :app:assembleDebug
```

也可以把 `pose_landmarker_lite.task` / `pose_landmarker_full.task` 直接
`adb push` 到 `/data/data/com.example.virtualfacecapture/files/models/`（免重新打包）。
没有模型时按钮只提示「缺少模型」，功能保持关闭，不影响面捕与推流。

设计与接口契约（新增模块 API / YUV 抽头 / 线程模型 / 验收清单）见
[`docs/upper-body.md`](docs/upper-body.md)；模块自己的说明见
[`MediapipeUpperBody/README.md`](MediapipeUpperBody/README.md)。

---

## 🎛 ARKit-52 → Live2D 参数映射

映射算法在 **[`docs/tracking-protocol.md` §8](docs/tracking-protocol.md)** 里给了完整的参考实现，
Windows 客户端 `renderer/mapping.js` 与它逐行等价，和旧版手机端渲染器手感一致：

- 眼睛 / 嘴巴 / 眼球：`ParamEyeLOpen = 1 - eyeBlinkLeft`、`ParamMouthOpenY = jawOpen`、
  `ParamMouthForm = (smileL + smileR)/2 - (frownL + frownR)/2`，眼球由 `eyeLook*` 合成；
- 头部姿态：先过 **自适应归一化器**（负向 / 正向量程各自动态跟随，前 100 帧与默认量程线性混合），
  再乘系数写入 `ParamAngleX/Y/Z` 与 `ParamBodyAngleX`；
- 渲染层要求：关掉 Cubism 自带的自动眨眼与鼠标跟随，保留 physics 与 idle motion，
  在 `InternalModel` 的 `beforeModelUpdate` 事件里**绝对赋值**我们算出来的参数。

`mapping.js` 是纯函数模块（无 DOM / 无 PIXI），可以直接在 Node 里跑数值单测：

```bash
cd windows-client
node -e "import('./renderer/mapping.js').then(m => console.log(m.mapTrack({bs:new Array(52).fill(0), pose:[10,-20,5]})))"
```

---

## 🖥 OBS / 直播叠加

三条路，按需选：

| 方式 | 怎么用 | 适合 |
|------|--------|------|
| **窗口捕获**（推荐） | Electron 客户端勾上「透明 + 无边框」，OBS 加「窗口捕获」 | 最简单的叠加，延迟最低 |
| **显示器捕获 / 虚拟摄像头** | 客户端全屏 + OBS 显示器捕获 / 系统虚拟摄像头 | 想要固定分辨率与构图 |
| **OBS 浏览器源** | OBS 加「浏览器」，URL 填 `http://127.0.0.1:17890/?ws=ws://<手机IP>:8765/ws`，宽高自定（如 720×720），勾「不可见时关闭源」 | 不需要开 Electron，OBS 直接吃透明网页 |

页面支持的查询参数：

| 参数 | 取值 | 默认 | 说明 |
|------|------|------|------|
| `ws` | `ws://ip:8765/ws` | `ws://127.0.0.1:8765/ws` | 手机地址（不填则用上次记住的） |
| `model` | 目录名，如 `ATRI` | 上次记住的 / 第一个 | 指定初始模型 |
| `transparent` | `0` / `1` | `0` | 透明底（Electron 会自己带这个参数） |
| `fps` | 整数，`0` = 不限制 | `60` | PC 渲染目标帧率；高刷屏默认限到 60 fps |

> 💡 浏览器源默认 30 fps 采样；客户端窗口模式没有这个限制，追求手感优先用窗口捕获。

---

## 🏗 构建与发布

### 本地构建

```bash
./gradlew :app:assembleDebug          # 调试包
./gradlew :app:assembleRelease        # 发布包（未配签名时回退 debug key）
./gradlew :app:testDebugUnitTest      # 追踪协议的 JVM 单元测试
```

> 首次构建需要 `git submodule update --init --depth 1 ThirdParty/MNN` 拿到 MNN 头文件；
> 本机 SDK 里的 build-tools 与 AGP 期望版本不一致时，加 `-Pandroid.builder.sdkDownload=false`
> 让 Gradle 用已安装的版本。

Windows 客户端是纯 JS，不需要编译，但可以打成**免安装便携版**（内置 Electron 运行时）：

```powershell
cd windows-client
npm ci
npm run selftest          # 36 项自检（映射 / 低帧率插值 / 抖动缓冲 / 静态路由）
npm run package           # → dist/windows/VFC-Live2D-win32-x64/
Compress-Archive -Path dist\windows\VFC-Live2D-win32-x64 -DestinationPath dist\VFC-Live2D-win32-x64.zip
```

打包后可用 `VFC-Live2D.exe --smoke` 自检（等模型渲染就绪，结果写 `resources/app/electron/smoke-result.json`）。

### GitHub Actions 自动构建（每次修改出包）

仓库已配置两个 workflow，**每次修改都会自动构建**：

**[Android CI](.github/workflows/android-ci.yml)** —— 任意分支 push / 任意 PR：

- **debug 与 release 两个变体独立并行构建**，各出一个 Artifact（保留 30 天），
  到仓库 `Actions` → 对应运行 → `Artifacts` 下载：
  - `VirtualFaceCapture-<sha>-arm64-debug` —— arm64-v8a debug APK
  - `VirtualFaceCapture-<sha>-arm64-release` —— arm64-v8a release APK + SDK AAR
- 两者互不阻塞：一个变体挂了另一个照样出包；
- CI 产物自带版本号 `1.0.<运行号>+<commit>`（versionCode 单调递增）；
- ⚠️ **能否覆盖安装取决于签名**：默认回退 debug key（每次构建都不同，必须先卸载旧包）；
  配好下面的「正式签名」后签名固定，才能直接覆盖升级；
- **发布正式安装包**：推送 `v` 开头的 tag，自动构建签名 Release APK 并创建 GitHub Release：

```bash
git tag v1.0.0
git push origin v1.0.0
```

**[Windows Client CI](.github/workflows/windows-client-ci.yml)** —— 改动落在 `windows-client/**` 时：

- 在 `windows-latest` 上 `npm ci` → `npm run selftest` → `npm run package`，
  产出免安装便携版 zip（内置 Electron 运行时 + 4 个 Live2D 模型）；
- 打包后**真的启动一次 exe 做自检**（`--smoke`，等渲染就绪才通过），
  避免「zip 生成了但程序起不来」；
- Artifact：`VFC-Live2D-win32-x64-<sha>`（zip + `SHA256SUMS.txt`）；
- 推送 `v*` 标签时同样附到 GitHub Release，和 APK 一起分发。


### 签名 / Release 打包

不做任何配置时 `release` 包会用 **debug key** 签名。这能装、能跑，但有一个坑：
**GitHub Actions 每台 runner 的 debug key 都是现场新生成的**，所以每次构建出来的包
签名都不一样，装上新的会报：

```
INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package ... signatures do not match previously installed version
```

解法是把签名密钥固定下来（顺便这也是发布到应用商店 / 分享给别人所必需的）。

配好之后**debug 包也会用这把固定 key 签名** —— 因为 CI 默认只出 debug 包，不固定的话
每次装新包都得先卸载。切换签名的那一次需要手动卸载一次旧包
（`adb uninstall com.example.virtualfacecapture`），之后就能一直覆盖升级了。

#### 1. 生成密钥库

```bash
keytool -genkeypair -v \
  -keystore virtualfacecapture-release.jks \
  -alias virtualfacecapture \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <你的密码> -keypass <同一个密码> \
  -dname "CN=VirtualFaceCapture, OU=Release, O=YourOrg, C=CN"
```

> 🔐 **这个 .jks 和密码务必自己备份好**：一旦丢失，就再也无法用同一个签名更新已安装的
> App（只能让用户卸载重装）。它已经被 `.gitignore` 排除，**不要提交**。

#### 2. 配到 GitHub（4 个 Secret）

仓库 `Settings` → `Secrets and variables` → `Actions` → `New repository secret`：

| Secret | 值 |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | 整个 `.jks` 文件的 base64（单行） |
| `RELEASE_STORE_PASSWORD` | keystore 密码 |
| `RELEASE_KEY_ALIAS` | 别名，如 `virtualfacecapture` |
| `RELEASE_KEY_PASSWORD` | key 密码（通常同上） |

base64 一行生成：

```bash
# Linux / macOS
base64 -w 0 virtualfacecapture-release.jks
# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("virtualfacecapture-release.jks"))
```

配好后每次 CI 都会用这把 key 签名，日志 / Summary 里会打印证书 DN 与 SHA-256 指纹，
方便核对「这次和上次是不是同一个签名」。

#### 3. 本地构建也能用

在**仓库根目录**放一个 `keystore.properties`（已在 `.gitignore` 里）：

```properties
storeFile=/absolute/path/to/virtualfacecapture-release.jks
storePassword=你的密码
keyAlias=virtualfacecapture
keyPassword=同一个密码
```

也可以用环境变量 `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD`，优先级高于 `keystore.properties`。

#### 4. 验证

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

顺带一提：**测帧率一定用 release 包**，debug 包带调试信息、跑得明显更慢。

---

## 🧩 作为 SDK 集成

如果只想要面捕能力（自己接渲染、或送到别的地方），请参考
[`facecapture-sdk/README.md`](facecapture-sdk/README.md)，门面 API 形如：

```kotlin
val engine = FaceCaptureEngine.create()
engine.initialize(context)
engine.setResultListener { result ->
    val landmarks   = result.landmarks         // 478 个 3D 关键点
    val blendShapes = result.blendShapes       // ARKit 52 BlendShape
    val pose        = result.relativeHeadPose  // pitch / yaw / roll（相对基线）
}
// 把 CameraX 的 ImageProxy 喂进来
engine.pushFrame(image, rotationDegrees)
```

想直接复用两端的约定，照抄 `docs/tracking-protocol.md` 就行 —— 它就是给
「手机产出数据、别处渲染」这种用法准备的。

---

## 🛠 工程特点

- **零 MediaPipe 运行时依赖**：删掉了 `CalculatorGraph`、`PacketCallback`、
  `AndroidPacketCreator` 等全部 MediaPipe 框架代码，模型管线由 C++ 顺序调度，上层 Kotlin 代码做多线程并行流水线。
- **MNN-CV 替代 OpenCV**：YUV 解码、缩放、仿射、归一化全走 MNN-CV，省掉
  几 MB 的 OpenCV-Android 依赖。
- **NEON SIMD**：`neon_intrinsics.h` 中实现了点积、矩阵乘、3×N 外积、
  Jacobi 旋转、4×4 仿射变换等常用算子；用于 SVD 求头姿和 478 点云的批量
  变换，热点相比标量实现有明显提速。
- **仿 OpenSeeFace 后处理**：参考 Python 版的 `tracker.py` / `similaritytransform.py`
  重写为 C++/Kotlin，包括 One-Euro 平滑、首帧标定、眨眼曲线整形等。
- **ARKit-52 BlendShape 自解算**：不依赖 MediaPipe 的第三个 BlendShape 模型，
  直接基于 478 关键点几何关系解算，省一次推理。
- **可发布的 AAR**：`:facecapture-sdk` 模块已经配置好 `maven-publish`，
  可一键打包成独立的面捕 SDK。
- **可选的上半身采集**：`:MediapipeUpperBody` 用官方 MediaPipe Tasks `PoseLandmarker`
  在相机同一路 YUV 上抽 30 fps 推理（上限目标），只把 13 个上半身点 + 相对躯干角增量塞进原协议；
  默认关、模型缺失自动降级，与面捕管线完全解耦。
- **多摄像头（含物理超广角）**：运行时枚举并切换前置 / 后置 / 外接摄像头；
  API 28+ 还会把逻辑多摄隐藏的物理镜头（后置超广角 / 长焦）作为独立可选项暴露出来
  （`OutputConfiguration.setPhysicalCameraId`），按设备重算旋转、垂直 FOV 与预览镜像，
  切换后自动重新标定中性脸。
- **横竖屏无缝切换**：`screenOrientation="sensor"` + `configChanges`，旋转时 Activity
  不重建，相机 session / 面捕管线 / 预览都不中断；帧按 `display.rotation` 自动转正，
  控制面板与摄像头列表按可用高度自适应。
- **推流不拖累推理**：每个 WS 客户端只有「最新一帧」覆盖槽，慢网络只会丢帧，
  不会把手机的推理线程堵住。
- **两端可独立验证**：手机端有协议的 JVM 单元测试；电脑端有
  `tools/verify/`（假手机端 + 无头 Chrome 截图 + 映射数值单测），
  改协议 / 改映射都能在 CI 之外本地跑一遍。

---

## 📁 目录速览

```
VirtualFaceCapture-MNN/
├── app/                         # 安卓采集端（相机 + 面捕 + 追踪数据服务）
│   └── src/main/java/.../tracking/   # TrackingServer / TrackingProtocol / TrackingOutputManager
├── windows-client/              # Windows 渲染端（Electron + Cubism Web）
│   ├── electron/                #   桌面壳（透明 / 无边框 / 置顶 / 鼠标穿透）
│   ├── renderer/                #   页面、映射 (mapping.js)、Cubism 渲染 (live2d.js)
│   ├── server.js                #   本地静态服务（零依赖，127.0.0.1:17890）
│   └── models/                  #   Live2D 模型（自动发现）
├── MediapipeFaceLandmark/       # MNN 推理 + NEON 优化的核心
│   ├── src/main/assets/         #   .mnn 模型 (detector / landmark)
│   ├── src/main/cpp/            #   C++ 流水线、YUV、几何
│   │   └── include/face_geomentry/
│   │       ├── mini_linalg.h        # 线代
│   │       └── neon_intrinsics.h    # ARM NEON SIMD
│   └── src/main/jniLibs/        #   预编译 libMNN.so
├── OpenSeeFaceProcess/          # 仿 OpenSeeFace 后处理
├── CommonData/                  # 共享数据类
├── facecapture-sdk/             # 对外发布的 AAR 门面
├── MediapipeUpperBody/          # 可选：上半身关键点（MediaPipe Tasks PoseLandmarker）
├── docs/tracking-protocol.md    # 两端通信协议（唯一契约）
├── docs/pipeline.md             # 动捕流水线架构（相机 → 推理 → 协议 → 渲染）
├── docs/upper-body.md           # 上半身采集的设计与接口契约
├── tools/verify/                # 端到端验证脚本
├── Live2D/                      # 旧版手机端渲染（已停止构建，仅参考）
└── ThirdParty/MNN/              # 仅提供 MNN 头文件
```

---

## 💡 遗留问题

- 由于模型参数表现自带差异，人脸形状也是各有不同，以及点位的映射很难做到理想意义上的
  0-1 值，所以实际上在 OpenSeeFaceProcess 后处理里面很多阈值设定偏向工程 trick
  （多次发动了俺寻思之力），目前暂时想不到一个在任何情况下都很完美的办法，
  如果有其他好的后处理方案欢迎直接提 PR (￣▽￣)"
- 自适应归一化器是「按人头学」的：换人 / 大幅换姿势后建议点一次 **重置基线**。
- 局域网内没有鉴权，**只在可信网络里开**。

---

## 🙏 借物 / 致谢

- Live2D 模型 sensi：<https://www.bilibili.com/video/BV1tk4y1n7Ls/>
- Live2D 模型 ATRI：<https://www.bilibili.com/video/BV1Rs4y187rJ>
- [google/mediapipe](https://github.com/google/mediapipe)
- [emilianavt/OpenSeeFace](https://github.com/emilianavt/OpenSeeFace)
- [alibaba/MNN](https://github.com/alibaba/MNN)
- [pixi.js](https://github.com/pixijs/pixijs) / [pixi-live2d-display](https://github.com/guansss/pixi-live2d-display) / [Live2D Cubism SDK](https://www.live2d.com/en/sdk/)
