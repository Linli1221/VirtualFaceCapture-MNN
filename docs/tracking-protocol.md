# VFC Tracking Protocol v1

> 手机端只做**面部捕捉**，通过局域网把**每帧追踪数据**（ARKit-52 BlendShape + 头部姿态）发给
> Windows 客户端，由客户端渲染 Live2D 虚拟形象。
>
> 本文是手机端（Android）与 Windows 客户端之间的**唯一契约**。两端实现都必须严格按本文的字段名、
> 索引顺序和符号约定来写；改动协议必须同时在本文与两端代码里升级 `version`。

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
- 只传**语义数据**（表情权重 + 角度），不传图像/视频。一帧约 **0.4~0.7 KB**，
  30 Hz 约 15~20 KB/s，60 Hz 约 30~40 KB/s，手机端零编码开销。

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
| `track` | 每处理完一帧 1 次（≤ 60 Hz） | 该帧的表情权重 + 头部姿态 |
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
| `t` | int | 手机本地 Unix 毫秒时间戳 |

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
  "calibrating": false
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

数值建议保留 **4 位小数**（`0.1235`），够用且省带宽。

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
   按到达时间做时间轴插值（延迟约一个输入间隔，断流时外推有上限），避免 60 fps 画面里出现
   「hold 一下再跳一步」的台阶；输入跟得上渲染时不加这段延迟。
4. **断线重连**：客户端指数退避（0.5 s → 1 s → 2 s → 5 s 封顶），并在 UI 上显示状态。
5. **`seq` 的生命周期 = 手机端一次连续推流**。手机 App 重启、或停止后再点开始，
   `seq` 会从 0 重新计数。因此客户端**每次收到 `hello`（即每建立一条新连接）都必须把
   本地 `lastSeq` 重置为 `-1`**。漏掉这一步的典型症状是：UI 显示"已连接"、帧率计数上涨，
   但画面完全不动 —— 因为重启后的所有帧都被当成过期帧丢掉了。
6. **断线后回中性**：连接断开（含用户手动断开）时，客户端应清空目标参数、让模型回到
   默认姿态（`clearTargetParams()` 等价操作）。否则模型会保持最后一帧的表情与头姿，
   看起来像"鬼脸冻结"。重新连上后由新的 `track` 帧重新驱动。
7. **心跳**：见 §4.4。手机在无面捕时也照常回 `pong`。
8. **时间戳**：两端时钟不同步是正常的，只用 `seq`，不要用 `t` 计算延迟。
   如需延迟估算，用 `ping/pong` 的往返时间。

---

## 8. 参考映射：ARKit-52 → Live2D 参数

以下算法与旧版 Android 渲染器（`Live2DAvatarAdapter` + `LAppModel::ModelParameterUpdate`）
**逐行等价**，Windows 客户端应直接照此实现，保证"换端后手感不变"。

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

### 8.3 与模型联动的要求

- **必须关闭**模型自带的自动眨眼（`EyeBlink` 组）和自动呼吸对 `ParamAngleX/Y/Z` 的覆盖，
  否则会和推流数据打架（呼吸只作用于身体/手臂参数时保留无妨）。
- 眼睛开合由 `bs[9]/bs[10]` 驱动，**不要**再叠加 Cubism 的 `CubismEyeBlink`。
- 物理演算（`*.physics3.json`）保留，头发/衣摆动起来才有"活人感"。
- 可选：`ParamBodyAngleZ`、`ParamBodyAngleY` 由 roll/pitch 派生少量值让身体跟着动。

---

## 9. 性能预算

| 指标 | 目标 |
|------|------|
| 手机端每帧序列化 | < 0.3 ms（手写 StringBuilder，不用反射/JSON 库） |
| 单帧报文 | < 1 KB |
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
