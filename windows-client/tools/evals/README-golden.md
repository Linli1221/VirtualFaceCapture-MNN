# tools/evals —— 黄金对齐评测

```bash
node windows-client/tools/evals/golden-run.mjs
```

跑完会在本目录生成 `golden-report.md`（逐帧、逐维度误差报告），控制台打摘要。
退出码 0 = 全部在容差内；1 = 有超出容差的项。

## 文件

| 文件 | 作用 |
| --- | --- |
| `golden-fixtures.mjs` | 合成 478 点人脸 fixture 生成器（几何可解析，见下） |
| `golden-reference.mjs` | **黄金参考实现**：照 Kotlin 源码逐条抄的直译版 |
| `golden-run.mjs` | 跑两套实现、逐帧逐维比对、出报告 |
| `golden-report.md` | 生成物：误差报告 |

## 基准是什么（重要，别误解）

本机**无 JDK / Android SDK，跑不了 Kotlin**。所以这里的「黄金基准」不是 Kotlin 的
运行输出，而是「照着 Kotlin 源码逐条抄下来的**第二份独立转录**」。

- 参考实现走**完全不同的代码路径**：朴素数组 / 对象、`Math.pow(dx,2)` 而不是 `dx*dx`、
  逐条 when 分支而不是查表、不做过任何 TypedArray 优化。
- 因此「两边一致」= 「两份独立转录一致」，能抓住抄写 / 分支顺序 / 阈值类笔误
  （本评测实际上就抓到过 3 处：校准落地时的姿态记录、动态透视补偿系数、以及
  眨眼曲线覆盖原始信号导致的比对盲区）。
- 它**不能**证明与 Kotlin 逐位一致。Kotlin 全程 Float32、JS 是 double，
  实测残余约 2e-6。

容差：**abs 1e-5 / rel 1e-4**（Lead 裁决），报告里分别统计 max / mean / p95。

## 分层

- **第一阶段（默认）**：`advancedMode = false` —— Android 当前实际行为。
  此时只产出 17 个维度，其余 35 维恒为 0（`FaceCapturePipeline` 用的是
  `new BlendShapeExtractor()`，高级分支要求 `landmarks.size >= 468 && advancedMode`）。
- **第二阶段（未纳入）**：`advancedMode = true` —— 需单独覆盖，另行出报告。

## 合成 fixture 的几何

`golden-fixtures.mjs` 造的点位是**可控合成几何**，每个特征量都能解析算出来：

| 量 | 值 |
| --- | --- |
| faceWidth = dist(33, 263) | 0.28 |
| faceHeight = dist(168, 152) | 0.30 |
| EAR(睁眼) = 0.018 / 0.10 | 0.18 = DEFAULT_EYE_OPEN_RATIO |
| EAR(闭眼) = 0.010 / 0.10 | 0.10 = DEFAULT_EYE_CLOSED_RATIO |
| MAR(闭嘴) = 0.016 / 0.16 | 0.10 = DEFAULT_MOUTH_CLOSED_RATIO |
| MAR(张大) = 0.072 / 0.16 | 0.45 = DEFAULT_MOUTH_OPEN_RATIO |

场景：neutral / blink / squint / mouthOpen / smile / frown / yawLeft / yawRight /
pitchDown / pitchUp / rollTilt / longDt / faceLost / browUp。

## ⚠️ 画幅必须一致

`FaceLandmarkIndices.extractKeyPoints` 的像素转换是**各向异性**的（x 乘 imageWidth、
y 乘 imageHeight），于是 EAR / MAR 这类「垂直/水平」比值会带上 H/W 的宽高比因子。

实测（归一化 EAR = 0.18）：画幅 1280x720 时 EAR 变成 0.18 x 0.5625 = 0.10125，
正好压到默认闭眼阈值 0.10，`eyeBlink` 从 ~0 直接变 0.984。

含义：

1. 这**不是移植错误**，是 Kotlin 的真实行为（按源码复刻、不修）。
2. 默认阈值只对特定画幅成立；自校准会自适应它。
3. **参考实现与被测实现必须喂同一画幅**，否则会大面积对不上。
   `golden-run.mjs` 把画幅写死为 `IMAGE_W x IMAGE_H`（1280x720）。
4. 真实录数据对齐时，画幅要进 fixture / `.vfcraw` 的 metaJson。

## 与 node 单测的关系

`windows-client/test/run-all.mjs` 是**单元测试**（断言具体语义与常量）；
这里是**对齐评测**（两套实现逐帧比对）。两者互补，都应通过。
