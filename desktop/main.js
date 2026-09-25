const { app, BrowserWindow, shell } = require('electron');

const START_URL = 'https://kiosk.r777b.site';

let mainWindow = null;
let gpuRestarting = false;

// Mantem WebGL/3D ativo mesmo se o processo GPU falhar algumas vezes.
// O Chromium normalmente pode bloquear APIs 3D por dominio ate reiniciar o app.
app.disableDomainBlockingFor3DAPIs();

app.commandLine.appendSwitch('enable-gpu');
app.commandLine.appendSwitch('enable-webgl');
app.commandLine.appendSwitch('ignore-gpu-blocklist');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

function createWindow() {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.destroy();
  }

  mainWindow = new BrowserWindow({
    fullscreen: true,
    kiosk: true,
    autoHideMenuBar: true,
    frame: false,
    backgroundColor: '#000000',
    show: true,
    webPreferences: {
      javascript: true,
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      backgroundThrottling: false
    }
  });

  mainWindow.setMenuBarVisibility(false);

  mainWindow.webContents.on('render-process-gone', (_event, details) => {
    console.error('[KIOSK] Renderer encerrado:', details);
    setTimeout(() => {
      if (!gpuRestarting) createWindow();
    }, 700);
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const target = new URL(url);
      const allowed =
        target.hostname === 'kiosk.r777b.site' ||
        target.hostname.endsWith('.kiosk.r777b.site');

      if (allowed) {
        mainWindow.loadURL(url);
      } else {
        shell.openExternal(url);
      }
    } catch (_) {}
    return { action: 'deny' };
  });

  mainWindow.webContents.on('will-navigate', (event, url) => {
    try {
      const target = new URL(url);
      const allowed =
        target.hostname === 'kiosk.r777b.site' ||
        target.hostname.endsWith('.kiosk.r777b.site');

      if (!allowed) {
        event.preventDefault();
        shell.openExternal(url);
      }
    } catch (_) {}
  });

  mainWindow.loadURL(START_URL);
}

app.on('child-process-gone', (_event, details) => {
  if (details.type !== 'GPU') return;

  console.error('[KIOSK] Processo GPU caiu:', details);

  // Reiniciar o Electron limpa o estado quebrado do processo GPU/WebGL.
  if (!gpuRestarting) {
    gpuRestarting = true;
    app.relaunch();
    app.exit(0);
  }
});

app.whenReady().then(async () => {
  app.setName('kiosk WebView');

  try {
    const status = app.getGPUFeatureStatus();
    console.log('[KIOSK] GPU:', status);
  } catch (_) {}

  createWindow();
});

app.on('window-all-closed', () => {
  app.quit();
});
