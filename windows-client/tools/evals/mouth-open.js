
// 自测用：冻结 WS 驱动 → 手动喂一帧 → 立即渲染 → 返回引擎里的实际参数与画面像素统计。
// 由 tools/shot.js --eval-file 执行（页面上下文里可直接用 window.__vfc）。
(() => {
  __vfc.freeze(true);
  const bs = new Array(52).fill(0);
  bs[25] = 0.9; // jawOpen
  bs[44] = 0.8; bs[45] = 0.8; // smile
  __vfc.applyTrack({ bs, pose: [0, 0, 0] });
  return {
    state: "mouth-open",
    engine: { eyeL: __vfc.stage.getEngineParam("ParamEyeLOpen"), eyeR: __vfc.stage.getEngineParam("ParamEyeROpen"), mouthY: __vfc.stage.getEngineParam("ParamMouthOpenY"), angleX: __vfc.stage.getEngineParam("ParamAngleX"), angleY: __vfc.stage.getEngineParam("ParamAngleY") },
    pixels: __vfc.pixelStats(),
  };
})()
