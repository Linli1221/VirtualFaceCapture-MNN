#!/usr/bin/env node
/**
 * mapping-test.mjs — protocol §8 numerical contract test for
 * windows-client/renderer/mapping.js (pure functions, no DOM).
 *
 * Run: node mapping-test.mjs
 * Exit: 0 pass, 1 fail, 2 pending (mapping.js missing)
 *
 * Coverage:
 *   - export surface / BS_INDEX 52 names / PARAM_IDS
 *   - §8.1 direct params (eyes, mouth, mouthForm, eyeBallX/Y) + clamp
 *   - §8.2 cold-start pose values (incl. pitch=+20 -> -15.14387, NOT -30)
 *   - §8.2 converged values (pitch 20 -> -15.0, pitch 40 -> -30,
 *     yaw 45 -> -30, yaw -90 -> +30, roll 30 -> +30)
 *   - AdaptiveAngleNormalizer.reset() reproducibility
 *   - createMapper.reset() reproducibility
 *   - 300-frame comparison against an independent reference implementation
 *     transcribed from docs/tracking-protocol.md §8
 *   - mapTrackToParams / mapTrack / mapBshapes / TrackMapper / normalizeBs / clamp
 */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import assert from 'node:assert/strict';
import { fileURLToPath, pathToFileURL } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..', '..');
const MAPPING = path.join(ROOT, 'windows-client', 'renderer', 'mapping.js');
const OUT_DIR = path.join(ROOT, 'build', 'tmp', 'verify');
const OUT_JSON = path.join(OUT_DIR, 'mapping-test.json');

const R = {
  tool: 'mapping-test.mjs',
  protocolRef: 'docs/tracking-protocol.md §8 (v1)',
  startedAt: new Date().toISOString(),
  env: { node: process.version, platform: process.platform, mappingPath: MAPPING },
  module: null,
  tests: [],
  referenceComparison: null,
  verdict: 'FAIL'
};

function test(name, fn) {
  try {
    fn();
    R.tests.push({ name, ok: true });
    console.log('[PASS] ' + name);
  } catch (e) {
    R.tests.push({ name, ok: false, error: e && e.message ? e.message : String(e) });
    console.log('[FAIL] ' + name + '  -> ' + (e && e.message ? e.message : e));
  }
}

function close(actual, expected, tol, label) {
  if (typeof actual !== 'number' || !Number.isFinite(actual)) {
    throw new Error((label || 'value') + ': expected ' + expected + ', got ' + String(actual));
  }
  if (Math.abs(actual - expected) > tol) {
    throw new Error((label || 'value') + ': expected ' + expected + ' +/- ' + tol + ', got ' + actual + ' (diff ' + Math.abs(actual - expected) + ')');
  }
}

function exact(actual, expected, label) {
  if (actual !== expected) throw new Error((label || 'value') + ': expected ' + expected + ', got ' + actual);
}

// ---------------------------------------------------------------- reference
// Transcribed line-by-line from docs/tracking-protocol.md §8.2.
const clampRef = (v, lo, hi) => (v < lo ? lo : v > hi ? hi : v);

class RefNormalizer {
  constructor(defaultNeg, defaultPos, minRange = 5, rate = 0.1) {
    this.defaultNeg = defaultNeg;
    this.defaultPos = defaultPos;
    this.minRange = minRange;
    this.rate = rate;
    this.pctNeg = 0;
    this.pctPos = 0;
    this.samples = 0;
  }
  normalize(v) {
    this.update(v);
    return v < 0 ? clampRef(v / this.effectiveNeg(), -1, 0) : clampRef(v / this.effectivePos(), 0, 1);
  }
  update(v) {
    this.samples++;
    const alpha = this.samples < 100 ? 0.05 : 0.01;
    if (v < 0) {
      const a = -v;
      this.pctNeg += a > this.pctNeg ? (a - this.pctNeg) * this.rate : (a - this.pctNeg) * alpha * 0.1;
    } else {
      this.pctPos += v > this.pctPos ? (v - this.pctPos) * this.rate : (v - this.pctPos) * alpha * 0.1;
    }
  }
  effectiveNeg() {
    const blend = Math.min(1, this.samples / 100);
    return Math.max(this.defaultNeg * (1 - blend) + this.pctNeg * blend, this.minRange);
  }
  effectivePos() {
    const blend = Math.min(1, this.samples / 100);
    return Math.max(this.defaultPos * (1 - blend) + this.pctPos * blend, this.minRange);
  }
  reset() {
    this.pctNeg = 0;
    this.pctPos = 0;
    this.samples = 0;
  }
}

const refNorms = () => ({
  pitch: new RefNormalizer(20, 40, 10),
  yaw: new RefNormalizer(45, 45, 15),
  roll: new RefNormalizer(30, 30, 10)
});

function refParams(bs, pose, norms, opts = {}) {
  const b = bs || [];
  const eyeLOpen = 1 - b[9];
  const eyeROpen = 1 - b[10];
  const mouthOpen = b[25];
  const mouthForm = clampRef((b[44] + b[45]) / 2 - (b[30] + b[31]) / 2, -1, 1);
  const eyeBallX = clampRef(((b[15] - b[13]) + (b[14] - b[16])) / 2, -1, 1);
  const eyeBallY = clampRef(((b[17] - b[11]) + (b[18] - b[12])) / 2, -1, 1);
  const angleScale = opts.angleScale === undefined ? 30 : opts.angleScale;
  const bodyScale = opts.bodyScale === undefined ? 10 : opts.bodyScale;
  const p = norms.pitch.normalize(pose[0]);
  const y = norms.yaw.normalize(pose[1]);
  const r = norms.roll.normalize(pose[2]);
  return {
    params: {
      ParamEyeLOpen: eyeLOpen,
      ParamEyeROpen: eyeROpen,
      ParamMouthOpenY: mouthOpen,
      ParamMouthForm: mouthForm,
      ParamEyeBallX: eyeBallX,
      ParamEyeBallY: eyeBallY,
      ParamAngleX: -y * angleScale,
      ParamAngleY: -p * angleScale,
      ParamAngleZ: r * angleScale,
      ParamBodyAngleX: -y * bodyScale
    },
    normalized: { pitch: p, yaw: y, roll: r },
    raw: { pitch: pose[0], yaw: pose[1], roll: pose[2] }
  };
}

const zeros = () => new Array(52).fill(0);
const bsOf = (over) => {
  const a = zeros();
  for (const k of Object.keys(over)) a[Number(k)] = over[k];
  return a;
};

function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------- load module
fs.mkdirSync(OUT_DIR, { recursive: true });
if (!fs.existsSync(MAPPING)) {
  R.verdict = 'PENDING';
  fs.writeFileSync(OUT_JSON, JSON.stringify(R, null, 2));
  console.error('[PENDING] mapping.js not found: ' + MAPPING);
  process.exit(2);
}
const rawBytes = fs.readFileSync(MAPPING);
R.module = {
  path: MAPPING,
  bytes: rawBytes.length,
  sha256: crypto.createHash('sha256').update(rawBytes).digest('hex'),
  mtime: fs.statSync(MAPPING).mtime.toISOString()
};

let api;
try {
  api = await import(pathToFileURL(MAPPING).href);
} catch (e) {
  R.verdict = 'FAIL';
  R.loadError = e && e.message ? e.message : String(e);
  fs.writeFileSync(OUT_JSON, JSON.stringify(R, null, 2));
  console.error('[FAIL] import mapping.js: ' + R.loadError);
  process.exit(1);
}

// ---------------------------------------------------------------- export surface
test('module exports AdaptiveAngleNormalizer / createNormalizers / createMapper / mapTrackToParams', () => {
  for (const fn of ['AdaptiveAngleNormalizer', 'createNormalizers', 'createMapper', 'mapTrackToParams']) {
    if (typeof api[fn] !== 'function') throw new Error('missing export: ' + fn);
  }
});
test('module exports BS_COUNT=52 / BS_INDEX / PARAM_IDS', () => {
  exact(api.BS_COUNT, 52, 'BS_COUNT');
  if (!api.BS_INDEX || typeof api.BS_INDEX !== 'object') throw new Error('BS_INDEX missing');
  assert.ok(Array.isArray(api.PARAM_IDS), 'PARAM_IDS is not an array');
});

const EXPECTED_BS = [
  'neutral', 'browDownLeft', 'browDownRight', 'browInnerUp', 'browOuterUpLeft', 'browOuterUpRight',
  'cheekPuff', 'cheekSquintLeft', 'cheekSquintRight', 'eyeBlinkLeft', 'eyeBlinkRight',
  'eyeLookDownLeft', 'eyeLookDownRight', 'eyeLookInLeft', 'eyeLookInRight', 'eyeLookOutLeft',
  'eyeLookOutRight', 'eyeLookUpLeft', 'eyeLookUpRight', 'eyeSquintLeft', 'eyeSquintRight',
  'eyeWideLeft', 'eyeWideRight', 'jawForward', 'jawLeft', 'jawOpen', 'jawRight', 'mouthClose',
  'mouthDimpleLeft', 'mouthDimpleRight', 'mouthFrownLeft', 'mouthFrownRight', 'mouthFunnel',
  'mouthLeft', 'mouthLowerDownLeft', 'mouthLowerDownRight', 'mouthPressLeft', 'mouthPressRight',
  'mouthPucker', 'mouthRight', 'mouthRollLower', 'mouthRollUpper', 'mouthShrugLower',
  'mouthShrugUpper', 'mouthSmileLeft', 'mouthSmileRight', 'mouthStretchLeft', 'mouthStretchRight',
  'mouthUpperUpLeft', 'mouthUpperUpRight', 'noseSneerLeft', 'noseSneerRight'
];
test('BS_INDEX matches protocol §5.1 table for all 52 indices', () => {
  const byIndex = new Array(52).fill(null);
  for (const k of Object.keys(api.BS_INDEX)) byIndex[api.BS_INDEX[k]] = k;
  for (let i = 0; i < 52; i++) {
    if (byIndex[i] !== EXPECTED_BS[i]) {
      throw new Error('index ' + i + ': expected ' + EXPECTED_BS[i] + ', got ' + byIndex[i]);
    }
  }
});
test('PARAM_IDS contains the 10 §8 parameters', () => {
  const expected = ['ParamEyeLOpen', 'ParamEyeROpen', 'ParamMouthOpenY', 'ParamMouthForm',
    'ParamEyeBallX', 'ParamEyeBallY', 'ParamAngleX', 'ParamAngleY', 'ParamAngleZ', 'ParamBodyAngleX'];
  for (const id of expected) if (!api.PARAM_IDS.includes(id)) throw new Error('PARAM_IDS missing ' + id);
});

// ---------------------------------------------------------------- §8.1 direct params
test('§8.1 bs[9]=1 -> ParamEyeLOpen=0 ; bs[9]=0 -> 1', () => {
  close(api.mapTrackToParams({ bs: bsOf({ 9: 1 }), pose: [0, 0, 0] }, api.createNormalizers()).ParamEyeLOpen, 0, 1e-12, 'eyeL closed');
  close(api.mapTrackToParams({ bs: zeros(), pose: [0, 0, 0] }, api.createNormalizers()).ParamEyeLOpen, 1, 1e-12, 'eyeL open');
});
test('§8.1 bs[10] -> ParamEyeROpen (1-bs[10])', () => {
  close(api.mapTrackToParams({ bs: bsOf({ 10: 0.25 }), pose: [0, 0, 0] }, api.createNormalizers()).ParamEyeROpen, 0.75, 1e-12, 'eyeR');
});
test('§8.1 bs[25]=0.5 -> ParamMouthOpenY=0.5', () => {
  close(api.mapTrackToParams({ bs: bsOf({ 25: 0.5 }), pose: [0, 0, 0] }, api.createNormalizers()).ParamMouthOpenY, 0.5, 1e-12, 'mouthOpen');
});
test('§8.1 ParamMouthForm = (bs44+bs45)/2-(bs30+bs31)/2', () => {
  const bs = bsOf({ 44: 1, 45: 1, 30: 0.5, 31: 0.5 });
  const p = api.mapTrackToParams({ bs, pose: [0, 0, 0] }, api.createNormalizers());
  close(p.ParamMouthForm, (1 + 1) / 2 - (0.5 + 0.5) / 2, 1e-12, 'mouthForm=0.5');
  const bs2 = bsOf({ 44: 0.2, 45: 0.4, 30: 0.6, 31: 0.8 });
  const p2 = api.mapTrackToParams({ bs: bs2, pose: [0, 0, 0] }, api.createNormalizers());
  close(p2.ParamMouthForm, (0.2 + 0.4) / 2 - (0.6 + 0.8) / 2, 1e-12, 'mouthForm negative');
});
test('§8.1 ParamEyeBallX/Y formulas', () => {
  const bs = bsOf({ 15: 1, 13: 0.2, 14: 0.4, 16: 0.1, 17: 1, 11: 0.2, 18: 0.3, 12: 0.1 });
  const p = api.mapTrackToParams({ bs, pose: [0, 0, 0] }, api.createNormalizers());
  close(p.ParamEyeBallX, ((1 - 0.2) + (0.4 - 0.1)) / 2, 1e-12, 'eyeBallX');
  close(p.ParamEyeBallY, ((1 - 0.2) + (0.3 - 0.1)) / 2, 1e-12, 'eyeBallY');
});
test('§8.1 eyeBall expression can reach +1 / -1', () => {
  const plus = bsOf({ 15: 1, 14: 1 });
  close(api.mapTrackToParams({ bs: plus, pose: [0, 0, 0] }, api.createNormalizers()).ParamEyeBallX, 1, 1e-12, 'eyeBallX +1');
  const minus = bsOf({ 13: 1, 16: 1 });
  close(api.mapTrackToParams({ bs: minus, pose: [0, 0, 0] }, api.createNormalizers()).ParamEyeBallX, -1, 1e-12, 'eyeBallX -1');
});

// ---------------------------------------------------------------- §8.2 cold start
test('§8.2 cold pitch=+20 -> ParamAngleY = -(20/39.62)*30 = -15.143867 (NOT -30)', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [20, 0, 0] }, api.createNormalizers());
  const effPos = 40 * 0.99 + (20 * 0.1) * 0.01; // 39.62 after first update()
  const expected = -(20 / effPos) * 30;
  close(p.ParamAngleY, expected, 1e-12, 'cold pitch+20');
  close(p.ParamAngleY, -15.143866733, 1e-9, 'cold pitch+20 known value');
});
test('§8.2 cold yaw=+45 -> ParamAngleX = -30', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [0, 45, 0] }, api.createNormalizers());
  close(p.ParamAngleX, -30, 1e-12, 'cold yaw+45');
});
test('§8.2 cold yaw=-45 -> ParamAngleX = +30', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [0, -45, 0] }, api.createNormalizers());
  close(p.ParamAngleX, 30, 1e-12, 'cold yaw-45');
});
test('§8.2 cold pitch=+40 clamps -> ParamAngleY = -30', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [40, 0, 0] }, api.createNormalizers());
  close(p.ParamAngleY, -30, 1e-12, 'cold pitch+40');
});
test('§8.2 cold pitch=-20 clamps -> ParamAngleY = +30', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [-20, 0, 0] }, api.createNormalizers());
  close(p.ParamAngleY, 30, 1e-12, 'cold pitch-20');
});
test('§8.2 cold roll=+30 -> ParamAngleZ = +30', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [0, 0, 30] }, api.createNormalizers());
  close(p.ParamAngleZ, 30, 1e-12, 'cold roll+30');
});
test('§8.2 default ParamBodyAngleX = -yawNormalized * 10', () => {
  const p = api.mapTrackToParams({ bs: zeros(), pose: [0, 45, 0] }, api.createNormalizers());
  close(p.ParamBodyAngleX, -10, 1e-12, 'bodyAngleX');
});
test('§8.2 custom angleScale/bodyScale honoured by createMapper(opts) and mapPose', () => {
  const m = api.createMapper({ angleScale: 45, bodyScale: 20 });
  const p = m.apply(zeros(), [0, 45, 0]).params;
  close(p.ParamAngleX, -45, 1e-12, 'createMapper custom angleScale');
  close(p.ParamBodyAngleX, -20, 1e-12, 'createMapper custom bodyScale');
  const pose = api.mapPose([0, 45, 0], api.createNormalizers(), { angleScale: 45, bodyScale: 20 });
  close(pose.params.ParamAngleX, -45, 1e-12, 'mapPose custom angleScale');
  close(pose.params.ParamBodyAngleX, -20, 1e-12, 'mapPose custom bodyScale');
  if (typeof api.mapTrackToParams({ bs: zeros(), pose: [0, 45, 0] }, api.createNormalizers()).ParamAngleX !== 'number') {
    throw new Error('mapTrackToParams still returns numbers');
  }
});

// ---------------------------------------------------------------- §8.2 convergence
test('§8.2 converged pitch: 150x(+40) then +20 -> ParamAngleY ~= -15.0 (tol 1e-2)', () => {
  const mapper = api.createMapper();
  for (let i = 0; i < 150; i++) mapper.apply(zeros(), [40, 0, 0]);
  const y = mapper.apply(zeros(), [20, 0, 0]).params.ParamAngleY;
  close(y, -15.0, 1e-2, 'converged pitch+20');
});
test('§8.2 converged pitch: then +40 -> ParamAngleY = -30 (tol 1e-2)', () => {
  const mapper = api.createMapper();
  for (let i = 0; i < 150; i++) mapper.apply(zeros(), [40, 0, 0]);
  for (let i = 0; i < 150; i++) mapper.apply(zeros(), [40, 0, 0]);
  close(mapper.apply(zeros(), [40, 0, 0]).params.ParamAngleY, -30, 1e-2, 'converged pitch+40');
});
test('§8.2 converged yaw +45 -> ParamAngleX = -30 (tol 1e-2)', () => {
  const mapper = api.createMapper();
  for (let i = 0; i < 150; i++) mapper.apply(zeros(), [0, 45, 0]);
  close(mapper.apply(zeros(), [0, 45, 0]).params.ParamAngleX, -30, 1e-2, 'converged yaw+45');
});
test('§8.2 yaw=-90 after +45 convergence clamps to +30 (tol 1e-2)', () => {
  const mapper = api.createMapper();
  for (let i = 0; i < 150; i++) mapper.apply(zeros(), [0, 45, 0]);
  close(mapper.apply(zeros(), [0, -90, 0]).params.ParamAngleX, 30, 1e-2, 'yaw -90 clamped');
});
test('§8.2 converged roll +30 -> ParamAngleZ = +30 (tol 1e-2)', () => {
  const mapper = api.createMapper();
  for (let i = 0; i < 150; i++) mapper.apply(zeros(), [0, 0, 30]);
  close(mapper.apply(zeros(), [0, 0, 30]).params.ParamAngleZ, 30, 1e-2, 'converged roll+30');
});

// ---------------------------------------------------------------- reset reproducibility
test('AdaptiveAngleNormalizer.reset() zeroes samples and replays identically', () => {
  const n = new api.AdaptiveAngleNormalizer(20, 40, 10);
  exact(n.samples, 0, 'fresh samples');
  close(n.effectiveNeg(), 20, 1e-12, 'fresh effectiveNeg');
  close(n.effectivePos(), 40, 1e-12, 'fresh effectivePos');
  const seq = [10, -5, 30, -40, 20, 0, -45, 45, 12, -22];
  const first = seq.map((v) => n.normalize(v));
  const samplesAfter = n.samples;
  exact(samplesAfter, seq.length, 'samples after seq');
  n.reset();
  exact(n.samples, 0, 'samples after reset');
  const second = seq.map((v) => n.normalize(v));
  assert.deepStrictEqual(second, first, 'normalize output after reset differs');
});
test('createMapper.reset() replays the same tracks identically', () => {
  const mapper = api.createMapper();
  const seq = [
    { bs: bsOf({ 9: 1, 25: 0.4 }), pose: [12, -20, 6] },
    { bs: bsOf({ 44: 0.8, 30: 0.2 }), pose: [-8, 30, -12] },
    { bs: bsOf({ 25: 0.9 }), pose: [35, 10, 25] },
    { bs: zeros(), pose: [-30, -40, -20] }
  ];
  const first = seq.map((t) => mapper.apply(t.bs, t.pose).params);
  const samples = [mapper.normalizers.pitch.samples, mapper.normalizers.yaw.samples, mapper.normalizers.roll.samples];
  assert.deepStrictEqual(samples, [seq.length, seq.length, seq.length], 'normalizer samples not advanced');
  mapper.reset();
  assert.deepStrictEqual([mapper.normalizers.pitch.samples, mapper.normalizers.yaw.samples, mapper.normalizers.roll.samples], [0, 0, 0], 'reset did not clear samples');
  const second = seq.map((t) => mapper.apply(t.bs, t.pose).params);
  assert.deepStrictEqual(second, first, 'mapper params after reset differ');
});
test('createMapper exposes normalizers with samples/effectiveNeg/effectivePos', () => {
  const mapper = api.createMapper();
  mapper.apply(zeros(), [10, 20, 30]);
  for (const k of ['pitch', 'yaw', 'roll']) {
    const n = mapper.normalizers[k];
    if (!(n instanceof api.AdaptiveAngleNormalizer)) throw new Error(k + ' is not AdaptiveAngleNormalizer');
    exact(n.samples, 1, k + '.samples');
    if (!(n.effectiveNeg() > 0) || !(n.effectivePos() > 0)) throw new Error(k + ' effective ranges invalid');
  }
});

// ---------------------------------------------------------------- independent reference comparison
test('300-frame comparison against independent §8 reference implementation', () => {
  const mapper = api.createMapper();
  const norms = refNorms();
  const rand = mulberry32(1234567);
  const usedPoses = [[0, 0, 0], [20, 0, 0], [0, 45, 0], [0, -45, 0], [40, 0, 0], [-20, 0, 0], [0, 0, 30], [0, -90, 0]];
  let maxParamDiff = 0;
  let maxNormDiff = 0;
  let worst = null;
  for (let i = 0; i < 300; i++) {
    const bs = zeros().map(() => (i % 7 === 0 ? 0 : rand()));
    const pose = i < usedPoses.length ? usedPoses[i].slice() : [(rand() * 2 - 1) * 70, (rand() * 2 - 1) * 70, (rand() * 2 - 1) * 70];
    const got = mapper.apply(bs, pose);
    const exp = refParams(bs, pose, norms);
    for (const id of Object.keys(exp.params)) {
      const d = Math.abs(got.params[id] - exp.params[id]);
      if (d > maxParamDiff) { maxParamDiff = d; worst = { frame: i, param: id, got: got.params[id], exp: exp.params[id] }; }
    }
    for (const k of ['pitch', 'yaw', 'roll']) {
      const d = Math.abs(got.normalized[k] - exp.normalized[k]);
      if (d > maxNormDiff) maxNormDiff = d;
    }
    for (const k of ['pitch', 'yaw', 'roll']) close(got.normalized[k], exp.normalized[k], 1e-12, 'frame ' + i + ' normalized.' + k);
  }
  R.referenceComparison = { frames: 300, maxParamDiff, maxNormDiff, worst };
  if (maxParamDiff > 1e-12) throw new Error('max param diff ' + maxParamDiff + ' at ' + JSON.stringify(worst));
});

// ---------------------------------------------------------------- convenience API
test('map_bShapes: eye/mouth/mouthForm/eyeBall keys present and correct', () => {
  const f = api.mapBshapes(bsOf({ 9: 1, 10: 0.5, 25: 0.3, 44: 1, 30: 0.5 }));
  close(f.eyeLOpen, 0, 1e-12, 'mapBshapes eyeL');
  close(f.eyeROpen, 0.5, 1e-12, 'mapBshapes eyeR');
  close(f.mouthOpen, 0.3, 1e-12, 'mapBshapes mouthOpen');
  close(f.mouthForm, 0.5 - 0.25, 1e-12, 'mapBshapes mouthForm');
  exact(typeof f.eyeBallX, 'number', 'mapBshapes eyeBallX');
  exact(typeof f.eyeBallY, 'number', 'mapBshapes eyeBallY');
});
test('mapTrack is stateless (two identical calls give identical params)', () => {
  const track = { bs: bsOf({ 9: 1, 25: 0.5 }), pose: [10, 30, -5] };
  assert.deepStrictEqual(api.mapTrack(track), api.mapTrack(track));
  close(api.mapTrack(track).ParamEyeLOpen, 0, 1e-12, 'mapTrack eye');
});
test('TrackMapper.apply(track) returns flat params and updates .result', () => {
  const tm = new api.TrackMapper();
  const p = tm.apply({ bs: bsOf({ 9: 1 }), pose: [0, 45, 0] });
  close(p.ParamEyeLOpen, 0, 1e-12, 'TrackMapper eye');
  close(p.ParamAngleX, -30, 1e-12, 'TrackMapper angleX');
  if (!tm.result || !tm.result.params) throw new Error('TrackMapper.result missing');
  close(tm.result.params.ParamAngleX, -30, 1e-12, 'TrackMapper.result angleX');
});
test('normalizeBs pads to 52 with finite numbers, never throws', () => {
  const n1 = api.normalizeBs([0.5, 1]);
  exact(n1.length, 52, 'padded length');
  close(n1[0], 0.5, 1e-12, 'kept [0]');
  close(n1[1], 1, 1e-12, 'kept [1]');
  close(n1[51], 0, 1e-12, 'padded [51]');
  const n2 = api.normalizeBs([NaN, Infinity, -Infinity]);
  exact(n2[0], 0, 'NaN->0');
  exact(n2[1], 0, 'Infinity->0');
  exact(n2[2], 0, '-Infinity->0');
  exact(api.normalizeBs(undefined).length, 52, 'undefined safe');
  exact(api.normalizeBs(new Float32Array(52)).length, 52, 'typed array safe');
});
test('clamp(v, lo, hi)', () => {
  exact(api.clamp(5, 0, 1), 1);
  exact(api.clamp(-2, 0, 1), 0);
  exact(api.clamp(0.5, 0, 1), 0.5);
});

// ---------------------------------------------------------------- upper body (protocol §4.2 / §5.3)
// 协议顺序（CommonData.UpperBodyLandmarkIndex.ORDER）
const BP = {
  nose: 0, eye_left: 1, eye_right: 2, ear_left: 3, ear_right: 4,
  shoulder_left: 5, shoulder_right: 6, elbow_left: 7, elbow_right: 8,
  wrist_left: 9, wrist_right: 10, hip_left: 11, hip_right: 12
};
/** 构造 52 项 ub：默认直立站姿、双臂自然下垂（肘在肩腕中点 = 手臂伸直）。 */
function makeUb({
  shoulderL = [0.35, 0.4], shoulderR = [0.65, 0.4],
  elbowL = null, elbowR = null,
  wristL = [0.35, 0.78], wristR = [0.65, 0.78],
  hipL = [0.39, 0.86], hipR = [0.61, 0.86],
  visibility = 0.9, visibilityL = null, visibilityR = null
} = {}) {
  const ub = new Array(52).fill(0);
  const set = (i, x, y, z, v) => { ub[i * 4] = x; ub[i * 4 + 1] = y; ub[i * 4 + 2] = z; ub[i * 4 + 3] = v; };
  const vL = visibilityL == null ? visibility : visibilityL;
  const vR = visibilityR == null ? visibility : visibilityR;
  set(BP.nose, 0.5, 0.16, 0, visibility);
  set(BP.eye_left, 0.46, 0.15, 0, visibility);
  set(BP.eye_right, 0.54, 0.15, 0, visibility);
  set(BP.ear_left, 0.42, 0.17, 0, visibility);
  set(BP.ear_right, 0.58, 0.17, 0, visibility);
  set(BP.shoulder_left, shoulderL[0], shoulderL[1], 0, vL);
  set(BP.shoulder_right, shoulderR[0], shoulderR[1], 0, vR);
  const mid = (a, b) => [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  const eL = elbowL || mid(shoulderL, wristL);
  const eR = elbowR || mid(shoulderR, wristR);
  set(BP.elbow_left, eL[0], eL[1], 0, vL);
  set(BP.elbow_right, eR[0], eR[1], 0, vR);
  set(BP.wrist_left, wristL[0], wristL[1], 0, vL);
  set(BP.wrist_right, wristR[0], wristR[1], 0, vR);
  set(BP.hip_left, hipL[0], hipL[1], 0, visibility);
  set(BP.hip_right, hipR[0], hipR[1], 0, visibility);
  return ub;
}

test('upper body: BODY_LANDMARK_ORDER / BODY_BONES match CommonData (§5.3)', () => {
  exact(api.BODY_COUNT, 13, 'BODY_COUNT');
  exact(api.BODY_FLOATS, 52, 'BODY_FLOATS');
  assert.deepStrictEqual(Object.values(api.BODY_LANDMARK_ORDER), [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]);
  exact(api.BODY_BONES.length, 15, 'BODY_BONES.length');
  assert.deepStrictEqual(
    api.BODY_BONES.map((e) => [...e]),
    [[0, 1], [0, 2], [1, 3], [2, 4], [1, 2], [0, 5], [0, 6], [5, 6], [5, 7], [7, 9], [6, 8], [8, 10], [5, 11], [6, 12], [11, 12]]
  );
  assert.deepStrictEqual(
    [...api.BODY_PARAM_IDS],
    ['ParamBodyAngleX', 'ParamBodyAngleY', 'ParamBodyAngleZ',
     'ParamArmLA', 'ParamArmRA', 'ParamArmLB', 'ParamArmRB',
     'ParamShoulderL', 'ParamShoulderR',
     'ParamHandL', 'ParamHandR', 'ParamHandAngleL', 'ParamHandAngleR']
  );
});

test('upper body: parseUpperBody 52 -> 13 points, wrong length -> null, NaN -> 0', () => {
  const pts = api.parseUpperBody(makeUb());
  exact(pts.length, 13, 'points length');
  close(pts[BP.shoulder_left].x, 0.35, 1e-12, 'shoulder_left.x');
  close(pts[BP.wrist_right].v, 0.9, 1e-12, 'wrist_right.v');
  exact(api.parseUpperBody(new Array(51).fill(0)), null, '51 -> null');
  exact(api.parseUpperBody(new Array(53).fill(0)), null, '53 -> null');
  exact(api.parseUpperBody(null), null, 'null -> null');
  const broken = makeUb();
  broken[BP.nose * 4] = NaN;
  broken[BP.nose * 4 + 3] = 7;
  const fixed = api.parseUpperBody(broken);
  exact(fixed[BP.nose].x, 0, 'NaN -> 0');
  exact(fixed[BP.nose].v, 1, 'visibility clamped to 1');
});

test('upper body: mapBody neutral -> all params 0, shoulder width, confidence', () => {
  const m = api.mapBody([0, 0], makeUb());
  close(m.params.ParamBodyAngleX, 0, 1e-12, 'BodyAngleX');
  close(m.params.ParamBodyAngleY, 0, 1e-12, 'BodyAngleY');
  close(m.params.ParamBodyAngleZ, 0, 1e-12, 'BodyAngleZ');
  close(m.params.ParamArmLA, 0, 1e-12, 'ArmLA');
  close(m.params.ParamArmRA, 0, 1e-12, 'ArmRA');
  close(m.metrics.shoulderWidth, 0.3, 1e-12, 'shoulderWidth');
  close(m.metrics.confidence, 0.9, 1e-12, 'confidence');
});

test('upper body: mapBody arm formulas (raise -> ParamArmLA, other side unchanged)', () => {
  const raised = api.mapBody([0, 0], makeUb({ wristL: [0.2, 0.05] }));
  close(raised.params.ParamArmLA, 30, 1e-12, 'ArmLA saturated');
  close(raised.params.ParamArmRA, 0, 1e-12, 'ArmRA unchanged');
  close(raised.metrics.armAngleL, (Math.atan2(0.15, -0.35) * 180) / Math.PI, 1e-9, 'armAngleL');
  const horizontal = api.mapBody([0, 0], makeUb({ wristL: [0.05, 0.4] }));
  close(horizontal.metrics.armAngleL, 90, 1e-12, 'armAngleL 90');
  close(horizontal.params.ParamArmLA, 18, 1e-12, 'ArmLA = 90/150*30 = 18');
});

test('upper body: mapBody body formulas (§5.3 Z=roll*0.5, X=-yaw*0.3, Y=-roll*0.2, clamp 30)', () => {
  close(api.mapBody([10, 0], null).params.ParamBodyAngleZ, 5, 1e-12, 'Z=roll*0.5');
  close(api.mapBody([10, 0], null).params.ParamBodyAngleY, -2, 1e-12, 'Y=-roll*0.2');
  close(api.mapBody([0, 20], null).params.ParamBodyAngleX, -6, 1e-12, 'X=-yaw*0.3');
  const ext = api.mapBody([300, 200], null).params;
  close(ext.ParamBodyAngleZ, 30, 1e-12, 'Z clamp');
  close(ext.ParamBodyAngleY, -30, 1e-12, 'Y clamp');
  close(ext.ParamBodyAngleX, -30, 1e-12, 'X clamp');
});

test('upper body: mapBody omits that side arm params when visibility < 0.3', () => {
  const low = api.mapBody([0, 0], makeUb({ visibilityL: 0.1, wristL: [0.2, 0.05] }));
  if ('ParamArmLA' in low.params) throw new Error('ParamArmLA should be omitted (visibility < 0.3)');
  if (!('ParamArmRA' in low.params)) throw new Error('ParamArmRA should still be present');
  close(low.metrics.confidence, 0.1, 1e-12, 'confidence = min visibility');
  const edge = api.mapBody([0, 0], makeUb({ visibilityL: 0.3 }));
  if (!('ParamArmLA' in edge.params)) throw new Error('visibility == 0.3 must keep the params');
});

test('upper body: mapBody tolerates bad ub and supports docs §3.6 arg order', () => {
  for (const bad of [null, undefined, [], [1, 2, 3], new Array(52).fill(NaN)]) {
    const m = api.mapBody([0, 0], bad);
    if (!Number.isFinite(m.params.ParamBodyAngleZ)) throw new Error('bad ub produced non-finite body params');
    if ('ParamArmLA' in m.params) throw new Error('bad ub must not fabricate arm params');
  }
  const doc = api.mapBody(makeUb({ wristL: [0.05, 0.4] }), [3, -7]);
  const task = api.mapBody([3, -7], makeUb({ wristL: [0.05, 0.4] }));
  close(doc.params.ParamBodyAngleX, task.params.ParamBodyAngleX, 1e-12, 'doc order X');
  close(doc.params.ParamArmLA, task.params.ParamArmLA, 1e-12, 'doc order ArmLA');
});

test('upper body skeleton (§8.5): elbow bend -> ParamArmLB/RB, symmetric sign, straight = 0', () => {
  const bent = api.mapBody([0, 0], makeUb({
    elbowL: [0.35, 0.57], wristL: [0.26, 0.57],
    elbowR: [0.65, 0.57], wristR: [0.74, 0.57]
  }));
  close(bent.params.ParamArmLB, 18, 1e-9, 'ParamArmLB = 90/150*30');
  close(bent.params.ParamArmRB, 18, 1e-9, 'ParamArmRB (mirror-symmetric)');
  close(bent.metrics.elbowBendL, 90, 0.01, 'elbowBendL');
  close(bent.metrics.elbowBendR, 90, 0.01, 'elbowBendR');
  // 手 = 前臂朝向的近似（侧别镜像后两侧同号），ParamHandL/R 与别名 ParamHandAngleL/R 同值
  close(bent.params.ParamHandL, 18, 1e-9, 'ParamHandL = 90/150*30');
  close(bent.params.ParamHandR, 18, 1e-9, 'ParamHandR');
  close(bent.params.ParamHandAngleL, bent.params.ParamHandL, 1e-12, 'ParamHandAngleL alias');
  const straight = api.mapBody([0, 0], makeUb());
  close(straight.params.ParamArmLB, 0, 1e-12, 'straight elbow = 0');
  close(straight.params.ParamArmRB, 0, 1e-12, 'straight elbow R = 0');
  close(straight.params.ParamHandL, 0, 1e-12, 'hanging hand = 0');
});

test('upper body skeleton (§8.5): clavicle differential -> ParamShoulderL/R opposite signs', () => {
  // right shoulder sinks (shoulder line +7.59deg, pelvis level) -> left shoulder up
  const shrug = api.mapBody([0, 0], makeUb({ shoulderL: [0.35, 0.38], shoulderR: [0.65, 0.42] }));
  close(shrug.metrics.clavicleRelTilt, (Math.atan2(0.04, 0.3) * 180) / Math.PI, 0.01, 'clavicleRelTilt');
  if (!(shrug.params.ParamShoulderL > 0)) throw new Error('ParamShoulderL should be positive (left up)');
  if (!(shrug.params.ParamShoulderR < 0)) throw new Error('ParamShoulderR should be negative');
  close(shrug.params.ParamShoulderL + shrug.params.ParamShoulderR, 0, 1e-9, 'antisymmetric');
  // whole-torso lean (shoulders and hips tilt the same angle) -> no shoulder drive
  const tilt = (8 * Math.PI) / 180;
  const shoulderDy = 0.3 * Math.tan(tilt);
  const hipDy = 0.22 * Math.tan(tilt);
  const lean = api.mapBody([0, 0], makeUb({
    shoulderL: [0.35, 0.4 - shoulderDy / 2], shoulderR: [0.65, 0.4 + shoulderDy / 2],
    hipL: [0.39, 0.86 - hipDy / 2], hipR: [0.61, 0.86 + hipDy / 2]
  }));
  close(lean.metrics.clavicleRelTilt, 0, 0.1, 'lean relTilt ~ 0');
  close(lean.params.ParamShoulderL, 0, 0.5, 'lean ParamShoulderL ~ 0');
});

test('upper body skeleton (§8.5): hierarchical bones keep arm angle torso-relative', () => {
  const sk = api.buildUpperBodySkeleton(makeUb({
    shoulderL: [0.4, 0.3], shoulderR: [0.7, 0.3],
    elbowL: [0.37058, 0.44709], wristL: [0.34117, 0.59417],
    hipL: [0.34, 0.8], hipR: [0.56, 0.8]
  }));
  const leanDeg = (Math.atan2(0.1, 0.5) * 180) / Math.PI;
  close(sk.spine.leanDeg, leanDeg, 0.01, 'spine lean');
  // arm hangs parallel to the torso: absolute angle follows the lean, torso-relative stays 0
  close(sk.arms.L.elevationDeg, leanDeg, 0.2, 'absolute elevation');
  close(sk.arms.L.elevationTorsoDeg, 0, 0.2, 'torso-relative elevation');
  if (!sk.arms.L.valid) throw new Error('arm should be valid');
});

test('upper body skeleton (§8.5): low visibility gates arm B / shoulder params but keeps metrics', () => {
  const low = api.mapBody([0, 0], makeUb({
    visibilityL: 0.1, elbowL: [0.35, 0.57], wristL: [0.26, 0.57],
    shoulderL: [0.35, 0.38], shoulderR: [0.65, 0.42]
  }));
  if ('ParamArmLB' in low.params) throw new Error('ParamArmLB must be omitted (visibility < 0.3)');
  if ('ParamShoulderL' in low.params || 'ParamShoulderR' in low.params) {
    throw new Error('ParamShoulderL/R must be omitted (shoulder visibility < 0.3)');
  }
  if (!('ParamArmRB' in low.params)) throw new Error('ParamArmRB should still be present');
  close(low.metrics.elbowBendL, 90, 0.01, 'metrics keep raw elbow bend');
});

test('upper body: readUpperBodyTrack handles ubOk / stale / missing fields', () => {
  const ub = makeUb();
  const first = api.readUpperBodyTrack({ ub, ubOk: true, ubT: 1000, body: [3, -5] }, 0, null);
  exact(first.ok, true, 'first.ok');
  close(first.torso[0], 3, 1e-12, 'torso roll');
  const stale = api.readUpperBodyTrack({ ub, ubOk: true, ubT: 1000, body: [3, -5] }, 1600, first);
  exact(stale.ok, false, 'stale.ok');
  exact(stale.stale, true, 'stale.stale');
  const fresh = api.readUpperBodyTrack({ ub, ubOk: true, ubT: 2000, body: [0, 0] }, 1700, stale);
  exact(fresh.ok, true, 'fresh.ok');
  const off = api.readUpperBodyTrack({ ub, ubOk: false, ubT: 3000, body: [0, 0] }, 1800, fresh);
  exact(off.ok, false, 'ubOk=false');
  exact(off.present, true, 'ubOk=false still present');
  const legacy = api.readUpperBodyTrack({ type: 'track', bs: zeros(), pose: [0, 0, 0] }, 1900, fresh);
  exact(legacy.present, false, 'legacy track has no ub');
  exact(legacy.ok, false, 'legacy track not ok');
});

test('upper body: smoothBodyParams decays 15%/frame and releases to idle', () => {
  close(api.smoothBodyParams({ ParamArmLA: 30 }, null).ParamArmLA, 25.499999999999996, 1e-9, '30*0.85');
  let cur = { ParamArmLA: 30 };
  for (let i = 0; i < 100; i++) cur = api.smoothBodyParams(cur, null);
  if ('ParamArmLA' in cur) throw new Error('param should be released after decay');
  assert.deepStrictEqual(api.smoothBodyParams({}, null), {}, 'never-driven ids stay untouched');
  close(api.smoothBodyParams({ ParamArmLA: 30 }, { ParamArmLA: 7 }, { snap: true }).ParamArmLA, 7, 1e-12, 'snap');
});

test('upper body: combineBodyAngleX sums head + torso then clamps to +/-30', () => {
  close(api.combineBodyAngleX(10, 25), 30, 1e-12, 'clamp +');
  close(api.combineBodyAngleX(-10, -25), -30, 1e-12, 'clamp -');
  close(api.combineBodyAngleX(4, -7), -3, 1e-12, 'sum');
  close(api.combineBodyAngleX(undefined, 5), 5, 1e-12, 'head missing');
});

// ---------------------------------------------------------------- verdict
R.finishedAt = new Date().toISOString();
const failed = R.tests.filter((t) => !t.ok);
R.verdict = failed.length ? 'FAIL' : 'PASS';
R.counts = { total: R.tests.length, failed: failed.length };
fs.writeFileSync(OUT_JSON, JSON.stringify(R, null, 2));
console.log('');
console.log('========== MAPPING TEST: ' + R.verdict + ' ==========');
console.log('tests: ' + R.tests.length + '  failed: ' + failed.length + '  module sha256: ' + R.module.sha256.slice(0, 16) + '...');
for (const f of failed) console.log('  FAILED: ' + f.name + '  ' + f.error);
console.log('result: ' + OUT_JSON);
process.exit(failed.length ? 1 : 0);
