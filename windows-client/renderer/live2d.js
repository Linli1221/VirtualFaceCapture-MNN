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
 * 全局依赖（index.html 按顺序加载）：window.Live2DCubismCore → window.PIXI → PIXI.live2d
 */
import { PARAM_IDS } from "./mapping.js";

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
   * @param {{canvas?:HTMLCanvasElement, smoothing?:number}} [options]
   *   smoothing = 每帧向目标值靠拢的系数（1 = 立即生效，0.6 ≈ 1~2 帧缓动）
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
    this.availableParams = [];
    this.lastApplyAt = 0;
    this.lastError = null;
    this._tickerFn = null;
    this._resizeFn = null;
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

    // 自己驱动 model.update，保证顺序在 PIXI 渲染之前（不依赖 PIXI.Ticker.shared）
    this._tickerFn = () => {
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
    if (this._relaxTimer) {
      clearTimeout(this._relaxTimer);
      this._relaxTimer = null;
    }
    this.targetParams = Object.create(null);
    this.currentParams = Object.create(null);

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
    this.ready = true;
    this.fit();
    return model;
  }

  /** 目标参数（每帧由 app.js 从 mapping 结果写入）。 */
  setTargetParams(params) {
    if (!params) return;
    if (this._relaxTimer) {
      clearTimeout(this._relaxTimer);
      this._relaxTimer = null;
    }
    this.targetParams = params;
  }

  /** 模型自身的参数默认值（断线后回到中性用）。 */
  getDefaultParams() {
    const result = Object.create(null);
    const core = this.model?.internalModel?.coreModel;
    if (!core) return result;
    const internal = this.model.internalModel;
    for (const id of PARAM_IDS) {
      const index = internal.coreModel.getParameterIndex(id);
      if (index < 0) continue;
      try {
        result[id] = core.getParameterDefaultValue(index);
      } catch {
        result[id] = 0;
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
    if (this._relaxTimer) clearTimeout(this._relaxTimer);
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
   * 把 targetParams 写进 Cubism 参数。
   * @param {boolean} step true = 推进一次缓动（每帧一次）；false = 用当前值直接写（物理演算前同步）
   */
  applyParams(step) {
    const model = this.model;
    if (!model) return;
    const core = model.internalModel?.coreModel;
    if (!core) return;
    this.lastApplyAt = performance.now();
    for (const id of PARAM_IDS) {
      const target = this.targetParams[id];
      if (typeof target !== "number" || !Number.isFinite(target)) continue;
      let value = target;
      if (this.smoothing < 1) {
        const current = typeof this.currentParams[id] === "number" ? this.currentParams[id] : target;
        if (step) {
          value = current + (target - current) * this.smoothing;
          if (Math.abs(target - value) < 0.0005) value = target;
          this.currentParams[id] = value;
        } else {
          value = current;
        }
      } else {
        this.currentParams[id] = target;
      }
      core.setParameterValueById(id, value);
    }
  }

  /** 立即把画面同步到最新目标参数（截图 / 单步调试用，不依赖 rAF）。 */
  renderNow() {
    if (!this.app || !this.model) return;
    this.applyParams(true);
    this.model.update(16.7);
    this.app.renderer.render(this.app.stage);
  }

  /** 设置缓动系数（1 = 立即）。 */
  setSmoothing(value) {
    const v = Math.max(0.05, Math.min(1, Number(value) || 1));
    this.smoothing = v;
    return v;
  }

  /** 清空参数驱动（回到模型默认姿态，用于断开连接后的 idle）。 */
  clearTargetParams() {
    this.targetParams = Object.create(null);
    this.currentParams = Object.create(null);
  }

  destroy() {
    if (this._relaxTimer) {
      clearTimeout(this._relaxTimer);
      this._relaxTimer = null;
    }
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
}
