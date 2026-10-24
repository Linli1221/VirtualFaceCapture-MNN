# golden/ —— 跨端黄金对拍 fixture（JVM 测试只读副本）

本目录是 **拷贝副本**，测试只读这里，不直接读 windows-client/ 下的原件（两侧 write scope 不越界）。

- **来源**：`windows-client/tools/evals/golden-export/`（win-postproc 导出；生成器 export-golden.mjs，确定性）
- **来源 commit**：`d6db95b`（fix(tracking): golden-export 改整数毫秒时间线 + BodyFusion 载入基线 API）
- **拷贝核验**：Get-FileHash(SHA-256) 12/12 与上游 README 哈希表一致（2026-10-24）
- **上游快照**：`README-upstream.md`（生成口径原文，含完整哈希表与比对口径）

## 口径要点（详见 README-upstream.md 与各 fixture 的 meta）

- 画幅 **1280x720 不可换**（EAR/MAR 带 H/W 因子，换画幅大面积假阳性）；
- advancedMode=false、**不调用 setCalibrationPose**；
- 时间戳为**整数毫秒 t**，nowMs 与 blink 时间戳同用 —— JS 与 Kotlin 消费同一时间线
  （Kotlin BlinkCurveProcessor 只收 Long ms，整数 t 是跨端对拍的必要口径）；
- 起始帧率 30（longDt 场景也不改 updateFrameRate）；
- 对拍容差（Lead 终裁）：|a-b| <= max(1e-4, 1e-4*|b|)；
- 比对拆两条：原始提取逐维（排除 9/10）/ 眨眼状态机输出；
- expectedBs52 的 [9]/[10] 是眨眼曲线**之后**的值，与 expectedBlinkOut 相同。

## SHA-256（拷贝时实测）

| 文件 | 字节 | sha256 |
| --- | ---: | --- |
| neutral.json | 316567 | ffc0fa80d5abe9635338f5705ec0e7d9d836fb6c78237acf4dac9ac0ffb72586 |
| blink.json | 316565 | bb5528c1d0e11dcfc9a22d8590b0fd03c6a5679249baf47e5b3e60f71a7d4911 |
| squint.json | 316566 | eea6cb1272a0e105bde5aad23db2c53b7fe2099078df1be85d2d285f3afbadbf |
| mouthOpen.json | 316729 | ac04e39c5d5022e3f90761d97346cc21555b99006504993d4b9b5357cdda5a40 |
| smile.json | 316965 | f7dbc75ac6129a87ee336bb696180bff12139cc570f63358ff72e9a882985603 |
| frown.json | 316965 | daff4ba0363dbc571d71496119447e4ae04661003a9862f2117fbc69adaa70b2 |
| yawLeft.json | 316607 | 1b7dad209cf94bfa98c91c15a39df65ae023a98a648bf65e7c521b1f9d3cf2df |
| yawRight.json | 316648 | 6b92314face37756da01ae5ec2b65defb3e9e029a7917e934d59e75a3951cb16 |
| pitchDown.json | 316609 | 8daff93e4c4369b52c63f21ab3dd9c0a59daf2f5f8ac65f909b5341f756eb95b |
| pitchUp.json | 316647 | d52287e3076febce70feb15e63876450f194000266265520122833663aae4ed4 |
| rollTilt.json | 316608 | 977bfc0bda06e4aa16a524982ff0b9d56f73585dc150374702ae7d0cca4652ee |
| longDt.json | 316392 | 929859dd79ac68c477dba410eea6f70f9fa1239124335ae3b0560cf5d11e92d1 |
| manifest.json | 3444 | 55dfc47d41390d951631f1d99f79d695134aff8de5d76094f2986d132fa7b774 |

## 消费方

`app/src/test/java/com/example/virtualfacecapture/golden/GoldenParityTest.kt`：

1. 解码 base64(Float32LE) landmarks（478*3 交错，不重算几何——那是第三份转录）；
2. FaceLandmarkIndices.extractKeyPoints(1280, 720)（归一化→像素，各向异性 x*W/y*H）；
3. BlendShapeExtractor.extractFullBlendShapes(keyPoints, landmarks, rawPose) 逐维对拍 expectedBs52
   （排除 9/10）；
4. 原始 eyeBlink 进 BlinkCurveProcessor.process(t) 对拍 expectedBlinkOut；
5. 自校准窗口（count>=10 且 elapsed>=1000ms）由 fixture t 驱动 —— JVM 无 uptimeMillis 走时，
   测试每帧把 startTimestampMs 设为 -(t_i - t_0)，与 JS ctx.nowMs 语义逐帧同序。
