# VFC Windows 客户端（Live2D 虚拟形象渲染）

把手机端（Android，WebSocket **服务端**）推来的 52 维 ARKit blend shape + 头部姿态，
实时映射到 Live2D Cubism 4 模型上。纯前端渲染，Electron 只是可选外壳。

```
手机 (ws://<手机IP>:8765/ws, 30~60Hz JSON)
        │  hello / track / calib / pong
        ▼
Windows 客户端 ── renderer/mapping.js  (协议 §8：blend shape + 姿态 → Cubism 参数)
                 renderer/live2d.js   (pixi.js + pixi-live2d-display + Cubism Core)
                 renderer/app.js      (WebSocket / 状态 / UI / 窗口开关)
```

协议与参数映射的唯一事实来源：`docs/tracking-protocol.md`（§4 消息、§5 姿态、§8 映射）。

## 目录结构

```
windows-client/
├─ package.json               # npm 脚本；type: module
├─ server.js                  # 零依赖静态服务（默认 http://127.0.0.1:17890）
├─ electron/
│  ├─ main.js                 # BrowserWindow：透明/无边框/置顶/鼠标穿透 + 位置持久化
│  └─ preload.cjs             # contextBridge 暴露 window.vfcNative
├─ renderer/
│  ├─ index.html              # 画布 + 控制面板
│  ├─ app.js                  # TrackerClient（WS/心跳/重连/丢旧帧）+ UI + 测试钩子
│  ├─ live2d.js               # Live2DStage：加载模型、逐帧写参数、渲染
│  ├─ interp.js               # 低输入帧率插值：输入帧时间轴（纯函数，可被 Node 直接 import）
│  ├─ calibrate.js            # 本地姿态校准：offset 叠加 / 一键采样 / 持久化（纯函数，可单测）
│  ├─ mapping.js              # 纯函数映射层：面部 / 头部 / 上半身（可被 Node 直接 import）
│  ├─ style.css
│  └─ vendor/                 # 已提交的浏览器构建：cubism core / pixi / pixi-live2d-display(cubism4)
├─ models/                    # ATRI / sensei / Yuuka / zzzz（已就绪）
└─ tools/
   ├─ start-electron.js       # npm start 入口
   ├─ web-launch.js           # npm run web：不用 Electron，用 Edge/Chrome --app 开窗
   ├─ mock-tracker.js         # 模拟手机端（零依赖 WS 服务端 + 60Hz 合成数据）
   ├─ mock-body.js            # mock 的上半身合成：13 关键点 + 躯干角（纯函数，可单测）
   ├─ vendor-assets.js        # 从 node_modules 复制浏览器构建到 renderer/vendor
   ├─ selftest.js             # 映射（面部/头部/上半身）+ 低/抖动输入插值 + 模型发现 + 静态路由的自检
   ├─ package-windows.js      # npm run package：组装免安装便携版（Electron 运行时 + 代码 + 模型）
   ├─ shot.js                 # 可选：系统 Chrome headless 截图（原始 CDP，不装 puppeteer）
   └─ evals/                  # 截图时注入的状态脚本（自测用）
```

## 安装

需要 Node.js >= 20。

```powershell
cd windows-client
npm i
```

- 默认 registry 走 `npmmirror`（见 `.npmrc`）。
- Electron 是 **optionalDependency**：二进制下载失败不会让 `npm i` 失败，只是 `npm start` 不可用。
  想装 Electron 二进制：

  ```powershell
  $env:ELECTRON_MIRROR = "https://npmmirror.com/mirrors/electron/"
  node node_modules/electron/install.js
  ```

- `postinstall` 会把 pixi.js / pixi-live2d-display(cubism4) 复制到 `renderer/vendor/`。
  这两个包已 pin 死版本（`pixi.js@7.4.3`、`pixi-live2d-display@0.4.0`），重复安装字节一致。
  **vendor 里的三个文件已提交**，所以完全不 `npm i` 也能跑 `npm run serve` + 浏览器。

## 启动方式

| 命令 | 说明 |
| --- | --- |
| `npm start` | Electron 桌面窗口（透明/无边框/置顶/鼠标穿透都可用） |
| `npm run web` | 不用 Electron：起本地服务 + Edge/Chrome `--app` 窗口（找不到浏览器则只起服务） |
| `npm run serve` | 只起静态服务：<http://127.0.0.1:17890/>，用任意浏览器打开 |
| `npm run mock` | 模拟手机端：`ws://127.0.0.1:8765/ws`，60Hz 推合成数据（`--hz` / `--jitter` 可调；默认带上半身关键点，`--no-upper-body` 关闭） |
| `npm run selftest` | 一条命令的自检（映射数值 / 模型发现 / 静态路由） |
| `npm run package` | 组装免安装便携版：`dist/windows/VFC-Live2D-win32-x64/`（见下文「打包」） |
| `npm run shot -- --url ... --out ...` | 可选，自测截图；用**系统 Chrome**，不需要 puppeteer |
| `npm run vendor` | 手动重新复制 vendor 构建 |

端口：静态服务 **17890**（`--port` 可改），协议 WS 端口 **8765**（手机端固定）。
`npm start -- --smoke` 是开发自检：启动窗口 → 等渲染就绪 → 写结果文件后退出，
需要**可交互桌面**（无桌面的 CI 里 Chromium 起不来，会直接退出，属环境限制）。

## 连接手机

1. 手机和 PC 在同一局域网；手机 App 启动后显示 `ws://<手机IP>:8765/`。
2. Windows 客户端启动后，在面板「手机地址」里填 `ws://<手机IP>:8765/ws`，点「连接」。
   默认值是 `ws://127.0.0.1:8765/ws`（配合 `npm run mock` 本机联调）。
3. 也可以用 URL 参数直接指定：

   ```
   http://127.0.0.1:17890/?ws=ws://192.168.1.23:8765/ws&model=ATRI
   ```

   支持 `?ws=`、`?model=`（大小写不敏感，默认 ATRI）、`?transparent=1`（纯浏览器下模拟透明底）、
   `?fps=<n>`（渲染目标帧率，默认 60，`0` = 不限制）、`?skeleton=1`（直接显示上半身调试骨架）。

状态条会显示 已连接 / 重连中 / 未连接、设备名、输入 fps、渲染 fps、RTT、seq、丢旧帧数；
连续 0.8s 收不到人脸（`face:false`）会显示「未检测到人脸」。

连接行为：5s 心跳（ping/pong），按 `seq` 丢旧帧，断线按 **0.5s → 1s → 2s → 5s** 退避重连；
重连/收到 `hello` 时重置 seq 跟踪与姿态归一化窗口（手机 App 重启后 seq 从 0 开始也能继续）。

## 校准：手机摄像头没正对你

手机斜着架在屏幕旁边、换座位后没重新摆手机时，头部姿态会带一个**固定的** pitch/yaw/roll
偏移，模型就会一直朝一个方向歪——这不是手机端基线没做，而是手机看不到你的正脸。
电脑面板上有两级校准（只影响本机，不改协议、不改手机）：

- **一键校准**（面板顶部的蓝色按钮）：点一下，保持自然坐姿看屏幕 3 秒，客户端把这段时间
  收到的 pose 取「截尾平均」再取反，记为新的中性零点。偶尔一帧抽风不会把零点带歪；
  有效人脸少于 12 帧会提示校准失败。采样期间按钮变成「取消校准」。
- **手动微调**：展开「手动微调姿态」，拖 左右 / 上下 / 歪头 三个滑块（±45°，0.5° 步进），
  直到模型正对镜头；松手自动保存，滑块右侧的数字就是当前偏移。点「清零」回到手机端基线。

偏移存在 `localStorage["vfc.poseOffset"]`，重开客户端仍然生效。应用顺序是
`pose + offset` **之后**再进自适应归一化，所以校准后自适应量程也按新零点重新学习。

> 手机端「重置基线」会重新定义 pose 的零点，本地偏移再叠上去会「双重校准」。所以点
> 「手机重做基线」（旧版「重新校准」按钮）后，本机偏移会自动清零；需要更细的对齐再点一次「一键校准」。

调试面板（`D`）里能看到「偏移 p/y/r」和「校准后 pose」；控制台可用
`__vfc.poseOffset`、`__vfc.setPoseOffset({ pitch, yaw, roll })`、`await __vfc.align(3)`。

## 帧率

- **渲染目标帧率固定 60 fps**（协议 §9）：`Live2DStage` 建好 PIXI Application 后设
  `app.ticker.maxFPS = 60`，高刷屏（120 / 144 / 165 Hz）不再按屏幕刷新率空转，
  GPU 占用与笔记本发热都跟着降。状态栏的「渲染 fps」是最近 0.5 s 的**实测均值**
  （限帧后 `ticker.FPS` 这个瞬时值会剧烈跳动，已不再使用）。
- **URL 参数 `?fps=<n>`** 可改目标帧率：`?fps=30` 省电、`?fps=0` 不限制（交给 vsync）。
  范围 10~240，非法值回落 60。
- **输入帧率低于渲染帧率时自动插值**：手机推 30 fps、PC 画 60 fps 时，一次输入要撑两帧渲染。
  旧做法是「收到就设目标 + 每帧按固定系数靠近」，两帧里先冲 60%、再拖尾，看起来一卡一卡。
  现在 `renderer/interp.js` 维护一条**播放时间轴**，渲染时取「当前时刻 − 自适应延迟」处的线性
  插值：输入跟得上渲染时延迟为 0（直接显示最新帧），断流最多外推 40 ms。
  代价是输入明显慢于渲染时多一个输入帧的延迟（30 fps → 约 33 ms），换来均匀的 60 fps 运动。
- **输入间隔不稳定时也能匀速播放**：延迟不是固定值，而是按「最近的输入节奏」自适应——
  间隔 p85 + 传输滞后 p90 + 8 ms 余量（上限 120 ms）；需要更多缓冲时快升（120 ms 时间常数），
  缓冲够了再慢降（600 ms），并且升降都限速，保证播放时间永远往前走、不会倒带。
  单次 100～200 ms 的抽风（GC / 推理卡顿 / Wi-Fi 排队）只影响那一两帧，不会把延迟永久抬高。
- **时间轴优先用手机报文的 `t`**（采集时间，协议 §4.2）：WebSocket/TCP 是有序的，但两帧可能被
  PC 调度挤在一起到达，用采集时间才能按真实间隔播放。手机与本机时钟不同步的部分，用「最近
  12 帧的 到达−采集 最小值」估计、每帧最多跟随 1 ms；偏移一次跳变超过 120 ms（帧缓冲重预缓冲 /
  网络路径切换）则重锚，绝不让时间轴倒退。没有 `t` 的老报文退化为按到达时间排队。
- **复现抖动**：`node tools/mock-tracker.js --hz 20 --jitter 25` 给每帧加 0～25 ms 随机发送延迟
  （时间戳仍按真实生产节奏走），用来对比「带时间戳 / 不带时间戳」的播放效果。
- **缓动与帧率无关**：`smoothing`（默认 0.6）按 60 fps 每帧标定，实际用
  `alpha = 1 - exp(-dt/τ)` 换算，改目标帧率、显示器抖动都不会改变手感；
  插值开启时只保留 10 ms 的轻量平滑来压输入噪声。
- 调试面板（`D`）里新增「目标帧率 / 实测渲染 / 输入间隔 / 插值延迟 / 输入抖动」，
  控制台也可以通过 `__vfcDebug.interp` / `__vfc.interp` 直接读
  （`delayMs`、`inputIntervalMs`、`jitterMs`、`transitJitterMs`、`starvedEpisodes`）。

## 模型

把 Cubism 4 模型目录放到：

```
models/<名字>/<xxx>.model3.json     # 必须包含 moc3 / textures / physics3.json 等
```

服务启动时扫描 `models/*/*.model3.json`，面板下拉框/URL `?model=<目录名>` 均可切换。
`GET /api/models` 返回发现结果。当前自带 4 个：ATRI / sensei / Yuuka / zzzz。

映射的 10 个头部参数（协议 §8）：

| 参数 | 来源 |
| --- | --- |
| ParamEyeLOpen / ParamEyeROpen | `1 - bs[9]` / `1 - bs[10]`（eyeBlinkLeft/Right） |
| ParamMouthOpenY | `bs[25]`（jawOpen） |
| ParamMouthForm | `(bs[44]+bs[45])/2 - (bs[30]+bs[31])/2`，clamp ±1 |
| ParamEyeBallX / ParamEyeBallY | `((outL-inL)+(inR-outR))/2` / `((upL-downL)+(upR-downR))/2` |
| ParamAngleX / Y / Z | 自适应归一的 yaw/pitch/roll：`-yaw*30` / `-pitch*30` / `roll*30` |
| ParamBodyAngleX | `-yaw*10` |

头部姿态用**自适应归一化**（协议 §8.2）：按当前会话观察到的最大幅度动态缩放，
首帧不会打满。模型缺少的参数不会报错（`__vfcDebug.missingParams` 可查）。
上半身（躯干 + 手臂）的 5 个可选参数见下一节，同样「模型没有就跳过」。
注意 Cubism 会按模型自身的参数范围 clamp（例如 ATRI 的 `ParamMouthForm` 范围是 `[-1,0]`），
所以「下发的目标值」与「引擎最终值」可能不同——调试面板里两个都显示。

## 上半身关键点（躯干 / 手臂，协议 §4.2 / §5.3）

手机端打开「上半身关键点」开关后，`track` 会多带 4 个**可选**字段：

- `ubOk`：这一帧有没有上半身结果；
- `ub`：13 个关键点 × `(x, y, z, visibility)`，固定 52 个数，镜像归一化坐标
  （顺序：鼻、左/右眼、左/右耳、左/右肩、左/右肘、左/右腕、左/右髋）；
- `ubT`：这批关键点的采集时间戳（手机时钟）；
- `body: [roll, yaw]`：躯干相对相机的旋转角（度）。

老手机 / 关掉开关时这几个字段**完全不存在**，客户端行为与以前一模一样（不会凭空造手臂姿势）。

PC 端自动消费，不需要任何开关：

| Cubism 参数 | 来源 |
| --- | --- |
| ParamBodyAngleZ | `clamp(roll * 0.5, ±30)`（躯干左右侧倾） |
| ParamBodyAngleY | `clamp(-roll * 0.2, ±30)`（侧倾时轻微扭转） |
| ParamBodyAngleX | `clamp(-yaw * 0.3, ±30)`，**与头部的 `-yaw*10` 相加后统一 clamp**，不覆盖头部 |
| ParamArmLA / ParamArmRA | `clamp(肩→腕与竖直向下的夹角 / 150°, 0, 1) * 30`（左/右独立） |

- **丢失时不跳变**：`ubOk=false`、字段缺失，或 `ubT` 超过 **1.5 s** 没有新值 → 参数按帧向 0
  衰减（每帧 ×0.85，约 0.3 s 回到模型默认），然后把控制权交还给 idle motion / breath。
- **15 fps 也不会一跳一跳**：Pose 只有 ~15 fps，上半身参数在写进模型前还会按 **0.16 s 一阶低通**
  再平滑一次（协议 §8.5 的建议值）；截图 / 单步调试模式（`renderNow`）直接落位，保证确定性。
- **该侧看不见就不动**：肩 / 肘 / 腕三个点的 visibility 最小值 < 0.3 时，那一侧的手臂参数
  直接不下发（保持自然状态），避免抬手时另一只手抽风。
- **模型降级（重要）**：自带 4 个模型（ATRI / sensei / Yuuka / zzzz）**只有**
  `ParamBodyAngleX/Y/Z` + `ParamBreath`，**没有** `ParamArmLA/RA`（也没有
  `ParamShoulderL/R`、`ParamHandL/R`）。所以现在能看到**躯干跟着侧倾 / 转身**，
  但**手臂不会动**——这是模型缺参数，不是数据没到。`Live2DStage` 用
  `coreModel.getParameterIndex(id) >= 0` 判断，模型没有的参数一律跳过（不算错误），
  `__vfcDebug.body.support` 能看每个 id 的支持情况。想验证手臂链路：换一个带
  `ParamArmLA/RA` 的模型（Cubism 4 标准参数的模型，例如 Live2D 官方 Haru / Hiyori），
  或临时给模型加这两个参数。
- **调试骨架**：按 `D` 打开调试面板（或 URL 加 `?skeleton=1`），画布右下角出现 13 点 /
  15 条边（与 `CommonData.UpperBodySkeleton.BONES` 一致）的骨架，直接画在归一化坐标上；
  低可见度点灰显，`ubOk=false` 时整具骨架灰显并显示「无上半身数据」或「过期」。
  调试面板新增「上半身 / ub age / torso r/y / arm L/R / 手臂参数 / BodyAngleX 头+身」。
- **不开手机也能验证**：`npm run mock` 默认就带上半身数据（双手交替抬起，并且每 9 s 有
  0.7 s 的「丢失窗口」，用来观察平滑回默认）；`--no-upper-body`（或 `MOCK_UPPER_BODY=0`）
  回到旧行为，用于回归「没有任何上半身字段时参数完全不变」。
  控制台也可以手动喂数据：`__vfc.applyUpperBody({ ub: [...52 个数], body: [roll, yaw], ubOk: true })`，
  或 `__vfc.applyUpperBody({ ubOk: false })` 验证平滑回默认。

## 四个窗口开关

面板上的开关只在 Electron 下生效（纯浏览器会提示改用 `?transparent=1`）：

- **透明**：`transparent: true` + 页面背景全透明（canvas 带 alpha），适合 OBS 叠加。
- **无边框**：`frame: false`，页面顶部有可拖动区域。
- **置顶**：`alwaysOnTop`，叠在其它窗口上。
- **鼠标穿透**：`setIgnoreMouseEvents(true, { forward: true })`，点击穿过窗口直达下层。

鼠标穿透后页面点不到面板，用全局快捷键 **Ctrl+Alt+T** 再按一次取消穿透（逃生键）。
窗口位置/尺寸和开关状态持久化在 `%APPDATA%\vfc-windows-client\window-state.json`。

## OBS 用法

1. **窗口捕获**：OBS 添加「窗口捕获」选 `VFC · Live2D 虚拟形象`。
2. 想要透明底：客户端打开「透明」，OBS 窗口捕获即得到带 alpha 的画面；
   若边缘有黑底，把「无边框」也打开再重新添加捕获源。
3. **游戏捕获** 也可用（窗口需置顶）；摄像头/背景素材放 OBS 里叠加。

## 常见问题

- **Electron 下载失败 / `npm start` 提示找不到可执行文件**：按上面 `ELECTRON_MIRROR` 重装；
  或者根本不装 Electron，用 `npm run web`。
- **端口 17890 被占用**：`node server.js --port 17891`，或 `npm run web -- --port 17891`；
  已有服务在跑时 `npm start` / `npm run web` 会自动复用。
- **连不上手机**：先在 PC 浏览器打开 `http://<手机IP>:8765/status`，应返回 JSON；
  检查同一网段、Windows 防火墙是否放行 8765、地址是否带 `/ws`。
- **画面不动但显示已连接**：先看 seq 是否在涨；手机 App 重启后 seq 归零，本客户端已自动处理。
- **模型加载失败**：看浏览器控制台（F12）或面板错误框，常见是 404（路径不对）、moc3 版本过低、
  或 `model3.json` 里引用的贴图/物理文件缺失。
- **模型头一直朝一边歪、「重新校准」也归不正**：手机没正对你，pose 里有固定偏移。点面板的
  「一键校准」保持坐姿 3 秒；还差一点就展开「手动微调姿态」拖滑块，直到模型正对镜头。
- **眼睛不闭 / 某个参数没反应**：模型没有该参数。展开「调试参数」看 `missingParams`，
  或换 ATRI / zzzz 等参数齐全的模型。
- **身体会侧倾但手臂不动**：自带 4 个模型都没有 `ParamArmLA/RA`，属预期降级（见上文
  「上半身关键点」）；`__vfcDebug.body.support.ParamArmLA === false` 即为此情况。
- **上半身数据过期 / 骨架灰了**：`__vfcDebug.body.ageMs` 超过 1500 ms。看手机端「上半身
  关键点」开关是否开着、`hello.upperBody` 是否为 true（手机端上半身推理掉帧时 age 会持续涨）。
- **透明窗口有黑底**：确认用 `npm start`（纯浏览器拿不到真实窗口透明），且页面已切到透明模式。

## 自测

```powershell
npm run selftest
```

覆盖（当前 70 项）：协议 §8.1 的 eye/mouth/mouthForm/eyeBall 公式与 clamp、§8.2 自适应归一化
首帧与收敛、本地姿态校准（offset 叠加 / 取反 / 截尾平均 / 持久化编解码 / 坏数据兜底）、
reset 可复现性、apply(bs,pose) 与 apply(track) 等价、上半身（§4.2 / §5.3：13 点解析与长度校验、
躯干 Z/X/Y 公式与 clamp、肩→腕抬臂角度、低可见度侧参数不输出、1.5 s 过期判定、
丢失后 15%/帧回 0、头+身 `ParamBodyAngleX` 相加不覆盖、模型缺参数跳过、mock 合成数据合法性）、
低输入帧率插值（30/15 fps 输入 + 60 fps 渲染时每帧步进均匀、延迟收敛、断流外推有上限）、
4 个模型发现、静态服务路由（含路径穿越拒绝）。

端到端渲染自测（可选，需要系统 Chrome）：

```powershell
npm run serve                      # 终端 A
npm run mock                       # 终端 B
node tools/shot.js --url "http://127.0.0.1:17890/?ws=ws://127.0.0.1:8765/ws&model=ATRI" `
  --out shots/live.png --wait 1500 --eval-file tools/evals/closed.js
```

`shot.js` 用原始 CDP 驱动 headless Chrome，不依赖 puppeteer；`--eval-file` 可注入
`__vfc.freeze(true)` + `__vfc.applyTrack({bs,pose})` 得到确定性状态，再用
`__vfc.pixelStats()`（canvas 缩略图 hash / 非透明像素占比）证明画面确实变了。

## 打包（便携版）

```powershell
npm run package        # 需要 npm i 装好的 Electron 运行时（optionalDependency）
```

产物是**免安装目录**（解压即用，无注册表 / 无安装程序）：

```
dist/windows/VFC-Live2D-win32-x64/
├─ VFC-Live2D.exe            # Electron 运行时（electron.exe 改名）
├─ resources/app/            # package.json / server.js / electron / renderer / models / tools
├─ 启动说明.txt
└─ locales / *.dll / *.pak   # Electron 自带
```

- 没装 Electron 时先 `npm i`（国内可设 `$env:ELECTRON_MIRROR='https://npmmirror.com/mirrors/electron/'`），
  或用 `--electron-dir <任意 Electron 发行版目录>`；也可 `--out` / `--name` 改输出位置与目录名。
- 压缩：`Compress-Archive -Path dist\windows\VFC-Live2D-win32-x64 -DestinationPath dist\VFC-Live2D-win32-x64.zip`
- 自检：`VFC-Live2D.exe --smoke` 启动后等渲染就绪，结果写 `resources/app/electron/smoke-result.json`
  （退出码 0 = 就绪）。CI / 多开时用 `VFC_USER_DATA_DIR=<临时目录>` 隔离配置，不写 `%APPDATA%`。
- **CI**：[`windows-client-ci.yml`](../.github/workflows/windows-client-ci.yml) 在改动
  `windows-client/**` 时自动打包 + 跑 selftest + 启动自检；Artifact 名
  `VFC-Live2D-win32-x64-<sha>`，推送 `v*` tag 时附到 GitHub Release。

## 与任务书的两处差异

- `electron/preload.cjs` 而不是 `preload.js`：`package.json` 是 `type: module`（为了 Node 能直接
  `import` 渲染层的纯函数模块），Electron 的 ESM preload 需要另加 `.mjs` 且限制沙箱；
  用 `.cjs` 让 preload 保持 CommonJS 最简单，路径在 `electron/main.js` 里显式指定。
- `pixi-live2d-display` 用 `dist/cubism4.min.js`（Cubism4-only）而不是 `dist/index.min.js`：
  后者是 Cubism2 + Cubism4 全量包，加载时强制要求 Cubism 2 的 `window.Live2D`，会直接抛错。

## 已知限制

- 这是实时驱动客户端，不含摄像头/人脸检测（在手机端做）。
- 物理（physics3）和 idle motion 保持开启；参数写入点在 `beforeMotionUpdate` 之后、`coreModel.update()` 之前，
  保证不被眨眼/焦点/呼吸覆盖。
- Electron 的 `--smoke` 自检需要可交互桌面；在无桌面的自动化环境里 Chromium 会启动失败，
  这不影响 `npm run web` / `npm run serve`（它们只用普通浏览器）。
