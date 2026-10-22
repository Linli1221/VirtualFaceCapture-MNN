// electron/preload.cjs — 渲染页与主进程之间唯一的桥（contextIsolation 打开，只暴露白名单方法）
//
// 注意：windows-client 是 ESM 工程（package.json "type": "module"），
// Electron 的 preload 在 ESM 包里不能用 .js 当 CJS 模块，所以这里用 .cjs 扩展名。
const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("vfcNative", {
  isElectron: true,
  getWindowState: () => ipcRenderer.invoke("vfc:get-window-state"),
  setWindowOptions: (patch) => ipcRenderer.invoke("vfc:set-window-options", patch),
  quit: () => ipcRenderer.send("vfc:quit"),
  onWindowState: (callback) => {
    if (typeof callback !== "function") return () => {};
    const listener = (_event, state) => callback(state);
    ipcRenderer.on("vfc:window-state", listener);
    return () => ipcRenderer.removeListener("vfc:window-state", listener);
  },
});
