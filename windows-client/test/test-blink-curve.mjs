import {
  BlinkCurveProcessor, SingleEyeProcessor, EyeState,
  closingCurve, openingCurve, isBlinkState,
  DEFAULT_CLOSING_DURATION_MS, DEFAULT_CLOSED_DURATION_MS, DEFAULT_OPENING_DURATION_MS,
  SQUINT_BLINK_VALUE, SYNC_WINDOW_MS,
} from "../renderer/blink-curve.js";

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function assertClose(a, b, eps, msg) {
  if (!(Math.abs(a - b) <= eps)) throw new Error(`${msg}: ${a} != ${b} (eps=${eps})`);
}

export const tests = [
  {
    name: "曲线端点与中点：closingCurve/openingCurve 与 Kotlin 公式一致",
    fn() {
      assertClose(closingCurve(0), 0, 1e-12, "closing(0)");
      assertClose(closingCurve(1), 1, 1e-12, "closing(1)");
      assertClose(closingCurve(0.5), 0.5, 1e-12, "closing(0.5)");
      assertClose(closingCurve(0.25), 0.1464466094, 1e-9, "closing(0.25)");
      assertClose(closingCurve(0.75), 0.8535533906, 1e-9, "closing(0.75)");
      assertClose(openingCurve(0), 1, 1e-12, "opening(0)");
      assertClose(openingCurve(1), 0, 1e-12, "opening(1)");
      assertClose(openingCurve(0.5), 0.5, 1e-12, "opening(0.5)");
      // 越界输入要 clamp（Kotlin coerceIn(0,1)）
      assertClose(closingCurve(-0.5), 0, 1e-12, "closing(-0.5)");
      assertClose(closingCurve(1.5), 1, 1e-12, "closing(1.5)");
      // 对称性：opening(t) = 1 - closing(t)
      for (const t of [0.1, 0.3, 0.7, 0.9]) {
        assertClose(openingCurve(t), 1 - closingCurve(t), 1e-12, `sym@${t}`);
      }
    },
  },
  {
    name: "isBlinkState 不含 SQUINT_HOLD（眯眼不同步）【按代码】",
    fn() {
      assert(isBlinkState(EyeState.CLOSING), "CLOSING");
      assert(isBlinkState(EyeState.BLINK_CLOSED), "BLINK_CLOSED");
      assert(isBlinkState(EyeState.BLINK_OPENING), "BLINK_OPENING");
      assert(!isBlinkState(EyeState.SQUINT_HOLD), "SQUINT_HOLD 必须不算眨眼态");
      assert(!isBlinkState(EyeState.TRACKING), "TRACKING");
      assert(!isBlinkState(EyeState.BLINK_DETECT), "BLINK_DETECT");
    },
  },
  {
    name: "blinkSpeedScale 的 coerceIn(0.3, 5.0) 与双眼同步",
    fn() {
      const p = new BlinkCurveProcessor();
      p.blinkSpeedScale = 0.01;
      assertClose(p.blinkSpeedScale, 0.3, 1e-12, "下限");
      p.blinkSpeedScale = 99;
      assertClose(p.blinkSpeedScale, 5, 1e-12, "上限");
      p.blinkSpeedScale = 2;
      assertClose(p.leftEye._blinkSpeedScale, 2, 1e-12, "左眼同步");
      assertClose(p.rightEye._blinkSpeedScale, 2, 1e-12, "右眼同步");
    },
  },
  {
    name: "isEnabled = true 会 reset（Kotlin setter 语义）",
    fn() {
      const p = new BlinkCurveProcessor();
      p.leftEye.state = EyeState.CLOSING;
      p.leftEye.curveProgress = 0.7;
      p.isEnabled = true;
      assert(p.leftEye.state === EyeState.TRACKING, "状态应被 reset");
      assertClose(p.leftEye.curveProgress, 0, 1e-12, "进度应被 reset");
    },
  },
  {
    name: "isEnabled = false 时原样返回原始 blink",
    fn() {
      const p = new BlinkCurveProcessor({ isEnabled: false });
      const [l, r] = p.process(0.37, 0.82, 1000);
      assertClose(l, 0.37, 0, "左眼透传");
      assertClose(r, 0.82, 0, "右眼透传");
    },
  },
  {
    name: "TRACKING 输出恒 0；BLINK_DETECT 输出恒 0；BLINK_CLOSED 恒 1；SQUINT_HOLD 恒 0.5",
    fn() {
      const e = new SingleEyeProcessor("T");
      assertClose(e.process(0, 0), 0, 1e-12, "TRACKING");
      e.state = EyeState.BLINK_DETECT;
      assertClose(e.process(0, 100), 0, 1e-12, "BLINK_DETECT");
      e.state = EyeState.BLINK_CLOSED;
      assertClose(e.process(1, 200), 1, 1e-12, "BLINK_CLOSED");
      e.state = EyeState.SQUINT_HOLD;
      assertClose(e.process(0.5, 300), SQUINT_BLINK_VALUE, 1e-12, "SQUINT_HOLD");
    },
  },
  {
    name: "完整眨眼：TRACKING -> BLINK_DETECT -> CLOSING -> BLINK_CLOSED -> BLINK_OPENING -> TRACKING",
    fn() {
      const e = new SingleEyeProcessor("B");
      e.updateFrameRate(30);
      let t = 0;
      // 先让 baseline 收敛到高位（earProxy = 1，即完全睁眼）
      for (let i = 0; i < 200; i++) { t += 33; e.process(0, t); }
      assert(e.state === EyeState.TRACKING, `预热后应在 TRACKING，实际 ${e.state}`);
      const baseline = e.baselineEAR;
      assert(baseline > 0.9, `baseline 应收敛到高位，实际 ${baseline}`);

      // 骤降 -> BLINK_DETECT
      t += 33;
      e.process(1, t);
      assert(e.state === EyeState.BLINK_DETECT, `应进 BLINK_DETECT，实际 ${e.state}`);
      // 继续低位 -> 总下降量超阈值 -> CLOSING
      t += 33;
      e.process(1, t);
      assert(e.state === EyeState.CLOSING, `应进 CLOSING，实际 ${e.state}`);

      // 播放闭眼曲线 80ms
      for (let i = 0; i < 5; i++) { t += 33; e.process(1, t); }
      assert(e.state === EyeState.BLINK_CLOSED, `应进 BLINK_CLOSED，实际 ${e.state}`);
      assertClose(e.curveProgress, 0, 1e-12, "进 BLINK_CLOSED 后进度归零");

      // 30ms 后若 EAR 回升 -> BLINK_OPENING
      for (let i = 0; i < 3; i++) { t += 33; e.process(0, t); }
      assert(e.state === EyeState.BLINK_OPENING, `应进 BLINK_OPENING，实际 ${e.state}`);

      // 120ms 睁眼曲线后回 TRACKING
      for (let i = 0; i < 6; i++) { t += 33; e.process(0, t); }
      assert(e.state === EyeState.TRACKING, `应回 TRACKING，实际 ${e.state}`);
    },
  },
  {
    name: "【按代码】眯眼 CLOSING 是整条曲线 × 0.5，不是半程停",
    fn() {
      const e = new SingleEyeProcessor("S");
      e.updateFrameRate(30);
      e._isBlinkNotSquint = false;
      e.state = EyeState.CLOSING;
      e.curveProgress = 0;
      // 逐帧推进，观察输出上限应趋近 0.5 而不是 1
      let t = 0;
      let maxOut = 0;
      for (let i = 0; i < 6; i++) {
        t += 33;
        const v = e.process(0.5, t);
        if (v > maxOut) maxOut = v;
      }
      assert(maxOut <= 0.5 + 1e-6, `眯眼输出不得超过 0.5，实际 ${maxOut}`);
      assert(maxOut > 0.4, `眯眼输出应接近 0.5（铺满整条曲线），实际 ${maxOut}`);
    },
  },
  {
    name: "【按代码】transitionTo 除进入 SQUINT_HOLD 外一律清零 curveProgress",
    fn() {
      const e = new SingleEyeProcessor("T2");
      e.curveProgress = 0.42;
      e._transitionTo(EyeState.CLOSING, 100);
      assertClose(e.curveProgress, 0, 1e-12, "进 CLOSING 应清零");

      e.curveProgress = 0.42;
      e._transitionTo(EyeState.SQUINT_HOLD, 200);
      assertClose(e.curveProgress, 0.42, 1e-12, "进 SQUINT_HOLD 不清零");

      e.curveProgress = 0.42;
      e._transitionTo(EyeState.BLINK_OPENING, 300);
      assertClose(e.curveProgress, 0, 1e-12, "进 BLINK_OPENING 也清零");
    },
  },
  {
    name: "【按代码】SQUINT_HOLD -> BLINK_OPENING 后调用方把进度设为 0.5",
    fn() {
      const e = new SingleEyeProcessor("S2");
      e.updateFrameRate(30);
      // 基线收敛
      let t = 0;
      for (let i = 0; i < 200; i++) { t += 33; e.process(0, t); }
      e.state = EyeState.SQUINT_HOLD;
      e.curveProgress = 0.9;
      t += 33;
      e.process(0, t); // earProxy=1 > baseline*0.8 -> 转 BLINK_OPENING 并设 0.5
      assert(e.state === EyeState.BLINK_OPENING, `应转 BLINK_OPENING，实际 ${e.state}`);
      assertClose(e.curveProgress, 0.5, 1e-12, "进度应被调用方设为 0.5");
    },
  },
  {
    name: "deltaMs 裁剪到 [1,100]（Kotlin coerceIn）",
    fn() {
      const e = new SingleEyeProcessor("D");
      e.updateFrameRate(30);
      let t = 0;
      e.process(0, t);
      // 间隔 5000ms -> 应按 100ms 推进
      t = 5000;
      e.process(0, t);
      // 间隔 -100ms（时间戳回退）-> 应按 1ms 推进
      e.process(0, t - 100);
      // 不抛异常即通过；再验证动态帧数被 clamp 到 [1,5]
      e.updateFrameRate(1000);
      assert(e._getDynamicConfirmFrames() >= 1 && e._getDynamicConfirmFrames() <= 5, "confirmFrames 范围");
    },
  },
  {
    name: "双眼同步：两眼都在眨眼态且时间接近 -> 取 max",
    fn() {
      const p = new BlinkCurveProcessor();
      p.leftEye.state = EyeState.CLOSING;
      p.rightEye.state = EyeState.BLINK_CLOSED;
      p.leftEye.stateStartTimeMs = 1000;
      p.rightEye.stateStartTimeMs = 1010;
      const out = p._synchronizeBlink(0.3, 0.8, 1100);
      assertClose(out[0], 0.8, 1e-12, "取 max");
      assertClose(out[1], 0.8, 1e-12, "取 max");
    },
  },
  {
    name: "双眼同步：时间窗超过 SYNC_WINDOW_MS*3 -> 不同步",
    fn() {
      const p = new BlinkCurveProcessor();
      p.leftEye.state = EyeState.CLOSING;
      p.rightEye.state = EyeState.BLINK_CLOSED;
      p.leftEye.stateStartTimeMs = 1000;
      p.rightEye.stateStartTimeMs = 1000 + SYNC_WINDOW_MS * 3 + 1;
      const out = p._synchronizeBlink(0.3, 0.8, 1200);
      assertClose(out[0], 0.3, 1e-12, "左眼保持");
      assertClose(out[1], 0.8, 1e-12, "右眼保持");
    },
  },
  {
    name: "双眼同步：SQUINT_HOLD 不参与（即使另一只眼在 CLOSING）",
    fn() {
      const p = new BlinkCurveProcessor();
      p.leftEye.state = EyeState.SQUINT_HOLD;
      p.rightEye.state = EyeState.CLOSING;
      p.leftEye.stateStartTimeMs = 1000;
      p.rightEye.stateStartTimeMs = 1000;
      const out = p._synchronizeBlink(0.5, 0.9, 1100);
      assertClose(out[0], 0.5, 1e-12, "眯眼不被拉齐");
      assertClose(out[1], 0.9, 1e-12, "另一眼不变");
    },
  },
  {
    name: "processInto 就地写 bs52[9]/[10]",
    fn() {
      const p = new BlinkCurveProcessor({ isEnabled: false });
      const bs = new Float32Array(52);
      bs[9] = 0.25;
      bs[10] = 0.75;
      p.processInto(bs, 1000);
      assertClose(bs[9], 0.25, 1e-6, "bs[9]");
      assertClose(bs[10], 0.75, 1e-6, "bs[10]");
    },
  },
  {
    name: "时长常量与 Kotlin 一致（80 / 30 / 120ms）",
    fn() {
      assertClose(DEFAULT_CLOSING_DURATION_MS, 80, 0, "CLOSING");
      assertClose(DEFAULT_CLOSED_DURATION_MS, 30, 0, "CLOSED");
      assertClose(DEFAULT_OPENING_DURATION_MS, 120, 0, "OPENING");
    },
  },
];
