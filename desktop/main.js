const { app, BrowserWindow, shell } = require('electron');

const START_URL = 'https://jk.r777b.site/jukebox';

let mainWindow = null;
let allowClose = false;
let closeSequence = [];

app.setName('Jukebox');

app.disableDomainBlockingFor3DAPIs();
app.commandLine.appendSwitch('enable-gpu');
app.commandLine.appendSwitch('enable-webgl');
app.commandLine.appendSwitch('ignore-gpu-blocklist');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

function createWindow() {
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

  mainWindow.on('close', (event) => {
    if (!allowClose) {
      event.preventDefault();
    }
  });

  mainWindow.webContents.on('before-input-event', (event, input) => {
    const key = String(input.key || '').toLowerCase();

    const isAltF4 =
      input.type === 'keyDown' &&
      input.alt === true &&
      key === 'f4';

    if (isAltF4) {
      event.preventDefault();
      return;
    }

    if (input.type !== 'keyDown') return;

    // Sequência autorizada: Ctrl + . e depois F
    if (input.control === true && key === '.') {
      closeSequence = ['ctrl-dot'];
      event.preventDefault();
      return;
    }

    if (closeSequence.length && key === 'f') {
      closeSequence = [];
      allowClose = true;
      app.quit();
      event.preventDefault();
      return;
    }

    if (key !== 'control') {
      closeSequence = [];
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    try {
      const target = new URL(url);
      const allowed =
        target.hostname === 'jk.r777b.site' ||
        target.hostname.endsWith('.jk.r777b.site');

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
        target.hostname === 'jk.r777b.site' ||
        target.hostname.endsWith('.jk.r777b.site');

      if (!allowed) {
        event.preventDefault();
        shell.openExternal(url);
      }
    } catch (_) {}
  });

  mainWindow.loadURL(START_URL);
}

app.whenReady().then(() => {
  createWindow();
});

app.on('window-all-closed', () => {
  if (allowClose) app.quit();
});
