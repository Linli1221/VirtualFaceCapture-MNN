import {
  OneEuroFilter, TorsoBaseline, UpperBodySmoother, BodyFusion,
  quality, isTracking, torsoInto,
  DEFAULT_MIN_CUTOFF, DEFAULT_BETA, DEFAULT_DERIVATIVE_CUTOFF,
  DEFAULT_TORSO_MIN_CUTOFF, DEFAULT_TORSO_BETA, DEFAULT_CALIBRATION_FRAMES,
  RELIABLE_VISIBILITY, MIN_TRACKING_POINTS, MIN_TORSO_VISIBILITY, MIN_REFERENCE_SPAN,
} from "../renderer/body-fusion.js";

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function assertClose(a, b, eps, msg) {
  if (!(Math.abs(a - b) <= eps)) throw new Error(`${msg}: ${a} != ${b}`);
}

/** 造一帧 13 点（13*4 交错 x,y,z,visibility），默认全部可信。 */
function frame(overrides = {}) {
  const p = new Float32Array(52);
  for (let i = 0; i < 13; i++) {
    p[i * 4] = 0.5;
    p[i * 4 + 1] = 0.5 + i * 0.01;
    p[i * 4 + 2] = 0;
    p[i * 4 + 3] = 0.9;
  }
  for (const k of Object.keys(overrides)) {
    const [idx, comp] = k.split(":").map(Number);
    p[idx * 4 + comp] = overrides[k];
  }
  return p;
}

export const tests = [
  {
    name: "OneEuroFilter 常量是 MediapipeUpperBody 那套（1.2 / 0.02 / 1.0）",
    fn() {
      assertClose(DEFAULT_MIN_CUTOFF, 1.2, 1e-12, "minCutoff");
      assertClose(DEFAULT_BETA, 0.02, 1e-12, "beta");
      assertClose(DEFAULT_DERIVATIVE_CUTOFF, 1.0, 1e-12, "derivCutoff");
      assertClose(DEFAULT_TORSO_MIN_CUTOFF, 0.6, 1e-12, "torso minCutoff");
      assertClose(DEFAULT_TORSO_BETA, 0.01, 1e-12, "torso beta");
      assert(DEFAULT_CALIBRATION_FRAMES === 10, "校准帧数 10");
      assertClose(RELIABLE_VISIBILITY, 0.5, 1e-12, "可见度阈值");
      assert(MIN_TRACKING_POINTS === 7, "最少可信点 7");
      assertClose(MIN_TORSO_VISIBILITY, 0.3, 1e-12, "躯干可见度");
      assertClose(MIN_REFERENCE_SPAN, 0.02, 1e-12, "最小水平基准");
    },
  },
  {
    name: "OneEuroFilter：首样本直通、非有限输入返回上一次有效值",
    fn() {
      const f = new OneEuroFilter();
      assertClose(f.filter(0.5, 0.033), 0.5, 1e-12, "首样本");
      assertClose(f.filter(NaN, 0.033), 0.5, 1e-12, "NaN 应返回上次值");
      assertClose(f.filter(Infinity, 0.033), 0.5, 1e-12, "Inf 应返回上次值");
      const g = new OneEuroFilter();
      assertClose(g.filter(NaN, 0.033), 0, 1e-12, "从未有效时返回 0");
    },
  },
  {
    name: "OneEuroFilter：dt <= 0 / NaN 时按 1ms 处理",
    fn() {
      const f = new OneEuroFilter();
      f.filter(0, 0.033);
      const a = f.filter(1, 0);
      const g = new OneEuroFilter();
      g.filter(0, 0.033);
      const b = g.filter(1, NaN);
      assertClose(a, b, 1e-12, "两种非法 dt 行为应一致");
    },
  },
  {
    name: "OneEuroFilter：reset 后重新直通（丢帧后不把旧位置拖过来）",
    fn() {
      const f = new OneEuroFilter();
      f.filter(0, 0.033);
      f.filter(1, 0.033);
      f.reset();
      assertClose(f.filter(9, 0.033), 9, 1e-12, "reset 后应直通新位置");
    },
  },
  {
    name: "TorsoBaseline：10 帧内从 0 平滑过渡到相对当前均值",
    fn() {
      const b = new TorsoBaseline(10);
      const out = [0, 0];
      b.applyInto(out, 10, 20);
      assertClose(out[0], 0, 1e-12, "第 1 帧权重 0 -> 输出 0");
      b.applyInto(out, 10, 20);
      // 第 2 帧：均值 = 10，(10-10)*0.2 = 0
      assertClose(out[0], 0, 1e-12, "恒定输入下输出恒 0");
      assert(b.isCalibrated === false, "10 帧前不应校准完成");
    },
  },
  {
    name: "TorsoBaseline：校准完成后输出 = 当前值 - 基线",
    fn() {
      const b = new TorsoBaseline(3);
      const out = [0, 0];
      b.applyInto(out, 5, 7);
      b.applyInto(out, 5, 7);
      b.applyInto(out, 5, 7);
      assert(b.isCalibrated, "3 帧后应校准完成");
      b.applyInto(out, 15, 27);
      assertClose(out[0], 10, 1e-9, "roll 差");
      assertClose(out[1], 20, 1e-9, "yaw 差");
    },
  },
  {
    name: "TorsoBaseline：非法输入返回 0 且不污染校准均值",
    fn() {
      const b = new TorsoBaseline(3);
      const out = [0, 0];
      b.applyInto(out, NaN, 0);
      assertClose(out[0], 0, 1e-12, "非法输入返回 0");
      b.applyInto(out, 4, 4);
      b.applyInto(out, 4, 4);
      b.applyInto(out, 4, 4);
      assert(b.isCalibrated, "只应统计 3 个有效帧");
      assertClose(b.baseline[0], 4, 1e-9, "基线只来自有效帧");
    },
  },
  {
    name: "isTracking：可见度 > 0.5 的点 >= 7（严格大于）",
    fn() {
      const p = frame();
      assert(isTracking(p) === true, "全部 0.9 应 tracking");
      // 恰好 7 个 0.5（不满足严格大于）+ 6 个 0.9
      const q = frame();
      for (let i = 0; i < 7; i++) q[i * 4 + 3] = 0.5;
      assert(isTracking(q) === false, "6 个 0.9 + 7 个 0.5 -> 只有 6 个 >0.5，应 false");
    },
  },
  {
    name: "quality 是 13 点可见度均值（clamp 到 [0,1]）",
    fn() {
      const p = frame();
      assertClose(quality(p), 0.9, 1e-6, "均值");
      const q = frame();
      q[3] = 5; // 越界的可见度应被 clamp
      assert(quality(q) <= 1, "越界可见度应 clamp");
    },
  },
  {
    name: "torsoInto：肩线 roll/yaw 的符号与数值正确",
    fn() {
      const p = frame();
      // 左肩 (5) x=0.4, y=0.5, z=0；右肩 (6) x=0.6, y=0.6, z=-0.1
      p[5 * 4] = 0.4; p[5 * 4 + 1] = 0.5; p[5 * 4 + 2] = 0; p[5 * 4 + 3] = 0.9;
      p[6 * 4] = 0.6; p[6 * 4 + 1] = 0.6; p[6 * 4 + 2] = -0.1; p[6 * 4 + 3] = 0.9;
      const out = [0, 0];
      torsoInto(out, p);
      const expected = Math.atan2(0.1, 0.2) * 180 / Math.PI;
      assertClose(out[0], expected, 1e-4, "roll：右肩更低为正");
      assertClose(out[1], expected, 1e-4, "yaw：右肩更靠近镜头为正");
    },
  },
  {
    name: "torsoInto：肩不可见时退化到肘、再退化到胯",
    fn() {
      const p = frame();
      // 肩不可见
      p[5 * 4 + 3] = 0.1; p[6 * 4 + 3] = 0.1;
      // 肘 (7,8) 给一个明确角度
      p[7 * 4] = 0.3; p[7 * 4 + 1] = 0.4; p[7 * 4 + 2] = 0; p[7 * 4 + 3] = 0.9;
      p[8 * 4] = 0.7; p[8 * 4 + 1] = 0.4; p[8 * 4 + 2] = 0; p[8 * 4 + 3] = 0.9;
      const out = [0, 0];
      torsoInto(out, p);
      assertClose(out[0], 0, 1e-4, "肘线水平 -> roll = 0");
      assertClose(out[1], 0, 1e-4, "肘线无深度差 -> yaw = 0");
    },
  },
  {
    name: "torsoInto：全部不可见 -> 返回 [0,0]",
    fn() {
      const p = frame();
      for (let i = 0; i < 13; i++) p[i * 4 + 3] = 0.05;
      const out = [9, 9];
      torsoInto(out, p);
      assertClose(out[0], 0, 1e-12, "roll");
      assertClose(out[1], 0, 1e-12, "yaw");
    },
  },
  {
    name: "torsoInto：水平基准下限 0.02（两肩几乎重合时不爆）",
    fn() {
      const p = frame();
      p[5 * 4] = 0.5; p[5 * 4 + 1] = 0.5; p[5 * 4 + 2] = 0; p[5 * 4 + 3] = 0.9;
      p[6 * 4] = 0.5; p[6 * 4 + 1] = 0.6; p[6 * 4 + 2] = -0.1; p[6 * 4 + 3] = 0.9;
      const out = [0, 0];
      torsoInto(out, p);
      assert(Number.isFinite(out[0]) && Number.isFinite(out[1]), "应为有限值");
      // horizontal 被顶到 0.02，roll = atan2(0.1, 0.02)
      assertClose(out[0], Math.atan2(0.1, 0.02) * 180 / Math.PI, 1e-3, "roll");
    },
  },
  {
    name: "UpperBodySmoother：visibility 不平滑、BodyPoint.ZERO 直通且不推进滤波器",
    fn() {
      const s = new UpperBodySmoother();
      const p = frame();
      p[0 * 4 + 3] = 0.42; // visibility 必须原样保留
      // 第 0 点设为全 0（ZERO），第 1 点给一个会跳变的值
      p[0 * 4] = 0; p[0 * 4 + 1] = 0; p[0 * 4 + 2] = 0; p[0 * 4 + 3] = 0;
      p[1 * 4] = 1; p[1 * 4 + 1] = 1; p[1 * 4 + 2] = 1;
      s.smoothInPlace(p, 0.033);
      assertClose(p[0 * 4], 0, 1e-12, "ZERO 点应保持 0");
      assertClose(p[1 * 4 + 3], 0.9, 1e-6, "visibility 不平滑（Float32 存储，容差 1e-6）");

      // ZERO 点的滤波器不应被推进：让它后来变成非零，应直通而不是被拖动
      p[0 * 4] = 5; p[0 * 4 + 1] = 5; p[0 * 4 + 2] = 5; p[0 * 4 + 3] = 0.9;
      s.smoothInPlace(p, 0.033);
      assertClose(p[0 * 4], 5, 1e-6, "ZERO 之后第一次出现应直通（滤波器从未被推进）");
    },
  },
  {
    name: "BodyFusion.process 输出形态：{torso, points, tracking, quality, ubT}",
    fn() {
      const bf = new BodyFusion();
      const p = frame();
      const r = bf.process(p, 0.033, 12345);
      assert(r.torso && r.torso.length === 2, "torso 长度 2");
      assert(r.points === p, "points 应是同一个数组（就地平滑）");
      assert(typeof r.tracking === "boolean", "tracking 是布尔");
      assert(typeof r.quality === "number", "quality 是数字");
      assert(r.ubT === 12345, "ubT 应透传");
    },
  },
  {
    name: "BodyFusion：tracking 丢失时清空滤波器状态（不把旧位置拖过来）",
    fn() {
      const bf = new BodyFusion();
      const p = frame();
      bf.process(p, 0.033, 0);
      const lost = frame();
      for (let i = 0; i < 13; i++) lost[i * 4 + 3] = 0.05;
      const r = bf.process(lost, 0.033, 1);
      assert(r.tracking === false, "应判为丢失");
      const back = frame();
      back[1 * 4] = 9; back[1 * 4 + 1] = 9; back[1 * 4 + 2] = 9;
      bf.process(back, 0.033, 2);
      assertClose(back[1 * 4], 9, 1e-6, "恢复后应从新位置开始");
    },
  },
  {
    name: "BodyFusion：无锚点（首帧）用 1/15 秒 dt，不被外部 dt 影响",
    fn() {
      const bf = new BodyFusion();
      const p = frame();
      const r = bf.process(p, 5.0, 0); // 外部给了 5 秒，首帧应按 1/15 处理
      assert(r.tracking === true, "tracking");
      assert(Number.isFinite(r.torso[0]) && Number.isFinite(r.torso[1]), "torso 有限");
    },
  },
  {
    name: "BodyFusion.loadBaseline：载入后立即生效，跳过 10 帧冷启动过渡",
    fn() {
      const bf = new BodyFusion();
      assert(bf.baseline.isCalibrated === false, "初始未校准");
      const ok = bf.loadBaseline({ roll: 3, yaw: -2 });
      assert(ok === true, "载入应成功");
      assert(bf.baseline.isCalibrated === true, "载入后应立即视为已校准");

      const p = frame();
      // 肩线给一个已知角：dx=0.2, dy=0.1, dz=-0.1 -> roll=yaw=26.565°
      p[5 * 4] = 0.4; p[5 * 4 + 1] = 0.5; p[5 * 4 + 2] = 0; p[5 * 4 + 3] = 0.9;
      p[6 * 4] = 0.6; p[6 * 4 + 1] = 0.6; p[6 * 4 + 2] = -0.1; p[6 * 4 + 3] = 0.9;
      const r = bf.process(p, 0.033, 0);
      // 首帧 One-Euro 直通 -> 躯干角即 26.565°，减基线得 23.565 / 28.565
      assertClose(r.torso[0], 26.565051177 - 3, 1e-3, "roll 应为 原始-基线");
      assertClose(r.torso[1], 26.565051177 - (-2), 1e-3, "yaw 应为 原始-基线");
    },
  },
  {
    name: "BodyFusion.serializeBaseline / loadBaseline 往返一致",
    fn() {
      const a = new BodyFusion({ calibrationFrames: 7 });
      a.loadBaseline({ roll: 1.25, yaw: -3.5 });
      const s = a.serializeBaseline();
      assertClose(s.baselineRoll, 1.25, 1e-12, "baselineRoll");
      assertClose(s.baselineYaw, -3.5, 1e-12, "baselineYaw");
      assert(s.calibrationFrames === 7, "calibrationFrames 应保留");

      const b = new BodyFusion();
      assert(b.loadBaseline(s) === true, "往返载入应成功");
      assertClose(b.baseline.baseline[0], 1.25, 1e-12, "roll 往返");
      assertClose(b.baseline.baseline[1], -3.5, 1e-12, "yaw 往返");
      assert(b.baseline.isCalibrated === true, "往返后仍应校准");
    },
  },
  {
    name: "BodyFusion.loadBaseline：非法输入返回 false 且不改状态",
    fn() {
      const bf = new BodyFusion();
      assert(bf.loadBaseline(null) === false, "null");
      assert(bf.loadBaseline({}) === false, "空对象");
      assert(bf.loadBaseline({ roll: NaN, yaw: 1 }) === false, "NaN");
      assert(bf.baseline.isCalibrated === false, "失败后不应变成已校准");
    },
  },
];
