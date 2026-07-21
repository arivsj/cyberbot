const { contextBridge, ipcRenderer } = require("electron");

const API = "http://127.0.0.1:5000";

contextBridge.exposeInMainWorld("api", {
  async get(url) {
    const res = await fetch(`${API}${url}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  },
  async post(url, body) {
    const res = await fetch(`${API}${url}`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return res.json();
  },
  showItemInFolder(filePath) {
    ipcRenderer.invoke("show-item-in-folder", filePath);
  },
  readFile(filePath) {
    return ipcRenderer.invoke("read-file", filePath);
  },
});
