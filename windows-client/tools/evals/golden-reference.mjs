/**
 * golden-reference.mjs — 黄金参考实现
 *
 * ═══════════════════════════════════════════════════════════════════════
 * 这个文件的定位（读之前请先看清楚）：
 *
 * 本机无 JDK/Android SDK，**跑不了 Kotlin**，所以黄金基准不是 Kotlin 的运行输出，
 * 而是「照着 Kotlin 源码逐条抄下来的直译版」。它的价值在于：
 *   - 与 renderer/ 下的优化版走**完全不同的代码路径**（朴素数组/对象、
 *     Math.pow 而不是 x*x、逐条 when 分支而不是查表），
 *   - 任何抄写错误、分支顺序错误、阈值写错都会让两边对不上。
 * 因此「两边一致」=「两份独立转录一致」，能抓住绝大多数移植笔误；
 * 但它**不能**证明与 Kotlin 逐位一致（Kotlin 全程 Float32，JS 是 double）。
 * 实际比对容差：abs 1e-5 / rel 1e-4（见 golden-run.mjs）。
 *
 * 风格约定：这里**故意**不做 TypedArray 优化、**故意**不做零分配，
 * 可读性和「能一眼对照 Kotlin」优先于性能。热路径在 renderer/ 里。
 * ═══════════════════════════════════════════════════════════════════════
 */

const f = (x) => x; // Kotlin Float 语义在 JS 里无法逐位模拟，见文件头说明

const clamp = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);
const clamp01 = (v) => clamp(v, 0, 1);

// Kotlin Point3D.distance2DTo 用 Math.pow(x, 2)；这里照抄（与优化版的 x*x 不同路径）
function d2(lm, ia, ib) {
  const dx = lm[ia * 3] - lm[ib * 3];
  const dy = lm[ia * 3 + 1] - lm[ib * 3 + 1];
  return Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2));
}

const R_UPPER = [246, 161, 160, 159, 158, 157, 173];
const R_LOWER = [7, 163, 144, 145, 153, 154, 155];
const L_UPPER = [466, 388, 387, 386, 385, 384, 398];
const L_LOWER = [249, 390, 373, 374, 380, 381, 382];
const M_IN = [78, 191, 80, 81, 82, 13, 312, 311, 310, 415, 308, 324, 318, 402, 317, 14, 87, 178, 88, 95];
const M_OUT = [61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185];

// ============ 参考版：眨眼状态机（BlinkCurveProcessor.kt 直译）============

const S = { TRACKING: 0, BLINK_DETECT: 1, CLOSING: 2, BLINK_CLOSED: 3, BLINK_OPENING: 4, SQUINT_HOLD: 5 };

function refClosingCurve(t) { const c = clamp(t, 0, 1); return (1 - Math.cos(c * Math.PI)) / 2; }
function refOpeningCurve(t) { const c = clamp(t, 0, 1); return (1 + Math.cos(c * Math.PI)) / 2; }

export class RefEye {
  constructor() { this.reset(); }
  reset() {
    this.state = S.TRACKING;
    this.stateStart = 0;
    this.lastUpdate = 0;
    this.hist = [];           // 朴素数组，容量 5
    this.baseline = 0.18;
    this.dropFrames = 0;
    this.dropStart = 0;
    this.progress = 0;
    this.lowStart = 0;
    this.inLow = false;
    this.isBlink = true;
    this.intervalMs = 33.3;
    this.speed = 1.0;
  }
  setFps(fps) { this.intervalMs = fps > 0 ? 1000 / fps : 33.3; }
  confirmFrames() {
    return clamp(Math.trunc(2 * (33.3 / this.intervalMs)), 1, 5);
  }
  push(v) { this.hist.push(v); if (this.hist.length > 5) this.hist.shift(); }
  last() { return this.hist[this.hist.length - 1]; }
  prev() { return this.hist[this.hist.length - 2]; }
  lastOr(fb) { return this.hist.length ? this.last() : fb; }

  go(st, now) { if (this.state !== st) { this.state = st; this.stateStart = now; if (st !== S.SQUINT_HOLD) this.progress = 0; } }

  process(rawBlink, now) {
    const ear = 1 - rawBlink;
    this.push(ear);
    let dt;
    if (this.lastUpdate > 0) { dt = clamp(now - this.lastUpdate, 1, 100); }
    else { dt = Math.trunc(this.intervalMs); }
    this.lastUpdate = now;

    if (this.state === S.TRACKING) {
      if (ear > this.baseline * 0.85) this.baseline = this.baseline * 0.95 + ear * 0.05;
      if (this.hist.length >= 2) {
        const drop = (this.prev() - this.last()) / this.baseline;
        if (drop > 0.12) { this.dropFrames = 1; this.dropStart = this.prev(); this.go(S.BLINK_DETECT, now); return this.out(); }
      }
      const low = this.baseline * 0.65;
      if (ear < low) {
        if (!this.inLow) { this.inLow = true; this.lowStart = now; }
        if (now - this.lowStart > 300) { this.isBlink = false; this.go(S.CLOSING, now); }
      } else { this.inLow = false; }

    } else if (this.state === S.BLINK_DETECT) {
      const tis = now - this.stateStart;
      if (this.hist.length >= 2) {
        const drop = (this.prev() - this.last()) / this.baseline;
        const total = (this.dropStart - this.last()) / this.baseline;
        if (total > 0.35 * 1.2) { this.isBlink = true; this.go(S.CLOSING, now); return this.out(); }
        if (drop > 0.12 * 0.5) {
          this.dropFrames++;
          if (this.dropFrames >= this.confirmFrames() && total > 0.35) { this.isBlink = true; this.go(S.CLOSING, now); return this.out(); }
        } else if (drop < -0.12 * 0.3 && total < 0.35 * 0.5) {
          this.go(S.TRACKING, now); this.dropFrames = 0; return this.out();
        }
      }
      if (tis > 300) {
        const cur = this.lastOr(this.baseline);
        if (cur < this.baseline * 0.4) { this.isBlink = true; this.go(S.CLOSING, now); }
        else { this.isBlink = false; this.go(S.CLOSING, now); }
      }

    } else if (this.state === S.CLOSING) {
      this.progress += dt / (80 * this.speed);
      if (this.isBlink) {
        if (this.progress >= 1) { this.progress = 0; this.go(S.BLINK_CLOSED, now); }
      } else {
        if (this.progress >= 1) { this.go(S.SQUINT_HOLD, now); }
      }

    } else if (this.state === S.BLINK_CLOSED) {
      this.progress += dt / (30 * this.speed);
      const cur = this.lastOr(0);
      const still = cur < this.baseline * 0.5;
      if (this.progress >= 1) {
        if (still) { this.progress = 0; }
        else { this.progress = 0; this.go(S.BLINK_OPENING, now); }
      }

    } else if (this.state === S.BLINK_OPENING) {
      this.progress += dt / (120 * this.speed);
      if (this.progress >= 1) { this.progress = 0; this.dropFrames = 0; this.go(S.TRACKING, now); }

    } else if (this.state === S.SQUINT_HOLD) {
      if (ear > this.baseline * 0.8) { this.go(S.BLINK_OPENING, now); this.progress = 0.5; this.inLow = false; return this.out(); }
      if (this.hist.length >= 2) {
        const drop = (this.prev() - this.last()) / this.baseline;
        if (drop > 0.12 && this.last() < this.baseline * 0.3) { this.isBlink = true; this.go(S.CLOSING, now); this.progress = 0.5; }
      }
    }
    return this.out();
  }

  out() {
    if (this.state === S.TRACKING || this.state === S.BLINK_DETECT) return 0;
    if (this.state === S.CLOSING) { const c = refClosingCurve(this.progress); return this.isBlink ? c : c * 0.5; }
    if (this.state === S.BLINK_CLOSED) return 1;
    if (this.state === S.BLINK_OPENING) return refOpeningCurve(this.progress);
    return 0.5;
  }
}

export class RefBlink {
  constructor() { this.l = new RefEye(); this.r = new RefEye(); this.sync = true; }
  process(lRaw, rRaw, now) {
    const lv = this.l.process(lRaw, now);
    const rv = this.r.process(rRaw, now);
    if (!this.sync) return [lv, rv];
    const lb = [S.CLOSING, S.BLINK_CLOSED, S.BLINK_OPENING].includes(this.l.state);
    const rb = [S.CLOSING, S.BLINK_CLOSED, S.BLINK_OPENING].includes(this.r.state);
    if (lb && rb) {
      if (Math.abs(this.l.stateStart - this.r.stateStart) <= 50 * 3) { const m = Math.max(lv, rv); return [m, m]; }
    }
    if (this.l.state === S.CLOSING && this.r.state === S.BLINK_DETECT) {
      if (now - this.r.stateStart <= 50 * 2) return [lv, lv];
    }
    if (this.r.state === S.CLOSING && this.l.state === S.BLINK_DETECT) {
      if (now - this.l.stateStart <= 50 * 2) return [rv, rv];
    }
    return [lv, rv];
  }
}

// ============ 参考版：眼 / 嘴 / 微笑提取（advancedMode = false）============

function refEar(lm, upper, lower, inner, outer) {
  let sum = 0;
  for (const u of upper) {
    let best = null;
    for (const l of lower) { const d = d2(lm, u, l); if (best === null || d < best) best = d; }
    sum += best === null ? 0 : best;
  }
  const avg = upper.length ? sum / upper.length : 0;
  const h = d2(lm, inner, outer);
  return h > 1e-6 ? avg / h : 0;
}

function refCalib() {
  return { sum: 0, sq: 0, min: Infinity, max: -Infinity, n: 0, start: 0,
           mean: 0, std: 0, closed: 0.10, open: 0.18, wide: 0.26, ok: false,
           calPitch: 0, calYaw: 0 };
}

function refAdd(c, v, now) {
  if (c.n === 0) c.start = now;
  c.sum += v; c.sq += v * v;
  if (v < c.min) c.min = v;
  if (v > c.max) c.max = v;
  c.n++;
}

function refReady(c, now) { return c.n >= 10 && now - c.start >= 1000; }

function refFinish(c) {
  if (c.n < 10) return;
  c.mean = c.sum / c.n;
  c.std = Math.sqrt(Math.max(0, c.sq / c.n - c.mean * c.mean));
  const range = c.max - c.min;
  const obsOpen = range > 0.02 ? c.max - 0.1 * range : c.mean - c.std;
  const infClosed = (range > 0.03 && c.min < c.mean - 2 * c.std) ? c.min + 0.1 * range : obsOpen * 0.6;
  const open = clamp(obsOpen, 0.12, 0.35);
  const closed = clamp(infClosed, 0.05, open - 0.03);
  c.open = open; c.closed = closed; c.wide = open + (open - closed) * 0.8;
  if (c.open - c.closed < 0.04) { c.closed = 0.10; c.open = 0.18; c.wide = 0.26; }
  c.ok = true;
}

function refEffClosed(c) {
  const base = c.ok ? c.closed : 0.10;
  const baseOpen = c.ok ? c.open : 0.18;
  return clamp(base + (1 - 1) * (baseOpen - base) * 0.3 + 0, 0.05, baseOpen - 0.02);
}
function refEffOpen(c) {
  const baseC = c.ok ? c.closed : 0.10;
  const base = c.ok ? c.open : 0.18;
  const baseWide = c.ok ? c.wide : 0.26;
  return clamp(base - (1 - 1) * (base - baseC) * 0.3 - 0, baseC + 0.02, baseWide - 0.02);
}

export class RefExtractor {
  constructor() {
    this.left = refCalib();
    this.right = refCalib();
    // 动态透视补偿（SimpleEyeExtractor.updateFaceDistanceEstimateNormalized）。
    // enableDynamicCompensation 默认 true，每帧用 478 点 bbox 覆盖 nearMult。
    this.faceRatio = 0.5;
    this.perspectiveStrength = Math.tan((63 / 2) * Math.PI / 180) / Math.tan(30 * Math.PI / 180);
    this.nearMult = 5.0;
    this.mouth = { sum: 0, sq: 0, min: Infinity, max: -Infinity, n: 0, start: 0,
                   mean: 0, std: 0, closed: 0.10, open: 0.45, ok: false };
    this.smile = { sl: 0, sr: 0, sql: 0, sqr: 0, n: 0, start: 0,
                   bl: 0, br: 0, sdl: 0, sdr: 0, sMax: 0.06, fMax: 0.04, ok: false,
                   // SimpleSmileExtractor.kt:329-335 —— 校准落地那一帧记录当时的姿态，
                   // 随后的 pitch/roll 补偿是「相对于校准时刻」的。
                   calPitch: 0, calYaw: 0, calRoll: 0 };
  }

  /** 一帧 -> 52 维（只写 advancedMode=false 会产出的那 17 个下标，其余保持 0）。 */
  extract(raw, out52, pose, ctx) {
    out52.fill(0);
    const now = ctx && ctx.nowMs ? ctx.nowMs : 0;
    const W = ctx && ctx.width ? ctx.width : 0;
    const H = ctx && ctx.height ? ctx.height : 0;

    // 像素转换（与 extractKeyPoints 同规则）
    const lm = new Float32Array(478 * 3);
    if (W > 0 && H > 0 && raw[0] <= 1 && raw[1] <= 1) {
      for (let i = 0; i < 478; i++) {
        lm[i * 3] = raw[i * 3] * W;
        lm[i * 3 + 1] = raw[i * 3 + 1] * H;
        lm[i * 3 + 2] = raw[i * 3 + 2];
      }
    } else { lm.set(raw); }

    const faceHeight = d2(lm, 168, 152);

    // ---- 人脸距离估计（FaceCapturePipeline.updateFaceDistanceFromLandmarks，用**原始**坐标）----
    {
      let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
      for (let i = 0; i < 478; i++) {
        const x = raw[i * 3], y = raw[i * 3 + 1];
        if (x < minX) minX = x;
        if (x > maxX) maxX = x;
        if (y < minY) minY = y;
        if (y > maxY) maxY = y;
      }
      let fw = 0, fh = 0, ok = false;
      if (minX < maxX && minY < maxY) {
        if (maxX <= 1.5 && maxY <= 1.5 && minX >= -0.5 && minY >= -0.5) {
          fw = maxX - minX; fh = maxY - minY; ok = true;
        } else if (W > 0 && H > 0) {
          fw = (maxX - minX) / W; fh = (maxY - minY) / H; ok = true;
        }
      }
      if (ok && fw > 0 && fh > 0 && fw <= 1.5 && fh <= 1.5) {
        const fr2 = Math.sqrt(fw * fh);
        this.faceRatio = this.faceRatio * 0.9 + fr2 * 0.1;
        const df = clamp((this.faceRatio - 0.4) / 0.4, -0.5, 1.5);
        const dyn = 3.0 * (1 + 2.5 * this.perspectiveStrength * df);
        this.nearMult = clamp(dyn, 0.5, 15.0);
      }
    }

    // ---- 眼 ----
    const rawR = refEar(lm, R_UPPER, R_LOWER, 133, 33);
    const rawL = refEar(lm, L_UPPER, L_LOWER, 362, 263);
    let earR = rawR, earL = rawL;

    const hasPose = pose != null;
    const pitch = hasPose ? pose[0] : 0;
    const yaw = hasPose ? pose[1] : 0;
    if (hasPose && Math.abs(pitch) > 5) {
      earR = this.compPitch(rawR, pitch, this.right);
      earL = this.compPitch(rawL, pitch, this.left);
    }
    if (hasPose && Math.abs(yaw) > 1) {
      const cur = Math.max(0, (Math.abs(yaw) - 1) / 90);
      const cal = Math.max(0, (Math.abs(this.left.calYaw) - 1) / 90);
      const delta = cur - cal;
      const rC = refEffClosed(this.right), rO = refEffOpen(this.right);
      const lC = refEffClosed(this.left), lO = refEffOpen(this.left);
      const rR = rO - rC, lR = lO - lC;
      const rDec = clamp((rawR - rC) / rR, 0, 1), lDec = clamp((rawL - lC) / lR, 0, 1);
      const nearMult = this.nearMult;
      if (delta > 0) {
        const far = delta * 0.4, near = delta * 0.4 * nearMult;
        // 【按代码】yaw > 0 时 imageLeft = 远侧、imageRight = 近侧
        if (yaw > 0) { earL += far * lR * lDec; earR += near * rR * rDec; }
        else { earR += far * rR * rDec; earL += near * lR * lDec; }
      }
      // 转头方向改变（校准时向左、现在向右）需要双向补偿
      const calDir = this.left.calYaw >= 0 ? 1 : -1;
      const curDir = yaw >= 0 ? 1 : -1;
      if (calDir !== curDir && Math.abs(this.left.calYaw) > 1) {
        const calF2 = Math.max(0, (Math.abs(this.left.calYaw) - 1) / 90);
        const opp = calF2 * 0.4;
        const oppNear = calF2 * 0.4 * nearMult;
        if (curDir > 0) { earR += opp * rR * rDec; earL += oppNear * lR * lDec; }
        else { earL += opp * lR * lDec; earR += oppNear * rR * rDec; }
      }
    }

    if (!this.right.ok) { refAdd(this.right, rawR, now); if (refReady(this.right, now)) refFinish(this.right); }
    if (!this.left.ok) {
      refAdd(this.left, rawL, now);
      if (refReady(this.left, now)) {
        refFinish(this.left);
        // 【按代码】左眼校准落地那一帧记录当时的姿态（SimpleEyeExtractor.kt:912-918）；
        // 右眼不记。FaceCapturePipeline 随后会用 setCalibrationPose(baseline) 覆盖它。
        if (hasPose) { this.left.calPitch = pitch; this.left.calYaw = yaw; }
      }
    }

    out52[9] = this.toBlink(earL, this.left);
    out52[10] = this.toBlink(earR, this.right);
    out52[21] = this.toWide(earL, this.left);
    out52[22] = this.toWide(earR, this.right);

    // ---- 眼球方向 ----
    this.gaze(lm, out52, R_UPPER, R_LOWER, 133, 33, 468, true);
    this.gaze(lm, out52, L_UPPER, L_LOWER, 362, 263, 473, false);

    // ---- 嘴 ----
    const mh = d2(lm, M_IN[5], M_IN[15]);
    const mw = d2(lm, M_IN[0], M_IN[10]);
    const rawMar = mw > 1e-6 ? mh / mw : 0;
    let mar = rawMar;
    if (hasPose && this.mouth.ok) {
      const delta = pitch - 0;
      if (Math.abs(delta) > 5) {
        const thr = this.mouth.ok ? this.mouth.closed : 0.10;
        if (rawMar >= thr * 1.5) {
          const eff = delta > 0 ? (delta - 5) * 1.3 : -(Math.abs(delta) - 5);
          mar = Math.max(0, rawMar + eff * 0.008);
        }
      }
    }
    if (!this.mouth.ok) {
      if (this.mouth.n === 0) this.mouth.start = now;
      this.mouth.sum += rawMar; this.mouth.sq += rawMar * rawMar;
      if (rawMar < this.mouth.min) this.mouth.min = rawMar;
      if (rawMar > this.mouth.max) this.mouth.max = rawMar;
      this.mouth.n++;
      if (this.mouth.n >= 10 && now - this.mouth.start >= 1000) {
        const n = this.mouth.n;
        this.mouth.mean = this.mouth.sum / n;
        this.mouth.std = Math.sqrt(Math.max(0, this.mouth.sq / n - this.mouth.mean * this.mouth.mean));
        this.mouth.closed = clamp(this.mouth.mean + 2 * this.mouth.std, 0.05, 0.25);
        this.mouth.open = clamp(this.mouth.closed * 3.5, 0.25, 0.70);
        this.mouth.ok = true;
      }
    }
    const mc = this.mouth.ok ? this.mouth.closed : 0.10;
    const mo = this.mouth.ok ? this.mouth.open : 0.45;
    out52[25] = mar <= mc ? 0 : mar >= mo ? 1 : clamp((mar - mc) / (mo - mc), 0, 1);

    // ---- 微笑 ----
    if (M_OUT.length >= 16 && faceHeight > 1e-6) {
      const cy = (lm[M_OUT[15] * 3 + 1] + lm[M_OUT[5] * 3 + 1]) / 2;
      const rawL2 = (cy - lm[M_OUT[0] * 3 + 1]) / faceHeight;
      const rawR2 = (cy - lm[M_OUT[10] * 3 + 1]) / faceHeight;
      let cl = rawL2, cr = rawR2;
      if (hasPose && this.smile.ok) {
        const dp = pitch - this.smile.calPitch;
        if (Math.abs(dp) > 5) {
          const comp = (dp > 0 ? dp - 5 : dp + 5) * 0.0008;
          cl += comp; cr += comp;
        }
        const dr = pose[2] - this.smile.calRoll;
        if (Math.abs(dr) > 3) { const rc = dr * 0.001; cl += -rc; cr += rc; }
      }
      if (!this.smile.ok) {
        const s = this.smile;
        if (s.n === 0) s.start = now;
        s.sl += rawL2; s.sr += rawR2; s.sql += rawL2 * rawL2; s.sqr += rawR2 * rawR2; s.n++;
        if (s.n >= 10 && now - s.start >= 1000) {
          const n = s.n;
          s.bl = s.sl / n; s.br = s.sr / n;
          s.sdl = Math.sqrt(Math.max(0, s.sql / n - s.bl * s.bl));
          s.sdr = Math.sqrt(Math.max(0, s.sqr / n - s.br * s.br));
          s.ok = true;
          // 【按代码】校准落地那一帧记录当时的姿态（SimpleSmileExtractor.kt:331-335）
          if (hasPose) { s.calPitch = pitch; s.calYaw = pose[1]; s.calRoll = pose[2]; }
        }
      }
      const s = this.smile;
      const dl = cl - s.bl, dr2 = cr - s.br;
      if (s.ok) {
        const avg = (dl + dr2) / 2;
        if (avg > 0) { const nm = avg * 1.2; if (nm > s.sMax) s.sMax = Math.min(nm, 0.15); }
        else if (avg < 0) { const nm = Math.abs(avg) * 1.2; if (nm > s.fMax) s.fMax = Math.min(nm, 0.10); }
      }
      out52[44] = dl > 0.003 ? clamp((dl - 0.003) / (s.sMax - 0.003), 0, 1) : 0;
      out52[30] = dl < -0.002 ? clamp((Math.abs(dl) - 0.002) / (s.fMax - 0.002), 0, 1) : 0;
      out52[45] = dr2 > 0.003 ? clamp((dr2 - 0.003) / (s.sMax - 0.003), 0, 1) : 0;
      out52[31] = dr2 < -0.002 ? clamp((Math.abs(dr2) - 0.002) / (s.fMax - 0.002), 0, 1) : 0;
    }

    // clamp()：只 clamp 7 个字段
    for (const i of [9, 10, 21, 22, 25, 44, 45]) out52[i] = clamp01(out52[i]);
    return out52;
  }

  compPitch(ear, pitch, c) {
    if (Math.abs(pitch) <= 5) return ear;
    const nC = refEffClosed(c), nO = refEffOpen(c);
    if (ear <= nC * 1.2) return ear;
    const abs = Math.abs(pitch);
    let shrink = (abs - 5) * 0.012;
    if (pitch < 0) shrink *= 1.2;
    const ratio = clamp(1 - shrink, 0.4, 1);
    const shrunkOpen = nC + (nO - nC) * ratio;
    if (ear >= shrunkOpen) return nO;
    if (ear <= nC) return ear;
    const range = shrunkOpen - nC;
    if (range < 0.01) return ear;
    return ((ear - nC) / range) * (nO - nC) + nC;
  }

  toBlink(ear, c) {
    const eC = refEffClosed(c), eO = refEffOpen(c);
    if (ear <= eC) return 1;
    if (ear >= eO) return 0;
    return clamp((eO - ear) / (eO - eC), 0, 1);
  }
  toWide(ear, c) {
    const eO = refEffOpen(c);
    const w = c.ok ? c.wide : 0.26;
    return ear > eO ? clamp((ear - eO) / (w - eO), 0, 1) : 0;
  }

  gaze(lm, out52, upper, lower, inner, outer, iris, isRight) {
    const ecx = (lm[inner * 3] + lm[outer * 3]) / 2;
    const ecy = (lm[inner * 3 + 1] + lm[outer * 3 + 1]) / 2;
    const w = d2(lm, inner, outer);
    let ux = 0, uy = 0, lx = 0, ly = 0;
    for (const i of upper) { ux += lm[i * 3]; uy += lm[i * 3 + 1]; }
    for (const i of lower) { lx += lm[i * 3]; ly += lm[i * 3 + 1]; }
    ux /= upper.length; uy /= upper.length; lx /= lower.length; ly /= lower.length;
    const h = Math.sqrt(Math.pow(ux - lx, 2) + Math.pow(uy - ly, 2));
    const ox = clamp((lm[iris * 3] - ecx) / (w / 2 + 1e-6), -1, 1);
    const oy = clamp((lm[iris * 3 + 1] - ecy) / (h / 2 + 1e-6), -1, 1);
    const li = isRight ? 14 : 13, lo = isRight ? 16 : 15;
    const lu = isRight ? 18 : 17, ld = isRight ? 12 : 11;
    if (isRight) { out52[14] = clamp01(ox); out52[16] = clamp01(-ox); }
    else { out52[13] = clamp01(ox); out52[15] = clamp01(-ox); }
    out52[lu] = clamp01(-oy);
    out52[ld] = clamp01(oy);
  }
}

// ============ 参考版：TimeBasedEMA（face_pipeline_filter.h 直译）============

export class RefEMA {
  constructor(tau) { this.tau = tau; this.init = false; this.v = 0; this.t = -1; }
  filter(x, ts) {
    if (!this.init) { this.init = true; this.v = x; this.t = ts; return x; }
    let dt = ts - this.t;
    this.t = ts;
    dt = clamp(dt, 0.001, 0.5);
    const a = 1 - Math.exp(-dt / this.tau);
    this.v = a * x + (1 - a) * this.v;
    return this.v;
  }
}

// ============ 参考版：上半身 ============

export class RefOneEuro {
  constructor(mc, beta, dc) { this.mc = mc; this.beta = beta; this.dc = dc; this.last = NaN; this.deriv = 0; }
  static alpha(cutoff, dt) { if (!(cutoff > 0)) return 1; const tau = 1 / (2 * Math.PI * cutoff); return 1 / (1 + tau / dt); }
  filter(v, dt) {
    if (!Number.isFinite(v)) return Number.isFinite(this.last) ? this.last : 0;
    if (!Number.isFinite(this.last)) { this.last = v; this.deriv = 0; return v; }
    dt = (Number.isFinite(dt) && dt > 0) ? Math.min(dt, 0.5) : 0.001;
    const raw = (v - this.last) / dt;
    this.deriv += RefOneEuro.alpha(this.dc, dt) * (raw - this.deriv);
    const cutoff = this.mc + this.beta * Math.abs(this.deriv);
    const a = RefOneEuro.alpha(cutoff, dt);
    this.last = this.last + a * (v - this.last);
    return this.last;
  }
}

export class RefTorsoBaseline {
  constructor(n) { this.n = n; this.reset(); }
  reset() { this.c = 0; this.sr = 0; this.sy = 0; this.br = 0; this.by = 0; }
  apply(roll, yaw) {
    if (!Number.isFinite(roll) || !Number.isFinite(yaw)) return [0, 0];
    if (this.c >= this.n) return [roll - this.br, yaw - this.by];
    this.c++; this.sr += roll; this.sy += yaw;
    const mr = this.sr / this.c, my = this.sy / this.c;
    if (this.c >= this.n) { this.br = mr; this.by = my; }
    const w = Math.min(1, this.c / this.n);
    return [(roll - mr) * w, (yaw - my) * w];
  }
}
