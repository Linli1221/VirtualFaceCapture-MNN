
// 自测用：冻结 WS 驱动 → 手动喂一帧 → 立即渲染 → 返回引擎里的实际参数与画面像素统计。
// 由 tools/shot.js --eval-file 执行（页面上下文里可直接用 window.__vfc）。
(() => {
  __vfc.freeze(true);
  const bs = new Array(52).fill(0);
  bs[9] = 1;  // eyeBlinkLeft
  bs[10] = 1; // eyeBlinkRight
  __vfc.applyTrack({ bs, pose: [0, 0, 0] });
  return {
    state: "closed",
    engine: { eyeL: __vfc.stage.getEngineParam("ParamEyeLOpen"), eyeR: __vfc.stage.getEngineParam("ParamEyeROpen"), mouthY: __vfc.stage.getEngineParam("ParamMouthOpenY"), angleX: __vfc.stage.getEngineParam("ParamAngleX"), angleY: __vfc.stage.getEngineParam("ParamAngleY") },
    pixels: __vfc.pixelStats(),
  };
})()
