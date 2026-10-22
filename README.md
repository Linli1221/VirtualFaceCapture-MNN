# VirtualFaceCapture-MNN
> ✨ Android 端虚拟角色面捕驱动系统，推理框架基于 [alibaba-MNN](https://github.com/alibaba/MNN).

> 🌐 English README: [README.en.md](./README.en.md)

[![Android CI](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml)
___

## 🧠 简介
基于 **MNN** 推理引擎、在移动端复刻 **MediaPipe Face Landmarker** 面部捕捉方案，
配合 **仿 OpenSeeFace** 风格的后处理流水线，用来实时驱动 **Live2D** 皮套
（测来测去，也就 Google 的模型还比较好用 -.-）。

整套面捕管线已经从 MediaPipe 源码里彻底剥离出来：不依赖 MediaPipe 的图计算
（CalculatorGraph）、不依赖 JavaCV / OpenCV-Android、不依赖任何 MediaPipe Tasks
Java 层，全部由 **MNN + MNN-CV + 本地 C++ 后处理** 实现，姿态估计等部分逻辑用 **ARM NEON**
做了 SIMD 优化以提升加载与计算速度，整体帧率能在骁龙888手机运行逼近**60**帧 🎉。

> 📈 帧率相关的完整优化清单、日志判读方法与上机调优步骤见 **[PERFORMANCE.md](PERFORMANCE.md)**
> （含本轮改动：检测 10Hz 节流、派发单消费者化、MNN 后端 Vulkan⇄CPU 运行时切换、
> 预览 RGB 帧门控、Compose 合批、后处理零分配化等）。

---

## ✨ 运行示例

<p align="center">
  <img src="./sensi.gif" width="45%" />
  <img src="./zzzz.gif" width="45%" />
</p>

---

## 🧠 方案概览

简单一句话概括为：

> **把 MediaPipe Face Landmarker 的 Detection + Landmark 两个模型搬到 MNN 上跑，
> 自己实现整套图前/图后处理，再接一层仿 OpenSeeFace 的滤波与表情解算，
> 最终输出 ARKit-52 BlendShape + 头部姿态去驱动 Live2D。**

与原版 MediaPipe Android 方案的差异：

| 维度 | 官方 MediaPipe Tasks | 本工程 (MNN 版)                                                |
|------|----------------------|------------------------------------------------------------|
| 推理引擎 | TFLite + GPU Delegate | **MNN** (CPU / GPU / NNAPI 后端可选)                           |
| 图调度 | MediaPipe CalculatorGraph | **本地 C++ 流水线**，零图计算依赖                                      |
| 图像处理 | JavaCV / OpenCV / GL | **MNN-CV** + **NEON kernel**                               |
| 模型数量 | Detection + Landmark + Blendshape | 仅 **Detection + Landmark** 两个模型，BlendShape 走单独解算           |
| 后处理 | MediaPipe 内置 | **仿 OpenSeeFace** 平滑 / 标定 / 头姿恢复                           |
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
                             │
                             ▼
              ┌──────────────────────────────────────────────┐
              │  仿 OpenSeeFace 后处理 (C++ / Kotlin)         │
              │   • PnP / SVD 头部姿态恢复 (pitch/yaw/roll)   │
              │   • 眨眼曲线 + 嘴形 + 眉毛                    │
              │   • One-Euro / 滚动均值平滑                  │
              │   • 首帧自动标定 (calibration)               │
              │   • ARKit-52 BlendShape 解算                 │
              └──────────────┬───────────────────────────────┘
                             ▼
              ┌──────────────────────────┐
              │  Live2D Cubism Renderer  │   ARKit → Live2D 参数映射
              └──────────────────────────┘
```

所有矩阵运算 / 仿射变换 / SVD / Jacobi 旋转 / 点云变换都走本地的
`mini_linalg` + `neon_intrinsics`，针对 3×N 关键点这种典型 shape 做了
向量化，加载和首帧推理耗时相比标量实现有明显下降。

---

## 📦 模块划分

工程是一个标准的多 module Gradle 项目：

| Module | 作用                                                     |
|--------|--------------------------------------------------------|
| `:app` | Demo 应用：相机、UI、Live2D 渲染、参数映射                           |
| `:Live2D` | Cubism SDK 包装层 (CMake + JNI)                           |
| `:MediapipeFaceLandmark` | **核心推理模块**：MNN + `.mnn` 模型 + 原生 C++ 流水线 + NEON 优化      |
| `:OpenSeeFaceProcess` | 仿 OpenSeeFace 的后处理：平滑、头姿、BlendShape 解算                 |
| `:CommonData` | 共享数据类型 (`Point3D` / `HeadPose` / `ARKitBlendShapes` …) |
| `:facecapture-sdk` | 对外发布的 AAR 门面，封装上面 3 个模块                                |
| `ThirdParty/MNN/` | 仅提供头文件                                                 |

---

## 🚀 快速开始

### 构建运行

```bash
git clone https://github.com/<your-name>/VirtualFaceCapture-MNN.git
cd VirtualFaceCapture-MNN
git submodule update --init --depth 1 ThirdParty/MNN   # 仅需 MNN 头文件
./gradlew :app:assembleDebug
# 或直接在 Android Studio 中打开并运行 app
```

首次启动需授予相机权限，之后保持人脸在画面内，点击 Start ，即可看到 Live2D 角色随着
表情 / 头部姿态实时联动。

### GitHub Actions 自动构建（每次修改出包）

仓库已配置 [Android CI](.github/workflows/android-ci.yml)，**每次修改都会自动构建安装包**：

- **任意分支 push / 任意 PR**：**debug 与 release 两个变体独立并行构建**，各出一个
  Artifact（保留 30 天），到仓库 `Actions` → 对应运行 → `Artifacts` 下载：
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

> 💡 想省去本地 Android 环境，直接从 Actions 产物下载 APK 安装即可；
> 本地构建则需先执行上面的 `git submodule update` 获取 MNN 头文件。

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

### 作为 SDK 集成

如果只想要面捕能力（不要 Live2D 渲染），请参考
[`facecapture-sdk/README.md`](facecapture-sdk/README.md)，门面 API 形如：

```kotlin
val engine = FaceCaptureEngine.create()
engine.initialize(context)
engine.setResultListener { result ->
    val landmarks   = result.landmarks         // 478 个 3D 关键点
    val blendShapes = result.blendShapes       // ARKit 52 BlendShape
    val pose        = result.relativeHeadPose  // pitch / yaw / roll
}
// 把 CameraX 的 ImageProxy 喂进来
engine.pushFrame(image, rotationDegrees)
```

---

## 🎮 操作指南

进入 Demo 后，屏幕底部会弹出一个可滚动的 **控制面板 (ControlPanel)**，
顶部显示当前加载的 Live2D 模型名。所有按钮 / 滑块的作用如下：

### 按钮区

| 按钮 | 状态切换 | 作用 |
|------|----------|------|
| **Start / Stop** | 红 ↔ 主色 | 启停整条面捕管线（摄像头采集 + 推理 + 驱动），关闭时模型会停在最后一帧的姿态 |
| **Show Debug / Hide Debug** | — | 显隐顶部的调试 HUD：FPS、推理耗时、头部 pitch/yaw/roll、EAR、BlendShape 数值等 |
| **Show Camera / Show Live2D** | 第三色 ↔ 第二色 | 切换主画面：Live2D 渲染视图 ↔ 相机预览 + 关键点叠加，方便排查跟踪问题 |
| **Select Model** | — | 弹出对话框选择 `assets/` 下的 Live2D 模型（默认提供 sensi / ATRI 等） |
| **Reset Baseline** | — | **基线重置**：把当前帧的头姿 / 表情记为「中性脸」，类似 OpenSeeFace 的首帧标定。佩戴眼镜、换光线、换人时建议重新点一下 |
| **Blink Curve: ON / OFF** | 绿 ↔ 第二色 | 是否启用**自动眨眼曲线整形**。开启后会把检测到的眨眼事件整形为更自然的 230 ms 曲线（避免抖动 / 半眨），关闭则直接透传原始 EAR |
| **Camera: 前置 / 后置 / 外接** | — | 打开**多摄像头选择**对话框：列出设备全部摄像头（前置 / 后置 / 逻辑多摄 / USB 外接），选中后立即切换；选择会被记住，下次启动自动恢复 |
| **Web Output** | — | 打开**浏览器输出**对话框：启动 / 停止输出服务、复制局域网地址、调整输出高度 / 帧率 / 质量、查看连接状态，详见下文「浏览器输出 / OBS」 |
| **Backend: Vulkan / CPU** | 紫 ↔ 青绿 | **运行时切换 MNN 推理后端**（会重建推理 session，画面中断一帧左右）。Vulkan 和 CPU 谁更快**因机型而异**（GPU 驱动质量、与 Live2D 的 GPU 争用、每帧 GPU→CPU 回读开销都不同），帧率不够稳时可以点一下实测对比，详见 [PERFORMANCE.md](PERFORMANCE.md) |

### 滑块区

| 滑块 | 取值范围 | 说明 |
|------|----------|------|
| **Blink Speed** *(仅在 Blink Curve 开启时显示)* | 0.5x ～ 3.0x | 控制整形后的眨眼速度，数值越大眨眼越慢越柔和；面板会同时显示对应的毫秒数（如 `1.0x (230ms)`）。`< 0.8x` 为「Fast blink」，`> 1.5x` 为「Slow & smooth」 |
| **Eye Sensitivity** | 0.5x ～ 2.0x | 眼部 EAR 阈值的灵敏度系数。下方实时显示当前生效的 `closed / open` 阈值；`< 0.8x` 需要更大幅度的眨眼才会触发（适合大眼妆 / 戴眼镜），`> 1.3x` 则对细微动作更敏感（适合表情戏多的录播） |

> 💡 TIPS：第一次跑、或者换了模型 / 换了人脸之后，建议先正脸看镜头点一次
> **Reset Baseline**，然后根据自己的眨眼习惯把 **Eye Sensitivity** 调到刚好能稳定触发即可。

### 多摄像头支持

Demo 现在支持在**多个摄像头之间切换**（不是同时采集多路）：

- 自动枚举 `CameraManager` 暴露的全部摄像头：前置、后置、逻辑多摄以及外接 USB 摄像头；
- 默认仍优先选择前置摄像头；没有前置时自动回退到后置 / 外接，单摄设备行为保持不变；
- 点击控制面板的 **Camera: …** 按钮可随时切换；如果正在追踪，会**原地重启采集管线**，追踪开关状态与 Live2D 画面不中断；
- 每台摄像头都会重新读取 **sensor orientation / 垂直 FOV**，据此计算帧旋转与头姿标定；切换后自动重新标定中性脸，避免前后摄 FOV 不同导致头姿偏移；
- 预览镜像跟随镜头朝向：前置镜像（照镜子），后置 / 外接保持真实画面；
- 选择结果按 camera id 保存在本地，下次启动自动恢复；如果该摄像头已不存在（比如拔掉 USB 摄像头），会安全回退到默认前置。

> ⚠️ 同一时刻只使用一路摄像头。同时打开多路摄像头需要设备支持并发流
> （`LOGICAL_MULTI_CAMERA` / concurrent stream），不在本功能的范围内。

---

## 📺 浏览器输出 / OBS

Demo App 内置了 **浏览器输出 (Web Output)**：把手机端 Live2D 的渲染结果实时编码，通过
**局域网 HTTP / WebSocket** 推给浏览器，OBS 加一个「浏览器源 (Browser Source)」就能把皮套
叠加到直播 / 录屏画面上。网页背景**默认透明**，OBS 直接采集即可拿到 alpha 通道，不需要抠像。

> 💡 适合「游戏画面 / 桌面 + 皮套」这类叠加场景：透明通道随 WebSocket 推送的 WebP 帧一起传，
> 电脑上不用装任何额外的推流或虚拟摄像头软件。

### 打开方式

底部控制面板新增 **Web Output** 按钮，点开是一个对话框，包含：

| 项目 | 说明 |
|------|------|
| **Start / Stop** | 启动 / 停止浏览器输出服务，**默认关闭** |
| **访问地址** | 局域网访问地址，形如 `http://192.168.1.23:8765/`，可复制 |
| **输出高度** | 360 / 480 / 640 / 720 / 1080，默认 640 |
| **自动降分辨率** | 默认开启：编码一帧的耗时吃不进目标帧率（超过预算的 85%）就自动降一档输出高度，负载下来再升回去；关掉则严格按设定高度输出 |
| **输出帧率** | 15 ～ 60 FPS，默认 30 |
| **编码质量** | 40 ～ 95，默认 75，越高越清晰、码率也越大 |
| **运行状态** | 当前是否运行、客户端数，以及 `输出 fps · 抓帧 fps · 编码 ms/帧`：**抓帧高而编码低 = 编码跟不上**（此时该降高度 / 降质量，或让它自动降），两个都低则是手机端渲染/编码整体吃力 |
| **OBS 提示** | 对话框内附带的 OBS 使用提示 |

服务固定监听 `0.0.0.0:8765`，端口不可改；手机 IP 变了，访问地址也会跟着变。

**OBS 里帧率偏低怎么查**（按顺序排除）：

1. 对话框里的 `输出 fps` 是否接近设定值？接近 → 手机侧没问题，问题在 OBS / 网络；
   明显更低 → 看同一行的 `编码 ms/帧`，它超过 `1000 / 帧率` 就是编码跟不上（开自动降分辨率、降高度或降质量）。
2. 浏览器源属性里 **FPS** 一栏是不是被设成了 10 / 20（默认 30，也可能是"自定义帧率"）；OBS 本身有性能问题时可先设 30 不要设 60。
3. 地址后面加 `?hud=1` 打开页面状态条，看它显示的 fps 和"已连接"是否稳定；反复显示"重连中"就是 Wi-Fi 丢包。
4. 页面参数建议 `dpr=1`（OBS 下不需要 2 倍渲染），并把浏览器源的分辨率设成和输出高度接近的值。

### 页面地址与参数

主页面 `http://<手机IP>:8765/` 由 WebSocket 持续推送 **WebP（带 alpha）** 帧，支持以下查询参数：

| 参数 | 取值 | 默认 | 说明 |
|------|------|------|------|
| `bg` | `transparent` / `black` / `white` / `green` | `transparent` | 背景色，`green` 配合 OBS 色度键使用 |
| `fit` | `contain` / `cover` / `stretch` | `contain` | 缩放方式，`contain` 居中显示且不裁切 |
| `mirror` | `0` / `1` | `0` | `1` 时水平镜像，得到「照镜子」的观感 |
| `hud` | `0` / `1` | `0` | `1` 时左上角显示状态条（连接状态 / fps / 分辨率 / 模型名） |
| `dpr` | `1` / `2` / `auto` | `auto` | 页面渲染倍率，OBS 下建议固定为 `1` |

参数直接拼在地址后面，多个用 `&` 连接：

```
http://192.168.1.23:8765/?bg=transparent&fit=contain&mirror=0&hud=0&dpr=1
```

备用地址：

| 地址 | 说明 |
|------|------|
| `http://<手机IP>:8765/stream.mjpg` | 备用 MJPEG 流，**无透明通道**，适合简单场景或其它支持 MJPEG 的软件 |
| `http://<手机IP>:8765/status` | 返回 JSON 状态，便于排障 |

页面快捷键：`h` 切换状态条、`m` 切换镜像、`r` 重连。

### OBS 浏览器源配置

1. 手机和电脑连到**同一个 Wi-Fi / 局域网**；
2. 在 App 里打开 **Web Output**，记下对话框里显示的访问地址（如 `http://192.168.1.23:8765/`）；
3. OBS → **来源** → `+` → **浏览器**，URL 填上一步的地址，宽度 / 高度按需设置（如 `720 × 720`），
   勾选「不可见时关闭源」和「场景激活时刷新浏览器」；
4. 页面默认就透明，直接把这个源叠在游戏画面上即可；想要纯色背景就在地址后面加 `?bg=black` /
   `?bg=white` 等，或者用 `?bg=green` 再给源加一个「色度键」滤镜抠像。

| 选项 | 作用 |
|------|------|
| 不可见时关闭源 | 当源不可见时一并关掉页面，省电并释放手机端连接 |
| 场景激活时刷新浏览器 | OBS 刷新该源时重新加载页面，改过的 URL 参数会立即生效 |

> 💡 不同 OBS 版本的中文翻译可能略有差异，按对应的
> **Shutdown source when not visible** / **Refresh browser when scene becomes active** 勾选即可。

> 💡 建议把浏览器源的「自定义帧率」设成与对话框里的输出帧率一致（例如都是 30），
> 否则 OBS 侧只会重复采样或丢帧，画面看起来反而更卡。

### 注意事项

- **仅限局域网**：服务没有鉴权，不要在公网环境或不可信的 Wi-Fi 下开启。
- **需要 App 在前台**：切后台 / 锁屏会停止渲染，画面停在最后一帧（App 已自动保持屏幕常亮）。
- **性能开销**：输出高度、帧率、质量越高，手机编码压力和带宽越大；默认 `30fps / 640 / 75` 一般够用。
- **透明通道只有 WebSocket 主页面才有**，`/stream.mjpg` 的 MJPEG 是纯 RGB，没有 alpha。

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
  直接基于 478 关键点几何关系解算，省一次推理（其实主要问题是 blend shape 模型直出的
  产物很难救，但推理代码还是留在工程里了，有需要的自取）。
- **可发布的 AAR**：`:facecapture-sdk` 模块已经配置好 `maven-publish`，
  可一键打包成独立的面捕 SDK。
- **多摄像头**：运行时枚举并切换前置 / 后置 / 外接摄像头，按设备重算旋转、
  垂直 FOV 与预览镜像，切换后自动重新标定中性脸。
- **浏览器输出**：渲染结果可直接作为 OBS 浏览器源（透明背景 / MJPEG 备用），
  手机即「推流端」，电脑侧无需额外的推流或虚拟摄像头软件。

---

## 📁 目录速览

```
VirtualFaceCapture-MNN/
├── app/                         # Demo + Live2D 联动
├── Live2D/                      # Cubism SDK 封装
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
└── ThirdParty/MNN/              # 仅提供 MNN 头文件 
```


---

## 💡 遗留问题

由于模型参数表现自带差异，人脸形状也是各有不同，以及点位的映射很难做到理想意义上的 0-1 值，所以实际上在 OpenSeeFaceProcess 后处理里面很多阈值设定偏向工程 trick（多次发动了俺寻思之力），目前暂时想不到一个在任何情况下都很完美的办法，如果有其他好的后处理方案欢迎直接提 PR (￣▽￣)"

---

## 🙏 借物 / 致谢

- Live2D 模型 sensi：<https://www.bilibili.com/video/BV1tk4y1n7Ls/>
- Live2D 模型 ATRI：<https://www.bilibili.com/video/BV1Rs4y187rJ>
- [google/mediapipe](https://github.com/google/mediapipe)
- [emilianavt/OpenSeeFace](https://github.com/emilianavt/OpenSeeFace)
- [alibaba/MNN](https://github.com/alibaba/MNN)