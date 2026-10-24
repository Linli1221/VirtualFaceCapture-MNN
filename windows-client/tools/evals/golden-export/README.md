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
| neutral.json | 40 | 316567 | `ffc0fa80d5abe9635338f5705ec0e7d9d836fb6c78237acf4dac9ac0ffb72586` |
| blink.json | 40 | 316565 | `bb5528c1d0e11dcfc9a22d8590b0fd03c6a5679249baf47e5b3e60f71a7d4911` |
| squint.json | 40 | 316566 | `eea6cb1272a0e105bde5aad23db2c53b7fe2099078df1be85d2d285f3afbadbf` |
| mouthOpen.json | 40 | 316729 | `ac04e39c5d5022e3f90761d97346cc21555b99006504993d4b9b5357cdda5a40` |
| smile.json | 40 | 316965 | `f7dbc75ac6129a87ee336bb696180bff12139cc570f63358ff72e9a882985603` |
| frown.json | 40 | 316965 | `daff4ba0363dbc571d71496119447e4ae04661003a9862f2117fbc69adaa70b2` |
| yawLeft.json | 40 | 316607 | `1b7dad209cf94bfa98c91c15a39df65ae023a98a648bf65e7c521b1f9d3cf2df` |
| yawRight.json | 40 | 316648 | `6b92314face37756da01ae5ec2b65defb3e9e029a7917e934d59e75a3951cb16` |
| pitchDown.json | 40 | 316609 | `8daff93e4c4369b52c63f21ab3dd9c0a59daf2f5f8ac65f909b5341f756eb95b` |
| pitchUp.json | 40 | 316647 | `d52287e3076febce70feb15e63876450f194000266265520122833663aae4ed4` |
| rollTilt.json | 40 | 316608 | `977bfc0bda06e4aa16a524982ff0b9d56f73585dc150374702ae7d0cca4652ee` |
| longDt.json | 40 | 316392 | `929859dd79ac68c477dba410eea6f70f9fa1239124335ae3b0560cf5d11e92d1` |
| manifest.json | - | 3444 | `55dfc47d41390d951631f1d99f79d695134aff8de5d76094f2986d132fa7b774` |

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

## ⚠️ 时间戳必须是整数毫秒（跨端对拍的必要口径）

fixture 的 `t` 全部是**整数毫秒**，这不是格式偏好，是硬约束：

- Kotlin 的 `BlinkCurveProcessor.process(currentTimeMs: Long)` 只收 Long；
  若给小数 t（如 33.3333333），JS 侧 delta = 33.3333…、Kotlin 侧 delta = 33/34 交替，
  眨眼曲线 `curveProgress += deltaMs / duration` 会逐步累积出 **~1e-2 量级**偏差，
  是 1e-4 容差的百倍 —— 这不是实现差异，是时间量化差，必须从源头消除。
- 同理，squint 的 300ms 严格比较、自校准的 1000ms 窗口都卡在边界上；
  小数 t 会造成**整帧位移**（实测 longDt 场景 SQUINT_HOLD 提前/推迟一帧，输出差 0.5）。

生成器用 `Math.round(i * dtMs)`（累计后取整，避免逐帧累加漂移），
并在 `export-golden.mjs` 里加了整数断言，非整数直接抛错。

JVM 侧请勿再做 `t.roundToLong()` 之类的二次处理 —— 落盘的就是最终值，
直接用即可，保证两端消费**同一份整数时间线**（delta 序列逐位相同）。

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
