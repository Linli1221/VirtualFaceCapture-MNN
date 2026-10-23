# VFC Tracking Protocol v1

> 手机端做**面部捕捉**（可选叠加**上半身关键点**），通过局域网把**每帧追踪数据**
> （ARKit-52 BlendShape + 头部姿态 + 可选上半身 13 点 / 躯干角）发给 Windows 客户端，
> 由客户端渲染 Live2D 虚拟形象。
>
> 本文是手机端（Android）与 Windows 客户端之间的**唯一契约**。两端实现都必须严格按本文的字段名、
> 索引顺序和符号约定来写；改动协议必须同时在本文与两端代码里升级 `version`。
>
> 想知道「这些字段是流水线里的哪一步算出来的」，看
> [`docs/pipeline.md`](./pipeline.md)（动捕流水线架构）。

---

## 1. 角色与拓扑

```
┌──────────────────────────┐            WebSocket (JSON, 30~60 Hz)         ┌──────────────────────────┐
│ Android 手机              │  ────────────────────────────────────────►  │ Windows 客户端            │
│  Camera2 → MNN 推理       │   ws://<phone-ip>:8765/ws                    │  Cubism Web (Live2D)     │
│  → OpenSeeFace 后处理     │                                             │  参数映射 + 渲染          │
│  → ARKit-52 + 头姿        │  ◄────────────────────────────────────────  │                          │
│                          │         ping / command（可选）               │                          │
└──────────────────────────┘                                             └──────────────────────────┘
        HTTP: GET /  (信息页)   GET /status (JSON 状态)
```

- **服务端 = 手机**，**客户端 = Windows**。手机不需要知道客户端地址；客户端主动连手机。
- 传输层：**WebSocket + 文本帧 + JSON（UTF-8）**。没有二进制帧，便于抓包排障。
- 端口：**8765**（固定，手机与客户端同一局域网即可）。
- 只传**语义数据**（表情权重 + 角度；可选上半身关键点），不传图像/视频。未开启上半身时
  一帧约 **0.4~0.7 KB**，30 Hz 约 15~20 KB/s，60 Hz 约 30~40 KB/s；开启上半身后约
  **1.1 KB / 60 fps ≈ 66 KB/s**（见 §9），手机端零编码开销。

---

## 2. 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/` | 信息页（HTML，纯静态 + 状态轮询），用来确认服务在线、查看连接数 |
| GET | `/status` | JSON 状态，见 §6 |
| WS  | `/ws` | **追踪数据长连接**（唯一的数据通道） |

> v0.x 的 `/stream.mjpg`、WebP 帧推送、`?bg=` 等页面参数已随「手机端渲染」一起移除。

---

## 3. 消息总览

手机 → 客户端：

| `type` | 频率 | 作用 |
|---------|------|------|
| `hello` | 每个连接建立后 **1 次** | 协议版本、设备信息、建议帧率 |
| `track` | 每处理完一帧 1 次（≤ 60 Hz） | 该帧的表情权重 + 头部姿态（+ 可选上半身关键点，见 §4.2） |
| `calib` | 校准开始时 1 次，结束时 1 次 | 校准窗口状态（可选，`track.calibrating` 已带同样信息） |
| `pong` | 收到 `ping` 时 | 心跳应答 |
| `ack`  | 收到 `command` 时 | 命令确认 |
| `bye`  | 停止服务/退出时 | 主动断开提示 |

客户端 → 手机（全部可选，手机不依赖它们）：

| `type` | 作用 |
|---------|------|
| `ping` | 心跳，手机回 `pong` |
| `command` | `{"name":"recalibrate"}` 让手机重做中性脸基线校准 |

**未知字段必须忽略，未知 `type` 必须忽略**（向前兼容）。缺失的 `track` 可选字段按默认值处理。

> `hello.upperBody` 与 `track` 的 `ubOk` / `ub` / `ubT` / `body` 是 v1 的**纯增量可选字段**：
> 手机端未开启上半身采集时完全不下发，老客户端按「未知字段忽略」即可（§10），不需要升 `version`。

---

## 4. 消息定义

### 4.1 `hello`

连接建立后手机**立即**发送。

```json
{
  "type": "hello",
  "protocol": "vfc-tracker",
  "version": 1,
  "device": "Pixel 8 Pro",
  "app": "VirtualFaceCapture/1.0.3",
  "decimation": 1,
  "targetFps": 60,
  "upperBody": true,
  "t": 1730000000000
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `protocol` | string | 固定 `"vfc-tracker"` |
| `version` | int | 协议版本，本文为 **1** |
| `device` | string | 手机型号（仅展示用） |
| `app` | string | App 名 + versionName |
| `decimation` | int | 每处理 N 帧发 1 帧（1 = 全量；手机端为省电/省带宽时可 >1） |
| `targetFps` | int | 手机当前的目标输出帧率 |
| `upperBody` | bool | **可选**：手机端上半身采集已启用。仅开启时下发 `true`，关闭时字段不出现（不是 `false`） |
| `t` | int | 手机本地 Unix 毫秒时间戳 |

> `upperBody` 只是**建连那一刻**的能力声明，**不代表每帧都有结果**；每帧是否有效看
> `track.ubOk`（§4.2）。连接期间打开 / 关闭上半身不会重发 `hello`，客户端以
> `track` 里是否出现 `ub` 作为最终依据：出现就开始画（`ubOk` 决定是否驱动），
> 消失就平滑回默认姿态。

### 4.2 `track`

每帧一条。**这是唯一需要高性能处理的报文**：客户端要按 `seq` 丢旧帧（见 §7）。

```json
{
  "type": "track",
  "seq": 128,
  "t": 1730000000123,
  "bs": [0,0.02,0.01, ...],
  "pose": [3.5, -8.2, 0.4],
  "face": true,
  "score": 0.98,
  "fps": 59.1,
  "calibrating": false,
  "ubOk": true,
  "ub": [0.5123,0.3311,-0.021,0.99, 0.4871,0.3302,-0.019,0.98, ...],
  "ubT": 1730000000100,
  "body": [2.5, -4.1]
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `seq` | int | ✅ | 帧序号，从 0 起单调递增。客户端用来丢旧帧 |
| `t` | int | ✅ | 采集时间戳（手机本地 Unix 毫秒） |
| `bs` | float[52] | ✅ | ARKit-52 权重，**顺序见 §5**，取值 [0,1] |
| `pose` | float[3] | ✅ | 相对头部姿态 `[pitch, yaw, roll]`，**度**，符号见 §5.2 |
| `face` | bool | ✅ | 本帧是否检测到人脸。`false` 时 `bs` 与 `pose` 保留上一帧值或为 0 |
| `score` | float | — | 人脸存在置信度 [0,1] |
| `fps` | float | — | 手机端处理帧率，仅展示 |
| `calibrating` | bool | — | 是否仍在首帧标定窗口内 |
| `ubOk` | bool | — | 本帧是否有可用的上半身结果。**开启上半身时每帧都出现**；`false` = 丢失 / 过期，客户端平滑回默认姿态 |
| `ub` | float[52] | — | 13 点 × `[x, y, z, visibility]`，顺序见 §5.3。`ubOk=false` 时仍是 52 个 `0`（结构稳定） |
| `ubT` | int | — | 该**关键点帧**的手机采集时间（ms）。`0` = 已启用但还没收到过有效帧 |
| `body` | float[2] | — | 相对基线的躯干角 `[roll, yaw]`（度），符号见 §5.3；`ubOk=false` 时为 `[0,0]` |

数值建议保留 **4 位小数**（`0.1235`），够用且省带宽。

**上半身字段的约定**（可选，但只有「开 / 关」两种形态）：

- **未开启上半身**：`ubOk` / `ub` / `ubT` / `body` **一个都不出现**，报文与 v1 原有形态完全一致。
- **已开启**：`ubOk` 每帧都出现。本帧有结果 → `true`；丢失 / 超过 **1500 ms** 没更新 /
  `tracking=false` → `false`，此时 `ub` 仍发 52 个 `0`、`body` 发 `[0,0]`。
  客户端收到 `ubOk=false` 应在约 0.3 s 内把手臂 / 身体参数平滑回 0，不要冻结在最后一帧。
- `ubT` 是**关键点帧**的时间，不是 `track` 帧的 `t`：上半身推理约 15 Hz，而 `track` 最高 60 Hz，
  连续几条 `track` 可能复用同一份关键点（`ubT` 相同 = 没有新结果，不要重复做平滑 / 插值）。
- `ubT` 和 `t` 都是**同一台手机**的时钟，客户端可以用 `t - ubT` 判断这条 `track` 复用的
  关键点有多旧（正常 ≤ 66 ms）；**不要**把 `ubT` 和 PC 本机时钟直接相减（两端时钟不同步，§7.8）。
  是否过期仍以 `ubOk` 为准——服务端已经用手机时钟做过 1500 ms 判定。
- `body` 已减基线：手机端重新打开上半身开关 / 重置基线后归零，客户端应同步把身体参数淡回中位。

### 4.3 `calib`

```json
{"type":"calib","active":true,"seq":42,"t":1730000000456}
```

| 字段 | 说明 |
|------|------|
| `active` | `true` = 进入校准窗口；`false` = 校准完成 |
| `seq` | 触发时的帧序号 |
| `t` | 手机时间戳 |

客户端收到 `active:true` 建议把形象姿态平滑回中性（可选），收到 `false` 后恢复正常驱动。

### 4.4 `ping` / `pong` / `bye` / `ack`

```json
{"type":"ping","t":1730000000789}
{"type":"pong","t":1730000000789}
{"type":"command","name":"recalibrate"}
{"type":"ack","name":"recalibrate"}
{"type":"bye","reason":"service stopped"}
```

- 客户端建议 **每 5 s** 发一次 `ping`；20 s 内既没有 `track` 也没有 `pong` 视为断线（见 §7）。
- `command` 的 `name` 目前只有 `recalibrate`（等价于手机 UI 上的 Reset Baseline）。

---

## 5. 数据语义

### 5.1 `bs`：ARKit-52 索引顺序

数组长度 **必须**是 52。顺序与 MediaPipe BlendShape 输出顺序 / 本仓
`ARKitBlendShapes` 的 `BS_*` 常量完全一致（索引 0 为 neutral，恒为 0）：

| idx | 名称 | idx | 名称 | idx | 名称 | idx | 名称 |
|----:|------|----:|------|----:|------|----:|------|
| 0 | neutral (恒 0) | 13 | eyeLookInLeft | 26 | jawRight | 39 | mouthRight |
| 1 | browDownLeft | 14 | eyeLookInRight | 27 | mouthClose | 40 | mouthRollLower |
| 2 | browDownRight | 15 | eyeLookOutLeft | 28 | mouthDimpleLeft | 41 | mouthRollUpper |
| 3 | browInnerUp | 16 | eyeLookOutRight | 29 | mouthDimpleRight | 42 | mouthShrugLower |
| 4 | browOuterUpLeft | 17 | eyeLookUpLeft | 30 | mouthFrownLeft | 43 | mouthShrugUpper |
| 5 | browOuterUpRight | 18 | eyeLookUpRight | 31 | mouthFrownRight | 44 | mouthSmileLeft |
| 6 | cheekPuff | 19 | eyeSquintLeft | 32 | mouthFunnel | 45 | mouthSmileRight |
| 7 | cheekSquintLeft | 20 | eyeSquintRight | 33 | mouthLeft | 46 | mouthStretchLeft |
| 8 | cheekSquintRight | 21 | eyeWideLeft | 34 | mouthLowerDownLeft | 47 | mouthStretchRight |
| 9 | eyeBlinkLeft | 22 | eyeWideRight | 35 | mouthLowerDownRight | 48 | mouthUpperUpLeft |
| 10 | eyeBlinkRight | 23 | jawForward | 36 | mouthPressLeft | 49 | mouthUpperUpRight |
| 11 | eyeLookDownLeft | 24 | jawLeft | 37 | mouthPressRight | 50 | noseSneerLeft |
| 12 | eyeLookDownRight | 25 | jawOpen | 38 | mouthPucker | 51 | noseSneerRight |

> 这些值**已经过**手机端的眨眼曲线整形（Blink Curve）、One-Euro/时间 EMA 平滑与首帧标定，
> 客户端不需要再做表情层平滑（姿态层建议做 1~2 帧插值/缓动，见 §8）。

### 5.2 `pose`：相对头部姿态（度）

已减去首帧标定基线，即"中性脸 = [0,0,0]"。

- `pitch` **< 0 = 抬头**，**> 0 = 低头**
- `yaw` **< 0 = 真人向右转**，**> 0 = 真人向左转**
- `roll` 正值 = 顺时针歪头（与手机调试 HUD 显示一致）
- 典型范围：pitch ∈ [-40, 40]，yaw ∈ [-45, 45]，roll ∈ [-30, 30]

> 这套符号是**手机坐标系下的人脸朝向**，与 Live2D 的 `ParamAngleX/Y/Z` 不是同一个方向约定，
> 不能直接赋值。参考映射见 §8.1（与 Android 旧版渲染器逐行等价）。

### 5.3 `ub`：上半身 13 点索引与坐标语义

`ub` 是 **13 个点 × 4 个值 = 52 个 float**，严格按下面的顺序展平
（`ub[i*4+0] = x`、`ub[i*4+1] = y`、`ub[i*4+2] = z`、`ub[i*4+3] = visibility`）：

| 协议 idx | 名称 | MediaPipe Pose 原始 idx | 说明 |
|---:|---|---:|---|
| 0 | nose | 0 | 鼻子 |
| 1 | eye_left | 2 | 被拍者左眼 |
| 2 | eye_right | 5 | 被拍者右眼 |
| 3 | ear_left | 7 | 被拍者左耳 |
| 4 | ear_right | 8 | 被拍者右耳 |
| 5 | shoulder_left | 11 | 左肩 |
| 6 | shoulder_right | 12 | 右肩 |
| 7 | elbow_left | 13 | 左肘 |
| 8 | elbow_right | 14 | 右肘 |
| 9 | wrist_left | 15 | 左腕 |
| 10 | wrist_right | 16 | 右腕 |
| 11 | hip_left | 23 | 左胯 |
| 12 | hip_right | 24 | 右胯 |

顺序来自 `CommonData` 的 `UpperBodyLandmarkIndex.ORDER`；Windows 客户端的
`BODY_LANDMARK_ORDER` 必须与上表逐项一致。只上传这 13 个点，MediaPipe Pose 的其余
20 个点（手指、脚等）不上传。

**坐标语义**（与手机预览一致，客户端画骨架时**不要再做任何翻转**）：

- `x` / `y`：归一化图像坐标，基于**旋转 + 镜像之后**的预览画面。`x=0` 是画面左缘、`x=1` 是右缘；
  `y=0` 是上缘、`y=1` 是下缘（**y 向下增大**）。协议里裁剪到 `[0,1]`。
- `z`：MediaPipe 的相对深度，量纲与 `x` 近似（约等于图宽的比例），**越小越靠近镜头**。
  它是相对量，不能当绝对距离；判断手的前后请用差值（如 `wrist.z - shoulder.z < 0` = 手伸向镜头）。
  协议里裁剪到 `[-5,5]`。
- `visibility`：该点可见度 `[0,1]`（MediaPipe 原值），协议里裁剪到 `[0,1]`。
  建议低于 **0.3~0.5** 的点不参与参数驱动（参考实现取 0.3）；肩 / 胯贴到画面边缘时可见度会明显下降。
- **镜像语义**：前置摄像头（`mirrorHorizontal=true`）只镜像**像素**，协议不交换左右标签。
  画面像照镜子：你抬右手，手机预览里抬起的手也在画面右侧；客户端直接用 `x` 画骨架即可。
- `left` / `right` 始终指**被拍者本人**的左右（MediaPipe 的 `LEFT_*` / `RIGHT_*` 语义），
  **不是画面左右**。不要用 `x` 的大小去推测左右，按索引取点。

**`body`：相对躯干角（度）**

- `body[0] = roll`：肩线倾斜。**> 0 = 被拍者右肩下沉**（画面上顺时针），典型 ±30。
- `body[1] = yaw`：肩线绕竖轴转角。**> 0 = 被拍者向自己的左侧转**（与 §5.2 头部 `yaw` 同号），典型 ±45。
- 已减基线：手机端重新打开上半身开关 / 重置基线后归零；范围裁剪到 `[-180,180]`。
- 这是**躯干**角，不是相机角；客户端不要把 §5.2 的 `pose` roll/yaw 再重复叠加（§8.5）。

> 手臂抬起角等派生公式见 §8.5；本节只定义**数据语义**，不含客户端映射算法。

---

## 6. `GET /status`

```json
{
  "running": true,
  "tracking": true,
  "clients": 1,
  "fps": 59.2,
  "device": "Pixel 8 Pro",
  "protocol": "vfc-tracker",
  "version": 1,
  "seq": 8421
}
```

| 字段 | 说明 |
|------|------|
| `running` | 追踪服务是否在监听 |
| `tracking` | 面捕管线是否在跑（相机 + 推理） |
| `clients` | 当前 WS 客户端数 |
| `fps` | 最近一秒实际发出帧率 |
| `seq` | 最新帧序号 |

---

## 7. 时序、丢帧与断线

1. **手机端**：推流与推理解耦，并且**发送前先过帧缓冲**。
   - 推理结果先进发送帧缓冲（默认 **500 ms**，`TrackingProtocol.FRAME_BUFFER_MS`）：
     队头攒够 0.5 s 才开始放行（起播预缓冲），之后按生产节奏（相邻帧入队间隔的滑动平均）
     **匀速放行**，与某一帧具体何时算完无关。因此推理偶尔卡一下只是把队列填厚，
     PC 收到的帧间隔仍然均匀，Live2D 不会跟着一卡一卡。
   - 队列被抽空（真断流）后再来帧时，重新走一次预缓冲，保证延迟余量能恢复。
   - 卡顿会吃掉一部分缓冲深度，所以放行节奏会按「当前深度 vs 目标深度」做比例微调
     （最多 ±10~15%），几秒内把 0.5 s 的垫子补回来 —— 否则跑久了延迟会悄悄变短。
   - 缓冲队列上限 120 条，溢出时丢最旧的帧，延迟不会无限增长；`seq` 仍在产出侧分配、
     单调递增，只是发送时刻整体推迟了缓冲时长。
   - 代价是**端到端延迟固定增加 0.5 s**。把 `FRAME_BUFFER_MS` 设为 `0` 即关闭缓冲，
     退化为「算完就发」的最低延迟模式。
   - 每个客户端一个发送队列，队列里只保留**最新一帧**（覆盖式写入，不做堆积），
     避免慢客户端拖慢推理；控制消息（hello / calib / pong / ack / bye）走独立队列，不会被数据帧冲掉。
2. **多客户端**：允许多个客户端同时连（1 ~ 4 个），互不影响；超过上限拒绝。
3. **客户端**：按 `seq` 丢弃过期帧 —— WebSocket 的 `message` 可能批量到达，
   只取最后一条 `track` 应用，然后立刻渲染。**永远不要为了追帧去做忙等。**
   渲染目标帧率固定 60 fps（`PIXI.Ticker.maxFPS`）；输入帧率低于渲染帧率时，客户端对最近几帧
   按时间轴插值（延迟约一个输入间隔，断流时外推有上限），避免 60 fps 画面里出现
   「hold 一下再跳一步」的台阶；输入跟得上渲染时不加这段延迟。
   到达间隔不稳定（Wi-Fi 排队、PC 调度 batch、推理卡顿）时，延迟自适应放宽
   （输入间隔 p85 + 传输滞后 p90 + 余量，上限 120 ms），并按「快升慢降 + 速率上限」
   缓慢变化，保证播放时间只前进、不倒带；单次 100~200 ms 的抽风不会把延迟永久抬高。
4. **断线重连**：客户端指数退避（0.5 s → 1 s → 2 s → 5 s 封顶），并在 UI 上显示状态。
5. **`seq` 的生命周期 = 手机端一次连续推流**。手机 App 重启、或停止后再点开始，
   `seq` 会从 0 重新计数。因此客户端**每次收到 `hello`（即每建立一条新连接）都必须把
   本地 `lastSeq` 重置为 `-1`**。漏掉这一步的典型症状是：UI 显示"已连接"、帧率计数上涨，
   但画面完全不动 —— 因为重启后的所有帧都被当成过期帧丢掉了。
6. **断线后回中性**：连接断开（含用户手动断开）时，客户端应清空目标参数、让模型回到
   默认姿态（`clearTargetParams()` 等价操作）。否则模型会保持最后一帧的表情与头姿，
   看起来像"鬼脸冻结"。重新连上后由新的 `track` 帧重新驱动。
7. **心跳**：见 §4.4。手机在无面捕时也照常回 `pong`。
8. **时间戳**：两端时钟不同步是正常的，**不能用 `t` 的绝对值计算延迟**；
   延迟估算用 `ping/pong` 的往返时间。但客户端可以用 `t` 的**相邻差值**做播放节奏估计
   （相对量，与两端时钟基线无关），并把它作为 §7.3 时间轴的首选时间。

---

## 8. 参考映射：追踪数据 → Live2D 参数

§8.1 ~ §8.4 的算法与旧版 Android 渲染器（`Live2DAvatarAdapter` + `LAppModel::ModelParameterUpdate`）
**逐行等价**，Windows 客户端应直接照此实现，保证"换端后手感不变"。§8.5 是可选上半身
（`ub` / `body`）的参考映射，由 Windows 客户端按自己模型的参数取舍。

### 8.1 直通参数（每帧绝对赋值）

```
eyeLOpen  = 1 - bs[9]                       // bs[9]  = eyeBlinkLeft
eyeROpen  = 1 - bs[10]                      // bs[10] = eyeBlinkRight
mouthOpen = bs[25]                          // bs[25] = jawOpen
mouthForm = (bs[44] + bs[45]) / 2 - (bs[30] + bs[31]) / 2   // smile - frown
eyeBallX  = ((bs[15] - bs[13]) + (bs[14] - bs[16])) / 2     // outL-inL, inR-outR
eyeBallY  = ((bs[17] - bs[11]) + (bs[18] - bs[12])) / 2     // upL-downL, upR-downR

setParam("ParamEyeLOpen",  eyeLOpen)
setParam("ParamEyeROpen",  eyeROpen)
setParam("ParamMouthOpenY", mouthOpen)
setParam("ParamMouthForm",  clamp(mouthForm, -1, 1))
setParam("ParamEyeBallX",   clamp(eyeBallX, -1, 1))
setParam("ParamEyeBallY",   clamp(eyeBallY, -1, 1))
```

### 8.2 头部姿态：自适应归一化后再乘系数

`normalize` 是**非对称自适应归一化器**（范围随实际动作幅度自适应，默认范围之外越界即截断）。

```js
class AdaptiveAngleNormalizer {
  constructor(defaultNegativeRange, defaultPositiveRange, minRange = 5, adaptationRate = 0.1) {
    this.defaultNeg = defaultNegativeRange;
    this.defaultPos = defaultPositiveRange;
    this.minRange = minRange;
    this.rate = adaptationRate;
    this.pctNeg = 0; this.pctPos = 0; this.samples = 0;
  }
  normalize(v) {
    this.update(v);
    return v < 0
      ? clamp(v / this.effectiveNeg(), -1, 0)
      : clamp(v / this.effectivePos(), 0, 1);
  }
  update(v) {
    this.samples++;
    const alpha = this.samples < 100 ? 0.05 : 0.01;
    if (v < 0) {
      const a = -v;
      this.pctNeg += (a > this.pctNeg)
        ? (a - this.pctNeg) * this.rate
        : (a - this.pctNeg) * alpha * 0.1;
    } else {
      this.pctPos += (v > this.pctPos)
        ? (v - this.pctPos) * this.rate
        : (v - this.pctPos) * alpha * 0.1;
    }
  }
  effectiveNeg() {
    const blend = Math.min(1, this.samples / 100);
    return Math.max(this.defaultNeg * (1 - blend) + this.pctNeg * blend, this.minRange);
  }
  effectivePos() {
    const blend = Math.min(1, this.samples / 100);
    return Math.max(this.defaultPos * (1 - blend) + this.pctPos * blend, this.minRange);
  }
  reset() { this.pctNeg = 0; this.pctPos = 0; this.samples = 0; }
}

const pitchNorm = new AdaptiveAngleNormalizer(20, 40, 10);  // 低头 ~20°，抬头 ~40°
const yawNorm   = new AdaptiveAngleNormalizer(45, 45, 15);
const rollNorm  = new AdaptiveAngleNormalizer(30, 30, 10);

const p = pitchNorm.normalize(pose[0]);
const y = yawNorm.normalize(pose[1]);
const r = rollNorm.normalize(pose[2]);

setParam("ParamAngleX",     -y * 30);
setParam("ParamAngleY",     -p * 30);
setParam("ParamAngleZ",      r * 30);
setParam("ParamBodyAngleX", -y * 10);
```

> 三个归一化器在手机端 `Reset Baseline` / 收到 `calib active:true` 时 **reset()**，
> 客户端也应同步 reset，否则"换人/重新落座"后归一化窗口会残留旧幅度。

### 8.3 客户端本地姿态偏移（可选，推荐实现）

协议**不传**这个值：它是电脑端自己的显示校准，只影响本机渲染。手机摄像头没正对用户时
（斜着架、放在屏幕旁边、换了座位），`pose` 里会带一个固定的 pitch/yaw/roll 偏移，模型会
一直朝一个方向歪；手机端基线只能把脸「归零」，管不到这种固定角度。

推荐实现（Windows 客户端已实现，见 `windows-client/renderer/calibrate.js`）：

1. **一键校准**：取最近约 3 s 的 `pose`（只收 `face !== false` 且三轴有限的帧），做 10%
   截尾平均，得到 `mean`；新的偏移 `offset = -mean`，限幅 ±45°。
2. **手动微调**：提供三轴滑块（±45°，0.5° 步进），用户拖到模型正对镜头为止。
3. 应用顺序：`poseCalibrated = pose + offset`，**之后**再进 §8.2 的自适应归一化；
   `offset` 变化时同步 reset 归一化器，让量程按新零点重新学习。
4. `offset` 只存在客户端本地（如 `localStorage`），重开客户端仍生效。

> ⚠️ 手机端重做基线（`Reset Baseline` / `command:recalibrate`）会重新定义 `pose` 的零点。
> 客户端收到 `calib active:false` 后应把本地 `offset` 清零，否则会「双重校准」、模型朝反方向偏；
> 需要更细的对齐时，用户再点一次一键校准即可。

### 8.4 与模型联动的要求

- **必须关闭**模型自带的自动眨眼（`EyeBlink` 组）和自动呼吸对 `ParamAngleX/Y/Z` 的覆盖，
  否则会和推流数据打架（呼吸只作用于身体/手臂参数时保留无妨）。
- 眼睛开合由 `bs[9]/bs[10]` 驱动，**不要**再叠加 Cubism 的 `CubismEyeBlink`。
- 物理演算（`*.physics3.json`）保留，头发/衣摆动起来才有"活人感"。
- 可选：`ParamBodyAngleZ`、`ParamBodyAngleY` 由 roll/pitch 派生少量值让身体跟着动。

### 8.5 上半身 → Live2D 参数参考映射

`body` / `ub` 是**可选**输入：模型没有对应参数时直接跳过（`getParameterIndex` 返回 -1）。
MediaPipe Pose 只有约 15 fps，所有派生值建议再做 0.15~0.25 s 的一阶低通后再写给模型。

**躯干**（`body = [roll, yaw]`，与 §8.2 的头姿派生值**相加后 clamp**，不要互相覆盖）：

```js
const poseBodyX = -y * 10;                                       // §8.2 已给的头姿派生值
ParamBodyAngleX = clamp(poseBodyX + (-body[1] * 0.3), -30, 30);  // 相加，不覆盖
ParamBodyAngleY = clamp(-body[0] * 0.2, -30, 30);                // 幅度很小，仅为接口完整
ParamBodyAngleZ = clamp(body[0] * 0.5, -30, 30);
```

**手臂**（画面坐标 y 向下；「自然下垂」= 0°，水平 = 90°，举过头 = 180°）：

```js
// ub 取点：下标顺序见 §5.3
const p = (i) => ({ x: ub[i * 4], y: ub[i * 4 + 1], z: ub[i * 4 + 2], v: ub[i * 4 + 3] });
const P_LEFT_SHOULDER = 5, P_RIGHT_SHOULDER = 6,
      P_LEFT_ELBOW = 7, P_RIGHT_ELBOW = 8, P_LEFT_WRIST = 9, P_RIGHT_WRIST = 10;

// 肩 → 腕向量相对「竖直向下」的夹角（度）：0 = 自然下垂、90 = 平举、180 = 举过头顶。
// 画面 y 向下，所以竖直向下 = (0, +1)；左右对称取 |dx|，镜像只影响 x 正负、不影响该角。
function armAngleFromDown(shoulder, wrist) {
  const dx = wrist.x - shoulder.x;
  const dy = wrist.y - shoulder.y;
  return Math.atan2(Math.abs(dx), dy) * 180 / Math.PI;
}

const angleL = armAngleFromDown(p(P_LEFT_SHOULDER), p(P_LEFT_WRIST));
const angleR = armAngleFromDown(p(P_RIGHT_SHOULDER), p(P_RIGHT_WRIST));

// 参考实现：满量程 150°，归一后乘模型手臂参数量程（常见 30）
const ARM_ANGLE_MAX = 150, ARM_SCALE = 30;
setParam("ParamArmLA", clamp(angleL / ARM_ANGLE_MAX, 0, 1) * ARM_SCALE);
setParam("ParamArmRA", clamp(angleR / ARM_ANGLE_MAX, 0, 1) * ARM_SCALE);
```

- 手肘被躯干挡住时用肩 → 腕更稳；需要更精细时可用
  `armAngleFromDown(shoulder, elbow)` + `armAngleFromDown(elbow, wrist)` 两段近似
  （协议没有手心朝向，不要过度解读）。
- `x` / `y` 分别按画面宽 / 高归一化，协议不带分辨率，参考实现没有做长宽比矫正；
  手臂主要是上下运动，误差可接受。要精确角度只能由手机端把像素纵横比信息一起下发（v1 不做）。
- 参考实现（`windows-client/renderer/mapping.js`）只驱动 `ParamBodyAngleX/Y/Z` 与
  `ParamArmLA/RA`；`ParamShoulderL/R`、`ParamHandL/R` 没有可靠数据源，模型真有参数时再自行接。
- 参与计算的肩 / 肘 / 腕 `visibility` 任一低于 **0.3** 时，本帧**不更新**该侧手臂参数
  （参考实现 `BODY_ARM_MIN_VISIBILITY`）。
- `ubOk=false` 或连续 1.5 s 没有 `ubOk=true`：目标值按帧向 0 衰减（每帧约 15%，
  0.3~0.8 s 回到中位，同 §7.6 的「回中性」），不要突然跳到默认姿态。
- `z` 差可以做「手前后」：`wrist.z - shoulder.z < 0` 表示手更靠近镜头；但 MediaPipe 的 `z`
  抖动较大，建议只在调试面板展示，不要直接映射成角度。

---

## 9. 性能预算

| 指标 | 目标 |
|------|------|
| 手机端每帧序列化 | < 0.3 ms（手写 StringBuilder，不用反射/JSON 库） |
| 单帧报文（未开启上半身） | < 1 KB |
| 单帧报文（开启上半身） | ≈ 1.1 KB（13 点 × 4 值 + `ubOk`/`ubT`/`body`）；60 fps ≈ 66 KB/s |
| 端到端延迟（相机曝光→PC 屏幕） | < 80 ms（同 Wi-Fi 5/6）：**不含**手机端 0.5 s 发送帧缓冲 |
| 端到端延迟（含默认 0.5 s 帧缓冲，实测口径） | ≈ 0.58 s（要更低就把 `FRAME_BUFFER_MS` 设为 0） |
| PC 渲染 | 60 FPS @ 1080p，GPU 占用 < 15% |

手机端**不做**任何图像编码；PC 端**不做**任何解码。这是相比旧版"手机渲染 + 推 WebP 帧"
方案最关键的收益。

---

## 10. 兼容与演进

- 每条消息都可能新增字段；**加减字段不需要升 version**，"语义不兼容"才升。
- `hello.version` 不匹配时：客户端提示"协议版本不一致"，但**仍可尝试只读 `track`**。
- v0.x 的 WebP / MJPEG / OBS 浏览器源接口已删除；如仍需 OBS 叠加，请用 Windows 客户端的
  「透明置顶窗口」把形象叠在画面最上层。
