import { RawFrameReorderBuffer, FrameIntervalTracker, unwrapFrameId, DEFAULT_MAX_PENDING, DEFAULT_GAP_TIMEOUT_MS } from "../renderer/raw-frame-reorder.js";

function assert(cond, msg) { if (!cond) throw new Error(msg); }
function assertEq(a, b, msg) { if (a !== b) throw new Error(`${msg}: ${a} != ${b}`); }
function ids(frames) { return frames.map((f) => f.frameId); }

export const tests = [
  {
    name: "常量与 Kotlin FrameReorderBuffer 一致（8 / 60ms）",
    fn() {
      assertEq(DEFAULT_MAX_PENDING, 8, "maxPending");
      assertEq(DEFAULT_GAP_TIMEOUT_MS, 60, "gapTimeoutMs");
    },
  },
  {
    name: "首帧立即放行，不空等前一帧",
    fn() {
      const b = new RawFrameReorderBuffer();
      const out = b.offer(100, { frameId: 100 }, 0);
      assertEq(out.length, 1, "长度");
      assertEq(out[0].frameId, 100, "id");
    },
  },
  {
    name: "乱序输入按 frameId 升序放行",
    fn() {
      const b = new RawFrameReorderBuffer();
      b.offer(1, { frameId: 1 }, 0);
      b.offer(3, { frameId: 3 }, 0);
      const out = b.offer(2, { frameId: 2 }, 0);
      assertEq(out.length, 2, "应一次放行 2 帧");
      assert(String(ids(out)) === "2,3", `顺序应为 2,3，实际 ${ids(out)}`);
    },
  },
  {
    name: "缺口未超时前不放行，超时后放行手里最旧的一帧",
    fn() {
      // 注：gapStartMs 用 0 作「无缺口」哨兵（与 Kotlin 一致），所以 nowMs 必须用非 0 值
      const b = new RawFrameReorderBuffer({ gapTimeoutMs: 60 });
      b.offer(1, { frameId: 1 }, 1000);
      const pending = b.offer(3, { frameId: 3 }, 1000);
      assertEq(pending.length, 0, "缺口未超时应继续等");
      assert(b.isWaitingForGap, "应处于等待缺口状态");
      const out = b.poll(1061);
      assertEq(out.length, 1, "超时后放行 1 帧");
      assertEq(out[0].frameId, 3, "放行手里最旧的");
    },
  },
  {
    name: "gapTimeoutMs <= 0 表示不等待，按到达顺序放行",
    fn() {
      const b = new RawFrameReorderBuffer({ gapTimeoutMs: 0 });
      b.offer(1, { frameId: 1 }, 0);
      const out = b.offer(5, { frameId: 5 }, 0);
      assertEq(out.length, 1, "应立即放行");
      assertEq(out[0].frameId, 5, "id");
    },
  },
  {
    name: "迟到帧（<= lastEmittedId）一律丢弃并计数",
    fn() {
      const b = new RawFrameReorderBuffer();
      b.offer(1, { frameId: 1 }, 0);
      const out = b.offer(1, { frameId: 1 }, 0);
      assertEq(out.length, 0, "重复帧不放行");
      const out2 = b.offer(0, { frameId: 0 }, 0);
      assertEq(out2.length, 0, "更旧的帧不放行");
      assert(b.stats().droppedLate >= 1, "应计入 droppedLate");
    },
  },
  {
    name: "同一 frameId 重复投递只保留第一份",
    fn() {
      // 制造一个缺口让 frame 2 先留在 pending 里，重复投递才观察得到
      const b = new RawFrameReorderBuffer({ gapTimeoutMs: 60 });
      b.offer(1, { frameId: 1, v: "a" }, 1000);
      b.offer(3, { frameId: 3, v: "x" }, 1000);
      b.offer(3, { frameId: 3, v: "y" }, 1000);
      assertEq(b.stats().droppedDup, 1, "第二份应被丢弃并计数");
      const out = b.poll(1100);
      assert(String(ids(out)) === "3", `实际 ${ids(out)}`);
      assertEq(out[0].v, "x", "应保留第一份");
    },
  },
  {
    name: "堆积超过 maxPending 置 forceSkipGap，下一轮跳过缺口",
    fn() {
      const b = new RawFrameReorderBuffer({ maxPending: 3, gapTimeoutMs: 1e9 });
      b.offer(1, { frameId: 1 }, 0);
      // 塞 4 帧，制造一个大缺口
      for (const id of [5, 6, 7, 8]) b.offer(id, { frameId: id }, 0);
      assert(b.stats().forceSkip || b.stats().gapsSkipped > 0, "应触发强制跳缺口");
    },
  },
  {
    name: "reset 清空全部状态",
    fn() {
      const b = new RawFrameReorderBuffer();
      b.offer(1, { frameId: 1 }, 0);
      b.offer(9, { frameId: 9 }, 0);
      b.reset();
      assertEq(b.size, 0, "pending 应清空");
      const out = b.offer(50, { frameId: 50 }, 0);
      assertEq(out.length, 1, "reset 后首帧应立即放行");
    },
  },
  {
    name: "unwrapFrameId：32 位序列算术展开回绕",
    fn() {
      const a = unwrapFrameId(100, null, null);
      assertEq(a.extended, 100, "首帧");
      const b = unwrapFrameId(101, a.raw, a.extended);
      assertEq(b.extended, 101, "正常递增");
      // 回绕：uint32 从 0xFFFFFFFE -> 0x00000001
      const c = unwrapFrameId(0x00000001, 0xFFFFFFFE, 0xFFFFFFFE);
      assertEq(c.extended, 0xFFFFFFFE + 3, "回绕后应继续递增");
      // 乱序（更旧）应产生负差
      const d = unwrapFrameId(95, 101, 101);
      assertEq(d.extended, 95, "回退帧展开后变小");
    },
  },
  {
    name: "FrameIntervalTracker：三个 dt 口径互不干扰",
    fn() {
      const t = new FrameIntervalTracker({ initialFps: 30 });
      t.push(0);
      const raw = t.push(300); // 隔 300ms
      assertEq(raw, 300, "原始 dt 不裁剪");
      assertEq(t.dtFilterSeconds, 0.3, "filter 口径应保留 0.3s");
      assertEq(t.dtBodySeconds, 0.3, "body 口径应保留 0.3s");
      assertEq(t.dtBlinkMs, 100, "blink 口径应裁到 100ms");

      t.push(400); // 隔 100ms
      t.push(400.0005); // 隔 0.0005ms -> 各口径下限
      assert(t.dtFilterSeconds >= 0.001, "filter 下限");
      assert(t.dtBodySeconds >= 0.001, "body 下限");
      assert(t.dtBlinkMs >= 1, "blink 下限");
    },
  },
  {
    name: "FrameIntervalTracker：时间戳回退不静默接受（计数）",
    fn() {
      const t = new FrameIntervalTracker();
      t.push(1000);
      const dt = t.push(500);
      assertEq(dt, 0, "回退应返回 0");
      assert(t.regressed > 0, "应计入 regressed");
    },
  },
];
