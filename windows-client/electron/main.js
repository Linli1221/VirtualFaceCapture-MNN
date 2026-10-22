/**
 * electron/main.js — 桌面壳：本地静态服务 + BrowserWindow（透明 / 无边框 / 置顶 / 鼠标穿透 + 位置持久化）
 *
 * npm start                    # 启动（静态服务内嵌在 17890，若已有服务则复用）
 * npm start -- --smoke         # 自检：启动 → 等渲染就绪 → 打印结果 → 退出
 */
// 注意：electron 包的 ESM 具名导入不可用（它是 CJS，且导出是动态挂载的），必须走 default import。
import electron from "electron";

const { app, BrowserWindow, Menu, globalShortcut, ipcMain, screen } = electron;
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { startServer, DEFAULT_PORT } from "../server.js";

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const SMOKE = process.argv.includes("--smoke");
const SMOKE_RESULT = path.join(ROOT, "electron", "smoke-result.json");

// 自定义配置目录：CI / 多开测试用，避免写 %APPDATA% 和单实例锁互相干扰。
//   VFC_USER_DATA_DIR=<目录> VFC-Live2D.exe --smoke
const userDataOverride =
  process.env.VFC_USER_DATA_DIR ||
  process.argv.find((arg) => arg.startsWith("--user-data-dir="))?.slice("--user-data-dir=".length);
if (userDataOverride) {
  const dir = path.resolve(userDataOverride);
  fs.mkdirSync(dir, { recursive: true });
  app.setPath("userData", dir);
}

// 自检环境（CI / 无交互桌面）经常没有可用 GPU，软件渲染会直接崩在 Chromium 启动阶段。
if (SMOKE) app.disableHardwareAcceleration();

const DEFAULT_STATE = {
  width: 900,
  height: 900,
  x: null,
  y: null,
  transparent: false,
  borderless: false,
  alwaysOnTop: false,
  mouseThrough: false,
};

let state = { ...DEFAULT_STATE };
let mainWindow = null;
let serverInfo = null;
let saveTimer = null;

function stateFile() {
  return path.join(app.getPath("userData"), "window-state.json");
}

function loadState() {
  try {
    const raw = fs.readFileSync(stateFile(), "utf8");
    const parsed = JSON.parse(raw);
    if (parsed && typeof parsed === "object") {
      state = { ...DEFAULT_STATE, ...parsed };
    }
  } catch {
    state = { ...DEFAULT_STATE };
  }
  clampToDisplays();
}

function saveState() {
  if (saveTimer) {
    clearTimeout(saveTimer);
    saveTimer = null;
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    const bounds = mainWindow.getBounds();
    state.width = bounds.width;
    state.height = bounds.height;
    state.x = bounds.x;
    state.y = bounds.y;
  }
  try {
    fs.mkdirSync(path.dirname(stateFile()), { recursive: true });
    fs.writeFileSync(stateFile(), JSON.stringify(state, null, 2), "utf8");
  } catch {
    /* 写不了就算了，不影响渲染 */
  }
}

function scheduleSaveState() {
  if (saveTimer) clearTimeout(saveTimer);
  saveTimer = setTimeout(saveState, 400);
}

/** 保证窗口至少有一部分落在某个显示器里（拔掉外接屏后不至于找不回来）。 */
function clampToDisplays() {
  if (state.x == null || state.y == null) return;
  const displays = screen.getAllDisplays();
  const visible = displays.some((display) => {
    const area = display.workArea;
    return (
      state.x + state.width > area.x + 40 &&
      state.x < area.x + area.width - 40 &&
      state.y + state.height > area.y + 40 &&
      state.y < area.y + area.height - 40
    );
  });
  if (!visible) {
    state.x = null;
    state.y = null;
  }
}

async function ensureServer() {
  if (serverInfo) return serverInfo;
  try {
    serverInfo = await startServer({ port: DEFAULT_PORT, quiet: true });
    console.log(`[vfc] 静态服务已启动：${serverInfo.url}`);
  } catch (error) {
    if (error?.code === "EADDRINUSE") {
      serverInfo = { url: `http://127.0.0.1:${DEFAULT_PORT}/`, port: DEFAULT_PORT, server: null, reused: true };
      console.log(`[vfc] 复用已在运行的静态服务：${serverInfo.url}`);
    } else {
      throw error;
    }
  }
  return serverInfo;
}

function rendererUrl() {
  const params = new URLSearchParams({ electron: "1" });
  if (state.transparent) params.set("transparent", "1");
  return `http://127.0.0.1:${DEFAULT_PORT}/?${params.toString()}`;
}

function windowStatePayload() {
  return {
    transparent: Boolean(state.transparent),
    borderless: Boolean(state.borderless),
    alwaysOnTop: Boolean(state.alwaysOnTop),
    mouseThrough: Boolean(state.mouseThrough),
  };
}

function broadcastWindowState() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send("vfc:window-state", windowStatePayload());
  }
}

function createWindow() {
  const win = new BrowserWindow({
    width: state.width,
    height: state.height,
    x: state.x ?? undefined,
    y: state.y ?? undefined,
    minWidth: 320,
    minHeight: 320,
    title: "VFC · Live2D 虚拟形象",
    transparent: Boolean(state.transparent),
    frame: !state.borderless,
    resizable: true,
    alwaysOnTop: Boolean(state.alwaysOnTop),
    backgroundColor: state.transparent ? "#00000000" : "#0b0e15",
    show: false,
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(ROOT, "electron", "preload.cjs"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      backgroundThrottling: false,
    },
  });

  win.loadURL(rendererUrl());

  win.once("ready-to-show", () => {
    win.show();
    win.setAlwaysOnTop(Boolean(state.alwaysOnTop));
    if (state.mouseThrough) win.setIgnoreMouseEvents(true, { forward: true });
    broadcastWindowState();
    if (SMOKE) runSmokeTest(win);
  });

  win.on("move", scheduleSaveState);
  win.on("resize", scheduleSaveState);
  win.on("closed", () => {
    if (mainWindow === win) mainWindow = null;
  });

  mainWindow = win;
  return win;
}

function recreateWindow() {
  const old = mainWindow;
  mainWindow = null;
  if (old && !old.isDestroyed()) {
    old.removeAllListeners("closed");
    old.destroy();
  }
  return createWindow();
}

function applyWindowOptions(patch = {}) {
  let recreate = false;
  if (typeof patch.alwaysOnTop === "boolean" && patch.alwaysOnTop !== state.alwaysOnTop) {
    state.alwaysOnTop = patch.alwaysOnTop;
    mainWindow?.setAlwaysOnTop(state.alwaysOnTop);
  }
  if (typeof patch.mouseThrough === "boolean" && patch.mouseThrough !== state.mouseThrough) {
    state.mouseThrough = patch.mouseThrough;
    mainWindow?.setIgnoreMouseEvents(state.mouseThrough, { forward: true });
  }
  // transparent / frame 只能在建窗时指定，变了就重建（渲染页状态在 localStorage，不丢）
  if (typeof patch.transparent === "boolean" && patch.transparent !== state.transparent) {
    state.transparent = patch.transparent;
    recreate = true;
  }
  if (typeof patch.borderless === "boolean" && patch.borderless !== state.borderless) {
    state.borderless = patch.borderless;
    recreate = true;
  }

  if (recreate) {
    saveState();
    recreateWindow();
  } else {
    scheduleSaveState();
    broadcastWindowState();
  }
  return windowStatePayload();
}

function registerIpc() {
  ipcMain.handle("vfc:get-window-state", () => windowStatePayload());
  ipcMain.handle("vfc:set-window-options", (_event, patch) => applyWindowOptions(patch ?? {}));
  ipcMain.on("vfc:quit", () => app.quit());
}

function registerShortcuts() {
  // 鼠标穿透后页面收不到点击，这里留一个全局逃生键（再按一次取消穿透）
  const ok = globalShortcut.register("Control+Alt+T", () => {
    applyWindowOptions({ mouseThrough: !state.mouseThrough });
  });
  if (!ok) console.warn("[vfc] 全局快捷键 Ctrl+Alt+T 注册失败（可能被其它程序占用）");
}

async function runSmokeTest(win) {
  const deadline = Date.now() + 30000;
  let result = null;
  while (Date.now() < deadline) {
    try {
      result = await win.webContents.executeJavaScript(
        "(() => { const d = window.__vfcDebug || {}; return { ready: !!d.ready, model: d.model, connected: d.connected, frames: d.frames, error: d.error || null }; })()",
        true,
      );
    } catch (error) {
      result = { ready: false, error: String(error) };
    }
    if (result?.ready) break;
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
  // Windows 上 Electron 是 GUI 子系统程序，stdout 在无控制台时拿不到，
  // 所以 smoke 结果同时写文件，由 tools/start-electron.js 读出来打印。
  try {
    fs.writeFileSync(SMOKE_RESULT, JSON.stringify({ ...result, at: new Date().toISOString() }, null, 2));
  } catch (error) {
    console.error("[vfc][smoke] 写结果文件失败：", error);
  }
  console.log("[vfc][smoke] " + JSON.stringify(result));
  saveState();
  app.exit(result?.ready ? 0 : 1);
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(async () => {
    Menu.setApplicationMenu(null);
    loadState();
    registerIpc();
    await ensureServer();
    createWindow();
    registerShortcuts();

    app.on("activate", () => {
      if (BrowserWindow.getAllWindows().length === 0) createWindow();
    });
  });

  app.on("window-all-closed", () => {
    saveState();
    app.quit();
  });

  app.on("will-quit", () => {
    globalShortcut.unregisterAll();
    if (serverInfo?.server) serverInfo.server.close();
  });
}
