# 黄金对齐评测报告

生成时间：2026-10-24T00:58:30.576Z
画幅：1280x720（参考实现与被测实现**同一画幅**，见观察项 5）
容差：abs 0.00001 / rel 0.0001

## 基准的诚实说明

本机无 JDK/Android SDK，跑不了 Kotlin。这里的「黄金基准」= 照 Kotlin 源码逐条抄下来的**第二份独立转录**（golden-reference.mjs，朴素数组/对象、Math.pow 而非 x*x、逐条 when 分支而非查表），与 renderer/ 下的 TypedArray 优化版走完全不同的代码路径。

- 能证明：两份独立转录一致（抓住抄写 / 分支顺序 / 阈值类笔误）。
- 不能证明：与 Kotlin **逐位**一致（Kotlin 全程 Float32，JS 是 double，实测残余约 2e-6）。

## 分层

- 第一阶段（本报告）：`advancedMode = false` —— Android 当前实际行为，仅产出 17 个维度，其余恒 0。
- 第二阶段（未纳入）：`advancedMode = true` —— 需单独覆盖，另行出报告。

## 协议一致性核对

| 项目 | 结论 | 依据 |
| --- | --- | --- |
| 52 维字段序 | ✅ 双端一致 | `blendshape-extractor.js` 的 BS_INDEX 与 `mapping.js` BS_INDEX 逐项相同（test-protocol-consistency.mjs 断言） | 
| 13 点 ORDER | ✅ 双端一致 | `body-fusion.js` / UpperBodyPose.kt 的 ORDER 与 `mapping.js` BODY_LANDMARK_ORDER 相同 | 
| neutral 占位 | ✅ 恒为 0 号位 | BS_INDEX.neutral === 0 | 

## BlendShape + Blink 链路（逐帧、逐维度）

### 场景 neutral（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 blink（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 squint（120 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 mouthOpen（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 smile（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 frown（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 yawLeft（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 yawRight（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 pitchDown（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 pitchUp（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 rollTilt（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 longDt（30 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 faceLost（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 1.788e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 1.788e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

### 场景 browUp（60 帧）

眨眼曲线误差：max=0.000e+0 mean=0.000e+0 p95=0.000e+0

| 维度 | 名称 | max | mean | p95 |
| --- | --- | --- | --- | --- |
| 14 | eyeLookInRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |
| 16 | eyeLookOutRight | 2.384e-7 | 2.384e-7 | 2.384e-7 |

（其余 50 个维度全程误差为 0）

## 时域滤波与上半身

| 模块 | max | mean | p95 |
| --- | --- | --- | --- |
| TimeBasedEMA(tau=0.12) | 0.000e+0 | 0.000e+0 | 0.000e+0 |
| OneEuroFilter(1.2/0.02/1.0) | 0.000e+0 | 0.000e+0 | 0.000e+0 |
| TorsoBaseline.roll(10 帧) | 0.000e+0 | 0.000e+0 | 0.000e+0 |
| TorsoBaseline.yaw(10 帧) | 0.000e+0 | 0.000e+0 | 0.000e+0 |

## 已知偏差观察项（按源码复刻，不修）

1. **像素转换各向异性**：`FaceLandmarkIndices.extractKeyPoints` 是 x 乘 imageWidth、y 乘 imageHeight，于是 EAR / MAR 这类「垂直/水平」比值会带上 H/W 宽高比因子。实测（归一化 EAR=0.18）：1280x720 时 EAR 变成 0.18 x 0.5625 = 0.10125，正好压到默认闭眼阈值 0.10，eyeBlink 从 ~0 变 0.984。含义：**默认阈值只对特定画幅成立**；自校准会自适应它；参考与被测必须用同一画幅（本报告写死 1280x720）。
2. **Float32 残余**：Kotlin 全程 Float，JS 是 double。合成 EAR 恰好等于阈值这类用例会留下约 2e-6 残余，由 abs 1e-5 覆盖。
3. **FaceCapturePipeline 第 2/3 步单位不一致**：若第 2 步做了像素转换，第 3 步校准用的 avgLandmarks 未做转换。按源码复刻保真，单列观察。
4. **眯眼 CLOSING 是整条曲线 x 0.5**（不是半程停）、**BLINK_DETECT/BLINK_CLOSED 的空历史 fallback 分别是 baselineEAR / 0f**、**SQUINT_HOLD 不参与双眼同步** —— 均与 Kotlin 注释不符，按代码移植（见 blink-curve.js 头注）。

## 结论

✅ **全部通过**：14 个场景 + 4 个滤波/基线模块，共 870 帧，逐维度最大误差均在容差内。
