# VirtualFaceCapture-MNN
> ✨ An Android virtual-avatar face-capture system, powered by the [alibaba-MNN](https://github.com/alibaba/MNN) inference engine.

> 🌐 中文版 README: [README.md](./README.md)

[![Android CI](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml/badge.svg)](https://github.com/Linli1221/VirtualFaceCapture-MNN/actions/workflows/android-ci.yml)

___

## 🧠 Overview
A mobile-side reimplementation of **MediaPipe Face Landmarker** on top of the
**MNN** inference engine, paired with an **OpenSeeFace-style** post-processing
pipeline to drive **Live2D** avatars in real time.
(After trying many alternatives it turns out Google's models are still the only
ones that hold up well on mobile -.-)

The whole capture pipeline has been completely **stripped out of the MediaPipe
source tree**: no `CalculatorGraph`, no JavaCV / OpenCV-Android, no MediaPipe
Tasks Java layer. Everything runs on **MNN + MNN-CV + native C++ post-processing**,
with hot paths (pose estimation, point-cloud transforms, …) accelerated by
**ARM NEON** SIMD intrinsics. End-to-end the system pushes close to **60 FPS**
on a Snapdragon 888 device. 🎉

---

## ✨ Demo

<p align="center">
  <img src="./sensi.gif" width="45%" />
  <img src="./zzzz.gif" width="45%" />
</p>

---

## 🧠 Architecture at a Glance

In one sentence:

> **Port MediaPipe Face Landmarker's Detection + Landmark models onto MNN,
> rebuild the pre/post image-processing in native code, then bolt on an
> OpenSeeFace-style smoothing & expression solver to emit ARKit-52 BlendShapes
> + head pose for driving Live2D.**

Differences vs. the official MediaPipe Android stack:

| Aspect | Official MediaPipe Tasks | This project (MNN port) |
|--------|--------------------------|--------------------------|
| Inference engine | TFLite + GPU Delegate | **MNN** (CPU / GPU / NNAPI backends) |
| Graph scheduling | MediaPipe `CalculatorGraph` | **Native C++ pipeline**, zero graph deps |
| Image processing | JavaCV / OpenCV / GL | **MNN-CV** + **NEON kernels** |
| Model count | Detection + Landmark + Blendshape | **Detection + Landmark only**, BlendShape solved geometrically |
| Post-processing | Built into MediaPipe | **OpenSeeFace-style** smoothing / calibration / head-pose recovery |
| Footprint | `mediapipe_tasks_vision.aar` | Just `libMNN.so` + `libmediapipefacelandmark.so` + 2 `.mnn` files |

---

## 🧩 Pipeline

```
            ┌───────────────────────────────┐
Camera ───► │ YUV_420_888 (Camera2/CameraX) │
            └──────────────┬────────────────┘
                           │  NEON YUV→RGB / rotation / resize
                           ▼
              ┌──────────────────────────┐
              │  FaceDetector  (MNN)     │   face_detector_mp.mnn
              │   - BlazeFace style      │   (int8-quantized variant included)
              └──────────────┬───────────┘
                             │ ROI affine crop (MNN-CV)
                             ▼
              ┌──────────────────────────┐
              │  FaceLandmark (MNN)      │   face_landmark_detector_mp.mnn
              │   - 478 3D landmarks     │
              └──────────────┬───────────┘
                             │
                             ▼
              ┌──────────────────────────────────────────────┐
              │  OpenSeeFace-style post-processing (C++/Kt)  │
              │   • PnP / SVD head-pose recovery (p/y/r)     │
              │   • Blink curve + mouth + brows              │
              │   • One-Euro / rolling-mean smoothing        │
              │   • Auto baseline calibration (1 s window)   │
              │   • ARKit-52 BlendShape solver               │
              └──────────────┬───────────────────────────────┘
                             ▼
              ┌──────────────────────────┐
              │  Live2D Cubism Renderer  │   ARKit → Live2D mapping
              └──────────────────────────┘
```

Linear algebra (matrix multiplies, affine transforms, SVD, Jacobi rotations,
3×N point-cloud transforms…) all goes through local `mini_linalg` +
`neon_intrinsics` headers, vectorized for the typical 3×N landmark shapes —
load time and first-frame latency drop noticeably vs. the scalar baseline.

---

## 📦 Modules

Standard multi-module Gradle project:

| Module | Purpose |
|--------|---------|
| `:app` | Demo app: camera, UI, Live2D rendering, parameter mapping |
| `:Live2D` | Cubism SDK wrapper (CMake + JNI) |
| `:MediapipeFaceLandmark` | **Core inference**: MNN + `.mnn` models + native C++ pipeline + NEON |
| `:OpenSeeFaceProcess` | OpenSeeFace-style post-processing: smoothing, head pose, BlendShape solver |
| `:CommonData` | Shared types (`Point3D` / `HeadPose` / `ARKitBlendShapes` …) |
| `:facecapture-sdk` | Public AAR facade wrapping the three modules above |
| `ThirdParty/MNN/` | MNN headers only |

---

## 🚀 Quick Start

### Build & run

```bash
git clone https://github.com/<your-name>/VirtualFaceCapture-MNN.git
cd VirtualFaceCapture-MNN
git submodule update --init --depth 1 ThirdParty/MNN   # only the MNN headers are needed
./gradlew :app:assembleDebug
# Or just open the project in Android Studio and hit Run.
```

Grant the camera permission on first launch, keep your face in frame, tap
**Start**, and the Live2D avatar will mirror your expression & head pose
in real time.

### GitHub Actions (every change gets a build)

[Android CI](.github/workflows/android-ci.yml) builds the installable APK for
**every modification**:

- **Every push (any branch) / every PR** builds the Debug + Release APKs plus the
  SDK AAR; download them from `Actions` → the run → `Artifacts` (kept 30 days);
- CI artifacts carry the version `1.0.<run number>+<commit>` (monotonic
  versionCode), so they install straight over older builds;
- **To publish a release**: push a `v*` tag and a GitHub Release with the APKs is
  created automatically:

```bash
git tag v1.0.0
git push origin v1.0.0
```

> 💡 No local Android toolchain needed — just grab the APK from the Actions
> artifacts. For local builds, run the `git submodule update` above first to get
> the MNN headers.

### Use as an SDK

If you only need the capture pipeline (no Live2D rendering), see
[`facecapture-sdk/README.md`](facecapture-sdk/README.md). The facade API
looks like:

```kotlin
val engine = FaceCaptureEngine.create()
engine.initialize(context)
engine.setResultListener { result ->
    // --- Raw perception layer (MediaPipe FaceLandmarker on MNN) ---
    val landmarks = result.landmarks         // 478 normalized 3D points
    val imgW      = result.imageWidth
    val imgH      = result.imageHeight
    val bbox      = result.detectionBox
    val presence  = result.presenceScore     // model confidence in [0, 1]

    // --- Post-processed layer (OpenSeeFace-style stack) ---
    val blendShapes = result.blendShapes     // 52 ARKit BlendShapes, smoothed
    val rawPose     = result.rawHeadPose     // raw pitch / yaw / roll
    val relPose     = result.relativeHeadPose // baseline-subtracted
}
// Push frames from CameraX:
engine.pushFrame(image, rotationDegrees)
```

Both layers (raw landmarks **and** post-processed BlendShape / head pose) come
from the same frame atomically — integrators can pick whichever they need.

---

## 🎮 Controls

When the demo launches, a scrollable **ControlPanel** appears at the bottom of
the screen with the current Live2D model name on top. All buttons and sliders:

### Buttons

| Button | Toggled state | Effect |
|--------|---------------|--------|
| **Start / Stop** | Red ↔ primary | Start / stop the whole capture pipeline (camera + inference + driving). When stopped, the avatar freezes at the last pose. |
| **Show Debug / Hide Debug** | — | Show or hide the on-screen debug HUD: FPS, inference timings, head pitch/yaw/roll, EAR, raw BlendShape values, etc. |
| **Show Camera / Show Live2D** | tertiary ↔ secondary | Switch the main view between the Live2D render and the camera preview (with landmarks overlay) — handy for diagnosing tracking issues. |
| **Select Model** | — | Open a dialog to pick a Live2D model from `assets/` (sensi / ATRI / … bundled). |
| **Reset Baseline** | — | Recalibrate: treat the current frame as the "neutral face" for head pose and expressions. Recommended whenever you put on glasses, change lighting, or hand the phone to a different person. |
| **Blink Curve: ON / OFF** | Green ↔ secondary | Toggle the **natural-blink curve shaper**. When on, raw blink events are reshaped into a smoother ~230 ms curve (no jitter / half-blinks). When off, the raw EAR is passed through. |
| **Camera: Front / Back / External** | — | Open the **camera picker**: lists every camera exposed by the device (front / back / logical multi-cam / USB external) and switches capture instantly. The choice is remembered across launches. |
| **Web Output** | — | Open the **Web Output** dialog: start / stop the server, copy the LAN URL, tune output height / FPS / quality and watch the connection status. See "Web Output / OBS" below. |

### Sliders

| Slider | Range | Notes |
|--------|-------|-------|
| **Blink Speed** *(only visible when Blink Curve is ON)* | 0.5x – 3.0x | Controls the duration of the reshaped blink. Larger = slower, smoother blink. The panel also shows the resulting duration in ms (e.g. `1.0x (230ms)`). `< 0.8x` is labeled "Fast blink", `> 1.5x` "Slow & smooth". |
| **Eye Sensitivity** | 0.5x – 2.0x | Sensitivity multiplier for the EAR thresholds. The panel shows the live `closed / open` thresholds below. `< 0.8x` → larger eye motion required to trigger (great for heavy makeup / glasses); `> 1.3x` → catches subtler motion (great for expressive recording). |

> 💡 TIP: On a fresh run, or after switching model / user, look straight at the
> camera once and tap **Reset Baseline**, then nudge **Eye Sensitivity** until
> blinks register reliably for your eye shape.

### Multi-camera support

The demo can **switch between cameras** (one active camera at a time, not
simultaneous multi-camera capture):

- Enumerates every camera reported by `CameraManager`: front, back, logical
  multi-cameras and external USB cameras;
- Front-first by default; falls back to back / external when no front camera
  exists, so single-camera devices keep the old behavior;
- Tap **Camera: …** in the control panel to switch at any time. While tracking,
  the capture pipeline restarts in place — the Start/Stop state and the Live2D
  avatar stay up;
- Sensor orientation and vertical FOV are re-read per camera and used for frame
  rotation and head-pose calibration; switching triggers a fresh neutral-face
  calibration so front/back FOV differences do not skew the head pose;
- Preview mirroring follows the lens facing: front is mirrored (selfie view),
  back / external stay true to the scene;
- The selected camera id is persisted and restored on the next launch. If it is
  no longer present (e.g. USB camera unplugged), the app safely falls back to
  the default camera.

> ⚠️ Only one camera stream is active at a time. Simultaneous multi-camera
> capture requires device-level concurrent streaming support
> (`LOGICAL_MULTI_CAMERA` / concurrent streams) and is out of scope here.

---

## 📺 Web Output / OBS

The demo app ships a **Web Output** feature: the Live2D frame rendered on the
phone is encoded on the fly and pushed to a browser over **HTTP / WebSocket on
your LAN**, so you can add it to OBS as a **Browser Source** and overlay the
avatar on your stream or recording. The page background is **transparent by
default**, so OBS captures the alpha channel as-is — no keying required.

> 💡 Great for the "game footage / desktop + avatar" overlay: the alpha channel
> travels with the WebP frames pushed over WebSocket, so the PC needs no extra
> streaming tool or virtual camera.

### How to open it

The bottom control panel has a new **Web Output** button that opens a dialog:

| Item | Description |
|------|-------------|
| **Start / Stop** | Start / stop the Web Output server, **off by default** |
| **Address** | LAN URL such as `http://192.168.1.23:8765/`, copyable |
| **Output Height** | 360 / 480 / 640 / 720 / 1080, default 640 |
| **Output FPS** | 15 – 60 FPS, default 30 |
| **Quality** | 40 – 95, default 75; higher = sharper, but bigger bitrate |
| **Status** | Whether it is running, connected client count, actual output fps |
| **OBS hint** | Short OBS usage tips shown inside the dialog |

The server always listens on `0.0.0.0:8765` (port is fixed); when the phone's
IP changes, the URL changes with it.

### Page URL & parameters

The main page `http://<phone-IP>:8765/` streams **WebP frames with alpha** over
WebSocket, and accepts these query parameters:

| Parameter | Values | Default | Meaning |
|-----------|--------|---------|---------|
| `bg` | `transparent` / `black` / `white` / `green` | `transparent` | Background fill; `green` is meant for OBS chroma key |
| `fit` | `contain` / `cover` / `stretch` | `contain` | Scaling mode; `contain` centers without cropping |
| `mirror` | `0` / `1` | `0` | `1` flips horizontally for a mirror-like view |
| `hud` | `0` / `1` | `0` | `1` shows a status bar (connection / fps / resolution / model) in the top-left corner |
| `dpr` | `1` / `2` / `auto` | `auto` | Page render scale; use `1` under OBS |

Chain them on the URL with `&`:

```
http://192.168.1.23:8765/?bg=transparent&fit=contain&mirror=0&hud=0&dpr=1
```

Fallback endpoints:

| Endpoint | Description |
|----------|-------------|
| `http://<phone-IP>:8765/stream.mjpg` | Fallback MJPEG stream, **no alpha channel**; fine for simple cases or other MJPEG-capable software |
| `http://<phone-IP>:8765/status` | Returns JSON status, handy for troubleshooting |

Page hotkeys: `h` toggles the status bar, `m` toggles mirroring, `r` reconnects.

### OBS Browser Source setup

1. Connect the phone and the PC to the **same Wi-Fi / LAN**;
2. Open **Web Output** in the app and note the address shown in the dialog
   (e.g. `http://192.168.1.23:8765/`);
3. In OBS: **Sources** → `+` → **Browser**, paste that URL, set width / height as
   needed (e.g. `720 × 720`), and tick **Shutdown source when not visible** and
   **Refresh browser when scene becomes active**;
4. The page is already transparent — just put the source over your game footage.
   For a solid backdrop append `?bg=black` / `?bg=white`, or use `?bg=green`
   together with a **Chroma Key** filter on the source.

| Option | Effect |
|--------|--------|
| Shutdown source when not visible | Closes the page when the source is hidden — saves power and frees the phone-side connection |
| Refresh browser when scene becomes active | Reloads the page whenever OBS refreshes the source, so URL parameter changes take effect |

> 💡 Set the Browser Source's **custom frame rate** to the output FPS from the
> dialog (e.g. 30). Otherwise OBS merely re-samples or drops frames and the
> result can look *worse* than the phone-side output.

### Notes & caveats

- **LAN only**: the server has no authentication — never expose it on a public
  network or untrusted Wi-Fi.
- **The app must stay in the foreground**: backgrounding the app or locking the
  screen stops rendering and the page freezes on the last frame (the app keeps
  the screen awake automatically).
- **Cost scales with settings**: higher height / FPS / quality means more
  encoding pressure on the phone and more bandwidth. The defaults
  (`30fps / 640 / 75`) are usually plenty.
- **Only the WebSocket page carries alpha**; the MJPEG endpoint
  (`/stream.mjpg`) is plain RGB with no transparency.

---

## 🛠 Highlights

- **Zero MediaPipe runtime dependency** — every `CalculatorGraph`,
  `PacketCallback`, `AndroidPacketCreator` etc. is gone; the model pipeline is
  scheduled by plain C++, with Kotlin running a multi-threaded parallel
  pipeline on top.
- **MNN-CV instead of OpenCV** — YUV decoding, resize, affine warp,
  normalization all live in MNN-CV. Saves several MB of `opencv-android`.
- **NEON SIMD** — `neon_intrinsics.h` implements dot products, matrix
  multiplies, 3×N outer products, Jacobi rotations, 4×4 affine transforms
  and friends; used by the SVD head-pose solver and the per-frame batch
  transform of 478 landmarks. Noticeable speed-up vs. scalar code.
- **OpenSeeFace-style post-processing** — rewrote bits of the Python
  `tracker.py` / `similaritytransform.py` in C++/Kotlin: One-Euro smoothing,
  first-frame calibration, blink curve shaping.
- **Geometric ARKit-52 solver** — does *not* require MediaPipe's third
  (BlendShape) model; solves the 52 BlendShapes from the 478 landmark
  geometry directly, saving one inference per frame. (Honestly the main
  reason is that the BlendShape model's raw output was hard to rescue;
  the inference code is still in the tree if you want to play with it :( .)
- **Publishable AAR** — `:facecapture-sdk` is already wired with
  `maven-publish` for one-shot release.
- **Multi-camera** — enumerate and switch front / back / external cameras at
  runtime, with per-device rotation, vertical FOV and preview mirroring, and a
  fresh baseline calibration after each switch.
- **Web Output** — the rendered frame can be used directly as an OBS Browser
  Source (transparent background, MJPEG fallback): the phone is the "streamer",
  the PC installs nothing and needs no virtual camera.

---

## 📁 Tree

```
VirtualFaceCapture-MNN/
├── app/                         # Demo + Live2D integration
├── Live2D/                      # Cubism SDK wrapper
├── MediapipeFaceLandmark/       # MNN inference + NEON-optimized core
│   ├── src/main/assets/         #   .mnn models (detector / landmark)
│   ├── src/main/cpp/            #   C++ pipeline, YUV, geometry
│   │   └── include/face_geomentry/
│   │       ├── mini_linalg.h        # linear algebra
│   │       └── neon_intrinsics.h    # ARM NEON SIMD
│   └── src/main/jniLibs/        #   prebuilt libMNN.so
├── OpenSeeFaceProcess/          # OpenSeeFace-style post-processing
├── CommonData/                  # Shared data types
├── facecapture-sdk/             # Public AAR facade
└── ThirdParty/MNN/              # MNN headers only
```

---

## 💡 Known Limitations

Because every face is shaped differently and the per-point mapping is hard to
nail down to clean 0–1 values, many thresholds in `OpenSeeFaceProcess` end up
as engineering trick — there is no single magic constant that fits every case.
If you have a better post-processing idea, PRs are very welcome (￣▽￣)"

---

## 🙏 Credits

- Live2D model **sensi**: <https://www.bilibili.com/video/BV1tk4y1n7Ls/>
- Live2D model **ATRI**: <https://www.bilibili.com/video/BV1Rs4y187rJ>
- [google/mediapipe](https://github.com/google/mediapipe)
- [emilianavt/OpenSeeFace](https://github.com/emilianavt/OpenSeeFace)
- [alibaba/MNN](https://github.com/alibaba/MNN)
