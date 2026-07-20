const { app, BrowserWindow, Menu, shell, ipcMain } = require("electron");
const path = require("path");
const fs = require("fs");
const { spawn } = require("child_process");
const http = require("http");

let mainWindow;
let serverProcess;

const STATE_PATH = path.join(app.getPath("userData"), "window-state.json");

function loadWindowState() {
  try {
    return JSON.parse(fs.readFileSync(STATE_PATH, "utf8"));
  } catch {
    return {};
  }
}

function saveWindowState() {
  if (!mainWindow) return;
  try {
    const bounds = mainWindow.getBounds();
    const maximized = mainWindow.isMaximized();
    fs.writeFileSync(STATE_PATH, JSON.stringify({ ...bounds, maximized }));
  } catch {}
}

function startServer() {
  try { spawn.sync("fuser", ["-k", "5000/tcp"], { stdio: "ignore" }); } catch (_) {}
  try { spawn.sync("rm", ["-f", "__pycache__/*.pyc"], { cwd: path.join(__dirname, ".."), shell: true }); } catch (_) {}
  const serverPath = app.isPackaged
    ? path.join(process.resourcesPath, "backend", "server.py")
    : path.join(__dirname, "..", "server.py");

  serverProcess = spawn("python3", [serverPath], {
    stdio: ["ignore", "pipe", "pipe"],
  });

  serverProcess.stdout.on("data", (data) => {
    process.stdout.write(`[server] ${data}`);
  });
  serverProcess.stderr.on("data", (data) => {
    process.stderr.write(`[server] ${data}`);
  });
}

function waitForServer(cb, attempt = 0) {
  http.get("http://127.0.0.1:5000/api/status", (res) => {
    cb();
  }).on("error", () => {
    if (attempt < 30) {
      setTimeout(() => waitForServer(cb, attempt + 1), 1000);
    }
  });
}

ipcMain.handle("show-item-in-folder", (_event, filePath) => {
  shell.showItemInFolder(filePath);
});

function clearCache() {
  const data = JSON.stringify({});
  const req = http.request({
    hostname: "127.0.0.1",
    port: 5000,
    path: "/api/cache/clear",
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "Content-Length": Buffer.byteLength(data),
    },
  });
  req.on("error", (e) => console.error("[menu] Erro ao limpar cache:", e.message));
  req.write(data);
  req.end();
}

function buildMenu() {
  const template = [
    {
      label: "CyberBot",
      submenu: [
        {
          label: "Limpar Cache",
          accelerator: "CmdOrCtrl+Shift+L",
          click: clearCache,
        },
        { type: "separator" },
        { role: "quit", label: "Sair" },
      ],
    },
    {
      label: "Exibir",
      submenu: [
        { role: "reload", label: "Recarregar" },
        { role: "toggleDevTools", label: "DevTools" },
        { type: "separator" },
        { role: "togglefullscreen", label: "Tela Cheia" },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

function createWindow() {
  const state = loadWindowState();

  mainWindow = new BrowserWindow({
    width: state.width || 820,
    height: state.height || 700,
    x: state.x,
    y: state.y,
    minWidth: 760,
    minHeight: 500,
    title: "CyberBot",
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
    },
    icon: path.join(__dirname, "assets", "img", "logo.jpg"),
    backgroundColor: "#0a0a1a",
    show: false,
  });

  if (state.maximized) mainWindow.maximize();

  mainWindow.loadFile(path.join(__dirname, "renderer", "index.html"));

  mainWindow.once("ready-to-show", () => {
    mainWindow.show();
  });

  mainWindow.on("resize", saveWindowState);
  mainWindow.on("move", saveWindowState);
  mainWindow.on("maximize", saveWindowState);
  mainWindow.on("unmaximize", saveWindowState);
  mainWindow.on("close", saveWindowState);
}

app.whenReady().then(() => {
  const logoPath = path.join(__dirname, "assets", "img", "logo.jpg");
  try { if (app.dock) app.dock.setIcon(logoPath); } catch (_) {}
  buildMenu();
  startServer();
  waitForServer(() => {
    setTimeout(createWindow, 500);
  });
});

app.on("before-quit", () => {
  try { spawn.sync("pkill", ["-9", "-f", "python3.*bot.py"], { stdio: "ignore" }); } catch (_) {}
  try { spawn.sync("pkill", ["-9", "ollama"], { stdio: "ignore" }); } catch (_) {}
});

app.on("window-all-closed", () => {
  if (serverProcess) serverProcess.kill();
  app.quit();
});
