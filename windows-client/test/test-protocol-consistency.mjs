import { BS_INDEX as EXTRACTOR_BS_INDEX, BS_COUNT } from "../renderer/blendshape-extractor.js";
import { BS_INDEX as MAPPING_BS_INDEX, BS_COUNT as MAPPING_BS_COUNT, BODY_LANDMARK_ORDER, BODY_COUNT, BODY_FLOATS } from "../renderer/mapping.js";

function assert(cond, msg) { if (!cond) throw new Error(msg); }

export const tests = [
  {
    name: `协议一致性核对：blendshape-extractor 的 BS_INDEX 与 mapping.js BS_INDEX 逐项相同`,
    fn() {
      const a = Object.keys(EXTRACTOR_BS_INDEX).sort();
      const b = Object.keys(MAPPING_BS_INDEX).sort();
      assert(String(a) === String(b), `键集合不同：\n${a}\nvs\n${b}`);
      for (const k of a) {
        assert(EXTRACTOR_BS_INDEX[k] === MAPPING_BS_INDEX[k],
          `BS_INDEX.${k} 不一致：extractor=${EXTRACTOR_BS_INDEX[k]} mapping=${MAPPING_BS_INDEX[k]}`);
      }
    },
  },
  {
    name: `协议一致性核对：52 个下标恰好覆盖 0..51，无重复无缺号`,
    fn() {
      const vals = Object.values(EXTRACTOR_BS_INDEX).sort((x, y) => x - y);
      assert(vals.length === 52, `应有 52 项，实际 ${vals.length}`);
      for (let i = 0; i < 52; i++) assert(vals[i] === i, `下标 ${i} 缺失或重复`);
      assert(BS_COUNT === 52, "BS_COUNT");
      assert(MAPPING_BS_COUNT === 52, "MAPPING_BS_COUNT");
    },
  },
  {
    name: `协议一致性核对：13 点 ORDER 与 mapping.js BODY_LANDMARK_ORDER 一致`,
    fn() {
      const expect = {
        nose: 0, eye_left: 1, eye_right: 2, ear_left: 3, ear_right: 4,
        shoulder_left: 5, shoulder_right: 6, elbow_left: 7, elbow_right: 8,
        wrist_left: 9, wrist_right: 10, hip_left: 11, hip_right: 12,
      };
      for (const k of Object.keys(expect)) {
        assert(BODY_LANDMARK_ORDER[k] === expect[k],
          `BODY_LANDMARK_ORDER.${k} 应为 ${expect[k]}，实际 ${BODY_LANDMARK_ORDER[k]}`);
      }
      assert(BODY_COUNT === 13, "BODY_COUNT");
      assert(BODY_FLOATS === 52, "BODY_FLOATS = 13*4");
    },
  },
  {
    name: `协议一致性核对：躯干退化对使用协议下标（肩 5,6 -> 肘 7,8 -> 胯 11,12）`,
    fn() {
      assert(BODY_LANDMARK_ORDER.shoulder_left === 5 && BODY_LANDMARK_ORDER.shoulder_right === 6, "肩");
      assert(BODY_LANDMARK_ORDER.elbow_left === 7 && BODY_LANDMARK_ORDER.elbow_right === 8, "肘");
      assert(BODY_LANDMARK_ORDER.hip_left === 11 && BODY_LANDMARK_ORDER.hip_right === 12, "胯");
    },
  },
  {
    name: `neutral 恒为 0 号位（协议 §5.1）`,
    fn() {
      assert(EXTRACTOR_BS_INDEX.neutral === 0, "extractor");
      assert(MAPPING_BS_INDEX.neutral === 0, "mapping");
    },
  },
];
