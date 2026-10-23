/**
 * live2d.js — Cubism Web 渲染层（只碰 pixi / Cubism API，不做协议与 UI）
 *
 * 关键实现点（协议 §8.3）：
 *  - 关闭 Cubism 自动眨眼（internalModel.eyeBlink = undefined）
 *  - 关闭 focus / 鼠标跟随对 ParamAngle* / ParamEyeBall* 的自动叠加（updateFocus = noop）
 *  - 保留 physics（头发/衣服摆动）与 idle motion
 *  - 每帧在 InternalModel 的 beforeModelUpdate 事件里写参数：该事件在 coreModel.update()
 *    之前、motion/expression/eyeBlink/breath/physics 之后触发，保证我们的值不会被覆盖。
 *    另在 afterMotionUpdate 也写一次（该点正好在 saveParameters 之前），让 physics 读到头姿。
 *
 * 帧率策略（协议 §9：PC 渲染 60 FPS）：
 *  - 用 PIXI Ticker 的 maxFPS 把渲染目标钉在 60 fps，高刷屏（120/144/165 Hz）不再空转；
 *    需要更高 / 更低时可用 URL `?fps=<n>` 或 setTargetFps() 调整，0 = 不限制。
 *  - 输入帧率（手机 WS）低于渲染帧率时，用 ParamTimeline 做时间轴插值（见 interp.js），
 *    让每个渲染帧都拿到连续变化的参数，而不是两次输入之间「hold 住 → 跳一下」。
 *  - 输入节奏不稳（推理耗时波动 / Wi-Fi 抖动 / PC 卡顿导致 WS 批量到达）时：
 *    优先用手机报文里的采集时间戳 `t` 排播放时间轴（把传输抖动从运动时间里剔除），
 *    抖动缓冲延迟按最近间隔 p85 + 传输滞后 p90 + 余量自适应，快升慢降、限速变化，画面不会倒带。
 *  - 缓动系数按 60 fps 每帧标定，实际用 dt 换算成时间常数（alpha = 1 - exp(-dt/τ)），
 *    因此换目标帧率 / vsync 抖动不会改变手感。
 *
 * 全局依赖（index.html 按顺序加载）：window.Live2DCubismCore → window.PIXI → PIXI.live2d
 */
import { BODY_ANGLE_LIMIT, BODY_PARAM_IDS, PARAM_IDS, combineBodyAngleX } from "./mapping.js";
import { ParamTimeline } from "./interp.js";

/** 默认渲染目标帧率（协议 §9：PC 渲染 60 FPS @ 1080p）。 */
export const DEFAULT_TARGET_FPS = 60;

/** smoothing 系数以 60 fps 的每帧标定。 */
const REFERENCE_FRAME_MS = 1000 / 60;

/** 目标帧率允许范围（PIXI Ticker 的 minFPS 下限是 10，这里取同一个下限）。 */
const MIN_TARGET_FPS = 10;
const MAX_TARGET_FPS = 240;

/** 插值开启时的轻量平滑时间常数：只压输入噪声，不制造额外拖尾。 */
const INTERP_SMOOTH_TAU_MS = 10;
/** 上半身参数的一阶低通时间常数（协议 §8.5 建议 0.15~0.25 s；Pose 只有 ~15 fps）。 */
const BODY_SMOOTH_TAU_MS = 160;

/** 单帧 dt 上限：切后台回来 / 卡顿一帧时不要一步跳到目标。 */
const MAX_STEP_MS = 100;

/** 渲染 fps 统计窗口（ms）。 */
const FPS_WINDOW_MS = 500;

/**
 * 契约单位（±canonicalHalf）→ 模型实际参数量程的**等比缩放**（纯函数，可单测）。
 *
 * 上半身映射在协议层用固定 ±30 量程出值，但每个模型的参数量程不同——Haru 的 `ParamArmLA`
 * 只有 [-1,1]，直接写 27 会被 Cubism clamp 到 1，动作「钉死」在极值上不跟手。
 * 这里把 `n = value / canonicalHalf ∈ [-1,1]` 映射进模型的 `[min,max]`（以默认值 def 为 0 点）：
 *   n ≥ 0 → def + n × (max - def)；n < 0 → def + n × (def - min)。
 * 量程信息缺失时原样返回（保持旧行为）。
 */
export function toModelRange(value, canonicalHalf, range) {
  const half = Math.max(1e-6, Math.abs(Number(canonicalHalf) || BODY_ANGLE_LIMIT));
  const v = Number(value);
  const n = Number.isFinite(v) ? Math.max(-1, Math.min(1, v / half)) : 0;
  const def = Number.isFinite(range?.def) ? range.def : 0;
  const min = Number.isFinite(range?.min) ? range.min : -half;
  const max = Number.isFinite(range?.max) ? range.max : half;
  return n >= 0 ? def + n * (max - def) : def + n * (def - min);
}

/** 环境自检：返回缺失项列表（空数组 = 可以渲染）。 */
export function checkEnvironment() {
  const problems = [];
  if (!window.Live2DCubismCore) {
    problems.push("Cubism Core 未加载（renderer/vendor/live2dcubismcore.min.js）");
  }
  if (!window.PIXI) {
    problems.push("pixi.js 未加载（renderer/vendor/pixi.min.js）");
  } else if (!window.PIXI.live2d || !window.PIXI.live2d.Live2DModel) {
    problems.push("pixi-live2d-display 未加载（renderer/vendor/pixi-live2d-display.js）");
  }
  try {
    const probe = document.createElement("canvas");
    const gl = probe.getContext("webgl2") || probe.getContext("webgl");
    if (!gl) problems.push("浏览器没有可用的 WebGL（检查显卡驱动 / chrome://gpu）");
  } catch {
    problems.push("创建 WebGL 上下文抛异常（浏览器不支持 WebGL）");
  }
  return problems;
}

export class Live2DStage {
  /**
   * @param {{canvas?:HTMLCanvasElement, smoothing?:number, targetFps?:number, interpolate?:boolean}} [options]
   *   smoothing  = 每帧（按 60 fps 计）向目标值靠拢的系数（1 = 立即生效，0.6 ≈ 1~2 帧缓动），
   *                实际生效值按 dt 折算，与渲染帧率无关；
   *   targetFps  = 渲染目标帧率（默认 60，0 / 负数 = 不限制）；
   *   interpolate = 输入帧率低于渲染帧率时是否做时间轴插值（默认 true）。
   */
  constructor(options = {}) {
    this.options = options;
    this.app = null;
    this.model = null;
    this.ready = false;
    this.modelUrl = null;
    this.modelName = null;
    this.targetParams = Object.create(null);
    this.currentParams = Object.create(null);
    this.smoothing = typeof options.smoothing === "number" ? options.smoothing : 0.6;
    this._smoothingTau = this._tauFor(this.smoothing);
    this.interpolate = options.interpolate !== false;
    this.timeline = new ParamTimeline();
    this.targetFps = DEFAULT_TARGET_FPS;
    this.availableParams = [];
    /** 上半身派生参数（mapBody 的结果）：与头部参数分开存，每帧在头部之后叠加写入。 */
    this.bodyParams = Object.create(null);
    /** 躯干 X 的低通状态（与头部参数分开，避免半身 15fps 的跳变直接叠到头部插值上）。 */
    this.currentTorsoX = null;
    /** 当前模型对 BODY_PARAM_IDS 的支持情况 { id: boolean }（调试面板 / 降级说明用）。 */
    this.bodySupport = Object.create(null);
    /** 模型对上半身参数声明的实际量程 { id: {def,min,max} }（写入前等比缩放，见 toModelRange）。 */
    this.bodyParamRanges = Object.create(null);
    /** 模型支持、且不在 PARAM_IDS 里的上半身参数（写参数时单独缓动 / 释放）。 */
    this.bodyOnlyParamIds = [];
    /** 本帧真正写进 core 的参数 id（PARAM_IDS + 模型支持的上半身可选参数）。 */
    this.paramIds = PARAM_IDS.slice();
    this.lastApplyAt = 0;
    this.lastError = null;
    this.missingParams = [];
    this._frameValues = Object.create(null);
    this._lastStepAt = 0;
    this._immediate = false;
    this._renderFps = 0;
    this._renderFrameCount = 0;
    this._renderWindowStart = 0;
    this._tickerFn = null;
    this._resizeFn = null;
    this.setTargetFps(options.targetFps ?? DEFAULT_TARGET_FPS);
  }

  /** 建 PIXI Application；失败抛错（错误信息可直接上屏）。 */
  async init() {
    if (this.app) return this.app;
    const problems = checkEnvironment();
    if (problems.length) throw new Error(problems.join("；"));

    const canvas = this.options.canvas ?? document.getElementById("live2d") ?? undefined;
    const app = new window.PIXI.Application({
      view: canvas,
      width: Math.max(1, window.innerWidth),
      height: Math.max(1, window.innerHeight),
      backgroundAlpha: 0, // 透明底：Electron 透明窗口 / OBS 叠加都要
      antialias: true,
      autoDensity: true,
      resolution: Math.min(window.devicePixelRatio || 1, 2),
      preserveDrawingBuffer: true, // 让 canvas.toDataURL / 无头截图能拿到画面
      powerPreference: "high-performance",
    });
    this.app = app;

    // 目标帧率：必须在 Application 建好之后设（PIXI 7 的 Ticker 才有 maxFPS）
    this.setTargetFps(this.targetFps);

    // 自己驱动 model.update，保证顺序在 PIXI 渲染之前（不依赖 PIXI.Ticker.shared）
    this._tickerFn = () => {
      const now = performance.now();
      this._countFrame(now);
      if (this.model) this.model.update(app.ticker.deltaMS);
    };
    app.ticker.add(this._tickerFn);

    this._resizeFn = () => this.resize();
    window.addEventListener("resize", this._resizeFn);
    this.resize();
    return app;
  }

  /** 视口变化：resize renderer 并重新适配模型。 */
  resize() {
    if (!this.app) return;
    const w = Math.max(1, window.innerWidth);
    const h = Math.max(1, window.innerHeight);
    this.app.renderer.resize(w, h);
    this.fit();
  }

  /** 模型缩放到视口内并居中（每次加载 / resize 调用）。 */
  fit() {
    const model = this.model;
    const app = this.app;
    if (!model || !app) return;
    const internal = model.internalModel;
    const mw = internal.width || internal.originalWidth || 1;
    const mh = internal.height || internal.originalHeight || 1;
    const w = app.screen.width;
    const h = app.screen.height;
    const scale = Math.min((w * 0.98) / mw, (h * 0.98) / mh);
    model.scale.set(scale);
    model.anchor.set(0.5, 0.5);
    model.position.set(w / 2, h / 2);
  }

  /**
   * 加载（或切换）模型。
   * @param {string} url 例如 /models/ATRI/ATRI.model3.json
   * @param {{name?:string}} [meta]
   */
  async loadModel(url, meta = {}) {
    await this.init();
    const Live2DModel = window.PIXI.live2d.Live2DModel;

    this.ready = false;
    this.lastError = null;
    this._clearRelaxTimer();
    this.targetParams = Object.create(null);
    this.currentParams = Object.create(null);
    this._frameValues = Object.create(null);
    this.timeline.reset();

    const previous = this.model;
    this.model = null;
    if (previous) {
      try {
        this.app.stage.removeChild(previous);
        previous.destroy({ children: true, texture: true, baseTexture: true });
      } catch {
        /* 旧模型销毁失败不影响新模型 */
      }
    }

    const model = await Live2DModel.from(url, { autoInteract: false, autoUpdate: false });
    const internal = model.internalModel;

    // §8.3：关掉自动眨眼（眼睛只能由 bs[9]/bs[10] 驱动）
    internal.eyeBlink = undefined;
    // §8.3：关掉 focus/鼠标跟随对 ParamAngle* / ParamEyeBall* 的叠加
    internal.updateFocus = function () {};
    internal.focusController.x = 0;
    internal.focusController.y = 0;
    // 不接管鼠标交互（Electron 鼠标穿透时更不能抢事件）
    model.eventMode = "none";
    model.interactive = false;
    model.autoInteract = false;

    // 每帧最后写参数：beforeModelUpdate 在 coreModel.update() 之前触发
    internal.on("afterMotionUpdate", () => this.applyParams(false));
    internal.on("beforeModelUpdate", () => this.applyParams(true));

    this.app.stage.addChild(model);
    this.model = model;
    this.modelUrl = url;
    this.modelName = meta.name ?? null;
    // 注意：不能只用 getParameterIndex 判断——它对未知 id 也会返回一个"虚拟索引"。
    // 这里读 Cubism core 的真实参数 id 表，缺参数时能明确告诉用户是模型不支持。
    const realIds = internal.coreModel._parameterIds ?? internal.coreModel._model?.parameters?.ids ?? [];
    this.availableParams = PARAM_IDS.filter((id) => realIds.includes(id));
    this.missingParams = PARAM_IDS.filter((id) => !realIds.includes(id));

    // 上半身骨骼参数（BODY_PARAM_IDS 里模型有的：BodyAngleY/Z、ArmLA/RB、ShoulderL/R 等）：
    // 模型没有就跳过 —— 属预期降级，不算错误（自带 4 个模型都只有 ParamBodyAngleX/Y/Z +
    // ParamBreath，没有手臂 / 肩参数）。判定用 getParameterIndex(id) >= 0 + 真实 id 表双保险：
    // Cubism 对未知 id 也会给虚拟索引。
    this.bodyParams = Object.create(null);
    this.bodySupport = Object.create(null);
    this.bodyParamRanges = Object.create(null);
    this.bodyOnlyParamIds = [];
    this.paramIds = PARAM_IDS.slice();
    for (const id of BODY_PARAM_IDS) {
      const supported = this._hasParam(internal.coreModel, id);
      this.bodySupport[id] = supported;
      if (!supported) continue;
      if (!this.paramIds.includes(id)) this.paramIds.push(id);
      if (!PARAM_IDS.includes(id)) this.bodyOnlyParamIds.push(id);
      const range = this._readParamRange(internal.coreModel, id);
      if (range) this.bodyParamRanges[id] = range;
    }

    this.ready = true;
    this.fit();
    return model;
  }

  /**
   * 设置目标参数（立即生效，不走插值缓冲）。
   * 用于模型加载后补一帧、断线回中性、截图/自测等确定性场景。
   */
  setTargetParams(params) {
    if (!params) return;
    this._clearRelaxTimer();
    this.targetParams = params;
    this.timeline.reset();
  }

  /**
   * 记录一帧**实时**输入（走时间轴插值）。
   * 渲染时按自适应延迟在两个输入帧之间线性插值；输入跟得上渲染时退化为「取最新帧 + 缓动」。
   * @param {object} params 映射后的 Live2D 参数
   * @param {number} [at] 到达时间（performance.now()，默认取当前时刻）
   * @param {number} [stamp] 手机采集时间戳（track.t）；有它时按真实采集时间排时间轴，
   *                        WS 批量到达 / PC 卡顿不会把两帧挤在同一个时刻
   */
  pushTrackParams(params, at = performance.now(), stamp = null) {
    if (!params) return false;
    this._clearRelaxTimer();
    this.targetParams = params;
    if (!this.interpolate) return true;
    return this.timeline.push(params, at, stamp);
  }

  /**
   * 上半身派生参数（mapping.mapBody 的结果）：**每帧在头部参数之后叠加**写入，不覆盖头部。
   *
   * - `ParamBodyAngleX`：与头部同源（协议 §8.2 的 `-yaw*10`），按 combineBodyAngleX() 相加后
   *   clamp 到 ±30，两个来源不会互相打架；
   * - `ParamBodyAngleY/Z`、`ParamArmLA/RA`、`ParamArmLB/RB`（肘）、`ParamShoulderL/R`（肩）：
   *   模型没有对应参数时自动跳过（见 bodySupport）；
   * - 传 null / {}：本帧没有可用的上半身数据（ubOk=false / 过期 / 该侧低可见度），
   *   立刻停止驱动这些参数，把控制权交还给 idle motion / breath，避免冻结在最后一帧。
   *
   * 平滑（丢失时按帧回 0）由调用方 app.js 的 smoothBodyParams() 做好，这里只负责写。
   *
   * @param {object|null} params mapBody(...).params
   */
  setBodyParams(params) {
    const next = Object.create(null);
    if (params && typeof params === "object") {
      for (const id of BODY_PARAM_IDS) {
        const value = params[id];
        if (typeof value === "number" && Number.isFinite(value)) next[id] = value;
      }
    }
    for (const id of this.bodyOnlyParamIds) {
      if (Object.prototype.hasOwnProperty.call(next, id)) continue;
      // 不再驱动的参数：清掉缓存值，否则会一直写最后一帧（idle motion 永远起不来）
      delete this._frameValues[id];
      delete this.currentParams[id];
    }
    if (typeof next.ParamBodyAngleX !== "number" || !Number.isFinite(next.ParamBodyAngleX)) this.currentTorsoX = null;
    this.bodyParams = next;
  }

  /** 模型是否真的有这个参数（getParameterIndex >= 0 且 id 在真实参数表里）。 */
  _hasParam(core, id) {
    if (!core) return false;
    try {
      const index = core.getParameterIndex(id);
      if (!(typeof index === "number" && index >= 0)) return false;
      const ids = core._parameterIds ?? core._model?.parameters?.ids;
      if (Array.isArray(ids) && ids.length > 0) return ids.includes(id);
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 模型对某个参数声明的 {def, min, max}；读不到返回 null（写入时退化为不缩放）。
   * 参数 id 表与最小/最大/默认值数组在 core 的同一个 struct 里，下标一一对应。
   */
  _readParamRange(core, id) {
    if (!core) return null;
    try {
      const ids = core._parameterIds ?? core._model?.parameters?.ids ?? core.parameters?.ids ?? [];
      const struct = core._model?.parameters ?? core.parameters ?? null;
      const index = ids.indexOf(id);
      if (index < 0 || !struct) return null;
      const min = struct.minimumValues?.[index];
      const max = struct.maximumValues?.[index];
      let def = struct.defaultValues?.[index];
      if (!Number.isFinite(def)) def = core.getParameterDefaultValue?.(core.getParameterIndex(id));
      def = Number.isFinite(def) ? def : 0;
      if (!Number.isFinite(min) || !Number.isFinite(max)) return null;
      return { def, min, max };
    } catch {
      return null;
    }
  }

  /** 上半身参数：契约单位（±30）→ 模型实际量程（读不到量程就原样写，保持旧行为）。 */
  _mapBodyValue(id, value) {
    const range = this.bodyParamRanges?.[id];
    return range ? toModelRange(value, BODY_ANGLE_LIMIT, range) : value;
  }

  /** 模型自身的参数默认值（断线后回到中性用）。 */
  getDefaultParams() {
    const result = Object.create(null);
    const internal = this.model?.internalModel;
    const core = internal?.coreModel;
    if (!core) return result;
    const realIds = core._parameterIds ?? core._model?.parameters?.ids ?? [];
    for (const id of PARAM_IDS) {
      if (!realIds.includes(id)) continue;
      try {
        result[id] = core.getParameterDefaultValue(core.getParameterIndex(id));
      } catch {
        /* 该模型没有这个参数就不驱动它，交给 idle motion */
      }
    }
    return result;
  }

  /**
   * 断线 / 停止推流：先平滑回模型默认姿态（不会保留最后一帧的鬼畜表情），
   * durationMs 之后清空目标，把参数控制权交还给 idle motion / breath。
   */
  relax(durationMs = 700) {
    if (!this.model) return;
    this.setTargetParams(this.getDefaultParams());
    // 上半身参数也一起平滑回 0（接下来几帧由每帧缓动走完），durationMs 后统一释放给 idle motion
    const zeroBody = Object.create(null);
    for (const id of this.bodyOnlyParamIds) {
      if (Object.prototype.hasOwnProperty.call(this._frameValues, id)) zeroBody[id] = 0;
    }
    if (Object.prototype.hasOwnProperty.call(this._frameValues, "ParamBodyAngleX")) zeroBody.ParamBodyAngleX = 0;
    this.bodyParams = zeroBody;
    this._clearRelaxTimer();
    this._relaxTimer = setTimeout(() => {
      this.clearTargetParams();
      this._relaxTimer = null;
    }, Math.max(0, durationMs));
  }

  /** 直接读取引擎里的实际参数值（调试 / 自测用）。 */
  getEngineParam(id) {
    try {
      return this.model?.internalModel?.coreModel?.getParameterValueById(id);
    } catch {
      return undefined;
    }
  }

  /**
   * 把参数写进 Cubism。
   * @param {boolean} step true = 推进一帧（每帧一次）；false = 用本帧已算好的值直接写（物理演算前同步）
   */
  applyParams(step) {
    const model = this.model;
    if (!model) return;
    const core = model.internalModel?.coreModel;
    if (!core) return;

    if (!step) {
      this._writeParams(core, this._frameValues);
      return;
    }

    const now = performance.now();
    const values = this._computeFrameValues(now, this._immediate);
    this._immediate = false;
    if (!values) return;
    this.lastApplyAt = now;
    this._lastStepAt = now;
    this._writeParams(core, values);
  }

  /** 每帧算一次参数：时间轴插值（或最新目标）+ 时间常数缓动。 */
  _computeFrameValues(now, immediate) {
    const target = this._resolveTarget(now);
    if (!target) return null;

    const dt =
      this._lastStepAt > 0 ? Math.max(0, Math.min(now - this._lastStepAt, MAX_STEP_MS)) : REFERENCE_FRAME_MS;
    let alpha = 1;
    if (!immediate && this.smoothing < 1) {
      // 插值本身已经连续，这里只留一点点平滑压输入噪声
      const tau = this.timeline.interpolating ? Math.min(this._smoothingTau, INTERP_SMOOTH_TAU_MS) : this._smoothingTau;
      alpha = tau > 0 ? 1 - Math.exp(-dt / tau) : 1;
    }

    const values = this._frameValues;
    for (const id of PARAM_IDS) {
      const goal = target[id];
      if (typeof goal !== "number" || !Number.isFinite(goal)) continue;
      const current = this.currentParams[id];
      let value = goal;
      if (alpha < 1) {
        const base = typeof current === "number" && Number.isFinite(current) ? current : goal;
        value = base + (goal - base) * alpha;
        // 足够接近就直接落位，避免浮点尾巴让参数永远"差一点"
        if (Math.abs(goal - value) < 0.0005) value = goal;
      }
      this.currentParams[id] = value;
      // ParamBodyAngleX 属于上半身契约（±30 量程）：写进模型前按实际量程等比缩放
      values[id] = id === "ParamBodyAngleX" ? this._mapBodyValue(id, value) : value;
    }

    // 上半身：在头部参数之后叠加（ParamBodyAngleX 相加后 clamp，其余写可选参数）。
    // Pose 只有 ~15 fps，按 0.15~0.2 s 一阶低通再写（协议 §8.5 建议），
    // 与头部参数的时间轴插值互不影响；immediate（截图 / 单步）直接落位。
    const bodyAlpha = immediate ? 1 : 1 - Math.exp(-dt / BODY_SMOOTH_TAU_MS);
    const body = this.bodyParams;
    const torsoX = body?.ParamBodyAngleX;
    if (typeof torsoX === "number" && Number.isFinite(torsoX)) {
      const previous =
        typeof this.currentTorsoX === "number" && Number.isFinite(this.currentTorsoX) ? this.currentTorsoX : torsoX;
      const smoothed = bodyAlpha >= 1 ? torsoX : previous + (torsoX - previous) * bodyAlpha;
      this.currentTorsoX = Math.abs(torsoX - smoothed) < 0.0005 ? torsoX : smoothed;
      const headX = this.currentParams.ParamBodyAngleX;
      values.ParamBodyAngleX = this._mapBodyValue(
        "ParamBodyAngleX",
        combineBodyAngleX(
          typeof headX === "number" && Number.isFinite(headX) ? headX : 0,
          this.currentTorsoX,
        ),
      );
    }
    for (const id of this.bodyOnlyParamIds) {
      const goal = body?.[id];
      if (typeof goal !== "number" || !Number.isFinite(goal)) continue;
      const current = this.currentParams[id];
      let value = goal;
      if (bodyAlpha < 1) {
        const base = typeof current === "number" && Number.isFinite(current) ? current : goal;
        value = base + (goal - base) * bodyAlpha;
        if (Math.abs(goal - value) < 0.0005) value = goal;
      }
      this.currentParams[id] = value;
      // 契约单位（±30）→ 模型实际量程（Haru 的 ParamArmLA 是 [-1,1]，不缩放会 clamp 死）
      values[id] = this._mapBodyValue(id, value);
    }
    return values;
  }

  /** 取当前应显示的目标：插值缓冲优先，没有样本时用最新目标参数。 */
  _resolveTarget(now) {
    if (this.interpolate && this.timeline.count > 0) {
      const sampled = this.timeline.sample(now);
      if (sampled) return sampled;
    }
    return this.targetParams;
  }

  _writeParams(core, values) {
    if (!values) return;
    // paramIds = PARAM_IDS + 该模型真实存在的上半身可选参数（不存在的绝不会被写）
    for (const id of this.paramIds) {
      const value = values[id];
      if (typeof value === "number" && Number.isFinite(value)) {
        core.setParameterValueById(id, value);
      }
    }
  }

  /** 立即把画面同步到最新目标参数（截图 / 单步调试用，不依赖 rAF）。 */
  renderNow() {
    if (!this.app || !this.model) return;
    // 截图 / 自测要的是「最新目标」，不能等插值缓冲，也不能把延迟留在时间轴上
    this.timeline.reset({ keepTiming: true });
    this._immediate = true;
    this.applyParams(true);
    this._immediate = false;
    this.model.update(16.7);
    this.app.renderer.render(this.app.stage);
  }

  /** 设置缓动系数（1 = 立即）——按 60 fps 的每帧系数解释，内部换算成时间常数。 */
  setSmoothing(value) {
    const v = Math.max(0.05, Math.min(1, Number(value) || 1));
    this.smoothing = v;
    this._smoothingTau = this._tauFor(v);
    return v;
  }

  /**
   * 设置渲染目标帧率（0 = 不限制）。
   * @param {number} fps
   * @returns {number} 实际生效的目标帧率
   */
  setTargetFps(fps) {
    const value = Number(fps);
    let next = DEFAULT_TARGET_FPS;
    if (Number.isFinite(value) && value <= 0) next = 0;
    else if (Number.isFinite(value) && value > 0) next = Math.min(Math.max(Math.round(value), MIN_TARGET_FPS), MAX_TARGET_FPS);

    this.targetFps = next;
    this.timeline.options.renderIntervalMs = next > 0 ? 1000 / next : REFERENCE_FRAME_MS;

    const ticker = this.app?.ticker;
    if (ticker) {
      if ("maxFPS" in ticker) {
        ticker.maxFPS = next; // PIXI 7：0 = 只在 vsync 上跑
      } else {
        console.warn("[vfc] 当前 PIXI 版本不支持 Ticker.maxFPS，渲染帧率不会被限制");
      }
    }
    return next;
  }

  /** 最近 0.5 s 的实测渲染帧率（限帧跳帧时 ticker.FPS 会剧烈跳动，不能用它）。 */
  getRenderFps() {
    return this._renderFps;
  }

  /** 插值状态（调试面板 / 自检用）。 */
  getInterpolatorStats() {
    const timeline = this.timeline;
    return {
      active: this.interpolate && timeline.interpolating,
      delayMs: Math.round(timeline.delayMs * 10) / 10,
      inputIntervalMs: Math.round(timeline.inputIntervalMs * 10) / 10,
      jitterMs: Math.round(Math.max(timeline.inputJitterMs, timeline.transitJitterMs) * 10) / 10,
      transitJitterMs: Math.round(timeline.transitJitterMs * 10) / 10,
      samples: timeline.count,
      starvedFrames: timeline.starvedFrames,
      starvedEpisodes: timeline.starvedEpisodes,
    };
  }

  /** 清空参数驱动（回到模型默认姿态，用于断开连接后的 idle）。 */
  clearTargetParams() {
    this.targetParams = Object.create(null);
    this.currentParams = Object.create(null);
    this._frameValues = Object.create(null);
    this.bodyParams = Object.create(null);
    this.currentTorsoX = null;
    this.timeline.reset();
  }

  destroy() {
    this._clearRelaxTimer();
    if (this._tickerFn && this.app) this.app.ticker.remove(this._tickerFn);
    if (this._resizeFn) window.removeEventListener("resize", this._resizeFn);
    if (this.model) {
      try {
        this.model.destroy({ children: true, texture: true, baseTexture: true });
      } catch {
        /* ignore */
      }
    }
    if (this.app) this.app.destroy(false, { children: true, texture: true });
    this.app = null;
    this.model = null;
    this.ready = false;
  }

  // ---------------------------------------------------------------- 内部

  /** smoothing（60 fps 每帧系数）→ 时间常数 τ（ms）。 */
  _tauFor(smoothing) {
    if (!(smoothing < 1)) return 0;
    const s = Math.max(0.01, smoothing);
    return -REFERENCE_FRAME_MS / Math.log(1 - s);
  }

  _clearRelaxTimer() {
    if (this._relaxTimer) {
      clearTimeout(this._relaxTimer);
      this._relaxTimer = null;
    }
  }

  /** 渲染帧率统计：0.5 s 窗口 + 幂等 EMA，避免限帧后数字乱跳。 */
  _countFrame(now) {
    if (!this._renderWindowStart) this._renderWindowStart = now;
    this._renderFrameCount += 1;
    const windowMs = now - this._renderWindowStart;
    if (windowMs < FPS_WINDOW_MS) return;
    const instant = (this._renderFrameCount * 1000) / windowMs;
    this._renderFps = this._renderFps > 0 ? this._renderFps * 0.5 + instant * 0.5 : instant;
    this._renderFrameCount = 0;
    this._renderWindowStart = now;
  }
}
