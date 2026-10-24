#!/usr/bin/env node
/**
 * export-golden.mjs — 导出跨端黄金对拍数据集（Kotlin/JVM 侧消费）
 *
 *   node windows-client/tools/evals/golden-export/export-golden.mjs
 *
 * 产出（本目录）：
 *   <scenario>.json   单场景：{scenario, width, height, frames[], meta}
 *   manifest.json     清单 + 每文件 SHA-256 + JS 内部对拍审计
 *   README.md         格式说明 / 生成命令 / 校验哈希
 *
 * ── 口径（写死在 meta 里，避免两端各说各话）────────────────────────
 *   advancedMode = false（Android 当前实际行为，只产出 17 维）
 *   不调用 setCalibrationPose（校准姿态保持各实现默认 0）
 *   width=1280 / height=720（各向异性像素转换会带 H/W 因子，必须同画幅）
 *   nowMs 与 blink 时间戳都用 t（fixture 约定；生产口径是
 *   nowMs=performance.now()、blink 用帧采集时间 t，见 README）
 *   比对容差：Kotlin↔JS 用 abs 1e-4 / rel 1e-4（Lead 终裁）；JS↔JS 内部用 abs 1e-5 / rel 1e-4
 *
 * ── 为什么要存「原始点位」而不是只存特征参数 ──────────────────────
 *   Kotlin 侧要驱动的是**它的** BlendShapeExtractor / BlinkCurveProcessor，
 *   必须喂到与 JS 逐位相同的输入。所以点位以 Float32LE base64 落盘，
 *   不让 JVM 侧重新生成几何（那会变成第三份转录）。
 */

import { writeFileSync, mkdirSync } from "node:fs";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import { makeSequence, EYE } from "../golden-fixtures.mjs";
import { BlendShapeExtractor, BS_COUNT, BS_INDEX } from "../../../renderer/blendshape-extractor.js";
import { BlinkCurveProcessor } from "../../../renderer/blink-curve.js";
import { RefExtractor, RefBlink } from "../golden-reference.mjs";

const OUT_DIR = dirname(fileURLToPath(import.meta.url));
const IMAGE_W = 1280;
const IMAGE_H = 720;
const FPS = 30;
const FRAMES = 40;

const SCENARIOS = {
  neutral:   () => makeSequence({ frames: FRAMES, fps: FPS }),
  blink:     () => makeSequence({ frames: FRAMES, fps: FPS,
                 eyeHeight: (i) => (i >= 20 && i < 25 ? [EYE.closedHeight, EYE.closedHeight] : [EYE.openHeight, EYE.openHeight]) }),
  squint:    () => makeSequence({ frames: FRAMES, fps: FPS,
                 eyeHeight: (i) => (i >= 20 ? [0.0135, 0.0135] : [EYE.openHeight, EYE.openHeight]) }),
  mouthOpen: () => makeSequence({ frames: FRAMES, fps: FPS,
                 mouthOpen: (i) => (i >= 20 ? (i - 20) / 15 : 0) }),
  smile:     () => makeSequence({ frames: FRAMES, fps: FPS,
                 smile: (i) => (i >= 20 ? 0.02 : 0) }),
  frown:     () => makeSequence({ frames: FRAMES, fps: FPS,
                 smile: (i) => (i >= 20 ? -0.015 : 0) }),
  yawLeft:   () => makeSequence({ frames: FRAMES, fps: FPS, pose: [0, 20, 0] }),
  yawRight:  () => makeSequence({ frames: FRAMES, fps: FPS, pose: [0, -20, 0] }),
  pitchDown: () => makeSequence({ frames: FRAMES, fps: FPS, pose: [20, 0, 0] }),
  pitchUp:   () => makeSequence({ frames: FRAMES, fps: FPS, pose: [-20, 0, 0] }),
  rollTilt:  () => makeSequence({ frames: FRAMES, fps: FPS, pose: [0, 0, 15] }),
  longDt:    () => makeSequence({ frames: FRAMES, fps: 3.333 }),
};

function f32ToBase64(arr) {
  const bytes = new Uint8Array(arr.buffer, arr.byteOffset, arr.byteLength);
  let s = "";
  const CHUNK = 0x8000;
  for (let i = 0; i < bytes.length; i += CHUNK) {
    s += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
  }
  return Buffer.from(s, "binary").toString("base64");
}

const r9 = (x) => Number(x.toPrecision(9));
const sha256 = (s) => createHash("sha256").update(s, "utf8").digest("hex");

function runScenario(name, frames) {
  const opt = new BlendShapeExtractor({ advancedMode: false });
  const optBlink = new BlinkCurveProcessor();
  optBlink.updateFrameRate(FPS);

  const ref = new RefExtractor();
  const refBlink = new RefBlink();
  refBlink.l.setFps(FPS);
  refBlink.r.setFps(FPS);

  const out = [];
  let maxJsDivergence = 0;

  for (const fr of frames) {
    const ctx = { width: IMAGE_W, height: IMAGE_H, nowMs: fr.t };
    const raw = fr.landmarks;

    const outOpt = new Float32Array(BS_COUNT);
    opt.extractFullBlendShapes(raw, outOpt, fr.pose, ctx);
    const outRef = new Float32Array(BS_COUNT);
    ref.extract(raw, outRef, fr.pose, ctx);
    for (let d = 0; d < BS_COUNT; d++) {
      const diff = Math.abs(outOpt[d] - outRef[d]);
      if (diff > maxJsDivergence) maxJsDivergence = diff;
    }

    const [ol, or] = optBlink.process(outOpt[9], outOpt[10], fr.t);
    outOpt[9] = ol; outOpt[10] = or;

    out.push({
      t: r9(fr.t),
      landmarks: f32ToBase64(raw),
      poseDeg: [r9(fr.pose[0]), r9(fr.pose[1]), r9(fr.pose[2])],
      expectedBs52: Array.from(outOpt, r9),
      expectedBlinkOut: [r9(ol), r9(or)],
    });
  }

  return {
    scenario: name,
    width: IMAGE_W,
    height: IMAGE_H,
    frames: out,
    meta: {
      schemaVersion: "golden-fixture/v1",
      stage: 1,
      advancedMode: false,
      fps: FPS,
      frameCount: out.length,
      tolerance: { abs: 1e-4, rel: 1e-4, scope: "Kotlin↔JS 跨端", note: "Lead 终裁：|a-b| <= max(1e-4, 1e-4*|b|)。JS↔JS 内部另用 abs 1e-5 / rel 1e-4。" },
      dimOrder: Object.keys(BS_INDEX).sort((a, b) => BS_INDEX[a] - BS_INDEX[b]),
      landmarkEncoding: {
        format: "base64(Float32Array little-endian)",
        layout: "478*3 interleaved x,y,z",
        length: 478 * 3,
        units: "归一化 [0,1]、y 向下、z 相对深度",
        jvmDecode: "ByteBuffer.wrap(Base64.getDecoder().decode(s)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()",
      },
      timing: {
        calibrationNowMsSource: "t",
        blinkTimestampSource: "t",
        note: "fixture 约定两者都用 t；生产口径是 nowMs=performance.now() / blink 用帧采集时间 t。",
      },
      calibration: { setCalibrationPoseCalled: false, note: "校准姿态保持各实现默认 0" },
      generator: "windows-client/tools/evals/golden-export/export-golden.mjs",
      jsInternalReferenceAgreement: r9(maxJsDivergence),
    },
  };
}

mkdirSync(OUT_DIR, { recursive: true });

const manifest = {
  schemaVersion: "golden-fixture-manifest/v1",
  generator: "windows-client/tools/evals/golden-export/export-golden.mjs",
  generatedAt: new Date().toISOString(),
  command: "node windows-client/tools/evals/golden-export/export-golden.mjs",
  width: IMAGE_W,
  height: IMAGE_H,
  advancedMode: false,
  tolerance: { abs: 1e-4, rel: 1e-4 },
  files: [],
};

for (const [name, make] of Object.entries(SCENARIOS)) {
  const fx = runScenario(name, make());
  const text = JSON.stringify(fx);
  writeFileSync(join(OUT_DIR, name + ".json"), text, "utf8");
  manifest.files.push({
    file: name + ".json",
    scenario: name,
    frameCount: fx.meta.frameCount,
    bytes: Buffer.byteLength(text, "utf8"),
    sha256: sha256(text),
    jsInternalReferenceAgreement: fx.meta.jsInternalReferenceAgreement,
  });
}

const manifestText = JSON.stringify(manifest, null, 2);
writeFileSync(join(OUT_DIR, "manifest.json"), manifestText, "utf8");

console.log("场景          帧数   字节      JS内部对拍max   sha256");
for (const f of manifest.files) {
  console.log(
    f.scenario.padEnd(13) + String(f.frameCount).padStart(4) +
    String(f.bytes).padStart(10) + "   " +
    f.jsInternalReferenceAgreement.toExponential(2).padStart(12) + "   " +
    f.sha256.slice(0, 16)
  );
}
const total = manifest.files.reduce((s, x) => s + x.bytes, 0);
console.log("合计 " + total + " 字节（" + (total / 1024 / 1024).toFixed(2) + " MB）");
console.log("manifest sha256: " + sha256(manifestText).slice(0, 32));
