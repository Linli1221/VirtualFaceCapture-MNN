# VFC 端到端验收报告（verifier）

> 范围：Android 仅做表情采集 → Windows Electron + Cubism Web Live2D 渲染。
> 本报告验证 **协议 v1 假手机 → Windows 静态服务 → 渲染页（真实 Live2D/Cubism 引擎）→ §8 参数映射** 的完整链路，
> 以及模型切换、断线重连、透明底三项附加验收。
>
> 结论：**PASS**。自动化断言 73/73 全绿（2 条 advisory 为环境信息），映射契约测试 32/32 全绿；
> 四轮迭代中发现并闭环了 2 个客户端问题（1 个阻断、1 个透明底缺陷），均复跑确认修复。

---

## 1. 环境

| 项 | 值 |
| --- | --- |
| OS | Windows（win32），中文环境 |
| Node.js | v24.21.0（npm 11.19.0） |
| 浏览器 | 系统 Chrome **155.0.8059.85**（`C:\Program Files\Google\Chrome\Application\chrome.exe`） |
| 浏览器驱动 | `puppeteer-core@25.16.0`（不下载 Chromium，使用上述系统 Chrome） |
| 其他依赖 | `ws@8.22.0`、`pngjs@7.0.0`（无 Playwright、无 Chromium 下载） |
| 被测静态服务 | `windows-client/server.js`（零依赖，默认 `127.0.0.1:17890`） |
| 被测渲染页 | `windows-client/renderer/index.html` + `app.js` + `live2d.js` + `mapping.js` |
| vendor 资产 | `pixi-live2d-display.js` = 119922 bytes（Cubism4-only 构建，不含 Cubism2 runtime 抛错）、`pixi.min.js` 456150 bytes、`live2dcubismcore.min.js` 150388 bytes |
| 被测模型 | ATRI / sensei / Yuuka / zzzz（`/api/models` 返回 4 个） |
| 运行模式 | headless Chrome 软件 WebGL（SwiftShader），页面与 Electron 使用同一套 renderer 文件 |

说明：Electron 主进程窗口创建（`transparent: true` 的 BrowserWindow）未由本 harness 启动；渲染层通过
`?transparent=1` 走与 Electron 相同的透明模式代码路径，验证 html/body/#stage/canvas 的 alpha 链路。

## 2. 交付物（`tools/verify/`）

| 文件 | 作用 |
| --- | --- |
| `mock-tracker.js` | 协议 v1 假手机（`:8765`），60 Hz 合成数据；支持 `--frames N` / `--once` / `--scenario hold-blink` / `--exit-after` 等 |
| `run-verify.mjs` | 全自动端到端验收：假手机 → 静态服务 → headless Chrome → 三态截图/像素分析 → 参数注入断言 → 模型切换/重连/透明底 → 工件输出 |
| `mapping-test.mjs` | 直接 import `windows-client/renderer/mapping.js`，校验 `docs/tracking-protocol.md` §8 数值契约（32 项） |
| `package.json` / `package-lock.json` | 固定上述依赖 |
| `REPORT.md` | 本文件 |

## 3. 如何运行

```powershell
cd tools\verify
npm install                 # 已固定 puppeteer-core / ws / pngjs

node mapping-test.mjs       # §8 映射契约测试（32 项）
node run-verify.mjs         # 全链路验收（默认 ws 8765 / http 17890）

# 端口被其他进程占用时可改端口（本次验收即如此，见 §8）
$env:VFC_WS_PORT='18765'; $env:VFC_HTTP_PORT='17891'; node run-verify.mjs
```

退出码：`0` = PASS，`1` = FAIL，`2` = PENDING（缺少被测文件/等待超时）。
工件写入 `build/tmp/verify/`：`result.json`、`params.json`、`mapping-test.json`、`*.png`、`harness.log`、`mock-tracker.log`、`client-server.log`。

单跑假手机 + 静态服务（手工调试）：

```powershell
node tools/verify/mock-tracker.js --port 8765
node windows-client/server.js --port 17890
# 打开 http://127.0.0.1:17890/?ws=ws://127.0.0.1:8765/ws&model=ATRI
```

## 4. 轮次记录（至少两轮：发现 → 修复 → 复跑）

| 轮次 | 命令 | 结果 | 说明 |
| --- | --- | --- | --- |
| R1 | `node run-verify.mjs`（8765/17890） | **FAIL**（9 项失败） | 阻断性 bug：vendor 用了 Cubism2+4 全量 bundle，但只加载了 Cubism4 Core → 页面抛 `Could not find Cubism 2 runtime`，`PIXI.live2d` 未注册，`app.boot()` 提前 return：WS 从未连接、`__vfcDebug.frames=0`、canvas 全黑、像素 diff 全 0 |
| R2 | 修复 vendor 后复跑（18765/17891） | **PASS**（54 checks，0 failed） | 模型真实渲染，三态截图 diff 非零；注入链路全 PASS；但当时尚无模型切换/重连/透明底断言 |
| R3 | 补充三项断言后复跑（18765/17891） | **FAIL**（67 checks，2 failed） | 模型切换、断线重连 PASS；透明底发现 `html` 背景仍为 `rgb(11,14,21)`（只覆盖了 `body.is-transparent`），Electron 透明窗口会露深色底 |
| R4 | windows-client 修复 html 背景后复跑（18765/17891） | **PASS**（73 checks，0 failed，2 advisory） | 全部断言绿；映射测试 32/32 绿 |

两次独立 `mapping-test.mjs` 运行均 32/32 PASS；被测模块：
`windows-client/renderer/mapping.js`，12254 bytes，sha256 `f6abc747182e1c99de78d1b4aa4afeb4a4c92a0c1d4df31d0d3e94a4b1c45d8f`。

## 5. 断言覆盖与关键证据（R4）

总 73 项检查 = 前置检查 + 假手机自检 + 静态服务 + 页面/WebGL + 像素 + debug 参数 + 真实渲染链路参数注入
+ 模型列表/切换 + 断线重连 + 透明底 + 控制台/网络健康。完整清单见 `build/tmp/verify/result.json`。

### 5.1 协议 v1 假手机自检

- `hello` 的 `protocol=vfc-tracker`、`version=1`；
- 连续 40 帧 `seq` 单调、`bs` 为 `float[52]` 且 `[0,1]`、`pose` 为 `float[3]`、`face` 为 bool、`seq/t` 为 int；
- 实测帧率在 `[45,75]`（drift-corrected 60 Hz 调度，实测 60）；
- `ping → pong`（回显 `echo`）、`command → ack`；
- 确定性场景 `hold-blink --frames 30 --exit-after`：30 帧 `bs[9]=bs[10]=1` 可复现。

### 5.2 静态服务 / 页面 / WebGL

- `GET /` HTTP 200；`/api/models` 返回 ATRI、sensei、Yuuka、zzzz 共 4 个；
- `canvas#live2d` 存在、尺寸非零（1280×800）、WebGL 上下文存活（WebGL 2.0 / SwiftShader）；
- `window.__vfcDebug` 暴露，`version=1`、`ready=true`、`connected=true`、`frames` 持续推进、`error=null`、`missingParams=[]`。

### 5.3 渲染与运动（像素级）

三态：neutral / blink（`bs[9]=bs[10]=1`）/ yaw（`pose[1]=+45`），每次 settle 1200 ms 后截图；
clip 为 canvas 区域 1280×800。

| 指标 | 数值（R4） | 判定 |
| --- | --- | --- |
| canvas 贡献（可见 canvas vs 隐藏 canvas） | 111,015 px / 1,024,000 = **10.84%**，strong 10.61%，平均 ΔRGB 504.95 | ≥ 0.5% → PASS：画面确由 canvas 绘制 |
| neutral 图像内容 | contentRatio 0.687，uniqueColors 423，亮度 std 60.2 | PASS |
| neutral ↔ blink | **18,806 px（1.84%）**变化，平均 ΔRGB 157.8 | ≥ 0.02% → PASS：眨眼真的改变了画面 |
| neutral ↔ yaw | **36,091 px（3.52%）**变化，平均 ΔRGB 145.0 | ≥ 0.5% → PASS：转头真的改变了画面 |
| blink ↔ yaw | **38,341 px（3.74%）**变化 | PASS |

页面内独立采样（`__vfc.pixelStats(192)`，不经过截图管线）：

| 时刻 | canvas hash | nonTransparentRatio | meanAlpha |
| --- | --- | --- | --- |
| neutral | 1973281911 | 0.1105 | 26.87 |
| blink | 57535943 | 0.1098 | 26.77 |
| yaw | 1763996379 | 0.1069 | 26.08 |

三个 hash 全不同 + 三帧都有模型像素 → 与截图 diff 相互印证。

### 5.4 截图

- `build/tmp/verify/neutral.png` / `neutral.canvas.png`
- `build/tmp/verify/blink.png` / `blink.canvas.png`
- `build/tmp/verify/yaw.png` / `yaw.canvas.png`
- `build/tmp/verify/neutral.canvas.hidden.png`（隐藏 canvas 后的同 clip 截图，用于 contribution diff）

![neutral](../../build/tmp/verify/neutral.png)
![blink](../../build/tmp/verify/blink.png)
![yaw](../../build/tmp/verify/yaw.png)

### 5.5 §8 映射契约（mapping-test.mjs，32/32 PASS）

覆盖：模块导出、`BS_INDEX` 52 项与协议 §5.1 表逐项一致、`PARAM_IDS` 10 项、§8.1 直接参数、
§8.2 冷启动/收敛/clamp、`AdaptiveAngleNormalizer` 与 `createMapper().reset()` 可复现性、
300 帧与独立参考实现逐帧对比（maxDiff=0）、`mapBShape`/stateless `mapTrack`/`TrackMapper`/容错。

关键数值（协议 §8.2 参考算法，cold start，samples=0）：

- pitch=+20 → `ParamAngleY = -(20/39.62)*30 = -15.143866733`（**不是**任务文字里的 -30；任务文字是简化说法，已按 Lead 指示以 §8.2 为准）
- pitch=+40 → -30（clamp），pitch=-20 → +30
- yaw=+45 → `ParamAngleX = -30`，yaw=-45 → +30，yaw=-90 → +30（clamp）
- roll=+30 → `ParamAngleZ = +30`
- bodyAngleX = `-yawNormalized * 10`（默认 `bodyScale=10`）
- 收敛场景：150×(+40) 后 pitch=+20 → ≈-15.0（|err|<1e-2），随后 pitch=+40 → -30

### 5.6 真实渲染链路参数注入（`window.__vfc.applyTrack` → mapper → Cubism）

通过 `window.__vfc.applyTrack({bs,pose})` 注入（自动 freeze + `renderNow`），读取 `__vfcDebug.params`：

| 场景 | ParamAngleX | ParamAngleY | ParamAngleZ | ParamBodyAngleX | 其他 |
| --- | --- | --- | --- | --- | --- |
| eyeNeutral（bs[9]=0） | 0 | 0 | 0 | 0 | EyeLOpen=1、EyeROpen=1 |
| eyeBlink（bs[9]=bs[10]=1） | 0 | 0 | 0 | 0 | EyeLOpen=0、EyeROpen=0 |
| mouthOpen（bs[25]=0.5） | 0 | 0 | 0 | 0 | MouthOpenY=0.5 |
| mouthForm（bs[44]=0.5, bs[30]=0.5） | 0 | 0 | 0 | 0 | MouthForm=0.5 |
| eyeBall（bs[13..18] 组合） | 0 | 0 | 0 | 0 | EyeBallX=0.55、EyeBallY=0.5 |
| warm pitch=+40 | 0 | **-30** | 0 | 0 | |
| warm 后 pitch=+20 | 0 | **-15.007504** | 0 | 0 | 收敛后接近 -15.0 |
| warm 后 pitch=+40 | 0 | **-30** | 0 | 0 | |
| warm yaw=+45 | **-30** | 0 | 0 | **-10** | |
| warm 后 yaw=-90 | **+30** | 0 | 0 | **+10** | clamp |
| warm roll=+30 | 0 | 0 | **30** | 0 | |

`freeze(false)` 后 WS 恢复：frames 由 1220 → 1244（1.2 s 后），正常回落到实时流。
完整快照（含 normalizer 中间状态）见 `build/tmp/verify/params.json`。

### 5.7 模型切换（ATRI → sensei）

- `window.__vfc.setModel('sensei')` 可调用；
- 切换后 `__vfcDebug.model='sensei'`、`ready=true`、`error=null`；
- 可见/隐藏 canvas diff：46,256 px / 1,024,000 = **4.52%**（strong 4.39%，平均 ΔRGB 492.2）→ 切换后 canvas 仍有真实画面，**不是白屏**。

![sensei](../../build/tmp/verify/model-sensei.canvas.png)

### 5.8 断线重连

- kill 假手机进程 → 客户端检测到断开：`__vfcDebug.connected=false`，状态栏 `0.5s 后重连（第 1 次）`；
- 重新启动假手机（同端口）→ 1.5 s 内自动恢复：`connected=true`，状态栏 `已连接 · Mock Pixel 8 Pro`，`frames` 继续增长（1473），无需刷新页面。

### 5.9 透明底（Electron 透明窗口的关键）

`?transparent=1` 新开页面，隐藏 UI 面板后取值：

| 对象 | 值 |
| --- | --- |
| `<html>` computed background | `rgba(0, 0, 0, 0)` |
| `<body>` computed background | `rgba(0, 0, 0, 0)` |
| `#stage` computed background | `rgba(0, 0, 0, 0)` |
| `canvas#live2d` computed background | `rgba(0, 0, 0, 0)` |
| PIXI `renderer.backgroundAlpha` | `0` |
| canvas `toDataURL` 四角 alpha | `0 / 0 / 0 / 0` |
| 不透明像素占比（模型本体） | 0.09796（可见 alpha 0.1071） |

证据文件：`build/tmp/verify/transparent.canvas.png`（canvas 原始 alpha 捕获）、`transparent.page.png`（页面合成截图，见下方说明）。

![transparent](../../build/tmp/verify/transparent.canvas.png)

> headless Chrome 155 在软件 WebGL 下，`page.screenshot({omitBackground:true})`、CDP
> `Emulation.setDefaultBackgroundColorOverride`、`element.screenshot` 都会把透明背景压成不透明白色，
> 因此透明断言使用 `canvas.toDataURL()`（Cubism 渲染器 `preserveDrawingBuffer:true`，alpha 保真）
> + computedStyle 两条权威链路；页面级截图仅作辅助证据。这是 headless 截图限制，不是产品 bug。

### 5.10 控制台 / 网络健康

R4：`console.error = 0`、`pageerror = 0`、失败请求 = 0、本地 HTTP ≥400 = 0、warning = 0。

## 6. 发现的问题与处理

### 6.1 [阻断，已修复] vendor 选错：Cubism2+4 全量 bundle

- 现象：页面 `Could not find Cubism 2 runtime. This plugin requires live2d.min.js to be loaded.`（`vendor/pixi-live2d-display.js:1:29263`），`PIXI.live2d` 未注册，`app.boot()` 提前 return：WS 不连接、`frames=0`、canvas 全黑、所有像素 diff 为 0。
- 根因：`windows-client/tools/vendor-assets.js` 复制的是 `pixi-live2d-display/dist/index.min.js`（同时包含 Cubism 2 和 4），而页面只加载 Cubism4 Core。
- 修复（windows-client）：改复制 `dist/cubism4.min.js`，重新生成 vendor（119922 bytes）。复跑 R2 起通过。
- 复现步骤：把 `renderer/vendor/pixi-live2d-display.js` 换回 `dist/index.min.js` → 打开页面 → console 出现上述错误、`__vfcDebug.frames=0`。harness 已加静态前置断言：vendor bundle 不得含 `Could not find Cubism 2 runtime`。

### 6.2 [透明底缺陷，已修复] `html` 背景未置透明

- 现象（R3）：`?transparent=1` 下 `body.is-transparent` 生效、`#stage` 透明、canvas `toDataURL` 四角 alpha=0，但
  `getComputedStyle(document.documentElement).backgroundColor = "rgb(11, 14, 21)"`。
- 影响：Electron `transparent:true` 窗口里模型背后会出现 #0b0e15 深色矩形，透明/叠加失效。
- 根因：`style.css` 的 `html, body { background: var(--bg) }` 只被 `body.is-transparent` 覆盖。
- 修复（windows-client）：新增
  `html.is-transparent, html:has(> body.is-transparent) { background: transparent; }`
  并在 `app.js` 增加 `setTransparentMode()`，同时切换 `<html>` 与 `<body>` 的 `is-transparent`。R4 复跑全绿。
- 最小复现：打开 `http://127.0.0.1:17890/?ws=...&model=ATRI&transparent=1`，
  `getComputedStyle(document.documentElement).backgroundColor` 应返回 `rgba(0, 0, 0, 0)`。

### 6.3 [verifier 脚本问题，非客户端] hold-blink 竞态

确定性自检开始时以 `--frames 30 --exit-after` 启动第二个假手机，原实现立即发起 WS 连接，
偶尔在进程 listen 之前被拒（`ECONNREFUSED`），导致 0 帧、误判。
修复：`connectWsWithRetry()` 每 100 ms 重试、最多 6 s，并等待子进程退出；之后稳定通过。
（这是 harness 的竞态，不是 mock-tracker 或客户端的缺陷。）

### 6.4 [文档简化说明] pitch 冷启动值不是 -30

任务文字里的「pitch=+20 → ParamAngleY ≈ -30」是简化写法；按 `docs/tracking-protocol.md` §8.2 参考算法，
冷启动（无历史样本）时 `effectivePos = 40 * 0.99 + (20*0.1)*0.01 = 39.62`，
故 `ParamAngleY = -(20/39.62)*30 = -15.143866733`。收敛后（≥100 帧）才接近 `-30` 满量程。
`mapping-test.mjs` 与 `run-verify.mjs` 都已按 §8.2 断言，并在两处同时覆盖冷启动与收敛两种情形。

### 6.5 [环境限制，非产品 bug] headless 截图 alpha

见 §5.9 说明：headless Chrome 软件 WebGL 下页面级截图无法保留 alpha；改用 canvas `toDataURL` 验证。

### 6.6 [非 bug] `ParamMouthForm` 引擎侧被模型 clamp

ATRI 的 `ParamMouthForm` 范围是 `[-1, 0]`（默认 -0.5），目标值 0.8 会被 Cubism clamp 到 0。
因此断言使用 `__vfcDebug.params`（下发目标值，未 clamp）；若需读引擎值应校验「在模型 min/max 内且方向正确」。
当前 `__vfcDebug.engine` 已可用于这类断言，harness 的 §8 断言使用目标值 + 像素证据，二者一致。

## 7. 未解决 / 遗留

- 无阻断项。R4 全绿。
- 2 条 advisory 为信息类检查（WebGL renderer 字符串、canvas.toDataURL 非空），不计入失败。
- **端口说明**：验收时标准端口 8765/17890 被 windows-client 自己的自测进程占用
  （`tools/mock-tracker.js --port 8765`、`server.js`），为避免误杀队友进程，
  R2–R4 使用 `VFC_WS_PORT=18765` / `VFC_HTTP_PORT=17891`。端口只影响监听地址，不影响被测代码路径；
  harness 默认端口仍是 8765/17890，空闲时可直接 `node run-verify.mjs`。
- Electron 主进程窗口（`transparent:true` 的真实 BrowserWindow、窗口重建逻辑）未在本 harness 启动；
  已验证其渲染层与透明模式（`?transparent=1`）的完整链路，Electron 壳层需另行冒烟。
- Android 端（CameraX / MediaPipe / 协议发送）不在本任务范围，由 android-capture 负责。

## 8. 失败复现步骤（供回归）

1. **vendor 回归**：`renderer/vendor/pixi-live2d-display.js` 换回 `pixi-live2d-display/dist/index.min.js`（约 126855 bytes）→ 打开渲染页 → console 报 `Could not find Cubism 2 runtime`，`__vfcDebug.frames=0`，canvas 空。
2. **透明底回归**：`?transparent=1` 打开页面 → 在 DevTools 执行
   `getComputedStyle(document.documentElement).backgroundColor`，若为 `rgb(11, 14, 21)` 则未透明。
3. **重连回归**：页面连接中 kill 假手机 → 期望 `__vfcDebug.connected=false` 且状态栏出现重连倒计时；重启假手机 → 期望 3–5 s 内恢复 `connected=true`。
4. **像素回归**：跑 `run-verify.mjs`，检查 `result.json.pixel.diffBlink.changedRatio`、`diffYaw.changedRatio` 是否仍显著大于阈值。

## 9. 工件清单（`build/tmp/verify/`）

| 文件 | 内容 |
| --- | --- |
| `result.json` | 73 项检查、像素统计、debug 快照、重连/透明/模型切换证据、日志 |
| `params.json` | 三态实时流的下发参数 + normalizer 状态 + 注入检查点 + unfreeze |
| `mapping-test.json` | 32 项映射测试结果、模块 sha256、300 帧参考实现对比 |
| `transparent.canvas.png` | canvas 原始 alpha 捕获（四角透明） |
| `transparent.page.png` | 页面合成截图（headless 下 alpha 被压平，仅供参考） |
| `neutral.png` / `blink.png` / `yaw.png` | 三态页面截图 |
| `*.canvas.png` | 三态 + sensei + transparent 的 canvas clip |
| `neutral.canvas.hidden.png` | 隐藏 canvas 后的同 clip 截图（contribution diff 基准） |
| `model-sensei.canvas.png` / `.hidden.png` | 模型切换后的可见/隐藏证据 |
| `harness.log` / `mock-tracker.log` / `client-server.log` | 运行日志 |
