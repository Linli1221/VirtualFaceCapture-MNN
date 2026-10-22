# VirtualFaceCapture-MNN

> ✨ **Android face capture → Windows rendering.** The phone only runs face capture; tracking
> frames are streamed over the local network via WebSocket, and a Windows client drives the
> Live2D avatar. Inference is built on [alibaba-MNN](https://github.com/alibaba/MNN).

> 🌐 中文说明: [README.md](./README.md)

[![Android CI](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml)
___

## 🧠 What this is

A mobile port of **MediaPipe Face Landmarker** running on the **MNN** inference engine,
followed by an **OpenSeeFace-inspired** post-processing pipeline that outputs
**ARKit-52 blendshapes + head pose** at ~60 fps.

This version splits the system in two:

```
   Phone (Android)                              PC (Windows)
┌────────────────────────┐   Wi-Fi / LAN     ┌────────────────────────┐
│ Camera → MNN inference  │   ~0.5 KB/frame  │ WebSocket client        │
│ 478 landmarks → post    │   WebSocket JSON │ adaptive normalizer     │
│ ARKit-52 + pose (no GL) │  ───────────────►│ → Live2D params         │
└────────────────────────┘      60 fps       │ Cubism Web + PIXI       │
                                             └────────────────────────┘
```

Why split it:

| | Phone = capture only | PC = render |
|---|---|---|
| **Heat / battery** | drops the whole GL render + encode path, steadier inference | — |
| **Quality** | — | rendered on the desktop GPU, any window size |
| **Overlay** | — | transparent always-on-top window over games, or an OBS browser source |
| **Iteration** | change avatar / mapping without reinstalling the app | drop a model folder in, it is auto-discovered |

The phone keeps the full debugging toolbox: camera preview with landmark overlay, fps and
frame cost, head-pose indicator, raw ARKit values, blink curve and eye sensitivity, and a
runtime MNN backend switch (Vulkan / CPU).

> 📈 The phone-side performance notes, log reading and on-device tuning guide live in
> **[PERFORMANCE.md](PERFORMANCE.md)** (sections about on-device Live2D rendering are
> historical — rendering moved to the Windows client).

---

## ✨ Demo

<p align="center">
  <img src="./sensi.gif" width="45%" />
  <img src="./zzzz.gif" width="45%" />
</p>

---

## 🧠 Approach

In one sentence:

> **Run MediaPipe's Face Detection + Face Landmark models on MNN, implement the whole
> pre/post-processing yourself, add an OpenSeeFace-style smoothing and expression solver,
> and output ARKit-52 blendshapes + head pose. The phone only produces that data;
> Live2D is drawn on the PC.**

Differences from the stock MediaPipe Android pipeline:

| Aspect | Official MediaPipe Tasks | This project (MNN) |
|--------|--------------------------|--------------------|
| Inference | TFLite + GPU delegate | **MNN** (CPU / GPU / NNAPI backends) |
| Scheduling | MediaPipe CalculatorGraph | **local C++ pipeline**, zero graph runtime |
| Image ops | JavaCV / OpenCV / GL | **MNN-CV** + **NEON kernels** |
| Models | Detection + Landmark + Blendshape | **Detection + Landmark** only, blendshapes solved in-house |
| Post-processing | built into MediaPipe | OpenSeeFace-style smoothing / calibration / pose recovery |
| Rendering | on device | **Windows client** (Cubism Web), no rendering on the phone |
| Size | needs mediapipe_tasks_vision aar | only `libMNN.so` + `libmediapipefacelandmark.so` + 2 `.mnn` models |

---

## 🧩 Pipeline

```
            ┌───────────────────────────────┐
Camera ───► │ YUV_420_888 (Camera2/CameraX) │
            └──────────────┬────────────────┘
                           │  NEON YUV→RGB / rotate / scale
                           ▼
              ┌──────────────────────────┐
              │  FaceDetector  (MNN)     │   face_detector_mp.mnn
              │   BlazeFace-style        │   (+ int8 variant)
              └──────────────┬───────────┘
                             │ ROI affine crop (MNN-CV)
                             ▼
              ┌──────────────────────────┐
              │  FaceLandmark (MNN)      │   face_landmark_detector_mp.mnn
              │   478 3D landmarks       │
              └──────────────┬───────────┘
                             ▼
              ┌───────────────────────────────────────────────┐
              │  OpenSeeFace-style post-processing (C++/Kotlin)│
              │   • PnP / SVD head pose (pitch/yaw/roll)      │
              │   • blink curve + mouth + brows               │
              │   • One-Euro / rolling mean smoothing         │
              │   • first-frame calibration                   │
              │   • ARKit-52 blendshape solve                 │
              └──────────────┬────────────────────────────────┘
                             ▼
              ┌──────────────────────────┐        ┌───────────────────────────┐
              │  TrackingServer (phone)   │ ─────► │ Windows client (PC)       │
              │  WS /ws, JSON, ~60 fps    │  Wi-Fi │ normalizer → Live2D params│
              └──────────────────────────┘        │ Cubism Web + PIXI         │
                                                  └───────────────────────────┘
```

All matrix math / affine transforms / SVD / Jacobi rotations / point-cloud transforms use
the local `mini_linalg` + `neon_intrinsics` (vectorized for the typical 3×N landmark shape).

### GPU multi-channel landmark inference

The landmark stage eats ~80% of the per-frame inference budget, yet a single Vulkan
channel leaves the GPU far from saturated: each inference also contains YUV
pre-processing, command recording and read-back, so the GPU idles in between.
`PipelineFaceTracker` therefore runs **several GPU engines** (each with its own MNN
session) that steal frames from one shared queue, keeping the GPU command queue fed —
while the CPU only does the re-ordering:

```
                      ┌──► [ GPU engine 0 (Vulkan) ] ──┐
yuvLandmarkFrame ─────┤                               ├──► reorder (CPU) ──► post-process
      Channel         ├──► [ GPU engine 1 (Vulkan) ] ──┤    by frameId
  (dispatch semantics) └──► [ GPU engine 2 (optional) ] ┘
```

- The sessions are fully independent: natively each engine is an `EngineSlot`, selected
  per thread (`thread_local`) once per inference thread, so no per-frame JNI signature
  had to change.
- Results are re-ordered by `frameId` before post-processing, so downstream still sees a
  strictly increasing stream (calibration baseline, blink curve and the `seq` sent to the
  PC all rely on it). The reorder stage is pure CPU work on its own thread.
- A missing frame is waited for at most 60 ms (`REORDER_GAP_TIMEOUT_MS`); after that the
  gap is accepted and the pipeline keeps moving instead of stalling.
- Parallelism is 1..4 (`MAX_GPU_CHANNELS`, including the primary), default 2. It only
  goes above 1 with "direct YUV mode + Vulkan primary"; switching the primary to CPU
  drops back to a single channel and frees the extra engines.
- Temporal smoothing (landmarks / head pose) is fed **per channel**: each engine sees only
  ~1/N of the frames, so the filter time constant is unchanged but the response is slightly
  slower. Head-pose estimation touches a process-global geometry pipeline + filter, so that
  path is serialized behind a mutex (no data race).
- Cost: one extra resident landmark model per extra engine. Cycle single / ×2 / ×3 in the
  settings sheet (**"Landmark: GPU parallel"**, restarts the pipeline briefly), or change
  `PipelineFaceTracker.GPU_CHANNEL_DEFAULT` / call `setGpuChannelCount(n)`.


---

## 📦 Layout

A multi-module Gradle project plus a standalone Windows client:

| Path | What it is |
|------|-----------|
| `app/` | **Android capture app**: camera, face pipeline, debug HUD, WebSocket tracking server |
| `windows-client/` | **Windows renderer**: Electron + Cubism Web, connects to the phone and draws Live2D |
| `docs/tracking-protocol.md` | **The single contract** between both sides: VFC Tracking Protocol v1 |
| `:MediapipeFaceLandmark` | **core inference**: MNN + `.mnn` models + native C++ pipeline + NEON |
| `:OpenSeeFaceProcess` | OpenSeeFace-style post-processing: smoothing, pose, blendshape solve |
| `:CommonData` | shared data types (`Point3D` / `HeadPose` / `ARKitBlendShapes` …) |
| `:facecapture-sdk` | publishable AAR facade over the modules above |
| `Live2D/` | **no longer built.** Legacy on-device Cubism SDK wrapper (CMake + JNI), kept for reference |
| `ThirdParty/MNN/` | headers only |
| `tools/verify/` | end-to-end verification (mock phone + headless Chrome screenshots + mapping unit tests) |

---

## 🚀 Quick start

### 1. Phone (Android)

```bash
git clone https://github.com/Linli1221/VirtualFaceCapture-MNN.git
cd VirtualFaceCapture-MNN
./check_setup.sh                                     # optional: sanity-check the layout
git submodule update --init --depth 1 ThirdParty/MNN   # headers only
./gradlew :app:assembleDebug
# or just open the project in Android Studio and run "app"
```

Install on an arm64 device, grant camera permission, then:

1. tap **Start** and check the preview;
2. tap **Reset baseline** (recommended whenever lighting, glasses or the person changes);
3. tap **Network output** → start, and note the address, e.g. `ws://192.168.1.23:8765/ws`.

> Phone and PC must be on the same LAN. Keep the app in the foreground — Android's camera
> model stops capture when you leave the activity (the app keeps the screen awake while running).

### 2. Windows

Requires **Node.js ≥ 20** (only for the local server and the Electron shell — no native build).

```bash
cd windows-client
npm install
npm start            # Electron client
```

Then type the phone address `ws://<phone-ip>:8765/ws` into the address bar and hit Enter.
The status dot turns green, phone model / input fps / RTT appear, and the avatar starts moving.

**Without Electron** (plain browser window, e.g. for OBS):

```bash
npm run web      # starts the server and opens an Edge --app window
npm run serve    # static server only → http://127.0.0.1:17890/
npm run mock     # fake phone on ws://127.0.0.1:8765/ws, no device needed
```

`node server.js` works even without `node_modules`: the server has zero dependencies and
`renderer/vendor/` ships browser builds of pixi / pixi-live2d-display / Cubism Core.

### 3. Where do models come from?

Drop them into `windows-client/models/<name>/<anything>.model3.json` — folders are
auto-discovered and shown by folder name. Four are bundled: `ATRI`, `sensei`, `Yuuka`, `zzzz`.

---

## 🎮 Controls

### Phone panel

| Control | What it does |
|---------|--------------|
| **Start / Stop** | camera + inference pipeline |
| **Reset baseline** | current frame becomes the "neutral face" (pose / expression zero). Also remotely triggerable from the PC via `command: recalibrate` |
| **Network output** | start/stop the tracking server, copy the `ws://` address, see client count, output fps and the active frame-buffer length, open the `/status` page |
| **Camera: front / back / external** | enumerate and switch cameras; re-calibrates after switching, remembers the choice |
| **Backend: Vulkan / CPU** | runtime MNN backend switch (rebuilds the session, one-frame hiccup) |
| **Landmark: GPU parallel** | cycles single / GPU×2 / GPU×3: several GPU engines steal frames from one queue to keep the GPU busy (restarts the pipeline briefly) |
| **Blink curve / speed** | shape blinks into a natural 230 ms curve, adjustable speed |
| **Eye sensitivity** | eye EAR threshold sensitivity (0.5x–2.0x) with live closed/open thresholds |
| **Debug / landmarks** | fps, frame cost, pitch/yaw/roll with bars, raw ARKit values, landmark + bbox overlay |

### Windows client

| Control | What it does |
|---------|--------------|
| **Address + Connect** | connect to the phone; the URL is remembered |
| **Model select + Reload** | switch Live2D model (auto-discovered), reload after editing files |
| **Transparent** | transparent window background (recreates the window) |
| **Borderless** | no title bar — combine with transparent for a "character only" overlay |
| **Always on top** | keep the avatar above other windows |
| **Click-through** | mouse passes through the window; **press `Ctrl + Alt + T` to undo** (global hotkey) |
| **One-click calibration + fine-tune** | when the phone camera is not facing you (mounted to the side, new seat), hold still for 3 s to treat the current head pose as neutral, then nudge yaw / pitch / roll with sliders. The offset is saved and survives restarts |
| **Redo phone baseline** | asks the phone to recapture its neutral face baseline (expression + head pose) and resets the PC adaptive ranges; the local pose offset is cleared afterwards to avoid double calibration |
| **Hide UI / Debug** | `H` hides every panel (avatar only), `D` toggles the live parameter grid |

Disconnects reconnect automatically (0.5 s → 1 s → 2 s → 5 s, capped) and the avatar returns to
its default pose instead of freezing on the last frame.
Rendering is capped at a **60 fps** target (high-refresh displays no longer spin at their refresh
rate); when the phone delivers fewer than 60 fps, the client interpolates the recent input frames on
a timeline so the motion stays smooth, and the jitter buffer widens adaptively when arrivals are
uneven (Wi-Fi queueing, PC scheduling, inference hiccups). See
[`windows-client/README.md`](windows-client/README.md) and `?fps=<n>`.

---

## 🌐 Tracking protocol (VFC Tracking Protocol v1)

The only contract between the two sides is **[`docs/tracking-protocol.md`](docs/tracking-protocol.md)**:
JSON text frames, hand-written serialization, ~0.5 KB per frame (≈240 kbps at 60 fps),
no image encoding on the phone.

| Endpoint / message | Direction | Purpose |
|---|---|---|
| `GET /` | PC → phone | embedded info page |
| `GET /status` | PC → phone | JSON: `running / tracking / clients / fps / device / protocol / version / seq` |
| `WS /ws` | both | tracking stream (fixed port 8765) |
| `hello` | phone → PC | once per connection: protocol / version / device / fps / decimation |
| `track` | phone → PC | per frame: `seq` / `t` / `bs[52]` / `pose[3]` / `face` / `score` / `fps` / `calibrating` |
| `calib` | phone → PC | entered / left the calibration window |
| `ping` / `pong` | both | keep-alive and RTT (client every 5 s, phone every 30 s) |
| `command` / `ack` | PC → phone | currently only `recalibrate` |
| `bye` | phone → PC | why streaming stopped |

Key rules: `bs` is **MediaPipe order, 52 values** (index 0 is always neutral); `pose` is
**relative to the baseline** `[pitch, yaw, roll]` in degrees (`pitch>0` = look down,
`yaw>0` = turn left); the client drops stale frames by `seq`, and a slow client can never
stall the phone (each connection only keeps the newest frame).

> **Phone-side 0.5 s frame buffer**: tracking frames are queued and only released once the
> queue holds 0.5 s of data, then played out at the observed production cadence
> (`TrackingProtocol.FRAME_BUFFER_MS = 500`). Frame intervals reaching the PC therefore stay
> even when inference time, scheduling or Wi-Fi hiccups jitter — at the cost of a constant
> ~0.5 s of extra end-to-end latency. Set it to `0` to disable the buffer and send immediately.
> The phone's *Network output* dialog shows the active buffer length.

---

## 🎛 ARKit-52 → Live2D mapping

The reference algorithm lives in **[`docs/tracking-protocol.md` §8](docs/tracking-protocol.md)**.
`windows-client/renderer/mapping.js` is line-by-line equivalent and matches the old on-device
renderer:

- eyes / mouth / eyeballs: `ParamEyeLOpen = 1 - eyeBlinkLeft`, `ParamMouthOpenY = jawOpen`,
  `ParamMouthForm = (smileL + smileR)/2 - (frownL + frownR)/2`, eyeballs combined from `eyeLook*`;
- head pose: through an **adaptive normalizer** (negative / positive ranges follow the observed
  motion, blended with defaults over the first 100 frames) and then scaled into
  `ParamAngleX/Y/Z` plus `ParamBodyAngleX`;
- renderer requirements: disable Cubism's built-in eye blink and mouse focus, keep physics and
  idle motions, and **absolutely assign** our parameters in the `beforeModelUpdate` hook.

`mapping.js` is a pure module (no DOM, no PIXI) and can be unit-tested in Node:

```bash
cd windows-client
node -e "import('./renderer/mapping.js').then(m => console.log(m.mapTrack({bs:new Array(52).fill(0), pose:[10,-20,5]})))"
```

---

## 🖥 OBS / streaming overlay

| Option | How | Best for |
|--------|-----|----------|
| **Window capture** (recommended) | enable Transparent + Borderless in the Electron client, add a Window Capture source | simplest overlay, lowest latency |
| **Display capture / virtual cam** | fullscreen client + display capture | fixed resolution and framing |
| **OBS browser source** | add a Browser source with `http://127.0.0.1:17890/?ws=ws://<phone-ip>:8765/ws`, size e.g. 720×720, enable "Shutdown source when not visible" | no Electron needed, transparent page |

Query parameters:

| Param | Values | Default | Meaning |
|-------|--------|---------|---------|
| `ws` | `ws://ip:8765/ws` | `ws://127.0.0.1:8765/ws` | phone address (remembered if omitted) |
| `model` | folder name, e.g. `ATRI` | last used / first | initial model |
| `transparent` | `0` / `1` | `0` | transparent background (Electron passes it itself) |
| `fps` | integer, `0` = uncapped | `60` | PC render target; high-refresh displays are capped at 60 fps |

> 💡 Browser sources sample at 30 fps; window capture has no such limit — prefer it for feel.

---

## 🏗 Building & releases

### Local

```bash
./gradlew :app:assembleDebug          # debug APK
./gradlew :app:assembleRelease        # release APK (falls back to the debug key)
./gradlew :app:testDebugUnitTest      # JVM unit tests for the tracking protocol
```

> First build needs `git submodule update --init --depth 1 ThirdParty/MNN` for the MNN headers.
> If your local SDK's build-tools don't match what AGP expects, add
> `-Pandroid.builder.sdkDownload=false` so Gradle uses what is already installed.

The Windows client is plain JavaScript (nothing to compile), but it can be bundled into a
**portable, installer-free zip** with the Electron runtime inside:

```powershell
cd windows-client
npm ci
npm run selftest          # 36 checks: mapping / low-fps interpolation / jitter buffer / static routes
npm run package           # → dist/windows/VFC-Live2D-win32-x64/
Compress-Archive -Path dist\windows\VFC-Live2D-win32-x64 -DestinationPath dist\VFC-Live2D-win32-x64.zip
```

Run `VFC-Live2D.exe --smoke` on the packaged app to verify it boots and renders (the result is
written to `resources/app/electron/smoke-result.json`).

### GitHub Actions

Two workflows build on every change:

**[Android CI](.github/workflows/android-ci.yml)** — debug and release variants run in parallel and
each publishes an artifact (kept 30 days):

- `VirtualFaceCapture-<sha>-arm64-debug` — arm64-v8a debug APK
- `VirtualFaceCapture-<sha>-arm64-release` — arm64-v8a release APK + SDK AAR

Artifacts carry `1.0.<run>+<commit>` so versionCode keeps increasing. Pushing a `v*` tag also
creates a signed GitHub Release.

**[Windows Client CI](.github/workflows/windows-client-ci.yml)** — runs when `windows-client/**`
changes:

- on `windows-latest`: `npm ci` → `npm run selftest` → `npm run package`, producing the portable
  win32-x64 zip (Electron runtime + the 4 bundled Live2D models);
- the packaged exe is **actually launched once** (`--smoke`, waits for the renderer to be ready),
  so "build passed" means "the app starts", not just "a zip exists";
- artifact `VFC-Live2D-win32-x64-<sha>` (zip + `SHA256SUMS.txt`), also attached to the GitHub
  Release on `v*` tags.

### Signing

By default a release build falls back to the **debug key**, which is regenerated on every CI
runner, so installing a new build fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Fix it by pinning a keystore:

1. create one:

```bash
keytool -genkeypair -v \
  -keystore virtualfacecapture-release.jks \
  -alias virtualfacecapture \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <password> -keypass <same> \
  -dname "CN=VirtualFaceCapture, OU=Release, O=YourOrg, C=CN"
```

> 🔐 Back this file up. If it is lost you can never update the installed app again.
> It is already in `.gitignore` — never commit it.

2. add four repository secrets: `RELEASE_KEYSTORE_BASE64` (single-line base64 of the .jks),
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`;

3. or, for local builds, put `keystore.properties` in the repo root (also gitignored):

```properties
storeFile=/absolute/path/to/virtualfacecapture-release.jks
storePassword=...
keyAlias=virtualfacecapture
keyPassword=...
```

Environment variables `RELEASE_STORE_FILE` / `RELEASE_STORE_PASSWORD` /
`RELEASE_KEY_ALIAS` / `RELEASE_KEY_PASSWORD` take precedence.

Verify with `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`.
And yes — **always benchmark fps on a release build**; debug builds are noticeably slower.

---

## 🧩 Using it as an SDK

If you only want face capture (to drive your own renderer, or anything else), see
[`facecapture-sdk/README.md`](facecapture-sdk/README.md):

```kotlin
val engine = FaceCaptureEngine.create()
engine.initialize(context)
engine.setResultListener { result ->
    val landmarks   = result.landmarks         // 478 3D landmarks
    val blendShapes = result.blendShapes       // ARKit-52 blendshapes
    val pose        = result.relativeHeadPose  // pitch / yaw / roll, relative to baseline
}
engine.pushFrame(image, rotationDegrees)       // feed CameraX ImageProxy
```

To reuse the wire format, just copy `docs/tracking-protocol.md` — it was written exactly for
"produce data on the phone, render somewhere else".

---

## 🛠 Engineering notes

- **No MediaPipe runtime**: `CalculatorGraph`, `PacketCallback`, `AndroidPacketCreator` etc.
  are gone; the C++ side schedules the models sequentially, Kotlin runs the parallel pipeline.
- **MNN-CV instead of OpenCV**: YUV decode, resize, warp affine and normalization all go
  through MNN-CV, saving the multi-MB OpenCV-Android dependency.
- **NEON SIMD**: `neon_intrinsics.h` implements dot products, matrix multiply, 3×N outer
  products, Jacobi rotations and 4×4 affine transforms used for SVD pose recovery and the
  batched 478-point transform.
- **OpenSeeFace-style post-processing**: a C++/Kotlin rewrite of `tracker.py` /
  `similaritytransform.py`, including One-Euro smoothing, first-frame calibration and blink
  curve shaping.
- **ARKit-52 solved in-house**: no third blendshape model — the shapes come from landmark
  geometry, saving an entire inference pass.
- **Publishable AAR**: `:facecapture-sdk` is wired for `maven-publish`.
- **Multi-camera**: enumerate and switch front / back / external cameras at runtime,
  recomputing rotation, vertical FOV and preview mirroring, re-calibrating after a switch.
- **Streaming never blocks inference**: each WS client has a single "latest frame" slot, so
  slow networks drop frames instead of stalling the inference thread.
- **Both ends are verifiable offline**: the phone has JVM unit tests for the protocol; the PC
  has `tools/verify/` (mock phone + headless Chrome screenshots + numeric mapping tests).

---

## 📁 Tree

```
VirtualFaceCapture-MNN/
├── app/                         # Android capture app (camera + pipeline + tracking server)
│   └── src/main/java/.../tracking/   # TrackingServer / TrackingProtocol / TrackingOutputManager
├── windows-client/              # Windows renderer (Electron + Cubism Web)
│   ├── electron/                #   desktop shell (transparent / borderless / always-on-top / click-through)
│   ├── renderer/                #   page, mapping.js, live2d.js, interp.js
│   ├── server.js                #   zero-dependency static server (127.0.0.1:17890)
│   └── models/                  #   Live2D models (auto-discovered)
├── MediapipeFaceLandmark/       # MNN inference + NEON core
│   ├── src/main/assets/         #   .mnn models (detector / landmark)
│   ├── src/main/cpp/            #   C++ pipeline, YUV, geometry
│   └── src/main/jniLibs/        #   prebuilt libMNN.so
├── OpenSeeFaceProcess/          # OpenSeeFace-style post-processing
├── CommonData/                  # shared data classes
├── facecapture-sdk/             # publishable AAR facade
├── docs/tracking-protocol.md    # the wire contract
├── tools/verify/                # end-to-end verification scripts
├── Live2D/                      # legacy on-device renderer (not built anymore)
└── ThirdParty/MNN/              # headers only
```

---

## 💡 Known limitations

- Blendshape mapping can never be perfect — a lot of the thresholds in `OpenSeeFaceProcess`
  are pragmatic engineering tricks. Better post-processing ideas are very welcome.
- The adaptive normalizer learns per person; press **Reset baseline** after changing person or
  posture.
- No authentication on the LAN — only enable it on trusted networks.

---

## 🙏 Credits

- Live2D model "sensi": <https://www.bilibili.com/video/BV1tk4y1n7Ls/>
- Live2D model "ATRI": <https://www.bilibili.com/video/BV1Rs4y187rJ>
- [google/mediapipe](https://github.com/google/mediapipe)
- [emilianavt/OpenSeeFace](https://github.com/emilianavt/OpenSeeFace)
- [alibaba/MNN](https://github.com/alibaba/MNN)
- [pixi.js](https://github.com/pixijs/pixijs) / [pixi-live2d-display](https://github.com/guansss/pixi-live2d-display) / [Live2D Cubism SDK](https://www.live2d.com/en/sdk/)
