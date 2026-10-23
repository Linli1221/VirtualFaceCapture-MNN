
// 诊断上半身骨骼参数绑定（Haru/Hiyori）：喂一帧「双臂下垂 + 前臂向外平摆（屈肘 90°）」的
// 合成 ub，对比 ①映射输出 ②参数支持判定 ③模型参数量程 ④引擎里真正生效的值。
// 期望（契约值）：armAngle≈41.4° → ParamArmLA/RA≈8.29；elbowBend=90 → ParamArmLB/RB=18；
// handDeg=90 → ParamHandL/R = ParamHandAngleL/R = 18。引擎实际值应为上述值按模型量程等比缩放。
(() => {
  const ub = new Array(52).fill(0);
  const set = (i, x, y, z, v) => { ub[i * 4] = x; ub[i * 4 + 1] = y; ub[i * 4 + 2] = z; ub[i * 4 + 3] = v; };
  set(0, 0.5, 0.16, 0, 0.95); set(1, 0.46, 0.15, 0, 0.95); set(2, 0.54, 0.15, 0, 0.95);
  set(3, 0.42, 0.17, 0, 0.95); set(4, 0.58, 0.17, 0, 0.95);
  set(5, 0.35, 0.4, 0, 0.95); set(6, 0.65, 0.4, 0, 0.95);          // 双肩
  set(7, 0.35, 0.57, 0, 0.95); set(8, 0.65, 0.57, 0, 0.95);        // 双肘：自然下垂
  set(9, 0.2, 0.57, 0, 0.95); set(10, 0.8, 0.57, 0, 0.95);         // 双腕：前臂向外平摆（屈肘 90°）
  set(11, 0.39, 0.86, 0, 0.95); set(12, 0.61, 0.86, 0, 0.95);      // 双胯

  const res = __vfc.applyUpperBody({ ub, body: [0, 0], ubOk: true });

  // 模型参数量程（Cubism 会把写入值 clamp 到 [min,max]，量程不对时“写了也白写”）
  const ranges = {};
  const internal = __vfc.stage.model && __vfc.stage.model.internalModel;
  const core = internal && internal.coreModel;
  const idSources = {
    _parameterIds: core && core._parameterIds,
    _model: core && core._model && core._model.parameters && core._model.parameters.ids,
    direct: core && core.parameters && core.parameters.ids,
  };
  const paramStruct = (core && core._model && core._model.parameters) || (core && core.parameters) || {};
  const ids = idSources._parameterIds || idSources._model || idSources.direct || [];
  for (const id of ["ParamArmLA", "ParamArmRA", "ParamArmLB", "ParamArmRB", "ParamShoulderL", "ParamShoulderR", "ParamHandL", "ParamHandR", "ParamHandAngleL", "ParamHandAngleR", "ParamBodyAngleZ"]) {
    const idx = ids.indexOf(id);
    const mins = paramStruct.minimumValues || [];
    const maxs = paramStruct.maximumValues || [];
    if (idx >= 0 && mins.length) {
      ranges[id] = {
        min: mins[idx],
        max: maxs[idx],
        engine: core.getParameterValueById(id),
      };
    } else {
      ranges[id] = { missing: true, engine: __vfc.stage.getEngineParam(id) };
    }
  }

  return {
    support: { ...(__vfcDebug.body.support || {}) },
    mapped: res.body.params,
    metrics: {
      armAngleL: __vfcDebug.body.armAngleL, armAngleR: __vfcDebug.body.armAngleR,
      elbowBendL: __vfcDebug.body.elbowBendL, elbowBendR: __vfcDebug.body.elbowBendR,
      confidence: __vfcDebug.body.confidence,
    },
    missingParams: __vfcDebug.missingParams,
    idSource: {
      has_parameterIds: Boolean(idSources._parameterIds),
      has_model: Boolean(idSources._model),
      has_direct: Boolean(idSources.direct),
      idCount: ids.length,
    },
    engine: {
      ParamArmLA: __vfcDebug.engine.ParamArmLA, ParamArmRA: __vfcDebug.engine.ParamArmRA,
      ParamArmLB: __vfcDebug.engine.ParamArmLB, ParamArmRB: __vfcDebug.engine.ParamArmRB,
      ParamHandL: __vfcDebug.engine.ParamHandL, ParamHandR: __vfcDebug.engine.ParamHandR,
      ParamHandAngleL: __vfcDebug.engine.ParamHandAngleL, ParamHandAngleR: __vfcDebug.engine.ParamHandAngleR,
      ParamBodyAngleZ: __vfcDebug.engine.ParamBodyAngleZ,
    },
    ranges,
    pixels: __vfc.pixelStats(),
  };
})()
