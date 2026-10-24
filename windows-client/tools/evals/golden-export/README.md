# golden-export —— 跨端黄金对拍数据集

给 **Kotlin/JVM 侧**（android-raw，task-5）对拍用：用这些输入驱动 Kotlin 的
BlendShapeExtractor / BlinkCurveProcessor，逐帧比对 expected，堵住
「JS 参考实现只是第二份转录」的缺口——CI 里跑出来的是**真·Kotlin 输出**。

## 生成命令

    node windows-client/tools/evals/golden-export/export-golden.mjs
    node windows-client/tools/evals/golden-export/make-readme.mjs

生成器是确定性的：同样的源码版本重跑会得到同样的内容与 SHA-256。
改动 `renderer/` 里的实现后**必须重跑并更新本 README 的哈希表**。

## 校验哈希（SHA-256）

拿走数据前请先核对，防止拷贝 / 换行 / 编码损坏：

| 文件 | 帧数 | 字节 | sha256 |
| --- | ---: | ---: | --- |
| neutral.json | 40 | 316700 | `2fef8cbb0ddada27ca0338a659eb388a1634840135840452df4be963b51656b0` |
| blink.json | 40 | 316698 | `ceb9e7e14e7385d148dcaeddd49bd6ad78fb4d944fa12c045da75b26cb410d38` |
| squint.json | 40 | 316699 | `3ffa4def8078c01a2810fb2c25c84900fa8fea6f9de75d6642f52e4ca1777b10` |
| mouthOpen.json | 40 | 316862 | `6c6b17fe5c3089669c637a606a11c1e22a03580fe00f883066e226ddd750b609` |
| smile.json | 40 | 317098 | `10676e7b24f768c38cb0dc6b193bd3222e8f38be01ec28593195b2a836d637b1` |
| frown.json | 40 | 317098 | `f5b6fb2075168fd11834c5764dc36a9d76e35a39094c2ea2ccdb089e57dac119` |
| yawLeft.json | 40 | 316740 | `db80388e8398db42a348dc5212be5a0341503e6fd145e8ab1d38150ee475c9aa` |
| yawRight.json | 40 | 316781 | `b9913bd078350b77cdefa30fa36e6b79cb9d6788e8866d78265d442e2e344807` |
| pitchDown.json | 40 | 316742 | `2e8568712c7e6c6dcd65b96e515d48c96522eabb31625d1521d1f6bfe2682c06` |
| pitchUp.json | 40 | 316780 | `4e8ffbd4dd39d8102483709f7071936918c94eba397653666aedf0395a167957` |
| rollTilt.json | 40 | 316741 | `7526907f856499f6f7e655c037f33eecb12e1dc1f65348eae4d039694a33199d` |
| longDt.json | 40 | 316584 | `5073836adfa48179c719747e28ff68e5db79d3064697f8b9d020eee8ec1a53e3` |
| manifest.json | - | 2808 | `83158bde22d12e0f8a2010c1273a5ccb74ee8034e5f8a1114c19c7ec8dcb4aa7` |

整目录校验（Node，跨平台）：

    node windows-client/tools/evals/golden-export/verify.mjs

## JSON 格式

| 层级 | 字段 | 类型 | 含义 |
| --- | --- | --- | --- |
| 根 | scenario | string | 场景名 |
| 根 | width / height | number | 画幅，固定 1280 / 720 |
| 根 | frames | array | 逐帧数据，见下 |
| 根 | meta | object | 口径、容差、下标序、编码、审计 |
| frames[i] | t | number | 帧采集时间戳（ms），同时用作 nowMs 与 blink 时间戳 |
| frames[i] | landmarks | string | base64(Float32Array little-endian)，478*3 = 1434 个浮点，x,y,z 交错 |
| frames[i] | poseDeg | number[3] | 原始头姿 [pitch, yaw, roll]（度，未减基线） |
| frames[i] | expectedBs52 | number[52] | extractFullBlendShapes 输出，含 blink 后的 [9]/[10] |
| frames[i] | expectedBlinkOut | number[2] | BlinkCurveProcessor 输出 [left, right] |
| meta | advancedMode | bool | 恒 false（第一阶段） |
| meta | tolerance | object | abs 1e-4 / rel 1e-4（跨端，Lead 终裁） |
| meta | dimOrder | string[52] | BS_INDEX 的 wire 顺序 |
| meta | landmarkEncoding | object | 编码与 JVM 解码方式 |
| meta | timing | object | nowMs / blink 时间戳的来源约定 |
| meta | calibration | object | setCalibrationPoseCalled = false |
| meta | jsInternalReferenceAgreement | number | JS 两份转录的最大差异（审计用） |

### landmarks 解码（JVM）

    ByteBuffer buf = ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN);
    FloatBuffer fb = buf.asFloatBuffer();   // 长度 478*3 = 1434，x,y,z 交错

归一化 [0,1]、y 向下、z 相对深度。**不要**在 JVM 侧重新生成几何——那是第三份转录。

### expectedBs52 的语义

- 顺序 = BS_INDEX（与 windows `renderer/mapping.js` 的 BS_INDEX 逐项相同）；
- `advancedMode = false`：52 维里只有 17 维可能非零，其余恒 0；
- `expectedBs52[9]` / `[10]` 已经是**眨眼曲线之后**的值（不是原始 eyeBlink）；
- `expectedBlinkOut` 与 `[9]/[10]` 相同，单列一份便于直接对拍状态机。

52 维下标顺序（wire order）：

    0=neutral, 1=browDownLeft, 2=browDownRight, 3=browInnerUp, 4=browOuterUpLeft, 5=browOuterUpRight, 6=cheekPuff, 7=cheekSquintLeft, 8=cheekSquintRight, 9=eyeBlinkLeft, 10=eyeBlinkRight, 11=eyeLookDownLeft, 12=eyeLookDownRight, 13=eyeLookInLeft, 14=eyeLookInRight, 15=eyeLookOutLeft, 16=eyeLookOutRight, 17=eyeLookUpLeft, 18=eyeLookUpRight, 19=eyeSquintLeft, 20=eyeSquintRight, 21=eyeWideLeft, 22=eyeWideRight, 23=jawForward, 24=jawLeft, 25=jawOpen, 26=jawRight, 27=mouthClose, 28=mouthDimpleLeft, 29=mouthDimpleRight, 30=mouthFrownLeft, 31=mouthFrownRight, 32=mouthFunnel, 33=mouthLeft, 34=mouthLowerDownLeft, 35=mouthLowerDownRight, 36=mouthPressLeft, 37=mouthPressRight, 38=mouthPucker, 39=mouthRight, 40=mouthRollLower, 41=mouthRollUpper, 42=mouthShrugLower, 43=mouthShrugUpper, 44=mouthSmileLeft, 45=mouthSmileRight, 46=mouthStretchLeft, 47=mouthStretchRight, 48=mouthUpperUpLeft, 49=mouthUpperUpRight, 50=noseSneerLeft, 51=noseSneerRight

## ⚠️ 必须遵守的口径

| 项 | 值 | 为什么 |
| --- | --- | --- |
| 画幅 | 1280 x 720 | 像素转换是**各向异性**的（x*W / y*H），EAR/MAR 会带 H/W 因子；换画幅会大面积假阳性 |
| advancedMode | false | Android 当前实际行为（`new BlendShapeExtractor()`） |
| setCalibrationPose | **不调用** | 校准姿态保持各实现默认 0 |
| nowMs | 用 `t` | fixture 约定；生产口径是 performance.now() |
| blink 时间戳 | 用 `t` | 与生产口径一致 |
| 起始帧率 | 30 fps（longDt 场景 3.333） | 眨眼确认帧数 `floor(2*33.3/interval)` 依赖它 |

## 容差

- **Kotlin ↔ JS 跨端**：abs 1e-4 / rel 1e-4（**Lead 终裁**）。断言写 `|a-b| <= max(1e-4, 1e-4*|b|)`；
- **JS ↔ JS 内部**：abs 1e-5 / rel 1e-4（见 `tools/evals/golden-run.mjs`）。

## 比对口径：请拆成两条统计

1. **原始提取逐维误差**（blink 之前）—— 比对 `BlendShapeExtractor.extractFullBlendShapes` 的原始输出；
2. **眨眼状态机输出误差** —— 比对 `BlinkCurveProcessor.process` 的输出。

别合成一条：眨眼曲线在 TRACKING 态恒输出 0，会把提取差异盖住（这个坑我踩过）。
为此 `expectedBs52` 里的 9/10 是 blink 后的值；若要单独比对原始 eyeBlink，
请改用 `export-golden.mjs` 里的 `bs52Raw` 路径，或参考 `tools/evals/golden-run.mjs` 的写法。

## 合成 fixture 的几何（便于 JVM 侧自检解码）

| 量 | 值 |
| --- | --- |
| faceWidth = dist(33,263) | 0.28 |
| faceHeight = dist(168,152) | 0.30 |
| EAR(睁眼) | 0.18 |
| EAR(闭眼) | 0.10 |
| MAR(闭嘴) | 0.10 |
| MAR(张大) | 0.45 |

生成器：`windows-client/tools/evals/golden-fixtures.mjs`。JVM 侧**不必**重算这些，
但要自检 base64 解码是否正确，这几条是最方便的探针。

## 场景清单

- `neutral.json` — neutral
- `blink.json` — blink
- `squint.json` — squint
- `mouthOpen.json` — mouthOpen
- `smile.json` — smile
- `frown.json` — frown
- `yawLeft.json` — yawLeft
- `yawRight.json` — yawRight
- `pitchDown.json` — pitchDown
- `pitchUp.json` — pitchUp
- `rollTilt.json` — rollTilt
- `longDt.json` — longDt

## 审计：JS 两份转录的一致性

每个 fixture 的 `meta.jsInternalReferenceAgreement` 是**JS 优化实现**与
**JS 直译参考实现**（`golden-reference.mjs`）在整个场景上的最大逐维差异，
当前全部为 **2.38e-7**，远在 1e-5 内。这个字段不是跨端指标，只是「基准本身可信」的旁证。
