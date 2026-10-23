# Round 4：相机预览不再抢相机线程（预览直出 Surface + 限速 + 缩尺寸 + 线程优先级）

> 触发：评审指出「页面打开期间预览仍会触发额外的整帧 YUV 转换」——预览路径
> （`MainActivity.kt` 相机预览帧门控附近）每帧走 **整帧 YUV→RGB → Kotlin 逐像素
> RGB→ARGB → Bitmap.setPixels**。这些 RGB 帧推理管线根本用不到（推理走 YUV 直通），
> 但它们跑在相机回调线程上，一旦超过帧间隔就开始丢相机帧，躯干输入跟着抖。

## 1. 改动总览

| # | 改动 | 位置 | 效果 |
|---|---|---|---|
| 1 | **预览只在 UI 可见时开**（生命周期 ≥ STARTED） | `MainActivity.kt`（LifecycleEventObserver + 预览容器） | 离开页面后相机线程零预览开销 |
| 2 | **预览限速 15~30 fps**（默认 30，`previewFps` 可调） | `Camera2Manager.processYuvToRgbForPreview` | 被跳过的帧**连整帧 YUV→RGB 都不做**；60fps 采集下预览开销直接减半以上 |
| 3 | **预览输出尺寸 ≤ 640×360**（长边 ≤640、短边 ≤360，只缩不放大） | `Camera2Manager.yuv420ToRgbRotated(maxOutLongEdge/…)`、`mnn_yuv_converter.cpp` 缩放矩阵、Kotlin 回退 | YUV→RGB / RGB→ARGB 打包 / setPixels 三段成本按像素数下降（480×640 → 360×480 ≈ -44% 像素） |
| 4 | **Camera2 独立 Preview Surface**：预览由相机 HAL 直接送显卡合成 | `CameraPreviewSurfaceContainer`（TextureView + 叠加层）、`Camera2Manager.setPreviewSurface` | 预览**完全不再走 YUV→RGB→Bitmap**，相机线程零转换 |
| 5 | **相机回调线程 THREAD_PRIORITY_DISPLAY**（-4） | `Camera2Manager.startBackgroundThread` | 与推理线程（`Thread.NORM_PRIORITY+2`）同级，减少回调被抢占导致的丢帧 |

推理路径（YUV 直通 → 检测 → 关键点 → 后处理）与采集帧尺寸**完全不变**：
第 2、3 条只作用于调试预览 RGB 转换，第 4 条只是把预览改由显卡合成。

## 2. 预览路径怎么选

`CameraPreviewSurfaceContainer` 只做一件事：按「UI 可见 × Surface 可用」挑预览路径，
回调给宿主（`MainActivity`）挂到 `PipelineFaceTracker`：

| UI 可见 | 直出 Surface | 预览走哪条路 |
|---|---|---|
| ✅ | ✅ | **Camera2 直出**（零 CPU 转换），位图帧关闭 |
| ✅ | ❌（HAL 拒绝双流 / View 未就绪） | 位图预览（限速 ≤30fps + ≤640×360） |
| ❌ | — | 两个模式都关 |

显示几何与 `CameraPreviewView.drawBitmapFrame` 同一套约定（旋转摆正 →
fit-center → 前置水平镜像），所以关键点 / 检测框 / 上半身骨架叠加层在两种底图上
都对得齐，`CameraPreviewView` 自身不需要改动。

## 3. 已知风险与真机验证项

> ⚠️ 本轮同样**只在 CI 编译过**，没有在真机上运行过。

1. **双流组合可能压采集帧率**：会话从「单路 YUV」变成「YUV + 直出 Surface」，
   个别 HAL 的流组合上限会把传感器压到 30fps —— 这与本项的目的相反。
   **A/B 方法**：看日志 `Camera input rate / cameraDeliveredFps`，直出开 / 关
   （摘掉 TextureView 或临时把 `CameraPreviewSurfaceContainer` 换回
   `CameraPreviewView`）各跑 30s 对比。若直出导致交付帧率下降，请保留位图路径。
2. **HAL 拒绝双流**：`onConfigureFailed` 会自动摘掉直出 Surface 重建会话并回退
   位图预览（日志：`Preview surface stream rejected`），验证回退后预览仍正常。
3. **直出画面方向 / 镜像**：TextureView 变换矩阵按「顺时针摆正 + fit-center +
   前置镜像」推导，与位图路径同一几何。前置摄像头竖屏下画面应竖直、镜像与
   原来一致，叠加层不漂移；转屏（app 声明了 `configChanges`）后仍应正确。
4. **预览缩尺寸的画质**：MNN 矩阵路径按比例采样（bilinear），NEON 自定义转换器
   不支持缩放、会跳过（走 Kotlin 回退）；两条路径抽帧对比不应有方向 / 色彩差异。
5. **回调线程优先级**：`CameraBackground` 线程应为 nice -4（`adb shell ps -T -p <pid>`
   看 PRIO 列），相机交付帧率（`cameraDeliveredFps`）应比改动前更稳。

## 4. 验证清单

- [ ] 面捕 + 上半身同时开，日志 `Camera input rate` 稳定 55~60fps（或与基线一致）
- [ ] 预览画面方向 / 镜像 / fit-center 与改动前一致，叠加层不漂移
- [ ] 退后台（onPause）→ 回前台：预览恢复、无黑屏 / 无重复会话报错
- [ ] 切换前后置摄像头：预览几何更新、无花屏
- [ ] 回退路径（HAL 拒绝双流 / 直出关闭）：位图预览 ≤30fps 且尺寸 ≤640×360
- [ ] `Camera callback avg` / `cameraDeliveredFps` 对比改动前（预览开销下降）
