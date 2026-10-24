# 集成验收报告：rawTrackV1 原始输出与 Windows 后处理迁移（task-6）

> 验收人：spec-arch（独立验收角色，非实现者）· 日期：2026-10-24
> 验收对象：feat/raw-track-post-pipeline 上 task-1 ~ task-5 / task-7 的全部提交
> 契约基线：docs/raw-track-protocol.md（协议）与 docs/post-pipeline-migration.md（迁移设计）
> 口径：本地可验项**现在全部出结论**；CI 项单列「待 push 后验证」区块（push 授权在用户，落地后补结论）。

---

## 1. 验收结论

**本地可验项：全部通过。** 四份实现（android-raw P1 + 收尾、win-postproc 后处理链、
win-integrate 总装、android-raw 真·Kotlin 对拍）与两份规格逐条对照**零未决偏差**；
观察项 2 条、已知边界 3 条均已按「保真复刻不修 / 有意扩展」定性归档（§5）。

**CI 项：待 push 后验证**（§6）——其中 android-ci 的 JVM 测试门槛在验收中发现缺口
（workflow 只跑 assemble* 不跑 test），已由 Lead 修复（debug 腿加 :app:testDebugUnitTest），
状态为「**已修复、待 CI 实跑确认**」。

---

## 2. 本地可验项复核结果（验收人现场执行）

| # | 项目 | 命令 | 结果 | 判定 |
|---|------|------|------|------|
| 1 | 后处理链专项单测 | node windows-client/test/run-all.mjs | **93 / 93 通过** | ✅ |
| 2 | selftest（v1 文本路径回归） | npm run selftest（windows-client） | **108 / 108 通过**（映射/上半身/滤波/时间轴/服务器路由全绿，jitterBufferV1 行为未动） | ✅ |
| 3 | golden 对齐主层 | node tools/evals/golden-run.mjs | **14 场景 + 4 个滤波/基线模块全过**；逐帧逐维最大误差 **2.38e-7**（容差 abs 1e-5 / rel 1e-4，JS↔JS） | ✅ |
| 4 | golden-export 哈希校验 | node tools/evals/golden-export/verify.mjs | **12 / 12 全部 OK**（README 哈希表为准） | ✅ |
| 5 | CI workflow 硬约束核对 | 静态核对 .github/workflows/* | android-36 / NDK 28.2.13676358 / CMake 3.22.1+3.18.1 一致；pose_landmarker_lite.task 构建前下载 + APK ZIP_STORED 断言完好；windows-client-ci 的 selftest + 打包 exe --smoke → smoke-result.json ready 定义完好、ELECTRON_RUN_AS_NODE 清除保留 | ✅ |
| 6 | 代码 ↔ 规格逐条对照 | 见 §3 | 零未决偏差 | ✅ |

golden 场景覆盖（主层 14）：neutral / blink / squint / mouthOpen / smile / frown /
yawLeft / yawRight / pitchDown / pitchUp / rollTilt / longDt / faceLost / browUp；
滤波/基线：TimeBasedEMA(tau=0.12)、OneEuroFilter(1.2/0.02/1.0)、TorsoBaseline(roll/yaw)，
误差全为 0。误差统计报告：windows-client/tools/evals/golden-report.md。

---

## 3. 规格逐条对照（摘要；完整核对在验收过程中逐文件完成）

### 3.1 协议（docs/raw-track-protocol.md）

| 规格条款 | 实现 | 结论 |
|---|---|---|
| §4 帧头 72 B 逐偏移（magic/version/msgType/flags、frameId@8 u32、cameraFps@12、t@16 u64、width/height、presence@28、inferenceMs@32、poseRaw 3×f32@36、box 4×f32@48、landmarkCount@64、boxScore@68） | app/.../TrackingRawProtocol.kt 逐偏移写入 | ✅ |
| §4.3 量化（x/y u16 归一化图幅、z i16 / Z_SCALE=5.0、LE、round/clamp 语义） | 同上 + TrackingProtocolTest 逐偏移/量化断言 | ✅ |
| §4.2 flags（bit0-3、bit4 sensor 保留、bit5 LANDMARKS_PRE_SMOOTHED 双口径） | TrackingRawProtocol 常量组 + 主口径恒 0 | ✅ |
| §5 ubRaw 128 B 保留块（13 点 ORDER、ubT、torsoRaw、quality、ubTracking） | 编码器 + 测试 128 B 布局 | ✅ |
| §2.2 per-client 分发（TEXT/RAW/IDLE 三态、IDLE 不发文本、降级矩阵） | TrackingServer.trackMode 聚合 + app.js 双模互斥状态机 | ✅ |
| §2.3/§6 控制消息（startStream/stopStream/resetTracking、ack requestId 幂等、rawMode 未协商报错、recalibrate 废止映射） | TrackingServer + TrackingProtocol.buildAckResult | ✅ |
| §2.5 raw 旁路 500 ms 发送缓冲、control 优先保序 | TrackingSendQueue/TrackingFrameBuffer 显式类型条目 | ✅ |
| §7 frameId 语义（相机回调打点、u32 主键、seq/epoch 仅诊断、回绕 32 位序列算术） | PipelineFaceTracker AtomicLong @相机回调 + raw-frame-reorder unwrapFrameId | ✅ |
| §8 异常（长度/magic 校验整条丢弃、NaN→0+CLAMPED、FACE_VALID=0 结构恒定） | 编码器/解码器双向 + 测试 | ✅ |
| §9/§10 性能与演进（零装箱 TypedArray、报文 2940/3068 B） | 实现注释与编码器一致 | ✅ |

### 3.2 迁移设计（docs/post-pipeline-migration.md）

| 规格条款 | 实现 | 结论 |
|---|---|---|
| §4 12 步链序（时间滤波=478 点域+rawHeadPose、**提取之前**；bs 域不平滑） | windows-post-pipeline.js 12 步注释与调用序一致 | ✅（步骤 10 映射留主线程 = 实现分区，链序语义不变，已确认） |
| §5 八状态模块职责/边界 | raw-frame-reorder / blendshape-extractor / blink-curve / expression-calibrator / pose-calibrator / temporal-filter / body-fusion / windows-post-pipeline | ✅ |
| 附录 A~K 机器常量与状态转移（含四处「按代码」） | 逐值核对（阈值/索引/常量/状态机/公式） | ✅ |
| §6 校准 Windows 拥有（recalibrate requestId 幂等、2 s 超时、手机仅流控三命令、CalibFile v1） | windows-post-pipeline 校准控制 + app.js UI/持久化 + JSON 导出 | ✅ |
| 附录 K.4 EMA 旁路双口径（setSmoothingEnabled(false)/setDetectionFilterType(NONE)/nativeSetHeadPoseSmoothingEnabled(false)+resetSmoothing()） | PipelineFaceTracker 模式切换三连调用 | ✅ |
| 附录 K.5 校准归属（累计器内置 BlendShapeExtractor、ExpressionCalibrator=窗口观测+serialize/load+reset、setCalibrationPose 时序） | expression-calibrator.js / pose-calibrator.js | ✅ |
| 裁决 3/4/5（500ms 唯一延迟 + jitterBuffer 最小口径、无 SAB 用 Transferable、worker 异常回退同步） | PlayoutBuffer + worker/post-pipeline-worker.js + 同步回退 | ✅ |

---

## 4. 三件套证据与基准性质说明

| 证据 | 状态 |
|---|---|
| ① 双独立转录对拍（win-postproc golden：JS 参考 vs JS 被测） | ✅ 已有：14 场景 + 4 模块，max 2.38e-7，抓到 3 处真问题 |
| ② 真·Kotlin 对拍（android-raw GoldenParityTest：Kotlin 原实现 vs golden-export） | ⏳ **待 android-ci 实跑确认**（修复已提交，见 §6） |
| ③ android-ci 绿（构建 + JVM 测试）/ windows-client-ci 打包 exe smoke ready | ⏳ **待 push 后验证**（见 §6） |

**基准性质说明（防误读，必须随结论引用）**：①的「黄金基准」是照 Kotlin 源码逐条抄的
**第二份独立转录**，不是 Kotlin 运行输出 —— 它能抓抄写/分支/阈值类笔误，**不能**证明与
Kotlin 逐位一致；该缺口由②（真·Kotlin 输出对拍，容差 abs 1e-4 / rel 1e-4，Lead 终裁）补上。
两者的覆盖范围也不同：golden 主层 **14 场景**，golden-export 对拍集 **12 场景 × 40 帧**。
差异排障顺序：meta.jsInternalReferenceAgreement → 画幅/时间戳口径 → 才怀疑实现。

---

## 5. 观察项与已知边界清单

**观察项（保真复刻，不修；修正另立任务）**：

1. FaceCapturePipeline 第 2/3 步单位不一致（extractKeyPoints 像素转换 vs avgLandmarks
   未转换）——规格 §7.6.1，Windows 按源码复刻同一不一致。
2. EAR/MAR 画幅依赖（extractKeyPoints 各向异性转换 → 比值特征带 H/W 因子；默认阈值
   0.10/0.18/0.26 仅对特定画幅成立；自校准自适应）——规格 §7.6.5，golden 参考/被测同画幅。
3. Float32 vs double 残余 ~2e-6（容差已覆盖）。
4. 眨眼状态机四处「与注释不符、按代码」（transitionTo 清零 / 眯眼整曲线×0.5 /
   空历史 fallback 两口径 / SQUINT_HOLD 不参与同步）——附录 B 已按代码记录。
5. Android 原生 0.08 / 0.12 注释与实参不一致（不修，无行为变更原则）；线上实跑 0.12、
   头姿 0.3。

**已知边界（有意为之）**：

1. legacy 链为手机 UI 保留（旁路精确条件 = ∃RAW 推流 ∧ ∄TEXT 客户端；wire 无影响）。
2. BodyFusion baseline load：CalibFile.body.torsoBaseline 保存但注入走冷启动重调，
   loadBaseline?.() 挂点保留（d6db95b 已补载入 API，验收以实现为准）。
3. 12 步链的步骤 10（参数映射）留主线程（mapper 单例），链序语义不变。
4. 本地无法跑 Electron --smoke（沙箱禁命名管道，Chromium 起不来）——以 CI 打包 exe 的
   smoke-result.json 为准。

---

## 6. CI 待 push 后验证（push 落地即按此补结论）

| 门槛 | 验证命令 / 入口 | 判定标准 |
|---|---|---|
| android-ci 构建 + JVM 测试 | push 后看 android-ci.yml 两个 job | 全绿；debug 腿含 :app:testDebugUnitTest（**已修复**：此前只跑 assemble*，GoldenParityTest/TrackingProtocolTest/SendQueue/FrameBuffer 会被静默跳过，验收发现后由 Lead 修复） |
| android-ci 首跑失败根因（已修复，待重跑确认） | 0685497 / b7b17e7 | ① app/build.gradle 的 `isReturnDefaultValues` Groovy DSL 属性名错误（配置阶段挂两腿）→ 改 `unitTests.returnDefaultValues`；② TrackingServer.kt:913 `recomputeUpperBodyPostV1(),` **语句位尾逗号**（Kotlin 语法错误，整个文件编译失败、assemble 两腿全挂）→ 删尾逗号。android-raw 已做 17 文件全量编译级核对（JNI 签名、数据类命名实参、成员存在性、尾逗号全仓扫描仅此一处），无其他问题 |
| 真·Kotlin 对拍（三件套②） | 同上，GoldenParityTest 12 场景 | 全绿；容差 max(1e-4, 1e-4×|b|)；失败信息自带 jsInternalReferenceAgreement/t 序列（按 §4 排障顺序判读） |
| 打包 exe smoke ready（三件套③） | windows-client-ci.yml「Smoke test packaged app」 | smoke-result.json 存在且 ready=true（含 --smoke 启动 + 渲染就绪 + selftest 步骤） |
| v1 文本路径回归 | windows-client-ci 的 npm run selftest | 108/108（本地已验，CI 复核） |

---

## 7. 遗留问题清单（给 Lead）

| # | 问题 | 状态 / 建议 |
|---|------|------------|
| 1 | android-ci 无 JVM 测试任务（golden 对拍会被跳过） | **已修复、待 CI 实跑确认**（Lead 兜底改 workflow，debug 腿加 :app:testDebugUnitTest） |
| 2 | 真·Kotlin 对拍（GoldenParityTest）尚未经 CI 实跑 | 随 #1 一起判定 |
| 3 | 打包 exe smoke 未经本地跑（沙箱限制） | CI 补证；本地限制已如实报备 |
| 4 | 观察项 1/2 的行为修正 | 建议另立任务裁决（本次不修，见 §5） |
| 5 | BodyFusion baseline load 注入路径 | loadBaseline API 已补（d6db95b）；如需 CalibFile 注入即生效可后续增强 |
| 6 | android-raw JVM 测试本机无 JDK 未跑 | 与 #1/#2 同一出口，CI 判定 |
| 7 | 首跑 CI 两处构建失败（Gradle DSL 属性名 / 语句位尾逗号） | **已修复**（0685497 / b7b17e7），待重跑 CI 确认。验收教训：无 JDK 环境的静态自查（符号存在性 + 括号平衡）抓不到语句位尾逗号这类纯语法笔误，编译级核对必须由 CI 承担。补救（android-raw）：无 JDK 自查可加「语句位尾逗号」正则扫描（形如 ^\s*[\w\.]+\(\),\s*$，需人工排除注释/实参列表内合法用例）作常规项，**最终判据仍是 CI 编译** |

**提交清单（验收范围）**：f19c2aa / a96dba5 / 96d2aba / 489272b / c5fccee / 42ec34c（规格）
· 464acfe / 55d1b55 / 503f549 / d6db95b（win-postproc）
· 1d3aafd / 3c85a3f / aaf0034（android-raw）
· 81cc829（win-integrate）
· 85e4a16（android-ci.yml JVM 测试门槛，Lead）· 0685497（Gradle DSL 属性修复）· b7b17e7（语句位尾逗号修复）。

> 注：首跑 CI 曾因上述两处构建失败全挂（见 §6 / §7 #7），修复后重跑结论以 Lead 转发为准。
