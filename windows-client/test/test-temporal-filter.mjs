import {
  TimeBasedEMAFilter, TimeBasedEMAFilter3D, HeadPoseSmoother, OneEuroFilterFace, LandmarkSmoother,
  DEFAULT_TIME_EMA_TAU, DEFAULT_TIME_EMA_TAU_HEAD_POSE, MIN_TIME_EMA_TAU,
  MIN_DT_SECONDS, MAX_DT_SECONDS, createTemporalFilter,
} from "../renderer/temporal-filter.js";

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function assertClose(a, b, eps, msg) {
  if (!(Math.abs(a - b) <= eps)) throw new Error(`${msg}: ${a} != ${b}`);
}

export const tests = [
  {
    name: "tau 常量：默认 0.12（线上实跑）、头姿 0.3、下限 0.01",
    fn() {
      assertClose(DEFAULT_TIME_EMA_TAU, 0.12, 1e-12, "DEFAULT_TIME_EMA_TAU");
      assertClose(DEFAULT_TIME_EMA_TAU_HEAD_POSE, 0.3, 1e-12, "HEAD_POSE");
      assertClose(MIN_TIME_EMA_TAU, 0.01, 1e-12, "MIN_TAU");
      const f = new TimeBasedEMAFilter();
      assertClose(f.tau, 0.12, 1e-12, "实例默认 tau");
      const p = new HeadPoseSmoother();
      assertClose(p._p.tau, 0.3, 1e-12, "头姿默认 tau");
    },
  },
  {
    name: "setTau 的下限 0.01（C++ std::max(0.01f, tau)）",
    fn() {
      const f = new TimeBasedEMAFilter();
      f.setTau(0.0001);
      assertClose(f.tau, 0.01, 1e-12, "下限");
    },
  },
  {
    name: "首样本直通并记 last_time（C++ 语义）",
    fn() {
      const f = new TimeBasedEMAFilter({ tau: 0.12 });
      assertClose(f.filter(0.7, 10), 0.7, 1e-12, "首样本直通");
      assert(f.hasValue, "hasValue");
    },
  },
  {
    name: "alpha = 1 - exp(-dt/tau) 的数值正确",
    fn() {
      const f = new TimeBasedEMAFilter({ tau: 0.12 });
      f.filter(0, 0);
      const dt = 0.12;
      const out = f.filter(1, dt);
      const expectedAlpha = 1 - Math.exp(-1); // dt == tau
      assertClose(out, expectedAlpha, 1e-12, "一步 EMA");
    },
  },
  {
    name: "dt 裁剪到 [0.001, 0.5] 秒",
    fn() {
      const f = new TimeBasedEMAFilter({ tau: 0.12 });
      f.filter(0, 0);
      // 极长间隔 5s -> 按 0.5s 算
      const out = f.filter(1, 5);
      assertClose(out, 1 - Math.exp(-MAX_DT_SECONDS / 0.12), 1e-12, "dt 上限");

      const g = new TimeBasedEMAFilter({ tau: 0.12 });
      g.filter(0, 0);
      const out2 = g.filter(1, 0.0001);
      assertClose(out2, 1 - Math.exp(-MIN_DT_SECONDS / 0.12), 1e-12, "dt 下限");
    },
  },
  {
    name: "长间隔（face-lost / jitter-dt 用例）：300ms 必须按 0.3s 算，不能被裁到 0.1s",
    fn() {
      const f = new TimeBasedEMAFilter({ tau: 0.12 });
      f.filter(0, 0);
      const out = f.filter(1, 0.3);
      assertClose(out, 1 - Math.exp(-0.3 / 0.12), 1e-12, "0.3s 未被裁剪");
    },
  },
  {
    name: "TimeBasedEMAFilter3D 三分量共享同一时间戳",
    fn() {
      const f = new TimeBasedEMAFilter3D({ tau: 0.12 });
      f.filter(1, 2, 3, 0);
      const out = f.filter(2, 3, 4, 0.12);
      const a = 1 - Math.exp(-1);
      assertClose(out[0], 1 + a * (2 - 1), 1e-12, "x");
      assertClose(out[1], 2 + a * (3 - 2), 1e-12, "y");
      assertClose(out[2], 3 + a * (4 - 3), 1e-12, "z");
    },
  },
  {
    name: "OneEuroFilterFace：computeAlpha 与 C++ 一致",
    fn() {
      const alpha = OneEuroFilterFace.computeAlpha(1, 0.1);
      const tau = 1 / (2 * Math.PI * 1);
      assertClose(alpha, 1 / (1 + tau / 0.1), 1e-12, "computeAlpha");
      assertClose(OneEuroFilterFace.computeAlpha(0, 0.1), 1, 1e-12, "cutoff<=0 -> 1");
    },
  },
  {
    name: "OneEuroFilterFace 默认参数是 0.05/80/1.0（**不是**上半身那套）",
    fn() {
      const f = new OneEuroFilterFace();
      assertClose(f.minCutoff, 0.05, 1e-12, "minCutoff");
      assertClose(f.beta, 80, 1e-12, "beta");
      assertClose(f.derivCutoff, 1, 1e-12, "derivCutoff");
    },
  },
  {
    name: "LandmarkSmoother 就地写、不新建对象",
    fn() {
      const s = new LandmarkSmoother({ count: 4, tau: 0.12 });
      const pts = new Float32Array(12);
      pts.fill(1);
      const same = s.filterInPlace(pts, 0);
      assert(same === pts, "必须返回同一个数组（就地）");
      for (let i = 0; i < 12; i++) pts[i] = 2; // 输入跳到 2
      s.filterInPlace(pts, 0.12); // dt = tau -> alpha = 1 - 1/e
      const alpha = 1 - Math.exp(-1);
      for (let i = 0; i < 12; i++) {
        if (!(Math.abs(pts[i] - (1 + alpha)) <= 1e-6)) {
          throw new Error(`第 ${i} 个分量应平滑到 ${1 + alpha}，实际 ${pts[i]}`);
        }
      }
    },
  },
  {
    name: "createTemporalFilter：enabled=false 时整体直通（协议 bit5 场景）",
    fn() {
      const tf = createTemporalFilter({ smoothing: { enabled: false, landmarkTauSec: 0.12, headPoseTauSec: 0.3 } });
      const pts = new Float32Array(478 * 3);
      pts[0] = 0.5;
      tf.filterLandmarks(pts, 0);
      tf.filterLandmarks(pts, 10);
      assertClose(pts[0], 0.5, 1e-12, "禁用时不应改动点位");
      const out = new Float32Array(3);
      tf.filterPose(out, 1, 2, 3, 0);
      tf.filterPose(out, 5, 6, 7, 10);
      assertClose(out[0], 5, 1e-12, "禁用时头姿直通");
    },
  },
  {
    name: "createTemporalFilter：enabled=true 时关键点 tau 默认 0.12、头姿 tau 默认 0.3",
    fn() {
      const tf = createTemporalFilter({ smoothing: { enabled: true } });
      assertClose(tf.landmarks.tau, 0.12, 1e-12, "landmark tau");
      assertClose(tf.pose._p.tau, 0.3, 1e-12, "headPose tau");
    },
  },
];
